package app.roll.core.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** All "now" in the app comes from here, so tests can pin the clock. */
interface TimeSource {
    fun now(): Instant

    /** Emits now, then again every [periodMillis]. Drives "is this session over yet". */
    fun ticks(periodMillis: Long = 30_000): Flow<Instant>
}

@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun now(): Instant = Instant.now()

    override fun ticks(periodMillis: Long): Flow<Instant> = flow {
        while (true) {
            emit(now())
            delay(periodMillis)
        }
    }
}

/** This version fixes the class timezone to Asia/Kolkata; the class doc stores it for later. */
const val DEFAULT_CLASS_TIMEZONE = "Asia/Kolkata"

fun zoneOf(timezone: String): ZoneId =
    runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of(DEFAULT_CLASS_TIMEZONE))

fun Instant.localDateIn(timezone: String): LocalDate = atZone(zoneOf(timezone)).toLocalDate()

fun Instant.localDateTimeIn(timezone: String): LocalDateTime = atZone(zoneOf(timezone)).toLocalDateTime()
