package app.roll.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.roll.R
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.Motion
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.domain.model.Mark

private val ToggleCorner = 6.dp
private val LeftShape = RoundedCornerShape(topStart = ToggleCorner, bottomStart = ToggleCorner)
private val RightShape = RoundedCornerShape(topEnd = ToggleCorner, bottomEnd = ToggleCorner)

/**
 * The signature control: two adjacent P / A segments.
 *
 * Tapping the selected segment again clears the mark (`onMarkChange(null)`); the caller
 * is responsible for the "Mark cleared · Undo" snackbar. Each segment is 44×36dp visually,
 * inside a 44×44dp touch target.
 */
@Composable
fun PAToggle(
    mark: Mark?,
    onMarkChange: (Mark?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    fun onSegmentClick(segment: Mark) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onMarkChange(if (mark == segment) null else segment)
    }

    Row(modifier.selectableGroup()) {
        Segment(
            letter = stringResource(R.string.mark_present_letter),
            label = stringResource(R.string.mark_present),
            selected = mark == Mark.Present,
            selectedFill = RollTheme.colors.accent,
            selectedContent = RollTheme.colors.onAccent,
            shape = LeftShape,
            enabled = enabled,
            onClick = { onSegmentClick(Mark.Present) },
            modifier = Modifier.zIndex(if (mark == Mark.Present) 1f else 0f),
        )
        Segment(
            letter = stringResource(R.string.mark_absent_letter),
            label = stringResource(R.string.mark_absent),
            selected = mark == Mark.Absent,
            selectedFill = RollTheme.colors.warning,
            selectedContent = RollTheme.colors.onWarning,
            shape = RightShape,
            enabled = enabled,
            onClick = { onSegmentClick(Mark.Absent) },
            // Overlap by 1dp so the shared edge is a single line, not two.
            modifier = Modifier
                .offset(x = (-1).dp)
                .zIndex(if (mark == Mark.Absent) 1f else 0f),
        )
    }
}

@Composable
private fun Segment(
    letter: String,
    label: String,
    selected: Boolean,
    selectedFill: Color,
    selectedContent: Color,
    shape: RoundedCornerShape,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val fill by animateColorAsState(
        targetValue = if (selected) selectedFill else Color.Transparent,
        animationSpec = Motion.short(),
        label = "segment fill",
    )
    val content by animateColorAsState(
        targetValue = when {
            selected -> selectedContent
            enabled -> colors.inkSecondary
            else -> colors.cancelled
        },
        animationSpec = Motion.short(),
        label = "segment content",
    )
    val border by animateColorAsState(
        targetValue = when {
            selected -> selectedFill
            enabled -> colors.outline
            else -> colors.hairline
        },
        animationSpec = Motion.short(),
        label = "segment border",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // Grows with font scale instead of clipping the letter.
            .sizeIn(minWidth = Sizes.toggleSegmentWidth, minHeight = Sizes.minTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = Sizes.toggleSegmentWidth, minHeight = Sizes.toggleSegmentHeight)
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape),
        ) {
            Text(
                text = letter,
                style = RollTheme.typography.bodyStrong,
                color = content,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@RollPreviews
@Composable
private fun PATogglePreview() {
    RollPreviewSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(Spacing.lg),
        ) {
            PAToggle(mark = null, onMarkChange = {})
            PAToggle(mark = Mark.Present, onMarkChange = {})
            PAToggle(mark = Mark.Absent, onMarkChange = {})
            PAToggle(mark = Mark.Present, onMarkChange = {}, enabled = false)
            var interactive by remember { mutableStateOf<Mark?>(null) }
            PAToggle(mark = interactive, onMarkChange = { interactive = it })
        }
    }
}
