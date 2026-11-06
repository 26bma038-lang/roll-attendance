package app.roll.data.notices

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toNotice
import app.roll.domain.model.Notice
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

data class NoticeDraft(
    val text: String,
    val pinned: Boolean,
    val expiresAt: Instant?,
) {
    val isValid: Boolean get() = text.isNotBlank() && text.length <= MAX_LENGTH

    companion object {
        const val MAX_LENGTH = 500
    }
}

/**
 * Class notices (CRs) and college notices (admin). Both are small collections, so they use a
 * plain listener on `status == "active"` rather than delta sync: a deleted notice (tombstone)
 * simply leaves the query, and a cold start re-reads only the handful still active.
 */
interface NoticeRepository {
    fun observeClass(classId: String): Flow<List<Notice>>
    fun observeCollege(): Flow<List<Notice>>

    /** [classId] null posts a college notice (admin only). */
    fun post(classId: String?, draft: NoticeDraft, authorName: String)
    fun setPinned(classId: String?, notice: Notice, pinned: Boolean)
    fun delete(classId: String?, notice: Notice)
}

@Singleton
class FirestoreNoticeRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : NoticeRepository {

    private fun collection(classId: String?): CollectionReference =
        if (classId == null) {
            firestore.collection(F.NOTICES)
        } else {
            firestore.collection(F.CLASSES).document(classId).collection(F.NOTICES)
        }

    private fun observe(classId: String?): Flow<List<Notice>> =
        collection(classId).whereEqualTo("status", F.STATUS_ACTIVE).snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toNotice(college = classId == null) }
                    .sortedByDescending { it.createdAt ?: Instant.MAX }
            }
            .catch { logWarning("Notices listener failed", it) }

    override fun observeClass(classId: String): Flow<List<Notice>> = observe(classId)

    override fun observeCollege(): Flow<List<Notice>> = observe(null)

    override fun post(classId: String?, draft: NoticeDraft, authorName: String) {
        collection(classId).document().set(
            mapOf(
                "text" to draft.text.trim(),
                "pinned" to draft.pinned,
                "createdAt" to FieldValue.serverTimestamp(),
                "expiresAt" to draft.expiresAt?.let { Timestamp(Date.from(it)) },
                "createdByName" to authorName,
                "status" to F.STATUS_ACTIVE,
                F.UPDATED_AT to FieldValue.serverTimestamp(),
            ),
        ).reportFailure { messages.show("Couldn't post the notice. Try again.") }
    }

    override fun setPinned(classId: String?, notice: Notice, pinned: Boolean) {
        collection(classId).document(notice.id)
            .update(mapOf("pinned" to pinned, F.UPDATED_AT to FieldValue.serverTimestamp()))
            .reportFailure { messages.show("Couldn't update the notice. Try again.") }
    }

    override fun delete(classId: String?, notice: Notice) {
        collection(classId).document(notice.id)
            .update(mapOf("status" to F.STATUS_DELETED, F.UPDATED_AT to FieldValue.serverTimestamp()))
            .reportFailure { messages.show("Couldn't delete the notice. Try again.") }
    }
}
