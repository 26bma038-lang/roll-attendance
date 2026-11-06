package app.roll.domain.planning

import app.roll.domain.model.Mark
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.TimetableSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanningTest {

    /** Thursday 5 Nov 2026. */
    private val today = LocalDate.of(2026, 11, 5)
    private val noon = today.atTime(12, 0)

    private fun slot(day: Int, subject: String, start: Int) =
        TimetableSlot("$day-$subject-$start", day, start, start + 50, subject, SessionType.Lecture, "")

    private fun session(id: String, date: LocalDate, subject: String, start: Int, status: SessionStatus = SessionStatus.Scheduled, weight: Int = 1) =
        Session(id, subject, date, start, start + 50, SessionType.Lecture, status, "", "", weight, "cr", "", null)

    /** DAA Mon/Wed/Fri 9:00, CN Thu 14:00 and Fri 10:00. */
    private val slots = listOf(
        slot(1, "daa", 540), slot(3, "daa", 540), slot(5, "daa", 540),
        slot(4, "cn", 840), slot(5, "cn", 600),
    )

    /** 8 past DAA classes, 6 attended; 4 past CN classes, 3 attended. */
    private val history: List<Session> = (1..8).map { session("d$it", today.minusDays(it.toLong() * 2), "daa", 540) } +
        (1..4).map { session("c$it", today.minusDays(it.toLong() * 3), "cn", 840) }
    private val marks: Map<String, Mark> =
        (1..6).associate { "d$it" to Mark.Present } + mapOf("d7" to Mark.Absent, "d8" to Mark.Absent) +
            (1..3).associate { "c$it" to Mark.Present } + mapOf("c4" to Mark.Absent)

    // region expected classes

    @Test
    fun usesTheTimetableWhenNothingIsPosted() {
        val friday = today.plusDays(1)
        val expected = expectedClasses(friday, history, slots, marks, noon)
        assertEquals(listOf("daa" to 540, "cn" to 600), expected.map { it.subjectId to it.startMinutes })
        assertTrue(expected.all { it.sessionId == null })
    }

    @Test
    fun usesPostedSessionsWhenThereAreAny_includingTheirWeight() {
        val friday = today.plusDays(1)
        val posted = history + session("lab", friday, "cn", 600, weight = 2)
        val expected = expectedClasses(friday, posted, slots, marks, noon)
        assertEquals(listOf("lab"), expected.map { it.sessionId })
        assertEquals(2, expected.single().weight)
    }

    @Test
    fun todayOnlyCountsClassesNotYetOver() {
        // CN today at 14:00; it's noon, so it's still ahead. A 9:00 slot today would be over.
        val withMorning = slots + slot(4, "daa", 540)
        assertEquals(listOf("cn"), expectedClasses(today, history, withMorning, marks, noon).map { it.subjectId })
    }

    @Test
    fun cancelledAndMarkedPostedSessionsAreNotExpected() {
        val posted = listOf(
            session("x", today, "cn", 840, SessionStatus.Cancelled),
            session("y", today, "daa", 900),
            session("z", today, "daa", 960),
        )
        val expected = expectedClasses(today, posted, slots, mapOf("z" to Mark.Present), noon)
        assertEquals(listOf("y"), expected.map { it.sessionId })
    }

    @Test
    fun pastDatesExpectNothing() {
        assertTrue(expectedClasses(today.minusDays(1), history, slots, marks, noon).isEmpty())
    }

    // endregion

    // region leave planner

    @Test
    fun noLeave_changesNothing() {
        val impact = LeavePlanner.plan(emptySet(), listOf("daa"), history, slots, marks, noon, 75).single()
        assertEquals(0, impact.missed)
        assertEquals(0, impact.attendedBefore)
        assertEquals(75.0, impact.projectedPercent!!, 1e-9)
        assertTrue(impact.meetsRequirement)
    }

    @Test
    fun leaveOnMondayAndWednesday_missesTwoDaaClasses_attendsFriday() {
        val monday = LocalDate.of(2026, 11, 9)
        val wednesday = LocalDate.of(2026, 11, 11)
        val impact = LeavePlanner.plan(setOf(monday, wednesday), listOf("daa"), history, slots, marks, noon, 75).single()
        // Fri 6 Nov attended, Mon + Wed missed: 7 / 11 = 63.6%.
        assertEquals(2, impact.missed)
        assertEquals(1, impact.attendedBefore)
        assertEquals(700.0 / 11, impact.projectedPercent!!, 1e-9)
        assertFalse(impact.meetsRequirement)
    }

    @Test
    fun subjectsWithNoClassesOnLeaveDays_areUnaffected() {
        val tuesday = LocalDate.of(2026, 11, 10)
        val impacts = LeavePlanner.plan(setOf(tuesday), listOf("daa", "cn"), history, slots, marks, noon, 75)
        assertEquals(listOf(0, 0), impacts.map { it.missed })
        // CN still gets today's 14:00 and Friday's 10:00 class before the leave day.
        assertEquals(2, impacts[1].attendedBefore)
    }

    @Test
    fun pastLeaveDatesAreIgnored() {
        val impact = LeavePlanner.plan(setOf(today.minusDays(3)), listOf("daa"), history, slots, marks, noon, 75).single()
        assertEquals(0, impact.missed)
    }

    @Test
    fun aSubjectWithNoHistoryOrClasses_hasNoPercentage() {
        assertNull(LeavePlanner.plan(setOf(today.plusDays(1)), listOf("ml"), history, slots, marks, noon, 75).single().projectedPercent)
    }

    // endregion

    // region class-end prompts

    @Test
    fun classEndPrompts_fallBackToTheTimetable() {
        val prompts = ClassEndPlanner.forToday(history, slots, marks, noon)
        assertEquals(listOf("class-end-2026-11-05-840-cn"), prompts.map { it.key })
        assertNull(prompts.single().sessionId)
    }

    @Test
    fun classEndPrompts_usePostedSessionsAndSkipMarked() {
        val posted = history + session("t1", today, "cn", 840) + session("t2", today, "daa", 900)
        val prompts = ClassEndPlanner.forToday(posted, slots, marks + ("t2" to Mark.Present), noon)
        assertEquals(listOf("t1"), prompts.map { it.sessionId })
    }

    // endregion
}
