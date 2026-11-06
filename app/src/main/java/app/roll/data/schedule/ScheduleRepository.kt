package app.roll.data.schedule

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.deltaSync
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toSession
import app.roll.data.firebase.toSubject
import app.roll.data.firebase.toTimetableSlot
import app.roll.data.firebase.wire
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.Subject
import app.roll.domain.model.TimetableSlot
import app.roll.domain.schedule.SessionDraft
import app.roll.domain.schedule.TimetablePosting
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class SessionsSnapshot(
    /** Every session including cancelled; deleted tombstones are dropped. */
    val sessions: List<Session>,
    /** Ids of deleted tombstones, so a stable id is never reused for a new session. */
    val deletedIds: Set<String>,
    val loaded: Boolean,
)

interface ScheduleRepository {
    fun observeSubjects(classId: String): Flow<List<Subject>>
    fun saveSubject(classId: String, subject: Subject)

    fun observeTimetable(classId: String): Flow<List<TimetableSlot>>
    fun saveSlot(classId: String, slot: TimetableSlot)
    fun deleteSlot(classId: String, slotId: String)

    fun observeSessions(classId: String): Flow<SessionsSnapshot>
    fun postSession(classId: String, draft: SessionDraft, crUid: String, crName: String)
    fun updateSession(classId: String, session: Session, draft: SessionDraft)
    fun setCancelled(classId: String, session: Session, cancelled: Boolean, note: String)
    suspend fun deleteSession(classId: String, sessionId: String)

    /** Posts the day's unposted timetable slots. Returns how many were posted. */
    fun postFromTimetable(
        classId: String,
        date: LocalDate,
        slots: List<TimetableSlot>,
        existing: List<Session>,
        deletedIds: Set<String>,
        crUid: String,
        crName: String,
    ): Int

    /** Asks the server for one day's sessions. False if offline. */
    suspend fun refreshDay(classId: String, date: LocalDate): Boolean
}

@Singleton
class FirestoreScheduleRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : ScheduleRepository {

    private fun classDoc(classId: String) = firestore.collection(F.CLASSES).document(classId)
    private fun subjects(classId: String) = classDoc(classId).collection(F.SUBJECTS)
    private fun timetable(classId: String) = classDoc(classId).collection(F.TIMETABLE)
    private fun sessions(classId: String) = classDoc(classId).collection(F.SESSIONS)

