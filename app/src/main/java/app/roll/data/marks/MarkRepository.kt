package app.roll.data.marks

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.deltaSync
import app.roll.data.firebase.firestoreCode
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toMarkRecord
import app.roll.data.firebase.wire
import app.roll.domain.model.Mark
import app.roll.domain.model.MarkRecord
import app.roll.domain.model.MemberMark
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class MyMarks(
    /** sessionId → mark. Cleared marks are absent from the map. */
    val marks: Map<String, MarkRecord>,
    /** True while some of the student's marks are queued and not yet on the server. */
    val hasPendingWrites: Boolean,
    val loaded: Boolean,
)

interface MarkRepository {
    fun observeMyMarks(uid: String, classId: String): Flow<MyMarks>

    /** `null` clears the mark. Works offline; queued until there is a connection. */
    fun setMark(classId: String, sessionId: String, uid: String, mark: Mark?)

    /**
     * Same as [setMark], for background work (notification actions, widget): waits until the
     * server confirms or [timeoutMillis] passes. Offline, the write is already saved locally and
     * syncs later.
     */
    suspend fun setMarkAndWait(classId: String, sessionId: String, uid: String, mark: Mark?, timeoutMillis: Long = 8_000)

    /** CR only: every member's mark for one session. */
    fun observeSessionMarks(classId: String, sessionId: String): Flow<List<MemberMark>>
}

@Singleton
class FirestoreMarkRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : MarkRepository {

    private fun markDoc(classId: String, sessionId: String, uid: String) =
        firestore.collection(F.CLASSES).document(classId)
            .collection(F.SESSIONS).document(sessionId)
            .collection(F.MARKS).document(uid)

    override fun observeMyMarks(uid: String, classId: String): Flow<MyMarks> {
        val mine = firestore.collectionGroup(F.MARKS).whereEqualTo(F.UID, uid)
        return deltaSync(
            fullQuery = mine,
            field = F.MARKED_AT,
            newerThan = { since -> if (since == null) mine else mine.whereGreaterThanOrEqualTo(F.MARKED_AT, since) },
        ).map { state ->
            MyMarks(
                marks = state.docs
                    .filter { it.getString(F.CLASS_ID) == classId }
                    .mapNotNull { it.toMarkRecord() }
                    .associateBy { it.sessionId },
                hasPendingWrites = state.hasPendingWrites,
                loaded = true,
            )
        }.catch { error ->
            logWarning("Marks listener failed", error)
            if (error.firestoreCode == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                messages.show("Marks need a database index. Deploy firestore.indexes.json (see README).")
            }
        }
    }

    private fun markData(classId: String, sessionId: String, uid: String, mark: Mark?) = mapOf(
        "status" to (mark?.wire() ?: F.STATUS_CLEARED),
        F.MARKED_AT to FieldValue.serverTimestamp(),
        F.UID to uid,
        F.CLASS_ID to classId,
        "sessionId" to sessionId,
    )

    override fun setMark(classId: String, sessionId: String, uid: String, mark: Mark?) {
        markDoc(classId, sessionId, uid)
            .set(markData(classId, sessionId, uid, mark))
            .reportFailure {
                messages.show("That mark wasn't saved: the class may be cancelled or outside the 2-day edit window.")
            }
    }

    override suspend fun setMarkAndWait(classId: String, sessionId: String, uid: String, mark: Mark?, timeoutMillis: Long) {
        val task = markDoc(classId, sessionId, uid).set(markData(classId, sessionId, uid, mark))
        withTimeoutOrNull(timeoutMillis) { runCatching { task.await() } }
    }

    override fun observeSessionMarks(classId: String, sessionId: String): Flow<List<MemberMark>> =
        firestore.collection(F.CLASSES).document(classId)
            .collection(F.SESSIONS).document(sessionId)
            .collection(F.MARKS)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toMarkRecord()?.let { MemberMark(uid = doc.id, mark = it.mark) }
                }
            }
            .catch { logWarning("Session marks listener failed", it) }
}
