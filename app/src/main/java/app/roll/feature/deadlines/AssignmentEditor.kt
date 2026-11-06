package app.roll.feature.deadlines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.roll.core.designsystem.component.PickerField
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollDatePickerDialog
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTimePickerDialog
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.data.assignments.AssignmentDraft
import app.roll.domain.model.AssignmentType
import app.roll.domain.model.Subject
import app.roll.feature.common.AttendanceCopy
import app.roll.feature.manage.SubjectPickerDialog
import java.time.LocalDate
import java.time.ZoneId

private enum class AssignmentPicker { None, Subject, Type, Date, Time }

/** Add or edit an assignment: subject, type, title, due date and time, description, link. */
@Composable
fun AssignmentEditor(
    title: String,
    confirmLabel: String,
    subjects: List<Subject>,
    initial: AssignmentDraft,
    today: LocalDate,
    zone: ZoneId,
    onSave: (AssignmentDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(AssignmentPicker.None) }
    val due = draft.dueAt.atZone(zone)
    val subject = subjects.firstOrNull { it.id == draft.subjectId }
    val linkLooksWrong = draft.link.isNotBlank() && ("." !in draft.link || " " in draft.link.trim())

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(title, style = RollTheme.typography.title, color = RollTheme.colors.ink)
        Spacer(Modifier.height(Spacing.lg))
        PickerField(label = "Subject", value = subject?.let { "${it.shortName} — ${it.name}" }.orEmpty(), onClick = { picker = AssignmentPicker.Subject })
        Spacer(Modifier.height(Spacing.md))
        PickerField(label = "Type", value = draft.type.label, onClick = { picker = AssignmentPicker.Type })
        Spacer(Modifier.height(Spacing.md))
        RollTextField(
            value = draft.title,
            onValueChange = { draft = draft.copy(title = it.take(120)) },
            label = "Title",
            supporting = "For example, DAA assignment 3",
        )
        Spacer(Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            PickerField(
                label = "Due date",
                value = AttendanceCopy.shortDate(due.toLocalDate()),
                onClick = { picker = AssignmentPicker.Date },
                modifier = Modifier.weight(1f),
            )
            PickerField(
                label = "Due time",
                value = formatClock(due.hour * 60 + due.minute, meridiem = true),
                onClick = { picker = AssignmentPicker.Time },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        RollTextField(
            value = draft.description,
            onValueChange = { draft = draft.copy(description = it.take(2000)) },
            label = "Description (optional)",
            singleLine = false,
        )
        Spacer(Modifier.height(Spacing.md))
        RollTextField(
            value = draft.link,
            onValueChange = { draft = draft.copy(link = it.take(500)) },
            label = "Link (optional)",
            supporting = if (linkLooksWrong) "That doesn't look like a web link." else "Google Classroom or Drive. Files can't be uploaded.",
            isError = linkLooksWrong,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        )
        Spacer(Modifier.height(Spacing.xl))
        RollButton(
            text = confirmLabel,
            onClick = { onSave(draft) },
            enabled = draft.isValid && !linkLooksWrong,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (picker) {
        AssignmentPicker.None -> Unit
        AssignmentPicker.Subject -> SubjectPickerDialog(
            subjects = subjects,
            selectedId = draft.subjectId,
            onPick = {
                draft = draft.copy(subjectId = it)
                picker = AssignmentPicker.None
            },
            onDismiss = { picker = AssignmentPicker.None },
        )
        AssignmentPicker.Type -> TypePickerDialog(
            selected = draft.type,
            onPick = {
                draft = draft.copy(type = it)
                picker = AssignmentPicker.None
            },
            onDismiss = { picker = AssignmentPicker.None },
        )
        AssignmentPicker.Date -> RollDatePickerDialog(
            initial = due.toLocalDate(),
            selectable = today.minusDays(30)..today.plusDays(365),
            onConfirm = {
                draft = draft.copy(dueAt = due.with(it).toInstant())
                picker = AssignmentPicker.None
            },
            onDismiss = { picker = AssignmentPicker.None },
        )
        AssignmentPicker.Time -> RollTimePickerDialog(
            title = "Due time",
            initialMinutes = due.hour * 60 + due.minute,
            onConfirm = {
                draft = draft.copy(dueAt = due.withHour(it / 60).withMinute(it % 60).withSecond(0).withNano(0).toInstant())
                picker = AssignmentPicker.None
            },
            onDismiss = { picker = AssignmentPicker.None },
        )
    }
}

@Composable
private fun TypePickerDialog(
    selected: AssignmentType,
    onPick: (AssignmentType) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RollTheme.colors
    RollDialog(title = "Type", confirmLabel = "Close", onConfirm = onDismiss, onDismiss = onDismiss) {
        Column(Modifier.selectableGroup()) {
            AssignmentType.entries.forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.buttonHeight)
                        .selectable(selected = type == selected, role = Role.RadioButton, onClick = { onPick(type) }),
                ) {
                    Text(type.label, style = RollTheme.typography.body, color = colors.ink, modifier = Modifier.weight(1f))
                    if (type == selected) Icon(painterResource(RollIcons.Check), contentDescription = null, tint = colors.accent)
                }
            }
        }
    }
}
