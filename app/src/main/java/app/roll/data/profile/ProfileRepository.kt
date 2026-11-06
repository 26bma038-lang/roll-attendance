package app.roll.data.profile

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toUserProfile
import app.roll.domain.model.UserProfile
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class ProfileSnapshot(
    val profile: UserProfile?,
    /** True when this came from the offline cache and the server hasn't answered yet. */
    val fromCache: Boolean,
)

interface ProfileRepository {
    fun observe(uid: String): Flow<ProfileSnapshot>

    /** Creates or updates name and roll number; also updates the class member doc if any. */
    fun saveProfile(uid: String, name: String, rollNo: String, email: String, isNew: Boolean, classId: String?)

    fun clearClassId(uid: String)

    suspend fun deleteProfile(uid: String)
}

@Singleton
class FirestoreProfileRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : ProfileRepository {

    private fun userDoc(uid: String) = firestore.collection(F.USERS).document(uid)

    override fun observe(uid: String): Flow<ProfileSnapshot> =
        userDoc(uid).snapshots().map { ProfileSnapshot(it.toUserProfile(), it.metadata.isFromCache) }

    override fun saveProfile(uid: String, name: String, rollNo: String, email: String, isNew: Boolean, classId: String?) {
        val batch = firestore.batch()
        val data = buildMap<String, Any?> {
            put("name", name)
            put("rollNo", rollNo)
            put("email", email)
            if (isNew) {
                put(F.CLASS_ID, null)
                put("createdAt", FieldValue.serverTimestamp())
            }
        }
        batch.set(userDoc(uid), data, SetOptions.merge())
        if (classId != null) {
            batch.update(
                firestore.collection(F.CLASSES).document(classId).collection(F.MEMBERS).document(uid),
                mapOf("name" to name, "rollNo" to rollNo),
            )
        }
        batch.commit().reportFailure { messages.show("Couldn't save your profile. Try again.") }
    }

    override fun clearClassId(uid: String) {
        userDoc(uid).update(F.CLASS_ID, null).reportFailure { }
    }

    override suspend fun deleteProfile(uid: String) {
        userDoc(uid).delete().await()
    }
}
