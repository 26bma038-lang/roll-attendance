package app.roll.domain.materials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudyTest {

    private fun item(id: String, section: String = "pyq", title: String = id, exam: String? = null, year: String? = null) =
        MaterialItem(id, section, section, "MA-111", "Maths", "MA-111(Maths)", title, exam, year, "u")

    @Test
    fun yearOfStudy_followsTheSemesterRules() {
        assertEquals(1, Study.yearOfStudy(2026, LocalDate.of(2026, 11, 7))) // semester 1
        assertEquals(1, Study.yearOfStudy(2026, LocalDate.of(2027, 3, 1))) // semester 2
        assertEquals(2, Study.yearOfStudy(2026, LocalDate.of(2027, 8, 1))) // semester 3
        assertEquals(2, Study.yearOfStudy(2025, LocalDate.of(2026, 11, 7)))
        assertEquals(1, Study.yearOfStudy(2026, LocalDate.of(2026, 5, 1))) // before joining: clamped
    }

    @Test
    fun sourceAppliesByBranchAndYear_branchCaseInsensitive() {
        val source = MaterialSource("s", "t", "c", "u", listOf(1), listOf("EC", "MA"), emptyList())
        assertTrue(source.appliesTo("ma", 1))
        assertFalse(source.appliesTo("MA", 2))
        assertFalse(source.appliesTo("CS", 1))
    }

    @Test
    fun starMeansEveryBranch_butStillOnlyItsYears() {
        val source = MaterialSource("first-year", "t", "c", "u", listOf(1), listOf(MaterialSource.ALL_BRANCHES), emptyList())
        for (branch in listOf("MA", "CS", "CH", "CE", "ME", "EC")) {
            assertTrue(branch, source.appliesTo(branch, 1))
            assertFalse(branch, source.appliesTo(branch, 2))
        }
    }

    @Test
    fun paperDetail_onlyWhenTheFileNameSaysMore() {
        assertNull(Study.paperDetail(item("a", title = "END SEM 2025-26", exam = "END", year = "2025-26")))
        assertNull(Study.paperDetail(item("b", title = "Mid-sem exam 2024-2025", exam = "MID", year = "2024-25")))
        assertEquals("MID SEM 2023 (NEW)", Study.paperDetail(item("c", title = "MID SEM 2023 (NEW)", exam = "MID", year = "2023")))
        assertEquals("MA111 END SEM 2024-25", Study.paperDetail(item("d", title = "MA111 END SEM 2024-25", exam = "END", year = "2024-25")))
        assertNull(Study.paperDetail(item("e", title = "PAPER 1")))
    }

    @Test
    fun paperLabels() {
        assertEquals("End sem · 2024-25", Study.paperLabel(item("a", exam = "END", year = "2024-25")))
        assertEquals("Mid sem", Study.paperLabel(item("b", exam = "MID")))
        assertEquals("2023", Study.paperLabel(item("c", year = "2023")))
        assertNull(Study.paperLabel(item("d")))
    }

    @Test
    fun papersSortNaturallyWithinTheSameTags() {
        val sorted = Study.sortPapers(listOf(item("PAPER 10"), item("PAPER 2"), item("PAPER 1")))
        assertEquals(listOf("PAPER 1", "PAPER 2", "PAPER 10"), sorted.map { it.title })
    }

    @Test
    fun aRangeYearIsNewerThanTheSingleYearItStartsIn_butOlderThanTheNext() {
        val sorted = Study.sortPapers(listOf(item("a", year = "2023"), item("b", year = "2024"), item("c", year = "2023-24")))
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun groups() {
        assertEquals(StudyGroup.Subject("MA-111", "Maths"), Study.groupOf(item("a", section = "notes")))
        assertEquals("s:MA-111", Study.groupOf(item("a", section = "books")).key)
        assertEquals(StudyGroup.Section("workshop", "Workshop"), Study.groupOf(item("a", section = "workshop")))
        val unknown = MaterialItem("o", "other", "Lab manuals", null, "Physics lab", "Physics lab", "Manual", null, null, "u")
        assertEquals(StudyGroup.Section("other", "Lab manuals"), Study.groupOf(unknown))
        assertEquals("o:Lab manuals", Study.groupOf(unknown).key)
        val named = MaterialItem("n", "notes", "Notes", null, "Physics notes", "Physics notes", "Ch 1", null, null, "u")
        assertEquals("n:Physics notes", Study.groupOf(named).key)
    }
}
