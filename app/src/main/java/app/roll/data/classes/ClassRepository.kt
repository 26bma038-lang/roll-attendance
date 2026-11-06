package app.roll.data.classes

import app.roll.core.common.DEFAULT_CLASS_TIMEZONE
import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.firestoreCode
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toMember
import app.roll.data.firebase.toRollClass
import app.roll.domain.JoinCode
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
import app.roll.domain.model.UserProfile
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ClassResult {
    data class Loaded(val rollClass: RollClass, val fromCache: Boolean) : ClassResult

    /** Doc doesn't exist (e.g. cache empty and offline). */
    data class Missing(val fromCache: Boolean) : ClassResult

    /** The server says we can't read it: removed from the class. */
    data object AccessLost : ClassResult
}

sealed interface JoinResult {
    data class Joined(val classId: String) : JoinResult
    data object CodeNotFound : JoinResult
}

class OfflineException : Exception("Needs a connection")

interface ClassRepository {
    fun observeClass(classId: String): Flow<ClassResult>

    /** CR only. */
    fun observeMembers(classId: String): Flow<List<Member>>

    /** Needs a connection: the join code is checked for collisions on the server. */
    suspend fun createClass(profile: UserProfile, name: String, semester: String, requiredPercent: Int): RollClass

    /** Needs a connection. */
    suspend fun joinClass(profile: UserProfile, code: String): JoinResult

    fun leaveClass(uid: String, classId: String)

    /** Removes the member doc and waits for the server. Used before deleting an account. */
    suspend fun deleteMembership(uid: String, classId: String)

    fun removeMember(classId: String, uid: String)

    fun transferCr(classId: String, newCrUid: String)

    fun updateSettings(classId: String, name: String, semester: String, requiredPercent: Int)

    /** Needs a connection. Returns the new code. */
    suspend fun regenerateJoinCode(rollClass: RollClass): String
}

@Singleton
class FirestoreClassRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : ClassRepository {

    private fun classDoc(classId: String) = firestore.collection(F.CLASSES).document(classId)
    private fun memberDoc(classId: String, uid: String) = classDoc(classId).collection(F.MEMBERS).document(uid)
    private fun codeDoc(code: String) = firestore.collection(F.JOIN_CODES).document(code)
    private fun userDoc(uid: String) = firestore.collection(F.USERS).document(uid)

    override fun observeClass(classId: String): Flow<ClassResult> = flow {
        var retried = false
        while (true) {
            var denied = false
            classDoc(classId).snapshots()
                .catch { error ->
                    if (error.firestoreCode == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        denied = true
                    } else {
                        logWarning("Class listener failed", error)
                    }
                }
                .collect { snapshot ->
                    val rollClass = snapshot.toRollClass()
                    emit(
                        if (rollClass != null) {
                            ClassResult.Loaded(rollClass, snapshot.metadata.isFromCache)
                        } else {
                            ClassResult.Missing(snapshot.metadata.isFromCache)
                        },
                    )
                }
            if (!denied) return@flow
            if (retried) {
                emit(ClassResult.AccessLost)
                return@flow
            }
            // Right after joining, our member doc may still be in flight. Wait for our own
            // writes to land, then try once more before concluding we were removed.
            retried = true
            withTimeoutOrNull(10_000) { firestore.waitForPendingWrites().await() }
        }
    }

    override fun observeMembers(classId: String): Flow<List<Member>> =
        classDoc(classId).collection(F.MEMBERS).snapshots()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toMember() }.sortedBy { it.rollNo } }
            .catch { logWarning("Members listener failed", it) }

    override suspend fun createClass(
        profile: UserProfile,
        name: String,
        semester: String,
        requiredPercent: Int,
    ): RollClass {
        val code = freeJoinCode()
        val classRef = firestore.collection(F.CLASSES).document()
        val batch = firestore.batch()
        batch.set(
            classRef,
            mapOf(
                "name" to name,
                "semester" to semester,
                "crUid" to profile.uid,
                "joinCode" to code,
                "requiredPercent" to requiredPercent,
                "timezone" to DEFAULT_CLASS_TIMEZONE,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(codeDoc(code), mapOf(F.CLASS_ID to classRef.id))
        batch.set(memberDoc(classRef.id, profile.uid), memberData(profile, code))
        batch.update(userDoc(profile.uid), F.CLASS_ID, classRef.id)
        batch.commit().await()
        return RollClass(
            id = classRef.id,
            name = name,
            semester = semester,
            crUid = profile.uid,
            joinCode = code,
            requiredPercent = requiredPercent,
            timezone = DEFAULT_CLASS_TIMEZONE,
        )
    }

    override suspend fun joinClass(profile: UserProfile, code: String): JoinResult {
        val codeSnapshot = try {
            codeDoc(code).get(Source.SERVER).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw OfflineException()
            throw e
        }
        val classId = codeSnapshot.getString(F.CLASS_ID) ?: return JoinResult.CodeNotFound
        val batch = firestore.batch()
        batch.set(memberDoc(classId, profile.uid), memberData(profile, code))
        batch.update(userDoc(profile.uid), F.CLASS_ID, classId)
        batch.commit().await()
        return JoinResult.Joined(classId)
    }

    override fun leaveClass(uid: String, classId: String) {
        val batch = firestore.batch()
        batch.delete(memberDoc(classId, uid))
        batch.update(userDoc(uid), F.CLASS_ID, null)
        // Applied locally at once; no need to wait for the server.
        batch.commit().reportFailure { messages.show("Couldn't leave the class. Try again.") }
    }

    override suspend fun deleteMembership(uid: String, classId: String) {
        memberDoc(classId, uid).delete().await()
    }

    override fun removeMember(classId: String, uid: String) {
        memberDoc(classId, uid).delete()
            .reportFailure { messages.show("Couldn't remove that member. Try again.") }
    }

    override fun transferCr(classId: String, newCrUid: String) {
        classDoc(classId).update("crUid", newCrUid)
            .reportFailure { messages.show("Couldn't transfer the CR role. Try again.") }
    }

    override fun updateSettings(classId: String, name: String, semester: String, requiredPercent: Int) {
        classDoc(classId)
            .update(mapOf("name" to name, "semester" to semester, "requiredPercent" to requiredPercent))
            .reportFailure { messages.show("Couldn't save class settings. Try again.") }
    }

    override suspend fun regenerateJoinCode(rollClass: RollClass): String {
        val code = freeJoinCode()
        val batch = firestore.batch()
        batch.update(classDoc(rollClass.id), "joinCode", code)
        batch.set(codeDoc(code), mapOf(F.CLASS_ID to rollClass.id))
        if (JoinCode.isValid(rollClass.joinCode)) batch.delete(codeDoc(rollClass.joinCode))
        batch.commit().await()
        return code
    }

    /** A code nobody uses yet. Reading from the server doubles as the "are we online" check. */
    private suspend fun freeJoinCode(): String {
        repeat(5) {
            val candidate = JoinCode.generate()
            val existing = try {
                codeDoc(candidate).get(Source.SERVER).await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw OfflineException()
                throw e
            }
            if (!existing.exists()) return candidate
        }
        error("Couldn't find a free join code")
    }

    private fun memberData(profile: UserProfile, code: String) = mapOf(
        "name" to profile.name,
        "rollNo" to profile.rollNo,
        "joinedAt" to FieldValue.serverTimestamp(),
        "joinCode" to code,
    )
}
