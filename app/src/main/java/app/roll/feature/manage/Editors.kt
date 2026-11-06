package app.roll.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.roll.core.designsystem.component.ChoiceRow
import app.roll.core.designsystem.component.PickerField
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollDatePickerDialog
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTimePickerDialog
import app.roll.core.designsystem.component.Stepper
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.schedule.SessionDraft
import app.roll.feature.common.AttendanceCopy
import java.time.LocalDate

/** Which picker dialog is open inside an editor. */
private enum class Picker { None, Subject, Date, Start, End }

/**
 * Add or edit a class (session): subject, date, time, type, room, weight.
 * Used from Manage ("+ Add class") and Session detail ("Edit").
 */
@Composable
fun SessionEditor(
    title: String,
    confirmLabel: String,
    subjects: List<Subject>,
    initial: SessionDraft,
    today: LocalDate,
    onSave: (SessionDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(Picker.None) }
    val subject = subjects.firstOrNull { it.id == draft.subjectId }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(title, style = RollTheme.typography.title, color = RollTheme.colors.ink)
        Spacer(Modifier.height(Spacing.lg))
        PickerField(label = "Subject", value = subject?.let { "${it.shortName} — ${it.name}" }.orEmpty(), onClick = { picker = Picker.Subject })
        Spacer(Modifier.height(Spacing.md))
        PickerField(label = "Date", value = AttendanceCopy.shortDate(draft.date), onClick = { picker = Picker.Date })
        Spacer(Modifier.height(Spacing.md))
        TimeRange(draft.startMinutes, draft.endMinutes, onStart = { picker = Picker.Start }, onEnd = { picker = Picker.End })
        if (draft.endMinutes <= draft.startMinutes) EndBeforeStart()
        Spacer(Modifier.height(Spacing.md))
        TypeChoice(draft.type) { draft = draft.copy(type = it) }
        Spacer(Modifier.height(Spacing.md))
        RollTextField(value = draft.room, onValueChange = { draft = draft.copy(room = it.take(30)) }, label = "Room")
        Spacer(Modifier.height(Spacing.md))
        Text("Counts as", style = RollTheme.typography.label, color = RollTheme.colors.inkSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = draft.weight,
                onValueChange = { draft = draft.copy(weight = it) },
                range = 1..SessionDraft.MAX_WEIGHT,
                valueDescription = { "Counts as ${AttendanceCopy.classes(it)}" },
            )
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = if (draft.weight == 1) "class" else "classes, e.g. a two-hour lab",
                style = RollTheme.typography.caption,
                color = RollTheme.colors.inkSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        RollButton(text = confirmLabel, onClick = { onSave(draft) }, enabled = draft.isValid, modifier = Modifier.fillMaxWidth())
    }

    when (picker) {
        Picker.None -> Unit
        Picker.Subject -> SubjectPickerDialog(subjects, draft.subjectId, onPick = { draft = draft.copy(subjectId = it); picker = Picker.None }, onDismiss = { picker = Picker.None })
        Picker.Date -> RollDatePickerDialog(
            initial = draft.date,
            selectable = today.minusDays(7)..today.plusDays(60),
            onConfirm = { draft = draft.copy(date = it); picker = Picker.None },
            onDismiss = { picker = Picker.None },
        )
        Picker.Start -> RollTimePickerDialog("Starts", draft.startMinutes, onConfirm = {
            val length = (draft.endMinutes - draft.startMinutes).coerceAtLeast(50)
            draft = draft.copy(startMinutes = it, endMinutes = (it + length).coerceAtMost(23 * 60 + 59))
            picker = Picker.None
        }, onDismiss = { picker = Picker.None })
        Picker.End -> RollTimePickerDialog("Ends", draft.endMinutes, onConfirm = { draft = draft.copy(endMinutes = it); picker = Picker.None }, onDismiss = { picker = Picker.None })
    }
}

