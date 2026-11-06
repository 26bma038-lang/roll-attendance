package app.roll.data.classes

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.firestoreCode
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toMember
import app.roll.data.firebase.toRollClass
import app.roll.domain.college.NithConfig
import app.roll.domain.college.NithIdentity
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
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
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ClassResult {
    data class Loaded(val rollClass: RollClass, val fromCache: Boolean) : ClassResult

    /** Doc doesn't exist (e.g. cache empty and offline). */
    data class Missing(val fromCache: Boolean) : ClassResult

    /** The server says we can't read it. */
    data object AccessLost : ClassResult
}

class OfflineException : Exception("Needs a connection")

interface ClassRepository {
    fun observeClass(classId: String): Flow<ClassResult>

    /** CRs and the admin only. Sorted by roll number. */
    fun observeMembers(classId: String): Flow<List<Member>>

    /**
     * Creates the student's profile and puts them in their batch + branch class, creating the
     * class (with no CRs) if they are its first student. Needs a connection.
     */
    suspend fun joinOwnClass(uid: String, identity: NithIdentity, name: String, today: LocalDate)

    /** CR: name, semester and required percentage. */
    fun updateSettings(classId: String, name: String, semester: String, requiredPercent: Int)

    /** Admin only: every class, for appointing CRs. */
    fun observeAllClasses(): Flow<List<RollClass>>

    /** Admin only. [crUids] and [crNames] are parallel lists, at most three. */
    fun setCRs(classId: String, crUids: List<String>, crNames: List<String>)

    /** Removes the member doc (and the count) and waits for the server. Before deleting an account. */
    suspend fun deleteMembership(uid: String, classId: String)
}

@Singleton
class FirestoreClassRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : ClassRepository {

    private fun classDoc(classId: String) = firestore.collection(F.CLASSES).document(classId)
    private fun memberDoc(classId: String, uid: String) = classDoc(classId).collection(F.MEMBERS).document(uid)
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
            // Right after joining, our own writes may still be in flight. Wait for them, then retry once.
            retried = true
            withTimeoutOrNull(10_000) { firestore.waitForPendingWrites().await() }
        }
    }

    override fun observeMembers(classId: String): Flow<List<Member>> =
        classDoc(classId).collection(F.MEMBERS).snapshots()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toMember() }.sortedBy { it.rollNo } }
            .catch { logWarning("Members listener failed", it) }

    override suspend fun joinOwnClass(uid: String, identity: NithIdentity, name: String, today: LocalDate) {
        val classId = identity.classId
        // Reading from the server doubles as the "are we online" check.
        val (classSnapshot, memberSnapshot) = try {
            classDoc(classId).get(Source.SERVER).await() to memberDoc(classId, uid).get(Source.SERVER).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) throw OfflineException()
            throw e
        }
        val batch = firestore.batch()
        batch.set(
            userDoc(uid),
            mapOf(
                "name" to name,
                "rollNo" to identity.rollNo,
                "email" to identity.email,
                "batch" to identity.batch,
                "branch" to identity.branch,
                F.CLASS_ID to classId,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        if (!memberSnapshot.exists()) {
            batch.set(
                memberDoc(classId, uid),
                mapOf("name" to name, "rollNo" to identity.rollNo, "joinedAt" to FieldValue.serverTimestamp()),
            )
            if (classSnapshot.exists()) {
                batch.update(classDoc(classId), "memberCount", FieldValue.increment(1))
            } else {
                batch.set(
                    classDoc(classId),
                    mapOf(
                        "name" to NithConfig.defaultClassName(identity.batch, identity.branch),
                        "semester" to NithConfig.semesterLabel(identity.batch, today),
                        "batch" to identity.batch,
                        "branch" to identity.branch,
                        "crUids" to emptyList<String>(),
                        "crNames" to emptyList<String>(),
                        "requiredPercent" to NithConfig.DEFAULT_REQUIRED_PERCENT,
                        "timezone" to NithConfig.TIMEZONE,
                        "memberCount" to 1,
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
        }
        batch.commit().await()
    }

    override fun updateSettings(classId: String, name: String, semester: String, requiredPercent: Int) {
        classDoc(classId)
            .update(mapOf("name" to name, "semester" to semester, "requiredPercent" to requiredPercent))
            .reportFailure { messages.show("Couldn't save class settings. Try again.") }
    }

    override fun observeAllClasses(): Flow<List<RollClass>> =
        firestore.collection(F.CLASSES).snapshots()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toRollClass() }.sortedWith(compareBy({ -it.batch }, { it.branch })) }
            .catch { logWarning("Classes listener failed", it) }

    override fun setCRs(classId: String, crUids: List<String>, crNames: List<String>) {
        require(crUids.size == crNames.size && crUids.size <= NithConfig.MAX_CRS)
        classDoc(classId)
            .update(mapOf("crUids" to crUids, "crNames" to crNames))
            .reportFailure { messages.show("Couldn't update the CRs. Try again.") }
    }

    override suspend fun deleteMembership(uid: String, classId: String) {
        val batch = firestore.batch()
        batch.delete(memberDoc(classId, uid))
        batch.update(classDoc(classId), "memberCount", FieldValue.increment(-1))
        batch.commit().await()
    }
}
