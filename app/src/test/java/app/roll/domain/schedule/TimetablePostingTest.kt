package app.roll.domain.schedule

import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.TimetableSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimetablePostingTest {

    private val thursday = LocalDate.of(2026, 11, 5)

    private val slots = listOf(
        TimetableSlot("a", 4, 600, 650, "dbms", SessionType.Lecture, "LH-204"),
        TimetableSlot("b", 4, 540, 590, "daa", SessionType.Lecture, "LH-204"),
        TimetableSlot("c", 5, 540, 590, "cn", SessionType.Lecture, "LH-204"),
    )

    private fun posted(subject: String, start: Int, status: SessionStatus = SessionStatus.Scheduled, date: LocalDate = thursday) =
        Session("x$subject$start", subject, date, start, start + 50, SessionType.Lecture, status, "", "", 1, "cr", "", null)

    @Test
    fun postsOnlyThatWeekday_inTimeOrder() {
        val result = TimetablePosting.slotsToPost(thursday, slots, emptyList())
        assertEquals(listOf("b", "a"), result.map { it.id })
    }

    @Test
    fun skipsSlotsAlreadyPosted_includingCancelled() {
        val existing = listOf(posted("daa", 540, SessionStatus.Cancelled))
        assertEquals(listOf("a"), TimetablePosting.slotsToPost(thursday, slots, existing).map { it.id })
    }

    @Test
    fun repostsSlotsWhoseSessionWasDeleted() {
        val existing = listOf(posted("daa", 540, SessionStatus.Deleted), posted("dbms", 600))
        assertEquals(listOf("b"), TimetablePosting.slotsToPost(thursday, slots, existing).map { it.id })
    }

    @Test
    fun sameSlotOnAnotherDate_doesNotCount() {
        val existing = listOf(posted("daa", 540, date = thursday.minusDays(7)))
        assertEquals(2, TimetablePosting.slotsToPost(thursday, slots, existing).size)
    }

    @Test
    fun nothingOnSunday() {
        assertTrue(TimetablePosting.slotsToPost(thursday.plusDays(3), slots, emptyList()).isEmpty())
    }

    @Test
    fun stableIdsArePerDateAndSlot() {
        assertEquals("2026-11-05_a", TimetablePosting.sessionIdFor(thursday, slots[0]))
    }

    @Test
    fun draftValidation() {
        val draft = SessionDraft.fromSlot(slots[0], thursday)
        assertTrue(draft.isValid)
        assertEquals(false, draft.copy(endMinutes = 500).isValid)
        assertEquals(false, draft.copy(weight = 5).isValid)
        assertEquals(false, draft.copy(subjectId = "").isValid)
    }
}
