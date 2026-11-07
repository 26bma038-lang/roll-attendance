package app.roll.domain.feed

import app.roll.domain.model.Notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NithFeedTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val seenAt = Instant.parse("2026-11-06T00:00:00Z")

    private fun nith(id: String, category: NithCategory, date: String, firstSeen: String = "2026-11-01T00:00:00Z") =
        NithNotice(id, id, "https://nith.ac.in/$id.pdf", category, null, LocalDate.parse(date), Instant.parse(firstSeen))

    private fun admin(id: String, created: String) =
        Notice(id, id, pinned = false, createdAt = Instant.parse(created), expiresAt = null, createdByName = "Admin", college = true)

    private val website = listOf(
        nith("exam", NithCategory.Exams, "2026-11-03", firstSeen = "2026-11-07T03:00:00Z"),
        nith("academic", NithCategory.Academic, "2026-10-01"),
        nith("students", NithCategory.Students, "2026-10-29"),
    )

    @Test
    fun newMeansFirstSeenAfterTheLastVisit_andNothingBeforeTheFirstVisit() {
        assertEquals(listOf("exam"), NithFeed.newSince(website, seenAt).map { it.id })
        assertTrue(NithFeed.newSince(website, null).isEmpty())
    }

    @Test
    fun allMixesAdminAndWebsiteNoticesByDate_adminFirstOnTheSameDay() {
        val entries = NithFeed.entries(
            listOf(admin("a-new", "2026-11-03T05:00:00Z"), admin("a-old", "2026-09-01T05:00:00Z")),
            website, seenAt, category = null, zone = zone,
        )
        assertEquals(listOf("admin:a-new", "nith:exam", "nith:students", "nith:academic", "admin:a-old"), entries.map { it.key })
        assertTrue((entries[1] as CollegeEntry.Website).isNew)
        assertTrue(!(entries[2] as CollegeEntry.Website).isNew)
    }

    @Test
    fun aCategoryShowsOnlyWebsiteNoticesOfIt() {
        val entries = NithFeed.entries(listOf(admin("a", "2026-11-03T05:00:00Z")), website, seenAt, NithCategory.Exams, zone)
        assertEquals(listOf("nith:exam"), entries.map { it.key })
    }

    @Test
    fun latestAcademic_skipsStudentNotices_newestFirst() {
        assertEquals(listOf("exam", "academic"), NithFeed.latestAcademic(website).map { it.id })
        assertEquals(listOf("exam"), NithFeed.latestAcademic(website, 1).map { it.id })
    }

    @Test
    fun unknownCategoriesReadAsAcademic() {
        assertEquals(NithCategory.Exams, NithCategory.of("exams"))
        assertEquals(NithCategory.Academic, NithCategory.of("Placements"))
        assertEquals(NithCategory.Academic, NithCategory.of(null))
    }
}
