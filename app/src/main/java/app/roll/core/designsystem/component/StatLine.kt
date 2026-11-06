package app.roll.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing

@Immutable
data class Stat(val value: String, val label: String)

/**
 * A run of counts in one line: "38 present · 4 absent · 12 not marked".
 * Numbers are in ink and medium weight; words are secondary.
 */
@Composable
fun StatLine(
    stats: List<Stat>,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val typography = RollTheme.typography
    Text(
        text = buildAnnotatedString {
            stats.forEachIndexed { index, stat ->
                if (index > 0) append(" · ")
                withStyle(typography.label.toSpanStyle().copy(color = colors.ink)) { append(stat.value) }
                append(" ")
                append(stat.label)
            }
        },
        style = typography.caption,
        color = colors.inkSecondary,
        modifier = modifier,
    )
}

/**
 * The one big number on a screen: the overall percentage. Display size is reserved for this.
 */
@Composable
fun BigStat(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val colors = RollTheme.colors
    Column(modifier) {
        Text(
            text = value,
            style = RollTheme.typography.display,
            color = if (warning) colors.warning else colors.ink,
        )
        Text(
            text = caption,
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
    }
}

@RollPreviewsWithFontScale
@Composable
private fun StatPreview() {
    RollPreviewSurface {
        Column(Modifier.padding(Spacing.lg)) {
            BigStat(value = "76.6%", caption = "Overall · 108 of 141 · includes 2 unmarked")
            StatLine(
                stats = listOf(Stat("38", "present"), Stat("4", "absent"), Stat("12", "not marked")),
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }
    }
}
