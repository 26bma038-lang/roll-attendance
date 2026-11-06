package app.roll.domain.deadlines

import app.roll.domain.model.Assignment
import app.roll.domain.model.AssignmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class DeadlinesTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Thursday 5 Nov 2026, 12:00 IST. */
    private val now: Instant = LocalDateTime.of(2026, 11, 5, 12, 0).atZone(zone).toInstant()

    private fun due(id: String, at: LocalDateTime) = Assignment(
        id = id, title = id, subjectId = "daa", type = AssignmentType.Assignment, description = "", link = "",
        dueAt = at.atZone(zone).toInstant(), createdBy = "cr", createdByName = "CR", createdAt = null,
    )

    private val overdue = due("overdue", LocalDateTime.of(2026, 11, 4, 23, 59))
    private val todayLater = due("today", LocalDateTime.of(2026, 11, 5, 23, 59))
    private val tomorrow = due("tomorrow", LocalDateTime.of(2026, 11, 6, 9, 0))
    private val inAWeek = due("week", LocalDateTime.of(2026, 11, 12, 10, 0))
    private val later = due("later", LocalDateTime.of(2026, 11, 13, 10, 0))
    private val all = listOf(later, inAWeek, tomorrow, todayLater, overdue)

    // region grouping

    @Test
    fun groupsIntoSections_inOrder() {
        val groups = Deadlines.group(all, emptySet(), now, zone)
        assertEquals(listOf(DeadlineGroup.Overdue, DeadlineGroup.DueToday, DeadlineGroup.ThisWeek, DeadlineGroup.Later), groups.keys.toList())
        assertEquals(listOf("overdue"), groups.getValue(DeadlineGroup.Overdue).map { it.id })
        assertEquals(listOf("today"), groups.getValue(DeadlineGroup.DueToday).map { it.id })
        assertEquals(listOf("tomorrow", "week"), groups.getValue(DeadlineGroup.ThisWeek).map { it.id })
        assertEquals(listOf("later"), groups.getValue(DeadlineGroup.Later).map { it.id })
    }

    @Test
    fun doneGoesToDone_evenWhenOverdue_newestFirst() {
        val groups = Deadlines.group(all, setOf("overdue", "week"), now, zone)
        assertEquals(listOf("week", "overdue"), groups.getValue(DeadlineGroup.Done).map { it.id })
        assertFalse(DeadlineGroup.Overdue in groups)
    }

    @Test
    fun dueExactlyNow_isOverdue() {
        val atNow = due("now", LocalDateTime.of(2026, 11, 5, 12, 0))
        assertEquals(DeadlineGroup.Overdue, Deadlines.group(listOf(atNow), emptySet(), now, zone).keys.single())
    }

    @Test
    fun emptySectionsAreLeftOut() {
        assertTrue(Deadlines.group(emptyList(), emptySet(), now, zone).isEmpty())
    }

    @Test
    fun earlierToday_isOverdue_notDueToday() {
        val morning = due("morning", LocalDateTime.of(2026, 11, 5, 9, 0))
        assertEquals(DeadlineGroup.Overdue, Deadlines.group(listOf(morning), emptySet(), now, zone).keys.single())
    }

    // endregion

    // region due soon

    @Test
    fun dueSoon_isNext48HoursNotDone() {
        assertEquals(listOf("today", "tomorrow"), Deadlines.dueSoon(all, emptySet(), now).map { it.id })
        assertEquals(listOf("tomorrow"), Deadlines.dueSoon(all, setOf("today"), now).map { it.id })
    }

    // endregion

    // region relative time

    @Test
    fun relativeTimes() {
        fun rel(minutes: Long) = Deadlines.relative(now.plus(Duration.ofMinutes(minutes)), now)
        assertEquals("45 min left", rel(45).text)
        assertEquals("3 h left", rel(3 * 60 + 20).text)
        assertEquals("in 1 day", rel(25 * 60).text)
        assertEquals("in 2 days", rel(2 * 24 * 60 + 5).text)
        assertEquals("overdue by 10 min", rel(-10).text)
        assertEquals("overdue by 5 h", rel(-5 * 60).text)
        assertEquals("overdue by 1 day", rel(-24 * 60).text)
        assertEquals("overdue by 3 days", rel(-3 * 24 * 60).text)
    }

    @Test
    fun urgency() {
        assertTrue(Deadlines.relative(now.plus(Duration.ofHours(5)), now).urgent)
        assertFalse(Deadlines.relative(now.plus(Duration.ofHours(30)), now).urgent)
        assertTrue(Deadlines.relative(now.minus(Duration.ofHours(1)), now).overdue)
    }

    // endregion

    // region reminders

    @Test
    fun remindsTwentyFourAndTwoHoursBefore() {
        val plan = Deadlines.plannedReminders(listOf(inAWeek), emptySet(), now)
        assertEquals(listOf(24L, 2L), plan.map { it.hoursBefore })
        assertEquals(inAWeek.dueAt.minus(Duration.ofHours(24)), plan[0].at)
        assertEquals("deadline-week-2", plan[1].key)
    }

    @Test
    fun skipsRemindersAlreadyPast() {
        // Due tomorrow 9:00, now is 12:00 today: the 24 h reminder (9:00 today) has passed.
        val plan = Deadlines.plannedReminders(listOf(tomorrow), emptySet(), now)
        assertEquals(listOf(2L), plan.map { it.hoursBefore })
    }

    @Test
    fun noRemindersForDoneOrOverdue() {
        assertTrue(Deadlines.plannedReminders(listOf(overdue), emptySet(), now).isEmpty())
        assertTrue(Deadlines.plannedReminders(listOf(inAWeek), setOf("week"), now).isEmpty())
    }

    @Test
    fun remindersAreSortedByTime() {
        val plan = Deadlines.plannedReminders(all, emptySet(), now)
        assertEquals(plan.sortedBy { it.at }, plan)
    }

    // endregion
}
