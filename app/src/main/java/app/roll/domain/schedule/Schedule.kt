package app.roll.domain.schedule

import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.TimetableSlot
import java.time.LocalDate

/** A session the CR is about to post or edit. */
data class SessionDraft(
    val subjectId: String,
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
    val type: SessionType,
    val room: String,
    val weight: Int,
    val note: String = "",
) {
    val isValid: Boolean
        get() = subjectId.isNotBlank() && endMinutes > startMinutes && weight in 1..MAX_WEIGHT

    companion object {
        const val MAX_WEIGHT = 4

        fun fromSlot(slot: TimetableSlot, date: LocalDate) = SessionDraft(
            subjectId = slot.subjectId,
            date = date,
            startMinutes = slot.startMinutes,
            endMinutes = slot.endMinutes,
            type = slot.type,
            room = slot.room,
            weight = 1,
        )
    }
}

object TimetablePosting {
    /**
     * Timetable slots for [date]'s weekday that haven't been posted yet. A slot counts as posted
     * when a non-deleted session on that date has the same subject and start time.
     */
    fun slotsToPost(date: LocalDate, slots: List<TimetableSlot>, sessions: List<Session>): List<TimetableSlot> {
        val posted = sessions
            .filter { it.date == date && it.status != SessionStatus.Deleted }
            .map { it.subjectId to it.startMinutes }
            .toSet()
        return slots
            .filter { it.dayOfWeek == date.dayOfWeek.value }
            .filter { (it.subjectId to it.startMinutes) !in posted }
            .sortedBy { it.startMinutes }
    }

    /** Stable id so two taps (or two devices) can't post the same slot twice. */
    fun sessionIdFor(date: LocalDate, slot: TimetableSlot): String = "${date}_${slot.id}"
}
