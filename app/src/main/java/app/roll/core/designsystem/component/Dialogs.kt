package app.roll.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A plain dialog: title, one line or a slot, two text buttons. */
@Composable
fun RollDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = RollTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RollShapes.control,
        containerColor = colors.surface,
        titleContentColor = colors.ink,
        textContentColor = colors.inkSecondary,
        title = { Text(title, style = RollTheme.typography.title) },
        text = if (text == null && content == null) {
            null
        } else {
            {
                Column {
                    if (text != null) Text(text, style = RollTheme.typography.body)
                    content?.invoke(this)
                }
            }
        },
        confirmButton = {
            RollTextButton(text = confirmLabel, onClick = onConfirm, destructive = destructive, enabled = confirmEnabled)
        },
        dismissButton = {
            RollTextButton(text = dismissLabel, onClick = onDismiss)
        },
    )
}

/** Keyboard-style time entry (no dial), 12-hour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RollTheme.colors
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = false,
    )
    RollDialog(
        title = title,
        confirmLabel = "Set",
        onConfirm = { onConfirm(state.hour * 60 + state.minute) },
        onDismiss = onDismiss,
    ) {
        TimeInput(
            state = state,
            colors = TimePickerDefaults.colors(
                containerColor = colors.surface,
                timeSelectorSelectedContainerColor = colors.hairline,
                timeSelectorUnselectedContainerColor = colors.background,
                timeSelectorSelectedContentColor = colors.ink,
                timeSelectorUnselectedContentColor = colors.ink,
                periodSelectorBorderColor = colors.outline,
                periodSelectorSelectedContainerColor = colors.ink,
                periodSelectorSelectedContentColor = colors.background,
                periodSelectorUnselectedContainerColor = colors.surface,
                periodSelectorUnselectedContentColor = colors.inkSecondary,
            ),
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }
}

/** A calendar date picker limited to [selectable]. Dates are plain LocalDates (no time zone games). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollDatePickerDialog(
    initial: LocalDate,
    selectable: ClosedRange<LocalDate>,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RollTheme.colors
    val selectableDates = remember(selectable) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) in selectable
            override fun isSelectableYear(year: Int): Boolean = year in selectable.start.year..selectable.endInclusive.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = selectableDates,
    )
    val pickerColors = DatePickerDefaults.colors(
        containerColor = colors.surface,
        titleContentColor = colors.inkSecondary,
        headlineContentColor = colors.ink,
        weekdayContentColor = colors.inkSecondary,
        dayContentColor = colors.ink,
        disabledDayContentColor = colors.cancelled,
        selectedDayContainerColor = colors.accent,
        selectedDayContentColor = colors.onAccent,
        todayContentColor = colors.accent,
        todayDateBorderColor = colors.accent,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = RollShapes.control,
        colors = pickerColors,
        confirmButton = {
            RollTextButton(
                text = "Set",
                onClick = { state.selectedDateMillis?.let { onConfirm(utcDate(it)) } ?: onDismiss() },
            )
        },
        dismissButton = { RollTextButton(text = "Cancel", onClick = onDismiss) },
    ) {
        DatePicker(state = state, colors = pickerColors, showModeToggle = false)
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()

/** The bottom sheet: surface colour, 10dp top corners, the only elevated surface besides dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RollTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RollShapes.sheet,
        containerColor = colors.surface,
        contentColor = colors.ink,
        scrimColor = colors.ink.copy(alpha = 0.32f),
    ) {
        Column(
            Modifier
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.lg)
                .navigationBarsPadding(),
            content = content,
        )
    }
}
