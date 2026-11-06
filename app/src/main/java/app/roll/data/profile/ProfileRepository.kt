package app.roll.data.profile

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toUserProfile
import app.roll.domain.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestore
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

    /** Only the name is editable; roll number, batch and branch come from the email. */
    fun updateName(uid: String, name: String, classId: String?)

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

    override fun updateName(uid: String, name: String, classId: String?) {
        val batch = firestore.batch()
        batch.update(userDoc(uid), "name", name)
        if (classId != null) {
            batch.update(firestore.collection(F.CLASSES).document(classId).collection(F.MEMBERS).document(uid), "name", name)
        }
        batch.commit().reportFailure { messages.show("Couldn't save your name. Try again.") }
    }

    override suspend fun deleteProfile(uid: String) {
        userDoc(uid).delete().await()
    }
}