    override fun observeSubjects(classId: String): Flow<List<Subject>> =
        subjects(classId).snapshots()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toSubject() }.sortedBy { it.name.lowercase() } }
            .catch { logWarning("Subjects listener failed", it) }

    override fun saveSubject(classId: String, subject: Subject) {
        val ref = if (subject.id.isBlank()) subjects(classId).document() else subjects(classId).document(subject.id)
        ref.set(
            mapOf(
                "name" to subject.name,
                "shortName" to subject.shortName,
                "code" to subject.code,
                "faculty" to subject.faculty,
                "colorIndex" to subject.colorIndex,
                "archived" to subject.archived,
            ),
        ).reportFailure { messages.show("Couldn't save ${subject.shortName}. Try again.") }
    }

    override fun observeTimetable(classId: String): Flow<List<TimetableSlot>> =
        timetable(classId).snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { it.toTimetableSlot() }
                    .sortedWith(compareBy({ it.dayOfWeek }, { it.startMinutes }))
            }
            .catch { logWarning("Timetable listener failed", it) }

    override fun saveSlot(classId: String, slot: TimetableSlot) {
        val ref = if (slot.id.isBlank()) timetable(classId).document() else timetable(classId).document(slot.id)
        ref.set(
            mapOf(
                "dayOfWeek" to slot.dayOfWeek,
                "startMinutes" to slot.startMinutes,
                "endMinutes" to slot.endMinutes,
                "subjectId" to slot.subjectId,
                "type" to slot.type.wire(),
                "room" to slot.room,
            ),
        ).reportFailure { messages.show("Couldn't save the timetable slot. Try again.") }
    }

    override fun deleteSlot(classId: String, slotId: String) {
        timetable(classId).document(slotId).delete()
            .reportFailure { messages.show("Couldn't delete the slot. Try again.") }
    }

    override fun observeSessions(classId: String): Flow<SessionsSnapshot> {
        val all = sessions(classId)
        return deltaSync(
            fullQuery = all,
            field = F.UPDATED_AT,
            newerThan = { since -> if (since == null) all else all.whereGreaterThanOrEqualTo(F.UPDATED_AT, since) },
        ).map { state ->
            val parsed = state.docs.mapNotNull { it.toSession() }
            SessionsSnapshot(
                sessions = parsed.filter { it.status != SessionStatus.Deleted }
                    .sortedWith(compareBy({ it.date }, { it.startMinutes })),
                deletedIds = parsed.filter { it.status == SessionStatus.Deleted }.map { it.id }.toSet(),
                loaded = true,
            )
        }.catch { logWarning("Sessions listener failed", it) }
    }

    private fun sessionData(draft: SessionDraft, status: SessionStatus) = mapOf(
        "subjectId" to draft.subjectId,
        F.DATE to draft.date.toString(),
        "startMinutes" to draft.startMinutes,
        "endMinutes" to draft.endMinutes,
        "type" to draft.type.wire(),
        "status" to status.wire(),
        "note" to draft.note,
        "room" to draft.room,
        "weight" to draft.weight,
        F.UPDATED_AT to FieldValue.serverTimestamp(),
    )

    private fun newSessionData(draft: SessionDraft, crUid: String, crName: String) =
        sessionData(draft, SessionStatus.Scheduled) + mapOf(
            "createdBy" to crUid,
            "createdByName" to crName,
            "createdAt" to FieldValue.serverTimestamp(),
        )

    override fun postSession(classId: String, draft: SessionDraft, crUid: String, crName: String) {
        sessions(classId).document().set(newSessionData(draft, crUid, crName))
            .reportFailure { messages.show("Couldn't post the class. Try again.") }
    }

    override fun updateSession(classId: String, session: Session, draft: SessionDraft) {
        sessions(classId).document(session.id)
            .update(sessionData(draft.copy(note = session.note), session.status))
            .reportFailure { messages.show("Couldn't save the class. Try again.") }
    }

    override fun setCancelled(classId: String, session: Session, cancelled: Boolean, note: String) {
        sessions(classId).document(session.id)
            .update(
                mapOf(
                    "status" to (if (cancelled) SessionStatus.Cancelled else SessionStatus.Scheduled).wire(),
                    "note" to note.take(200),
                    F.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
            )
            .reportFailure { messages.show("Couldn't update the class. Try again.") }
    }

    override suspend fun deleteSession(classId: String, sessionId: String) {
        val ref = sessions(classId).document(sessionId)
        // Marks go with the session; the session itself becomes a tombstone so other
        // devices' incremental sync sees it disappear.
        val marks = runCatching { ref.collection(F.MARKS).get().await() }.getOrNull()
        val batch = firestore.batch()
        marks?.documents?.forEach { batch.delete(it.reference) }
        batch.update(ref, mapOf("status" to SessionStatus.Deleted.wire(), F.UPDATED_AT to FieldValue.serverTimestamp()))
        batch.commit().reportFailure { messages.show("Couldn't delete the class. Try again.") }
    }

    override fun postFromTimetable(
        classId: String,
        date: LocalDate,
        slots: List<TimetableSlot>,
        existing: List<Session>,
        deletedIds: Set<String>,
        crUid: String,
        crName: String,
    ): Int {
        val toPost = TimetablePosting.slotsToPost(date, slots, existing)
        if (toPost.isEmpty()) return 0
        val takenIds = existing.map { it.id }.toSet() + deletedIds
        val batch = firestore.batch()
        for (slot in toPost) {
            val stableId = TimetablePosting.sessionIdFor(date, slot)
            // A deleted tombstone may already hold the stable id; use a fresh one then.
            val ref = if (stableId in takenIds) sessions(classId).document() else sessions(classId).document(stableId)
            batch.set(ref, newSessionData(SessionDraft.fromSlot(slot, date), crUid, crName))
        }
        batch.commit().reportFailure { messages.show("Couldn't post today's classes. Try again.") }
        return toPost.size
    }

    override suspend fun refreshDay(classId: String, date: LocalDate): Boolean {
        val result = withTimeoutOrNull(8_000) {
            runCatching { sessions(classId).whereEqualTo(F.DATE, date.toString()).get(Source.SERVER).await() }
        }
        return result?.isSuccess == true
    }
}
