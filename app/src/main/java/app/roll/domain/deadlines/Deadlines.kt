package app.roll.domain.deadlines

import app.roll.domain.model.Assignment
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Sections of the Deadlines tab, in display order. */
enum class DeadlineGroup(val label: String) {
    Overdue("Overdue"),
    DueToday("Due today"),
    ThisWeek("This week"),
    Later("Later"),
    Done("Done"),
}

/** How far away a deadline is, in words: "in 2 days", "3 h left", "overdue by 1 day". */
data class RelativeDue(val text: String, val overdue: Boolean, val urgent: Boolean)

/** A notification to post before a deadline. */
data class PlannedReminder(
    val assignmentId: String,
    /** How long before the deadline: 24 h or 2 h. */
    val hoursBefore: Long,
    val at: Instant,
) {
    /** Stable key for WorkManager: one job per assignment and offset. */
    val key: String get() = "deadline-$assignmentId-$hoursBefore"
}

/** Pure deadline logic for the Deadlines tab, the Today strip and local reminders. */
object Deadlines {

    val ReminderOffsetsHours = listOf(24L, 2L)
    val DueSoonWindow: Duration = Duration.ofHours(48)

    /**
     * Sorts assignments into sections. Done ones always go to [DeadlineGroup.Done], newest due
     * first; everything else is soonest first. "This week" is the next seven days after today.
     */
    fun group(
        assignments: List<Assignment>,
        doneIds: Set<String>,
        now: Instant,
        zone: ZoneId,
    ): Map<DeadlineGroup, List<Assignment>> {
        val today = now.atZone(zone).toLocalDate()
        val grouped = assignments.groupBy { assignment ->
            val dueDate = assignment.dueAt.atZone(zone).toLocalDate()
            when {
                assignment.id in doneIds -> DeadlineGroup.Done
                !assignment.dueAt.isAfter(now) -> DeadlineGroup.Overdue
                dueDate == today -> DeadlineGroup.DueToday
                !dueDate.isAfter(today.plusDays(7)) -> DeadlineGroup.ThisWeek
                else -> DeadlineGroup.Later
            }
        }
        return DeadlineGroup.entries.associateWith { group ->
            val items = grouped[group].orEmpty()
            if (group == DeadlineGroup.Done) items.sortedByDescending { it.dueAt } else items.sortedBy { it.dueAt }
        }.filterValues { it.isNotEmpty() }
    }

    /** Not done and due within the next 48 hours (and not already past). */
    fun dueSoon(assignments: List<Assignment>, doneIds: Set<String>, now: Instant): List<Assignment> =
        assignments
            .filter { it.id !in doneIds && it.dueAt.isAfter(now) && !it.dueAt.isAfter(now.plus(DueSoonWindow)) }
            .sortedBy { it.dueAt }

    fun relative(dueAt: Instant, now: Instant): RelativeDue {
        val minutes = ChronoUnit.MINUTES.between(now, dueAt)
        if (minutes < 0) {
            val late = -minutes
            val text = when {
                late < 60 -> "overdue by ${late.coerceAtLeast(1)} min"
                late < 24 * 60 -> "overdue by ${late / 60} h"
                else -> (late / (24 * 60)).let { "overdue by $it ${if (it == 1L) "day" else "days"}" }
            }
            return RelativeDue(text, overdue = true, urgent = true)
        }
        val text = when {
            minutes < 60 -> "$minutes min left"
            minutes < 24 * 60 -> "${minutes / 60} h left"
            else -> (minutes / (24 * 60)).let { "in $it ${if (it == 1L) "day" else "days"}" }
        }
        return RelativeDue(text, overdue = false, urgent = minutes < 24 * 60)
    }

    /**
     * Reminders 24 h and 2 h before each deadline that isn't done. Times already past are
     * skipped, so editing a deadline or ticking it done simply drops its reminders on the next sync.
     */
    fun plannedReminders(assignments: List<Assignment>, doneIds: Set<String>, now: Instant): List<PlannedReminder> =
        assignments
            .filter { it.id !in doneIds && it.dueAt.isAfter(now) }
            .flatMap { assignment ->
                ReminderOffsetsHours.map { hours ->
                    PlannedReminder(assignment.id, hours, assignment.dueAt.minus(Duration.ofHours(hours)))
                }
            }
            .filter { it.at.isAfter(now) }
            .sortedBy { it.at }
}
