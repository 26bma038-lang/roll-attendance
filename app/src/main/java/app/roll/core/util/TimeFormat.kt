package app.roll.core.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Minutes from midnight to a 12-hour clock string: 540 -> "9:00", 790 -> "1:10".
 * With [meridiem], adds a lowercase suffix: "9:12 am".
 */
fun formatClock(minutesFromMidnight: Int, meridiem: Boolean = false): String {
    require(minutesFromMidnight in 0 until 24 * 60) { "Out of range: $minutesFromMidnight" }
    val hours24 = minutesFromMidnight / 60
    val minutes = minutesFromMidnight % 60
    val hours12 = when (val h = hours24 % 12) {
        0 -> 12
        else -> h
    }
    val clock = "%d:%02d".format(Locale.ROOT, hours12, minutes)
    return if (meridiem) "$clock ${if (hours24 < 12) "am" else "pm"}" else clock
}

private val DayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)

/** "Thursday, 5 Nov". */
fun formatDayHeader(date: LocalDate): String = DayHeaderFormatter.format(date)

/** One decimal place, always: 75 -> "75.0%". */
fun formatPercent(value: Double): String = "%.1f%%".format(Locale.ROOT, value)
