package app.roll.data.firebase

import app.roll.core.common.DEFAULT_CLASS_TIMEZONE
import app.roll.domain.model.Mark
import app.roll.domain.model.MarkRecord
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.model.TimetableSlot
import app.roll.domain.model.UserProfile
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import java.time.Instant
import java.time.LocalDate

/**
 * Firestore documents to domain models, by hand: explicit, tolerant of missing fields,
 * and no reflection for R8 to trip over. Returns null for documents too broken to use.
 */

/** Field names and enum spellings shared with firestore.rules. */
internal object F {
    const val USERS = "users"
    const val CLASSES = "classes"
    const val JOIN_CODES = "joinCodes"
    const val MEMBERS = "members"
    const val SUBJECTS = "subjects"
    const val TIMETABLE = "timetable"
    const val SESSIONS = "sessions"
    const val MARKS = "marks"

    const val CLASS_ID = "classId"
    const val UPDATED_AT = "updatedAt"
    const val MARKED_AT = "markedAt"
    const val UID = "uid"
    const val DATE = "date"

    const val STATUS_CLEARED = "cleared"
}

private fun DocumentSnapshot.instant(field: String): Instant? =
    getTimestamp(field, ServerTimestampBehavior.ESTIMATE)?.toInstant()

private fun DocumentSnapshot.int(field: String, default: Int = 0): Int = getLong(field)?.toInt() ?: default

private fun DocumentSnapshot.str(field: String): String = getString(field).orEmpty()

internal fun SessionType.wire(): String = name.lowercase()

internal fun sessionTypeOf(value: String?): SessionType =
    SessionType.entries.firstOrNull { it.wire() == value } ?: SessionType.Lecture

internal fun SessionStatus.wire(): String = name.lowercase()

internal fun Mark.wire(): String = name.lowercase()

internal fun DocumentSnapshot.toUserProfile(): UserProfile? {
    if (!exists()) return null
    return UserProfile(
        uid = id,
        name = str("name"),
        rollNo = str("rollNo"),
        email = str("email"),
        classId = getString(F.CLASS_ID)?.takeIf { it.isNotBlank() },
    )
}

internal fun DocumentSnapshot.toRollClass(): RollClass? {
    if (!exists()) return null
    return RollClass(
        id = id,
        name = str("name"),
        semester = str("semester"),
        crUid = str("crUid"),
        joinCode = str("joinCode"),
        requiredPercent = int("requiredPercent", 75).coerceIn(1, 100),
        timezone = getString("timezone") ?: DEFAULT_CLASS_TIMEZONE,
    )
}

internal fun DocumentSnapshot.toMember(): Member? {
    if (!exists()) return null
    return Member(uid = id, name = str("name"), rollNo = str("rollNo"), joinedAt = instant("joinedAt"))
}

internal fun DocumentSnapshot.toSubject(): Subject? {
    if (!exists()) return null
    return Subject(
        id = id,
        name = str("name"),
        shortName = str("shortName"),
        code = str("code"),
        faculty = str("faculty"),
        colorIndex = int("colorIndex"),
        archived = getBoolean("archived") ?: false,
    )
}

internal fun DocumentSnapshot.toTimetableSlot(): TimetableSlot? {
    if (!exists()) return null
    return TimetableSlot(
        id = id,
        dayOfWeek = int("dayOfWeek", 1),
        startMinutes = int("startMinutes"),
        endMinutes = int("endMinutes"),
        subjectId = str("subjectId"),
        type = sessionTypeOf(getString("type")),
        room = str("room"),
    )
}

internal fun DocumentSnapshot.toSession(): Session? {
    if (!exists()) return null
    val date = getString(F.DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return Session(
        id = id,
        subjectId = str("subjectId"),
        date = date,
        startMinutes = int("startMinutes"),
        endMinutes = int("endMinutes"),
        type = sessionTypeOf(getString("type")),
        status = SessionStatus.entries.firstOrNull { it.wire() == getString("status") } ?: SessionStatus.Scheduled,
        note = str("note"),
        room = str("room"),
        weight = int("weight", 1).coerceAtLeast(1),
        createdBy = str("createdBy"),
        createdByName = str("createdByName"),
        createdAt = instant("createdAt"),
    )
}

/** Null for "cleared" tombstones and unknown values: no mark. */
internal fun DocumentSnapshot.toMarkRecord(): MarkRecord? {
    if (!exists()) return null
    val mark = Mark.entries.firstOrNull { it.wire() == getString("status") } ?: return null
    return MarkRecord(
        sessionId = getString("sessionId") ?: reference.parent.parent?.id ?: return null,
        mark = mark,
        markedAt = instant(F.MARKED_AT),
    )
}
