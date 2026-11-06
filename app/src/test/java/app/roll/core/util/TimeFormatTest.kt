package app.roll.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimeFormatTest {

    @Test
    fun clock_usesTwelveHourTime() {
        assertEquals("12:00", formatClock(0))
        assertEquals("9:00", formatClock(9 * 60))
        assertEquals("12:30", formatClock(12 * 60 + 30))
        assertEquals("1:10", formatClock(13 * 60 + 10))
        assertEquals("11:59", formatClock(23 * 60 + 59))
    }

    @Test
    fun clock_withMeridiem_isLowercase() {
        assertEquals("9:12 am", formatClock(9 * 60 + 12, meridiem = true))
        assertEquals("12:00 pm", formatClock(12 * 60, meridiem = true))
        assertEquals("12:05 am", formatClock(5, meridiem = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun clock_rejectsOutOfRange() {
        formatClock(24 * 60)
    }

    @Test
    fun dayHeader_matchesBrief() {
        assertEquals("Thursday, 5 Nov", formatDayHeader(LocalDate.of(2026, 11, 5)))
    }

    @Test
    fun percent_alwaysOneDecimal() {
        assertEquals("75.0%", formatPercent(75.0))
        assertEquals("71.4%", formatPercent(71.428571))
        assertEquals("100.0%", formatPercent(100.0))
    }
}
