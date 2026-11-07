package app.roll.domain.materials

import app.roll.domain.college.NithConfig
import java.time.LocalDate

/**
 * Study material copied from a senior's Google Classroom by tools/classroom-sync. Roll keeps only
 * the index; the files stay in Google Drive.
 */
data class MaterialSource(
    val id: String,
    val title: String,
    /** "Material shared by …". Always shown: the senior gave permission on that condition. */
    val credit: String,
    val classroomUrl: String,
    val years: List<Int>,
    /** Branch codes, or [ALL_BRANCHES] for every branch Roll supports. */
    val branches: List<String>,
    val items: List<MaterialItem>,
) {
    fun appliesTo(branch: String, year: Int): Boolean =
        year in years && branches.any { it == ALL_BRANCHES || it.equals(branch, ignoreCase = true) }

    companion object {
        /** `"branches": ["*"]`: every branch in [app.roll.domain.college.NithConfig.branches]. */
        const val ALL_BRANCHES = "*"
    }
}

data class MaterialItem(
    val id: String,
    /** pyq, notes, books, assignments, solutions, practicals, workshop, drawing, syllabus, calendar, other. */
    val section: String,
    val sectionLabel: String,
    /** "MA-111"; null for Sunil Sir's notes and non-subject groups. */
    val subjectCode: String?,
    val subjectName: String,
    /** The Classroom post's title: the chapter for Sunil Sir's notes, "CIVIL" for workshop. */
    val itemTitle: String,
    val title: String,
    /** "MID", "END" or null. Papers only. */
    val exam: String?,
    /** "2024-25", "2023" or null. */
    val academicYear: String?,
    val url: String,
)

/** Section keys written by the sync script. */
object Sections {
    const val PYQ = "pyq"
    const val NOTES = "notes"
    const val BOOKS = "books"
    const val ASSIGNMENTS = "assignments"
    const val SOLUTIONS = "solutions"
    const val PRACTICALS = "practicals"
    const val WORKSHOP = "workshop"
    const val DRAWING = "drawing"
    const val SYLLABUS = "syllabus"
    const val CALENDAR = "calendar"
    const val OTHER = "other"

    /** Sections that belong to a subject, in the order the subject screen shows them. */
    val subjectOrder = listOf(PYQ, NOTES, BOOKS, ASSIGNMENTS, SOLUTIONS, PRACTICALS)

    val labels = mapOf(
        PYQ to "Previous year papers",
        NOTES to "Notes",
        BOOKS to "Books",
        ASSIGNMENTS to "Assignments",
        SOLUTIONS to "Solutions",
        PRACTICALS to "Practical files",
        WORKSHOP to "Workshop",
        DRAWING to "Engineering drawing",
        SYLLABUS to "Syllabus",
        CALENDAR to "Academic calendar",
    )
}

const val SUNIL_SIR_LABEL = "Sunil Sir's notes"

/**
 * One row on the Study home and the screen it opens: a subject (by code, or by name when there
 * is no code), Sunil Sir's notes, or a whole non-subject section such as Workshop.
 */
sealed interface StudyGroup {
    /** Stable, navigation-safe key. */
    val key: String
    val title: String

    data class Subject(val code: String?, val name: String) : StudyGroup {
        override val key: String get() = if (code != null) "s:$code" else "n:$name"
        override val title: String get() = code ?: name
    }

    data object SunilSir : StudyGroup {
        override val key = "sunil"
        override val title = SUNIL_SIR_LABEL
    }

    data class Section(val section: String, val label: String) : StudyGroup {
        override val key: String get() = if (section == Sections.OTHER) "o:$label" else "x:$section"
        override val title: String get() = label
    }
}

/** A titled block of files on a group screen. */
data class StudyBlock(val title: String, val items: List<MaterialItem>)

object Study {
    /** Year of study (1–5) from the batch and today, using the semester rules in [NithConfig]. */
    fun yearOfStudy(batch: Int, today: LocalDate): Int = (NithConfig.semesterNumber(batch, today) + 1) / 2

    fun sourcesFor(sources: List<MaterialSource>, branch: String, year: Int): List<MaterialSource> =
        sources.filter { it.appliesTo(branch, year) }

    fun groupOf(item: MaterialItem): StudyGroup = when {
        item.subjectName == SUNIL_SIR_LABEL && item.subjectCode == null -> StudyGroup.SunilSir
        item.section in Sections.subjectOrder -> StudyGroup.Subject(item.subjectCode, item.subjectName)
        else -> StudyGroup.Section(item.section, Sections.labels[item.section] ?: item.sectionLabel)
    }

