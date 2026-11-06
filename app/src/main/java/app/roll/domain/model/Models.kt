package app.roll.domain.model

import java.time.Instant
import java.time.LocalDate

data class UserProfile(
    val uid: String,
    val name: String,
    val rollNo: String,
    val email: String,
    val classId: String?,
) {
    val isComplete: Boolean get() = name.isNotBlank() && rollNo.isNotBlank()
}

data class RollClass(
    val id: String,
    val name: String,
    val semester: String,
    val crUid: String,
    val joinCode: String,
    val requiredPercent: Int,
    val timezone: String,
)

data class Member(
    val uid: String,
    val name: String,
    val rollNo: String,
    val joinedAt: Instant?,
)

data class Subject(
    val id: String,
    val name: String,
    val shortName: String,
    val code: String,
    val faculty: String,
    val colorIndex: Int,
    val archived: Boolean,
)

data class TimetableSlot(
    val id: String,
    /** 1 = Monday … 7 = Sunday, as java.time.DayOfWeek. */
    val dayOfWeek: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val subjectId: String,
    val type: SessionType,
    val room: String,
)

data class Session(
    val id: String,
    val subjectId: String,
    val date: LocalDate,
    val startMinutes: Int,
    val endMinutes: Int,
    val type: SessionType,
    val status: SessionStatus,
    val note: String,
    val room: String,
    /** How many classes this counts as. A two-hour lab can count as 2. */
    val weight: Int,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant?,
)

/** One of the signed-in student's own marks. */
data class MarkRecord(
    val sessionId: String,
    val mark: Mark,
    val markedAt: Instant?,
)

/** A mark as the CR sees it in session detail. */
data class MemberMark(
    val uid: String,
    val mark: Mark,
)