/** Add or edit a weekly timetable slot. */
@Composable
fun SlotEditor(
    title: String,
    subjects: List<Subject>,
    initial: SlotDraft,
    onSave: (SlotDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var draft by remember { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(Picker.None) }
    val subject = subjects.firstOrNull { it.id == draft.subjectId }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(title, style = RollTheme.typography.title, color = RollTheme.colors.ink)
        Spacer(Modifier.height(Spacing.lg))
        PickerField(label = "Subject", value = subject?.let { "${it.shortName} — ${it.name}" }.orEmpty(), onClick = { picker = Picker.Subject })
        Spacer(Modifier.height(Spacing.md))
        Text("Day", style = RollTheme.typography.label, color = RollTheme.colors.inkSecondary)
        Spacer(Modifier.height(Spacing.xs))
        ChoiceRow(
            options = (1..6).toList(),
            selected = draft.dayOfWeek,
            onSelect = { draft = draft.copy(dayOfWeek = it) },
            label = { WeekdayShort[it - 1] },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.md))
        TimeRange(draft.startMinutes, draft.endMinutes, onStart = { picker = Picker.Start }, onEnd = { picker = Picker.End })
        if (draft.endMinutes <= draft.startMinutes) EndBeforeStart()
        Spacer(Modifier.height(Spacing.md))
        TypeChoice(draft.type) { draft = draft.copy(type = it) }
        Spacer(Modifier.height(Spacing.md))
        RollTextField(value = draft.room, onValueChange = { draft = draft.copy(room = it.take(30)) }, label = "Room")
        Spacer(Modifier.height(Spacing.xl))
        RollButton(text = "Save slot", onClick = { onSave(draft) }, enabled = draft.isValid, modifier = Modifier.fillMaxWidth())
        if (onDelete != null) {
            RollTextButton(text = "Delete slot", onClick = onDelete, destructive = true, modifier = Modifier.padding(top = Spacing.sm))
        }
    }

    when (picker) {
        Picker.Subject -> SubjectPickerDialog(subjects, draft.subjectId, onPick = { draft = draft.copy(subjectId = it); picker = Picker.None }, onDismiss = { picker = Picker.None })
        Picker.Start -> RollTimePickerDialog("Starts", draft.startMinutes, onConfirm = {
            val length = (draft.endMinutes - draft.startMinutes).coerceAtLeast(50)
            draft = draft.copy(startMinutes = it, endMinutes = (it + length).coerceAtMost(23 * 60 + 59))
            picker = Picker.None
        }, onDismiss = { picker = Picker.None })
        Picker.End -> RollTimePickerDialog("Ends", draft.endMinutes, onConfirm = { draft = draft.copy(endMinutes = it); picker = Picker.None }, onDismiss = { picker = Picker.None })
        else -> Unit
    }
}

data class SlotDraft(
    val id: String = "",
    val subjectId: String = "",
    val dayOfWeek: Int = 1,
    val startMinutes: Int = 9 * 60,
    val endMinutes: Int = 9 * 60 + 50,
    val type: SessionType = SessionType.Lecture,
    val room: String = "",
) {
    val isValid: Boolean get() = subjectId.isNotBlank() && endMinutes > startMinutes && dayOfWeek in 1..7
}

