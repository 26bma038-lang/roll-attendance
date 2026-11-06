package app.roll.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JoinCodeTest {

    @Test
    fun generatedCodes_areValidAndAvoidLookAlikes() {
        val random = Random(42)
        repeat(1_000) {
            val code = JoinCode.generate(random)
            assertTrue(code, JoinCode.isValid(code))
            assertFalse(code, code.any { it in "0O1I" })
        }
    }

    @Test
    fun normalize_uppercasesAndStripsInvalid() {
        assertEquals("K7MP2X", JoinCode.normalize(" k7-mp2x "))
        assertEquals("ABCDEF", JoinCode.normalize("abcdefgh"))
        assertEquals("", JoinCode.normalize("0O1I"))
    }

    @Test
    fun isValid_checksLengthAndAlphabet() {
        assertTrue(JoinCode.isValid("K7MP2X"))
        assertFalse(JoinCode.isValid("K7MP2"))
        assertFalse(JoinCode.isValid("K7MP2O"))
    }
}
