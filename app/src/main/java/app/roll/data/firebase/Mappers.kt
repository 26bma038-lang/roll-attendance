package app.roll.data.firebase

import app.roll.core.common.DEFAULT_CLASS_TIMEZONE
import app.roll.domain.model.Assignment
import app.roll.domain.model.AssignmentType
import app.roll.domain.model.Department
import app.roll.domain.model.DepartmentLink
import app.roll.domain.model.Mark
import app.roll.domain.model.MarkRecord
import app.roll.domain.model.Notice
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
    const val MEMBERS = "members"
    const val SUBJECTS = "subjects"
    const val TIMETABLE = "timetable"
    const val SESSIONS = "sessions"
    const val MARKS = "marks"
    const val ASSIGNMENTS = "assignments"
    const val ASSIGNMENT_STATUS = "assignmentStatus"
    const val NOTICES = "notices"
    const val DEPARTMENTS = "departments"

    const val CLASS_ID = "classId"
    const val UPDATED_AT = "updatedAt"
    const val MARKED_AT = "markedAt"
    const val UID = "uid"
    const val DATE = "date"

    const val STATUS_CLEARED = "cleared"
    const val STATUS_ACTIVE = "active"
    const val STATUS_DELETED = "deleted"
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
        batch = int("batch"),
        branch = str("branch"),
        classId = getString(F.CLASS_ID)?.takeIf { it.isNotBlank() },
    )
}

internal fun DocumentSnapshot.toRollClass(): RollClass? {
    if (!exists()) return null
    fun strings(field: String): List<String> = (get(field) as? List<*>).orEmpty().filterIsInstance<String>()
    return RollClass(
        id = id,
        name = str("name"),
        semester = str("semester"),
        batch = int("batch"),
        branch = str("branch"),
        crUids = strings("crUids"),
        crNames = strings("crNames"),
        requiredPercent = int("requiredPercent", 75).coerceIn(1, 100),
        timezone = getString("timezone") ?: DEFAULT_CLASS_TIMEZONE,
        memberCount = int("memberCount"),
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

/** Wire names for assignment types, shared with firestore.rules. */
internal val AssignmentType.wire: String
    get() = when (this) {
        AssignmentType.Assignment -> "assignment"
        AssignmentType.LabReport -> "lab_report"
        AssignmentType.Quiz -> "quiz"
        AssignmentType.MidSem -> "mid_sem"
        AssignmentType.EndSem -> "end_sem"
        AssignmentType.Presentation -> "presentation"
        AssignmentType.Other -> "other"
    }

internal fun assignmentTypeOf(value: String?): AssignmentType =
    AssignmentType.entries.firstOrNull { it.wire == value } ?: AssignmentType.Other

/** Null for deleted tombstones and broken docs. */
internal fun DocumentSnapshot.toAssignment(): Assignment? {
    if (!exists() || getString("status") == F.STATUS_DELETED) return null
    return Assignment(
        id = id,
        title = str("title"),
        subjectId = str("subjectId"),
        type = assignmentTypeOf(getString("type")),
        description = str("description"),
        link = str("link"),
        dueAt = instant("dueAt") ?: return null,
        createdBy = str("createdBy"),
        createdByName = str("createdByName"),
        createdAt = instant("createdAt"),
    )
}

/** Null for deleted tombstones. */
internal fun DocumentSnapshot.toNotice(college: Boolean): Notice? {
    if (!exists() || getString("status") == F.STATUS_DELETED) return null
    return Notice(
        id = id,
        text = str("text"),
        pinned = getBoolean("pinned") ?: false,
        createdAt = instant("createdAt"),
        expiresAt = instant("expiresAt"),
        createdByName = str("createdByName"),
        college = college,
    )
}

internal fun DocumentSnapshot.toDepartment(code: String, name: String): Department {
    if (!exists()) return Department(code = code, name = name)
    val links = (get("links") as? List<*>).orEmpty().mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val url = map["url"] as? String ?: return@mapNotNull null
        DepartmentLink(title = (map["title"] as? String).orEmpty().ifBlank { url }, url = url)
    }
    return Department(
        code = code,
        name = getString("name")?.ifBlank { null } ?: name,
        about = str("about"),
        hod = str("hod"),
        office = str("office"),
        email = str("email"),
        phone = str("phone"),
        links = links,
    )
}