/** Add or edit a subject, including its colour and archived state. */
@Composable
fun SubjectEditor(
    initial: Subject,
    onSave: (Subject) -> Unit,
) {
    var subject by remember { mutableStateOf(initial) }
    var colorIndex by remember { mutableIntStateOf(initial.colorIndex) }
    val valid = subject.name.isNotBlank() && subject.shortName.isNotBlank()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(if (initial.id.isBlank()) "Add subject" else "Edit subject", style = RollTheme.typography.title, color = RollTheme.colors.ink)
        Spacer(Modifier.height(Spacing.lg))
        RollTextField(value = subject.name, onValueChange = { subject = subject.copy(name = it.take(80)) }, label = "Name", supporting = "For example, Design and Analysis of Algorithms")
        Spacer(Modifier.height(Spacing.md))
        RollTextField(value = subject.shortName, onValueChange = { subject = subject.copy(shortName = it.take(10)) }, label = "Short name", supporting = "Shown in lists, e.g. DAA")
        Spacer(Modifier.height(Spacing.md))
        RollTextField(value = subject.code, onValueChange = { subject = subject.copy(code = it.take(20)) }, label = "Course code", mono = true)
        Spacer(Modifier.height(Spacing.md))
        RollTextField(value = subject.faculty, onValueChange = { subject = subject.copy(faculty = it.take(60)) }, label = "Faculty")
        Spacer(Modifier.height(Spacing.md))
        Text("Colour", style = RollTheme.typography.label, color = RollTheme.colors.inkSecondary)
        Spacer(Modifier.height(Spacing.xs))
        ColourChoice(selected = colorIndex, onSelect = { colorIndex = it })
        Spacer(Modifier.height(Spacing.xl))
        RollButton(
            text = "Save subject",
            onClick = { onSave(subject.copy(name = subject.name.trim(), shortName = subject.shortName.trim(), colorIndex = colorIndex)) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        )
        if (initial.id.isNotBlank()) {
            RollTextButton(
                text = if (initial.archived) "Restore subject" else "Archive subject",
                onClick = { onSave(initial.copy(archived = !initial.archived)) },
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

private val WeekdayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
private fun TimeRange(start: Int, end: Int, onStart: () -> Unit, onEnd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        PickerField(label = "Starts", value = formatClock(start, meridiem = true), onClick = onStart, modifier = Modifier.weight(1f))
        PickerField(label = "Ends", value = formatClock(end, meridiem = true), onClick = onEnd, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EndBeforeStart() {
    Text(
        text = "The end time must be after the start.",
        style = RollTheme.typography.caption,
        color = RollTheme.colors.warning,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

@Composable
private fun TypeChoice(selected: SessionType, onSelect: (SessionType) -> Unit) {
    Text("Type", style = RollTheme.typography.label, color = RollTheme.colors.inkSecondary)
    Spacer(Modifier.height(Spacing.xs))
    ChoiceRow(
        options = SessionType.entries,
        selected = selected,
        onSelect = onSelect,
        label = AttendanceCopy::typeLabel,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColourChoice(selected: Int, onSelect: (Int) -> Unit) {
    val colors = RollTheme.colors
    Row(Modifier.selectableGroup()) {
        colors.subjects.forEachIndexed { index, color ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.minTouchTarget)
                    .selectable(selected = index == selected, role = Role.RadioButton, onClick = { onSelect(index) }),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 28.dp, height = 36.dp)
                        .then(if (index == selected) Modifier.border(2.dp, colors.ink, RollShapes.small) else Modifier),
                ) {
                    Box(
                        Modifier
                            .width(8.dp)
                            .fillMaxHeight()
                            .padding(vertical = 4.dp)
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
fun SubjectPickerDialog(
    subjects: List<Subject>,
    selectedId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RollTheme.colors
    RollDialog(
        title = "Subject",
        confirmLabel = "Close",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        text = if (subjects.isEmpty()) "Add subjects first, in Manage → Subjects." else null,
    ) {
        Column(
            Modifier
                .selectableGroup()
                .verticalScroll(rememberScrollState()),
        ) {
            subjects.filter { !it.archived }.forEach { subject ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.buttonHeight)
                        .selectable(selected = subject.id == selectedId, role = Role.RadioButton, onClick = { onPick(subject.id) }),
                ) {
                    Box(Modifier.size(width = Sizes.subjectBarWidth, height = 28.dp)) { SubjectBar(colors.subject(subject.colorIndex)) }
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(subject.shortName, style = RollTheme.typography.bodyStrong, color = colors.ink)
                        Text(subject.name, style = RollTheme.typography.caption, color = colors.inkSecondary)
                    }
                    if (subject.id == selectedId) {
                        Icon(painterResource(RollIcons.Check), contentDescription = null, tint = colors.accent)
                    }
                }
            }
        }
    }
}
