package app.roll.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.domain.JoinCode

/** Label above, field below, optional supporting line. 10dp corners, accent focus border. */
@Composable
fun RollTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Next,
    ),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    mono: Boolean = false,
) {
    val colors = RollTheme.colors
    val typography = RollTheme.typography
    Column(modifier) {
        Text(text = label, style = typography.label, color = colors.inkSecondary)
        Spacer(Modifier.height(Spacing.xs))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            isError = isError,
            textStyle = if (mono) typography.rollNo.copy(fontSize = typography.body.fontSize) else typography.body,
            shape = RollShapes.control,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.outline,
                errorBorderColor = colors.warning,
                cursorColor = colors.accent,
                errorContainerColor = colors.surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = typography.caption,
                color = if (isError) colors.warning else colors.inkSecondary,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/**
 * Six boxes for a join code, backed by one text field so paste and the keyboard work.
 * Uppercases and drops characters that can't appear in a code (0, O, 1, I).
 */
@Composable
fun CodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val colors = RollTheme.colors
    BasicTextField(
        value = TextFieldValue(code, selection = TextRange(code.length)),
        onValueChange = { onCodeChange(JoinCode.normalize(it.text)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.semantics { contentDescription = "Join code, 6 characters" },
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                repeat(JoinCode.LENGTH) { index ->
                    val char = code.getOrNull(index)
                    val active = index == code.length || (index == JoinCode.LENGTH - 1 && code.length == JoinCode.LENGTH)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 52.dp)
                            .clip(RollShapes.small)
                            .background(colors.surface)
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) colors.accent else colors.outline,
                                shape = RollShapes.small,
                            ),
                    ) {
                        Text(
                            text = char?.toString() ?: "",
                            style = RollTheme.typography.code.copy(fontSize = RollTheme.typography.title.fontSize),
                            color = colors.ink,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Adjacent segments for a small set of options (lecture / lab / tutorial). Selection is
 * shown with ink, not the accent: the accent is reserved for the screen's main action.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    Row(
        modifier
            .clip(RollShapes.small)
            .border(1.dp, colors.outline, RollShapes.small)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            if (index > 0) {
                Box(Modifier.width(1.dp).heightIn(min = Sizes.minTouchTarget).background(colors.outline))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.minTouchTarget)
                    .background(if (isSelected) colors.ink else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = Spacing.sm),
            ) {
                Text(
                    text = label(option),
                    style = RollTheme.typography.label,
                    color = if (isSelected) colors.background else colors.inkSecondary,
                )
            }
        }
    }
}

/** A read-only field that opens a picker (time, date, subject). */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose",
) {
    val colors = RollTheme.colors
    Column(modifier) {
        Text(text = label, style = RollTheme.typography.label, color = colors.inkSecondary)
        Spacer(Modifier.height(Spacing.xs))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RollShapes.control)
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.outline), RollShapes.control)
                .clickable(onClickLabel = label, onClick = onClick)
                .padding(horizontal = Spacing.lg),
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = RollTheme.typography.body,
                color = if (value.isEmpty()) colors.inkSecondary else colors.ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(RollIcons.ExpandMore),
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(Sizes.icon),
            )
        }
    }
}

/** − value + with 44dp buttons. */
@Composable
fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..99,
    valueDescription: (Int) -> String = { it.toString() },
) {
    val colors = RollTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RollShapes.small)
            .border(1.dp, colors.outline, RollShapes.small),
    ) {
        RollIconButton(
            icon = RollIcons.Remove,
            contentDescription = "Fewer",
            onClick = { if (value > range.first) onValueChange(value - 1) },
            tint = if (value > range.first) colors.ink else colors.cancelled,
        )
        Text(
            text = value.toString(),
            style = RollTheme.typography.bodyStrong,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .sizeIn(minWidth = 32.dp)
                .semantics { contentDescription = valueDescription(value) },
        )
        RollIconButton(
            icon = RollIcons.Add,
            contentDescription = "More",
            onClick = { if (value < range.last) onValueChange(value + 1) },
            tint = if (value < range.last) colors.ink else colors.cancelled,
        )
    }
}

@RollPreviews
@Composable
private fun InputsPreview() {
    RollPreviewSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            modifier = Modifier.padding(Spacing.lg),
        ) {
            RollTextField(value = "Sahil", onValueChange = {}, label = "Name")
            RollTextField(value = "", onValueChange = {}, label = "Roll number", supporting = "As on your ID card", mono = true)
            CodeInput(code = "K7M", onCodeChange = {})
            ChoiceRow(
                options = listOf("Lecture", "Lab", "Tutorial"),
                selected = "Lab",
                onSelect = {},
                label = { it },
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(label = "Starts", value = "9:00 am", onClick = {})
            Stepper(value = 3, onValueChange = {})
        }
    }
}
