package app.roll.domain.model

import java.time.Instant
import java.time.LocalDate

data class UserProfile(
    val uid: String,
    val name: String,
    /** Upper-case, from the college email, e.g. "26BMA038". */
    val rollNo: String,
    val email: String,
    val batch: Int,
    /** Upper-case branch code, e.g. "MA". */
    val branch: String,
    val classId: String?,
) {
    val isComplete: Boolean get() = name.isNotBlank() && rollNo.isNotBlank() && classId != null
}

/** One batch + branch, e.g. `nith-26-ma`. Created by its first student, without CRs. */
data class RollClass(
    val id: String,
    val name: String,
    val semester: String,
    val batch: Int,
    val branch: String,
    /** Up to three CRs, appointed by the admin. */
    val crUids: List<String>,
    /** Display names, same order as [crUids], so students can see who their CRs are. */
    val crNames: List<String>,
    val requiredPercent: Int,
    val timezone: String,
    val memberCount: Int,
) {
    fun isCR(uid: String): Boolean = uid in crUids
}

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

enum class AssignmentType(val label: String) {
    Assignment("Assignment"),
    LabReport("Lab report"),
    Quiz("Quiz"),
    MidSem("Mid-sem"),
    EndSem("End-sem"),
    Presentation("Presentation"),
    Other("Other"),
}

/** Posted by a CR (or the admin) for the whole class. */
data class Assignment(
    val id: String,
    val title: String,
    val subjectId: String,
    val type: AssignmentType,
    val description: String,
    /** Optional URL (Google Classroom, Drive). No file uploads on the Spark plan. */
    val link: String,
    val dueAt: Instant,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant?,
)

/** A class notice (posted by a CR) or a college notice (posted by the admin). */
data class Notice(
    val id: String,
    val text: String,
    val pinned: Boolean,
    val createdAt: Instant?,
    val expiresAt: Instant?,
    val createdByName: String,
    val college: Boolean,
) {
    fun isActive(now: Instant): Boolean = expiresAt == null || expiresAt.isAfter(now)
}

data class DepartmentLink(val title: String, val url: String)

/** Admin-maintained facts about a branch. Blank fields read "Not added yet". */
data class Department(
    val code: String,
    val name: String,
    val about: String = "",
    val hod: String = "",
    val office: String = "",
    val email: String = "",
    val phone: String = "",
    val links: List<DepartmentLink> = emptyList(),
)
