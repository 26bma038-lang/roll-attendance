package app.roll.domain.attendance

import app.roll.domain.model.Mark
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AttendanceCalculatorTest {

    private val today = LocalDate.of(2026, 11, 5)

    /** 12:00 on [today]. Sessions ending at or before noon today are over. */
    private val noon = today.atTime(12, 0)

    private fun session(
        id: String,
        date: LocalDate = today.minusDays(1),
        start: Int = 9 * 60,
        end: Int = 9 * 60 + 50,
        status: SessionStatus = SessionStatus.Scheduled,
        weight: Int = 1,
    ) = Session(
        id = id, subjectId = "daa", date = date, startMinutes = start, endMinutes = end,
        type = SessionType.Lecture, status = status, note = "", room = "", weight = weight,
        createdBy = "cr", createdByName = "CR", createdAt = null,
    )

    private fun pastSessions(count: Int) = (1..count).map { session("s$it") }

    // region tally

    @Test
    fun zeroSessions_hasNoPercentage() {
        val tally = AttendanceCalculator.tally(emptyList(), emptyMap(), noon)
        assertEquals(0, tally.total)
        assertNull(tally.percent)
        assertEquals(AttendanceStatus.NoClasses, AttendanceCalculator.status(tally, 75))
    }

    @Test
    fun allCancelled_countsNothing() {
        val sessions = (1..5).map { session("s$it", status = SessionStatus.Cancelled) }
        val marks = sessions.associate { it.id to Mark.Present }
        val tally = AttendanceCalculator.tally(sessions, marks, noon)
        assertEquals(Tally(), tally)
        assertNull(tally.percent)
    }

    @Test
    fun deletedSessions_areIgnoredEvenWithMarks() {
        val sessions = listOf(session("a"), session("b", status = SessionStatus.Deleted))
        val marks = mapOf("a" to Mark.Present, "b" to Mark.Absent)
        val tally = AttendanceCalculator.tally(sessions, marks, noon)
        assertEquals(1, tally.total)
        assertEquals(100.0, tally.percent!!, 0.0)
    }

    @Test
    fun unmarkedPastSessions_countAsAbsent_andAreReported() {
        val sessions = pastSessions(4)
        val marks = mapOf("s1" to Mark.Present, "s2" to Mark.Present, "s3" to Mark.Absent)
        val tally = AttendanceCalculator.tally(sessions, marks, noon)
        assertEquals(Tally(attended = 2, absent = 1, unmarkedPast = 1, unmarkedPastCount = 1), tally)
        assertEquals(50.0, tally.percent!!, 1e-9)
    }

    @Test
    fun upcomingUnmarkedSessions_areNotCounted() {
        val sessions = listOf(
            session("past"),
            session("laterToday", date = today, start = 14 * 60, end = 15 * 60),
            session("tomorrow", date = today.plusDays(1)),
        )
        val tally = AttendanceCalculator.tally(sessions, mapOf("past" to Mark.Present), noon)
        assertEquals(1, tally.total)
    }

    @Test
    fun markedUpcomingSession_counts() {
        val later = session("laterToday", date = today, start = 14 * 60, end = 15 * 60)
        val tally = AttendanceCalculator.tally(listOf(later), mapOf("laterToday" to Mark.Present), noon)
        assertEquals(1, tally.attended)
    }

    @Test
    fun sessionEndingExactlyNow_isOver() {
        val endsAtNoon = session("x", date = today, start = 11 * 60, end = 12 * 60)
        assertTrue(AttendanceCalculator.isOver(endsAtNoon, noon))
        assertEquals(SessionState.UnmarkedPast, AttendanceCalculator.stateOf(endsAtNoon, null, noon))
        assertFalse(AttendanceCalculator.isOver(endsAtNoon, today.atTime(11, 59)))
    }

    @Test
    fun weights_scaleEveryBucket() {
        val sessions = listOf(
            session("lab", weight = 2),
            session("lecture"),
            session("missedLab", weight = 2),
            session("unmarkedLab", weight = 2),
        )
        val marks = mapOf("lab" to Mark.Present, "lecture" to Mark.Present, "missedLab" to Mark.Absent)
        val tally = AttendanceCalculator.tally(sessions, marks, noon)
        assertEquals(Tally(attended = 3, absent = 2, unmarkedPast = 2, unmarkedPastCount = 1), tally)
        assertEquals(7, tally.total)
        assertEquals(300.0 / 7, tally.percent!!, 1e-9)
    }

    @Test
    fun nonPositiveWeight_isTreatedAsOne() {
        val tally = AttendanceCalculator.tally(listOf(session("x", weight = 0)), mapOf("x" to Mark.Present), noon)
        assertEquals(1, tally.attended)
    }

    @Test
    fun tallies_add() {
        val a = Tally(1, 2, 3, 1)
        val b = Tally(4, 5, 6, 2)
        assertEquals(Tally(5, 7, 9, 3), a + b)
    }

    // endregion

    // region status

    @Test
    fun exactlyAtRequirement_canMissZero() {
        // 3 of 4 = 75.0%. Missing one more gives 3 / 5 = 60%.
        assertTrue(AttendanceCalculator.meets(3, 4, 75))
        assertEquals(0, AttendanceCalculator.canMiss(3, 4, 75))
        assertEquals(AttendanceStatus.CanMiss(0), AttendanceCalculator.status(Tally(attended = 3, absent = 1), 75))
    }

    @Test
    fun exactlyAtRequirement_largerNumbers() {
        // 18 / 24 = 75%: not one more miss.
        assertEquals(AttendanceStatus.CanMiss(0), AttendanceCalculator.status(Tally(attended = 18, absent = 6), 75))
        // 30 / 40 = 75%.
        assertEquals(0, AttendanceCalculator.canMiss(30, 40, 75))
    }

    @Test
    fun canMiss_matchesBruteForce() {
        for (total in 0..60) for (attended in 0..total) for (p in listOf(50, 60, 65, 70, 75, 80, 85, 90, 99)) {
            if (!AttendanceCalculator.meets(attended, total, p)) continue
            var expected = 0
            while (attended * 100L >= p.toLong() * (total + expected + 1)) expected++
            assertEquals("a=$attended t=$total p=$p", expected, AttendanceCalculator.canMiss(attended, total, p))
        }
    }

    @Test
    fun mustAttend_matchesBruteForce() {
        for (total in 0..60) for (attended in 0..total) for (p in listOf(50, 60, 65, 70, 75, 80, 85, 90, 99)) {
            var expected = 0
            while ((attended + expected) * 100L < p.toLong() * (total + expected)) expected++
            assertEquals("a=$attended t=$total p=$p", expected, AttendanceCalculator.mustAttend(attended, total, p))
        }
    }

    @Test
    fun examplesFromTheBrief() {
        // 31 / 36 = 86.1%: 31 / 41 = 75.6% ok, 31 / 42 = 73.8% not.
        assertEquals(AttendanceStatus.CanMiss(5), AttendanceCalculator.status(Tally(attended = 31, absent = 5), 75))
        // 22 / 31 = 71.0%: 27 / 36 = 75.0%.
        assertEquals(AttendanceStatus.MustAttend(5), AttendanceCalculator.status(Tally(attended = 22, absent = 9), 75))
    }

    @Test
    fun justBelowRequirement_mustAttendOne() {
        // 2 / 3 = 66.7%; 3 / 4 = 75%.
        assertEquals(1, AttendanceCalculator.mustAttend(2, 3, 75))
    }

    @Test
    fun zeroAttended_needsThreeTimesTheMissesAt75() {
        // 0 / 2 → need N with N / (2 + N) ≥ 0.75 → N = 6.
        assertEquals(6, AttendanceCalculator.mustAttend(0, 2, 75))
    }

    @Test
    fun hundredPercentRequired() {
        assertEquals(AttendanceStatus.CanMiss(0), AttendanceCalculator.status(Tally(attended = 10), 100))
        assertEquals(AttendanceStatus.Unreachable, AttendanceCalculator.status(Tally(attended = 9, absent = 1), 100))
        assertNull(AttendanceCalculator.mustAttend(9, 10, 100))
    }

    @Test
    fun lowRequirement() {
        // 1 / 1 at 1%: can miss 99 more (1 / 100 = 1%).
        assertEquals(99, AttendanceCalculator.canMiss(1, 1, 1))
    }

    @Test
    fun unmarkedSessionsPullStatusBelow() {
        val sessions = pastSessions(4)
        val marks = mapOf("s1" to Mark.Present, "s2" to Mark.Present, "s3" to Mark.Present)
        val tally = AttendanceCalculator.tally(sessions, marks, noon)
        assertEquals(AttendanceStatus.CanMiss(0), AttendanceCalculator.status(tally, 75))
        assertEquals(1, tally.unmarkedPastCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiredZero_isRejected() {
        AttendanceCalculator.canMiss(1, 1, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiredOverHundred_isRejected() {
        AttendanceCalculator.mustAttend(1, 1, 101)
    }

    // endregion

    // region projection

    @Test
    fun projection_missingNextN() {
        assertEquals(31 * 100.0 / 41, AttendanceCalculator.projectedPercent(31, 36, 5)!!, 1e-9)
        assertEquals(31 * 100.0 / 36, AttendanceCalculator.projectedPercent(31, 36, 0)!!, 1e-9)
    }

    @Test
    fun projection_fromNothing() {
        assertNull(AttendanceCalculator.projectedPercent(0, 0, 0))
        assertEquals(0.0, AttendanceCalculator.projectedPercent(0, 0, 3)!!, 0.0)
    }

    // endregion

    // region edit window

    @Test
    fun editWindow_isTodayAndTwoDaysBack() {
        assertTrue(EditWindow.isEditable(today, today))
        assertTrue(EditWindow.isEditable(today.minusDays(1), today))
        assertTrue(EditWindow.isEditable(today.minusDays(2), today))
        assertFalse(EditWindow.isEditable(today.minusDays(3), today))
        assertFalse(EditWindow.isEditable(today.plusDays(1), today))
    }

    @Test
    fun editWindow_acrossMonthBoundary() {
        val first = LocalDate.of(2026, 12, 1)
        assertTrue(EditWindow.isEditable(LocalDate.of(2026, 11, 29), first))
        assertFalse(EditWindow.isEditable(LocalDate.of(2026, 11, 28), first))
    }

    // endregion
}
