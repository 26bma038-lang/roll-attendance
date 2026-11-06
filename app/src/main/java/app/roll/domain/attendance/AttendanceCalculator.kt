package app.roll.domain.attendance

import app.roll.domain.model.Mark
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import java.time.LocalDate
import java.time.LocalDateTime

/** How one session stands for one student at a given moment. */
enum class SessionState {
    Present,
    Absent,

    /** Already over and never marked. Counted as absent. */
    UnmarkedPast,

    /** Not over yet and not marked. Not counted. */
    Upcoming,
    Cancelled,
    Deleted,
}

/**
 * Weighted counts for a set of sessions. Unmarked past sessions are counted as absent,
 * and reported separately so the UI can say "includes N unmarked".
 */
data class Tally(
    val attended: Int = 0,
    val absent: Int = 0,
    val unmarkedPast: Int = 0,
    /** Number of unmarked past sessions (not weighted), for the "includes N unmarked" note. */
    val unmarkedPastCount: Int = 0,
) {
    val total: Int get() = attended + absent + unmarkedPast

    /** Null when nothing counts yet: 0 / 0 is not 0%. */
    val percent: Double? get() = if (total == 0) null else attended * 100.0 / total

    operator fun plus(other: Tally) = Tally(
        attended = attended + other.attended,
        absent = absent + other.absent,
        unmarkedPast = unmarkedPast + other.unmarkedPast,
        unmarkedPastCount = unmarkedPastCount + other.unmarkedPastCount,
    )
}

/** What the student needs to do, in numbers. The UI turns this into words. */
sealed interface AttendanceStatus {
    data object NoClasses : AttendanceStatus

    /** At or above the requirement. [count] may be 0 ("can't miss the next one"). */
    data class CanMiss(val count: Int) : AttendanceStatus

    /** Below the requirement; attending the next [count] in a row gets back to it. */
    data class MustAttend(val count: Int) : AttendanceStatus

    /** Below a 100% requirement: no number of classes gets back to it. */
    data object Unreachable : AttendanceStatus
}

/**
 * Pure attendance arithmetic. No Android, no Firebase: everything here is unit-tested.
 *
 * Percentage = attended / (attended + absent + unmarked past) × 100, all weighted.
 */
object AttendanceCalculator {

    fun stateOf(session: Session, mark: Mark?, now: LocalDateTime): SessionState = when (session.status) {
        SessionStatus.Deleted -> SessionState.Deleted
        SessionStatus.Cancelled -> SessionState.Cancelled
        SessionStatus.Scheduled -> when (mark) {
            Mark.Present -> SessionState.Present
            Mark.Absent -> SessionState.Absent
            null -> if (isOver(session, now)) SessionState.UnmarkedPast else SessionState.Upcoming
        }
    }

    /** A session is over once its end time has passed, in the class's local time. */
    fun isOver(session: Session, now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        return when {
            session.date.isBefore(today) -> true
            session.date.isAfter(today) -> false
            else -> session.endMinutes <= now.hour * 60 + now.minute
        }
    }

    fun tally(sessions: Iterable<Session>, marks: Map<String, Mark>, now: LocalDateTime): Tally {
        var attended = 0
        var absent = 0
        var unmarked = 0
        var unmarkedCount = 0
        for (session in sessions) {
            val weight = session.weight.coerceAtLeast(1)
            when (stateOf(session, marks[session.id], now)) {
                SessionState.Present -> attended += weight
                SessionState.Absent -> absent += weight
                SessionState.UnmarkedPast -> {
                    unmarked += weight
                    unmarkedCount++
                }
                SessionState.Upcoming, SessionState.Cancelled, SessionState.Deleted -> Unit
            }
        }
        return Tally(attended, absent, unmarked, unmarkedCount)
    }

    fun status(tally: Tally, requiredPercent: Int): AttendanceStatus {
        if (tally.total == 0) return AttendanceStatus.NoClasses
        return if (meets(tally.attended, tally.total, requiredPercent)) {
            AttendanceStatus.CanMiss(canMiss(tally.attended, tally.total, requiredPercent))
        } else {
            mustAttend(tally.attended, tally.total, requiredPercent)
                ?.let { AttendanceStatus.MustAttend(it) }
                ?: AttendanceStatus.Unreachable
        }
    }

    /** attended / total ≥ required, in exact integer arithmetic. */
    fun meets(attended: Int, total: Int, requiredPercent: Int): Boolean {
        checkRequired(requiredPercent)
        if (total == 0) return true
        return attended.toLong() * 100 >= requiredPercent.toLong() * total
    }

    /**
     * Largest N such that attended / (total + N) ≥ required. 0 if already below.
     * attended·100 ≥ p·(total + N)  ⇔  N ≤ attended·100 / p − total.
     */
    fun canMiss(attended: Int, total: Int, requiredPercent: Int): Int {
        checkRequired(requiredPercent)
        val limit = attended.toLong() * 100 / requiredPercent - total
        return limit.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Smallest N such that (attended + N) / (total + N) ≥ required. 0 if already there.
     * (a + N)·100 ≥ p·(t + N)  ⇔  N·(100 − p) ≥ p·t − 100·a.
     * Returns null when it can never be reached (p = 100 with any absence).
     */
    fun mustAttend(attended: Int, total: Int, requiredPercent: Int): Int? {
        checkRequired(requiredPercent)
        val deficit = requiredPercent.toLong() * total - attended.toLong() * 100
        if (deficit <= 0) return 0
        val headroom = 100L - requiredPercent
        if (headroom == 0L) return null
        return ((deficit + headroom - 1) / headroom).toInt()
    }

    /** Percentage after missing the next [missNext] classes (each of weight 1). */
    fun projectedPercent(attended: Int, total: Int, missNext: Int): Double? {
        require(missNext >= 0) { "missNext must be ≥ 0" }
        val newTotal = total + missNext
        return if (newTotal == 0) null else attended * 100.0 / newTotal
    }

    private fun checkRequired(requiredPercent: Int) {
        require(requiredPercent in 1..100) { "Required percent must be 1–100, was $requiredPercent" }
    }
}

/**
 * Students can mark today and the two days before it. Mirrors the rule in firestore.rules.
 */
object EditWindow {
    const val DAYS_BACK = 2L

    fun isEditable(date: LocalDate, today: LocalDate): Boolean =
        !date.isAfter(today) && !date.isBefore(today.minusDays(DAYS_BACK))
}
