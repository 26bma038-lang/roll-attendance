package app.roll.data.feed

import app.roll.domain.feed.NithCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class NithNoticeMapperTest {
    private val full = mapOf(
        "id" to "4f17", "title" to "UG End Semester Date Sheet", "url" to "https://nith.ac.in/x.pdf",
        "category" to "Exams", "page" to "academic-notices", "sortDate" to "2026-10-01",
        "monthLabel" to "October 2026", "firstSeenAt" to "2026-11-07T09:13:33.428Z",
    )

    @Test
    fun readsWhatTheSyncWrites() {
        val notice = full.toNithNotice()!!
        assertEquals("4f17", notice.id)
        assertEquals(NithCategory.Exams, notice.category)
        assertEquals(LocalDate.of(2026, 10, 1), notice.sortDate)
        assertEquals("October 2026", notice.monthLabel)
        assertEquals(Instant.parse("2026-11-07T09:13:33.428Z"), notice.firstSeenAt)
    }

    @Test
    fun noMonthLabel_andNoSortDateFallsBackToFirstSeen() {
        val notice = (full - "monthLabel" - "sortDate").toNithNotice()!!
        assertNull(notice.monthLabel)
        assertEquals(LocalDate.of(2026, 11, 7), notice.sortDate)
    }

    @Test
    fun brokenEntriesAreSkipped() {
        assertNull((full - "url").toNithNotice())
        assertNull((full - "title").toNithNotice())
        assertNull((full - "sortDate" - "firstSeenAt").toNithNotice())
    }
}
