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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import app.roll.R
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatPercent

/**
 * One subject in the Subjects list.
 *
 * Below the requirement, the percentage and the status line turn the warning colour,
 * and the status line says what to do in words, so colour is never the only signal.
 */
@Composable
fun SubjectRow(
    name: String,
    attended: Int,
    total: Int,
    percent: Double,
    status: String,
    belowRequirement: Boolean,
    subjectColor: Color,
    modifier: Modifier = Modifier,
    percentText: String = formatPercent(percent),
    onClick: (() -> Unit)? = null,
) {
    val colors = RollTheme.colors
    val typography = RollTheme.typography
    val emphasis = if (belowRequirement) colors.warning else colors.ink
    val countA11y = stringResource(R.string.subject_attended_of_total_a11y, attended.toString(), total.toString())

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Sizes.rowTallHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { },
    ) {
        SubjectBar(color = subjectColor)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.gutter - Sizes.subjectBarWidth, top = Spacing.md, bottom = Spacing.md),
        ) {
            Text(text = name, style = typography.bodyStrong, color = colors.ink)
            Text(
                text = status,
                style = typography.caption,
                color = if (belowRequirement) colors.warning else colors.inkSecondary,
            )
        }
        Spacer(Modifier.width(Spacing.lg))
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(end = Spacing.gutter),
        ) {
            Text(
                text = percentText,
                style = typography.bodyStrong,
                color = emphasis,
                textAlign = TextAlign.End,
            )
            Text(
                text = stringResource(R.string.subject_attended_of_total, attended.toString(), total.toString()),
                style = typography.caption,
                color = colors.inkSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.clearAndSetSemantics { contentDescription = countA11y },
            )
        }
    }
}

@RollPreviewsWithFontScale
@Composable
private fun SubjectRowPreview() {
    RollPreviewSurface {
        Column(Modifier.background(RollTheme.colors.surface)) {
            SubjectRow(
                name = "Design and Analysis of Algorithms",
                attended = 31, total = 36, percent = 86.1,
                status = "Can miss 5 more", belowRequirement = false,
                subjectColor = RollTheme.colors.subject(0),
            )
            HairlineDivider(startIndent = Spacing.gutter)
            SubjectRow(
                name = "Computer Networks",
                attended = 22, total = 31, percent = 71.0,
                status = "Attend the next 5 to reach 75%", belowRequirement = true,
                subjectColor = RollTheme.colors.subject(2),
            )
        }
    }
}
