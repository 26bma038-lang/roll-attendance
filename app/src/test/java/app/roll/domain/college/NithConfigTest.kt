package app.roll.domain.college

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NithConfigTest {

    private fun student(email: String): NithIdentity =
        (NithConfig.parse(email) as NithConfig.Parsed.Student).identity

    @Test
    fun parsesTheExampleFromTheBrief() {
        val identity = student("26bma038@nith.ac.in")
        assertEquals(2026, identity.batch)
        assertEquals("MA", identity.branch)
        assertEquals("26BMA038", identity.rollNo)
        assertEquals("nith-26-ma", identity.classId)
        assertEquals("Mathematics and Computing · 2026 batch", identity.description)
    }

    @Test
    fun isCaseInsensitive_andTrims() {
        val identity = student("  26BCS105@NITH.AC.IN ")
        assertEquals("CS", identity.branch)
        assertEquals("26BCS105", identity.rollNo)
        assertEquals("26bcs105@nith.ac.in", identity.email)
    }

    @Test
    fun everySupportedBranchParses() {
        for (code in listOf("ma", "cs", "ch", "ce", "me", "ec")) {
            assertEquals(code.uppercase(), student("25b${code}001@nith.ac.in").branch)
        }
    }

    @Test
    fun otherDomains_areWrongDomain() {
        for (email in listOf("someone@gmail.com", "26bma038@nith.ac.in.evil.com", "26bma038@nitham.ac.in", "", null)) {
            assertEquals(email.toString(), NithConfig.Parsed.WrongDomain, NithConfig.parse(email))
        }
    }

    @Test
    fun unsupportedBranchesAndFormats_areUnsupported() {
        for (email in listOf(
            "26bee001@nith.ac.in", // electrical: not supported yet
            "26bms001@nith.ac.in", // materials science
            "26mma001@nith.ac.in", // M.Tech
            "hod.ma@nith.ac.in", // faculty / office
            "26bma38@nith.ac.in", // two-digit serial
            "26bma0381@nith.ac.in", // four-digit serial
            "2026bma038@nith.ac.in", // four-digit year
            "26b-ma038@nith.ac.in",
        )) {
            assertEquals(email, NithConfig.Parsed.Unsupported, NithConfig.parse(email))
        }
    }

    @Test
    fun classIds_areStableAndLowercase() {
        assertEquals("nith-26-ma", NithConfig.classId(2026, "MA"))
        assertEquals("nith-05-ec", NithConfig.classId(2005, "ec"))
        assertEquals("MA 2026", NithConfig.defaultClassName(2026, "ma"))
    }

    @Test
    fun admin_isRecognisedCaseInsensitively() {
        assertTrue(NithConfig.isAdmin("26BMA038@nith.ac.in"))
        assertFalse(NithConfig.isAdmin("26bma039@nith.ac.in"))
        assertFalse(NithConfig.isAdmin(null))
    }

    @Test
    fun semester_julyToDecemberIsOdd_januaryToJuneIsEven() {
        assertEquals(1, NithConfig.semesterNumber(2026, LocalDate.of(2026, 7, 20)))
        assertEquals(1, NithConfig.semesterNumber(2026, LocalDate.of(2026, 12, 31)))
        assertEquals(2, NithConfig.semesterNumber(2026, LocalDate.of(2027, 1, 1)))
        assertEquals(2, NithConfig.semesterNumber(2026, LocalDate.of(2027, 6, 30)))
        assertEquals(3, NithConfig.semesterNumber(2026, LocalDate.of(2027, 7, 1)))
        assertEquals(8, NithConfig.semesterNumber(2026, LocalDate.of(2030, 3, 1)))
    }

    @Test
    fun semester_isClampedBeforeTheBatchStarts() {
        assertEquals(1, NithConfig.semesterNumber(2026, LocalDate.of(2026, 5, 1)))
    }

    @Test
    fun semesterLabel() {
        assertEquals("Semester 1 · Odd 2026-27", NithConfig.semesterLabel(2026, LocalDate.of(2026, 11, 5)))
        assertEquals("Semester 4 · Even 2026-27", NithConfig.semesterLabel(2025, LocalDate.of(2027, 2, 1)))
    }
}
