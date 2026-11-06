package app.roll.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.roll.R
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.domain.model.Mark
import app.roll.domain.model.SessionType

/**
 * One class in a day's list: time · subject · trailing control.
 *
 * TalkBack reads the time, then the subject (merged into one node), then the trailing
 * control. Cancelled sessions are struck through and greyed, with the CR's note.
 */
@Composable
fun SessionRow(
    startMinutes: Int,
    endMinutes: Int,
    shortName: String,
    fullName: String,
    room: String,
    type: SessionType,
    subjectColor: Color,
    modifier: Modifier = Modifier,
    cancelled: Boolean = false,
    note: String? = null,
    onClick: (() -> Unit)? = null,
    /** Extra line under the subject, e.g. the CR's present / absent counts. */
    detail: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = RollTheme.colors
    val typography = RollTheme.typography
    val timeA11y = stringResource(
        R.string.session_time_range_a11y,
        formatClock(startMinutes, meridiem = true),
        formatClock(endMinutes, meridiem = true),
    )
    val meta = listOfNotNull(room.takeIf { it.isNotBlank() }, type.labelOrNull()).joinToString(" · ")
    val secondLine = when {
        cancelled && !note.isNullOrBlank() -> stringResource(R.string.session_cancelled_with_note, note)
        cancelled -> stringResource(R.string.session_cancelled)
        !note.isNullOrBlank() -> "$fullName · $note"
        else -> fullName
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Sizes.rowMinHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        SubjectBar(color = if (cancelled) colors.cancelled else subjectColor)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.gutter - Sizes.subjectBarWidth, top = Spacing.md, bottom = Spacing.md)
                .semantics(mergeDescendants = true) { },
        ) {
            Column(
                modifier = Modifier
                    // Measured, so the column fits "10:50" at any font scale and rows stay aligned.
                    .width(timeColumnWidth())
                    .clearAndSetSemantics { contentDescription = timeA11y },
            ) {
                Text(
                    text = formatClock(startMinutes),
                    style = typography.body,
                    softWrap = false,
                    color = if (cancelled) colors.cancelled else colors.ink,
                )
                Text(
                    text = formatClock(endMinutes),
                    style = typography.caption,
                    softWrap = false,
                    color = if (cancelled) colors.cancelled else colors.inkSecondary,
                )
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            typography.bodyStrong.toSpanStyle().copy(
                                color = if (cancelled) colors.cancelled else colors.ink,
                                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                            ),
                        ) { append(shortName) }
                        if (meta.isNotEmpty()) {
                            append("  ")
                            withStyle(
                                typography.label.toSpanStyle().copy(
                                    color = if (cancelled) colors.cancelled else colors.inkSecondary,
                                ),
                            ) { append(meta) }
                        }
                    },
                    style = typography.bodyStrong,
                )
                Text(
                    text = secondLine,
                    style = typography.caption,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    detail()
                }
            }
        }
        Spacer(Modifier.width(Spacing.md))
        trailing()
        Spacer(Modifier.width(Spacing.gutter))
    }
}

/** Student variant: the trailing control is the P / A toggle. Cancelled sessions get no toggle. */
@Composable
fun SessionRow(
    startMinutes: Int,
    endMinutes: Int,
    shortName: String,
    fullName: String,
    room: String,
    type: SessionType,
    subjectColor: Color,
    mark: Mark?,
    onMarkChange: (Mark?) -> Unit,
    modifier: Modifier = Modifier,
    cancelled: Boolean = false,
    note: String? = null,
    markEnabled: Boolean = true,
) {
    SessionRow(
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        shortName = shortName,
        fullName = fullName,
        room = room,
        type = type,
        subjectColor = subjectColor,
        modifier = modifier,
        cancelled = cancelled,
        note = note,
    ) {
        if (!cancelled) {
            PAToggle(mark = mark, onMarkChange = onMarkChange, enabled = markEnabled)
        }
    }
}

/** Lectures are the default and go unlabelled; labs and tutorials say so. */
@Composable
private fun SessionType.labelOrNull(): String? = when (this) {
    SessionType.Lecture -> null
    SessionType.Lab -> stringResource(R.string.session_type_lab)
    SessionType.Tutorial -> stringResource(R.string.session_type_tutorial)
}

@RollPreviewsWithFontScale
@Composable
private fun SessionRowPreview() {
    RollPreviewSurface {
        Column(Modifier.background(RollTheme.colors.surface)) {
            SessionRow(
                startMinutes = 9 * 60, endMinutes = 9 * 60 + 50,
                shortName = "DAA", fullName = "Design and Analysis of Algorithms",
                room = "LH-204", type = SessionType.Lecture,
                subjectColor = RollTheme.colors.subject(0),
                mark = Mark.Present, onMarkChange = {},
            )
            HairlineDivider(startIndent = Spacing.gutter)
            SessionRow(
                startMinutes = 11 * 60 + 40, endMinutes = 13 * 60 + 20,
                shortName = "OS", fullName = "Operating Systems Laboratory",
                room = "Lab 3", type = SessionType.Lab,
                subjectColor = RollTheme.colors.subject(4),
                mark = null, onMarkChange = {},
            )
            HairlineDivider(startIndent = Spacing.gutter)
            SessionRow(
                startMinutes = 10 * 60 + 50, endMinutes = 11 * 60 + 40,
                shortName = "CN", fullName = "Computer Networks",
                room = "LH-204", type = SessionType.Lecture,
                subjectColor = RollTheme.colors.subject(2),
                mark = null, onMarkChange = {},
                cancelled = true, note = "faculty on leave",
            )
        }
    }
}

/** Width of the widest time ("10:50") in the body style, at the current font scale. */
@Composable
private fun timeColumnWidth(): Dp {
    val style = RollTheme.typography.body
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) {
        with(density) { measurer.measure("10:50", style).size.width.toDp() + 2.dp }
    }
}
