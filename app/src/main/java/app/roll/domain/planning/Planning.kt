package app.roll.domain.planning

import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.attendance.SessionState
import app.roll.domain.attendance.Tally
import app.roll.domain.model.Mark
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.TimetableSlot
import java.time.LocalDate
import java.time.LocalDateTime

/** A class the student is expected to have on a day, predicted from what's posted or the timetable. */
data class ExpectedClass(
    val date: LocalDate,
    val subjectId: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val weight: Int,
    /** The posted session, when there is one. */
    val sessionId: String?,
)

/**
 * The classes still to come on [date]: the posted sessions if the CR has posted that day,
 * otherwise the timetable for that weekday. On [now]'s own date only classes not yet over
 * and not yet marked are returned.
 */
fun expectedClasses(
    date: LocalDate,
    sessions: List<Session>,
    slots: List<TimetableSlot>,
    marks: Map<String, Mark>,
    now: LocalDateTime,
): List<ExpectedClass> {
    if (date.isBefore(now.toLocalDate())) return emptyList()
    val posted = sessions.filter { it.date == date && it.status != SessionStatus.Deleted }
    return if (posted.isNotEmpty()) {
        posted
            .filter { AttendanceCalculator.stateOf(it, marks[it.id], now) == SessionState.Upcoming }
            .map { ExpectedClass(date, it.subjectId, it.startMinutes, it.endMinutes, it.weight.coerceAtLeast(1), it.id) }
    } else {
        val minutesNow = if (date == now.toLocalDate()) now.hour * 60 + now.minute else -1
        slots
            .filter { it.dayOfWeek == date.dayOfWeek.value && it.endMinutes > minutesNow }
            .map { ExpectedClass(date, it.subjectId, it.startMinutes, it.endMinutes, 1, null) }
    }.sortedBy { it.startMinutes }
}

/** What taking leave on some days does to one subject. */
data class LeaveImpact(
    val subjectId: String,
    val current: Tally,
    /** Weighted classes on the leave days. */
    val missed: Int,
    /** Weighted classes between now and the last leave day that the student would attend. */
    val attendedBefore: Int,
    val projectedPercent: Double?,
    val meetsRequirement: Boolean,
)

/**
 * The leave planner. Assumes the student attends every other class between now and the last
 * leave day, and misses every class on the leave days. Past dates are ignored.
 */
object LeavePlanner {

    fun plan(
        leaveDates: Set<LocalDate>,
        subjectIds: List<String>,
        sessions: List<Session>,
        slots: List<TimetableSlot>,
        marks: Map<String, Mark>,
        now: LocalDateTime,
        requiredPercent: Int,
    ): List<LeaveImpact> {
        val today = now.toLocalDate()
        val leave = leaveDates.filter { !it.isBefore(today) }.toSet()
        val last = leave.maxOrNull()
        val upcoming = if (last == null) {
            emptyList()
        } else {
            generateSequence(today) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .flatMap { expectedClasses(it, sessions, slots, marks, now) }
                .toList()
        }
        return subjectIds.map { subjectId ->
            val current = AttendanceCalculator.tally(sessions.filter { it.subjectId == subjectId }, marks, now)
            val mine = upcoming.filter { it.subjectId == subjectId }
            val missed = mine.filter { it.date in leave }.sumOf { it.weight }
            val attendedBefore = mine.filter { it.date !in leave }.sumOf { it.weight }
            val attended = current.attended + attendedBefore
            val total = current.total + attendedBefore + missed
            LeaveImpact(
                subjectId = subjectId,
                current = current,
                missed = missed,
                attendedBefore = attendedBefore,
                projectedPercent = if (total == 0) null else attended * 100.0 / total,
                meetsRequirement = AttendanceCalculator.meets(attended, total, requiredPercent),
            )
        }
    }
}

/** A "mark attendance" prompt at the end of one class. */
data class ClassEndPrompt(
    val date: LocalDate,
    val subjectId: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val sessionId: String?,
) {
    /** One WorkManager job per class: stable across re-plans of the same day. */
    val key: String get() = "class-end-$date-$startMinutes-$subjectId"
}

object ClassEndPlanner {
    /** Prompts for the rest of today: posted classes, or the timetable when nothing is posted yet. */
    fun forToday(
        sessions: List<Session>,
        slots: List<TimetableSlot>,
        marks: Map<String, Mark>,
        now: LocalDateTime,
    ): List<ClassEndPrompt> =
        // Cancelled and already-marked classes are left out by expectedClasses.
        expectedClasses(now.toLocalDate(), sessions, slots, marks, now)
            .map { ClassEndPrompt(it.date, it.subjectId, it.startMinutes, it.endMinutes, it.sessionId) }
}
