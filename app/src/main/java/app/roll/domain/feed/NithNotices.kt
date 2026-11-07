package app.roll.domain.feed

import app.roll.domain.model.Notice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A notice from nith.ac.in, copied by tools/nith-notices into `collegeFeed/nith`. Only the title
 * and the link: the PDF stays on the college website.
 */
data class NithNotice(
    val id: String,
    val title: String,
    val url: String,
    val category: NithCategory,
    /** "October 2026" when the website or the file name says when; null otherwise. */
    val monthLabel: String?,
    /** Best known date, for sorting. Not shown when [monthLabel] is null (it is then a guess). */
    val sortDate: LocalDate,
    /** When the sync first saw it. Drives "new since you last looked". */
    val firstSeenAt: Instant?,
)

enum class NithCategory(val label: String) {
    Academic("Academic"),
    Exams("Exams"),
    Students("Students"),
    ;

    companion object {
        fun of(value: String?): NithCategory = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Academic
    }
}

/** One row on the College notices screen: the admin's own notice, or one from nith.ac.in. */
sealed interface CollegeEntry {
    val key: String
    val date: LocalDate

    data class Admin(val notice: Notice, override val date: LocalDate) : CollegeEntry {
        override val key: String get() = "admin:${notice.id}"
    }

    data class Website(val notice: NithNotice, val isNew: Boolean) : CollegeEntry {
        override val key: String get() = "nith:${notice.id}"
        override val date: LocalDate get() = notice.sortDate
    }
}

object NithFeed {
    /** Notices the sync first saw after [seenAt]. None before the student's first look. */
    fun newSince(notices: List<NithNotice>, seenAt: Instant?): List<NithNotice> =
        if (seenAt == null) emptyList() else notices.filter { it.firstSeenAt?.isAfter(seenAt) == true }

    /**
     * Admin notices and website notices in one list, newest first. On the same day the admin's
     * notice comes first. [category] null = everything; otherwise only website notices of it.
     */
    fun entries(
        adminNotices: List<Notice>,
        website: List<NithNotice>,
        seenAt: Instant?,
        category: NithCategory?,
        zone: ZoneId,
    ): List<CollegeEntry> {
        val newIds = newSince(website, seenAt).mapTo(HashSet()) { it.id }
        val nith = website.filter { category == null || it.category == category }
            .map { CollegeEntry.Website(it, it.id in newIds) }
        val admin = if (category != null) {
            emptyList()
        } else {
            adminNotices.map { CollegeEntry.Admin(it, (it.createdAt ?: Instant.EPOCH).atZone(zone).toLocalDate()) }
        }
        return (admin + nith).sortedWith(compareByDescending<CollegeEntry> { it.date }.thenBy { if (it is CollegeEntry.Admin) 0 else 1 })
    }

    /** The latest [count] academic or exam notices, for Today. */
    fun latestAcademic(notices: List<NithNotice>, count: Int = 3): List<NithNotice> =
        notices.filter { it.category != NithCategory.Students }.sortedByDescending { it.sortDate }.take(count)
}
