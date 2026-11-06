package app.roll.data.assignments

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.deltaSync
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toAssignment
import app.roll.data.firebase.wire
import app.roll.domain.model.Assignment
import app.roll.domain.model.AssignmentType
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** What the CR fills in when posting or editing an assignment. */
data class AssignmentDraft(
    val title: String,
    val subjectId: String,
    val type: AssignmentType,
    val description: String,
    val link: String,
    val dueAt: Instant,
) {
    val isValid: Boolean get() = title.isNotBlank() && subjectId.isNotBlank()
}

interface AssignmentRepository {
    /** Active assignments of the class. Delta-synced, like sessions. */
    fun observe(classId: String): Flow<List<Assignment>>
    fun post(classId: String, draft: AssignmentDraft, uid: String, name: String)
    fun update(classId: String, assignment: Assignment, draft: AssignmentDraft)
    /** Tombstone, so other phones' incremental sync sees it go. */
    fun delete(classId: String, assignment: Assignment)

    /** The student's private "done" ticks: assignment id → done. Small, plain listener. */
    fun observeDone(uid: String): Flow<Set<String>>
    fun setDone(uid: String, assignmentId: String, done: Boolean)
}

@Singleton
class FirestoreAssignmentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : AssignmentRepository {

    private fun assignments(classId: String) =
        firestore.collection(F.CLASSES).document(classId).collection(F.ASSIGNMENTS)

    private fun statuses(uid: String) =
        firestore.collection(F.USERS).document(uid).collection(F.ASSIGNMENT_STATUS)

    override fun observe(classId: String): Flow<List<Assignment>> {
        val all = assignments(classId)
        return deltaSync(
            fullQuery = all,
            field = F.UPDATED_AT,
            newerThan = { since -> if (since == null) all else all.whereGreaterThanOrEqualTo(F.UPDATED_AT, since) },
        ).map { state -> state.docs.mapNotNull { it.toAssignment() }.sortedBy { it.dueAt } }
            .catch { logWarning("Assignments listener failed", it) }
    }

    private fun fields(draft: AssignmentDraft) = mapOf(
        "title" to draft.title.trim(),
        "subjectId" to draft.subjectId,
        "type" to draft.type.wire,
        "description" to draft.description.trim(),
        "link" to draft.link.trim(),
        "dueAt" to Timestamp(Date.from(draft.dueAt)),
        F.UPDATED_AT to FieldValue.serverTimestamp(),
        "status" to F.STATUS_ACTIVE,
    )

    override fun post(classId: String, draft: AssignmentDraft, uid: String, name: String) {
        assignments(classId).document().set(
            fields(draft) + mapOf(
                "createdBy" to uid,
                "createdByName" to name,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).reportFailure { messages.show("Couldn't post the assignment. Try again.") }
    }

    override fun update(classId: String, assignment: Assignment, draft: AssignmentDraft) {
        assignments(classId).document(assignment.id).update(fields(draft))
            .reportFailure { messages.show("Couldn't save the assignment. Try again.") }
    }

    override fun delete(classId: String, assignment: Assignment) {
        assignments(classId).document(assignment.id)
            .update(mapOf("status" to F.STATUS_DELETED, F.UPDATED_AT to FieldValue.serverTimestamp()))
            .reportFailure { messages.show("Couldn't delete the assignment. Try again.") }
    }

    override fun observeDone(uid: String): Flow<Set<String>> =
        statuses(uid).snapshots()
            .map { snapshot -> snapshot.documents.filter { it.getBoolean("done") == true }.map { it.id }.toSet() }
            .catch { logWarning("Assignment status listener failed", it) }

    override fun setDone(uid: String, assignmentId: String, done: Boolean) {
        statuses(uid).document(assignmentId)
            .set(mapOf("done" to done, "doneAt" to FieldValue.serverTimestamp()))
            .reportFailure { messages.show("Couldn't save that. Try again.") }
    }
}