    /**
     * Subjects sorted by code, then named-only subjects by name. One row per code: if posts spell
     * a subject's name differently, the first spelling (alphabetically) wins.
     */
    fun subjects(items: List<MaterialItem>): List<StudyGroup.Subject> =
        items.map(::groupOf).filterIsInstance<StudyGroup.Subject>().distinct()
            .sortedWith(compareBy<StudyGroup.Subject>({ it.code == null }, { it.code.orEmpty() }, { it.name.lowercase() }))
            .distinctBy { it.key }

    /** Sunil Sir's notes, Syllabus, Academic calendar, Workshop, Engineering drawing, then any others. */
    fun otherGroups(items: List<MaterialItem>): List<StudyGroup> {
        val groups = items.map(::groupOf).filter { it !is StudyGroup.Subject }.distinct()
        val order = listOf(StudyGroup.SunilSir.key, "x:${Sections.SYLLABUS}", "x:${Sections.CALENDAR}", "x:${Sections.WORKSHOP}", "x:${Sections.DRAWING}")
        return groups.sortedWith(compareBy({ order.indexOf(it.key).let { i -> if (i < 0) order.size else i } }, { it.title.lowercase() }))
    }

    fun itemsIn(items: List<MaterialItem>, key: String): List<MaterialItem> = items.filter { groupOf(it).key == key }

    fun group(items: List<MaterialItem>, key: String): StudyGroup? =
        subjects(items).firstOrNull { it.key == key } ?: otherGroups(items).firstOrNull { it.key == key }

    /**
     * The blocks a group screen shows. A subject: its sections in [Sections.subjectOrder], only
     * those with files, papers newest first. Anything else: one block per Classroom post
     * (chapters for Sunil Sir's notes, CIVIL / ELECTRICAL … for Workshop).
     */
    fun blocks(items: List<MaterialItem>, key: String): List<StudyBlock> {
        val inGroup = itemsIn(items, key)
        return if (key.startsWith("s:") || key.startsWith("n:")) {
            Sections.subjectOrder.mapNotNull { section ->
                val files = inGroup.filter { it.section == section }
                if (files.isEmpty()) null
                else StudyBlock(Sections.labels.getValue(section), if (section == Sections.PYQ) sortPapers(files) else files)
            }
        } else {
            inGroup.groupBy { it.itemTitle.ifBlank { it.sectionLabel } }
                .map { (title, files) -> StudyBlock(title, if (files.any { it.exam != null }) sortPapers(files) else files) }
        }
    }

    /** Newest academic year first, then End before Mid, then untagged; title as a tie-break. */
    fun sortPapers(papers: List<MaterialItem>): List<MaterialItem> = papers.sortedWith(
        compareByDescending<MaterialItem> { startYear(it.academicYear) }
            .thenByDescending { it.academicYear.orEmpty() }
            .thenBy { examRank(it.exam) }
            .thenBy(NaturalOrder) { it.title },
    )

    private fun startYear(academicYear: String?): Int = academicYear?.take(4)?.toIntOrNull() ?: Int.MIN_VALUE

    private fun examRank(exam: String?): Int = when (exam) {
        "END" -> 0
        "MID" -> 1
        else -> 2
    }

    /** "End sem · 2024-25", "Mid sem", "2023", or null when the file says neither. */
    fun paperLabel(item: MaterialItem): String? {
        val exam = when (item.exam) {
            "END" -> "End sem"
            "MID" -> "Mid sem"
            else -> null
        }
        return listOfNotNull(exam, item.academicYear).joinToString(" · ").ifEmpty { null }
    }

    /**
     * The file name under a paper's label, only when it says more than the label: "MID SEM
     * 2023 (NEW)" under "Mid sem · 2023", but nothing under "End sem · 2025-26" for
     * "END SEM 2025-26".
     */
    fun paperDetail(item: MaterialItem): String? {
        if (paperLabel(item) == null) return null
        val rest = item.title.lowercase()
            .replace(Regex("20\\d{2}\\s*[-–_/]\\s*(20)?\\d{2}|20\\d{2}"), " ")
            .replace(Regex("\\b(mid|end)\\s*-?\\s*(sem(ester)?|term)?\\b|\\b(sem(ester)?|exam|paper|question)s?\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), "")
        return item.title.takeIf { rest.isNotEmpty() }
    }

    /**
     * Files matching every word of [query] in their subject code, subject name or title.
     * "ma111" finds MA-111: dashes and spaces are ignored inside codes.
     */
    fun search(items: List<MaterialItem>, query: String): List<MaterialItem> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return items.filter { item ->
            val haystack = listOfNotNull(item.subjectCode, item.subjectName, item.title).joinToString(" ").lowercase()
            val compact = haystack.replace("-", "").replace(" ", "")
            words.all { it in haystack || it.replace("-", "") in compact }
        }
    }
}

/** "Assignment 2" before "Assignment 10". */
private object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val result = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.compareTo(q)
            } else {
                p.compareTo(q)
            }
            if (result != 0) return result
        }
        return x.size.compareTo(y.size)
    }
}
