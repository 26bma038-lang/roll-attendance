package app.roll.feature.common

import app.roll.domain.attendance.AttendanceStatus
import app.roll.domain.attendance.SessionState
import app.roll.domain.model.SessionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain words for attendance numbers, in the timetable voice of the brief. */
object AttendanceCopy {

    fun status(status: AttendanceStatus, requiredPercent: Int): String = when (status) {
        AttendanceStatus.NoClasses -> "No classes counted yet"
        is AttendanceStatus.CanMiss -> when (status.count) {
            0 -> "Can't miss the next one"
            else -> "Can miss ${status.count} more"
        }
        is AttendanceStatus.MustAttend -> when (status.count) {
            1 -> "Attend the next one to reach $requiredPercent%"
            else -> "Attend the next ${status.count} to reach $requiredPercent%"
        }
        AttendanceStatus.Unreachable -> "Can't reach $requiredPercent% any more"
    }

    fun isBelow(status: AttendanceStatus): Boolean =
        status is AttendanceStatus.MustAttend || status == AttendanceStatus.Unreachable

    fun unmarkedNote(count: Int): String? = when (count) {
        0 -> null
        1 -> "includes 1 unmarked"
        else -> "includes $count unmarked"
    }

    fun stateLabel(state: SessionState): String = when (state) {
        SessionState.Present -> "Present"
        SessionState.Absent -> "Absent"
        SessionState.UnmarkedPast -> "Not marked"
        SessionState.Upcoming -> "Upcoming"
        SessionState.Cancelled -> "Cancelled"
        SessionState.Deleted -> "Deleted"
    }

    fun typeLabel(type: SessionType): String = when (type) {
        SessionType.Lecture -> "Lecture"
        SessionType.Lab -> "Lab"
        SessionType.Tutorial -> "Tutorial"
    }

    fun classes(count: Int): String = if (count == 1) "1 class" else "$count classes"

    private val ShortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val MonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    /** "Thu 5 Nov". */
    fun shortDate(date: LocalDate): String = ShortDay.format(date)

    /** "November 2026". */
    fun monthYear(date: LocalDate): String = MonthYear.format(date)
}
