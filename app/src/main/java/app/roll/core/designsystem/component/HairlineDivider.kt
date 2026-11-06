package app.roll.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing

/** The only list separator in the app. One physical pixel, hairline colour. */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 0.dp,
) {
    val onePixel = with(LocalDensity.current) { 1.toDp() }
    Spacer(
        modifier
            .padding(start = startIndent)
            .fillMaxWidth()
            .height(onePixel)
            .background(RollTheme.colors.hairline),
    )
}

/** The 3dp subject colour mark on the left edge of a row. Fills the row's height. */
@Composable
fun SubjectBar(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .fillMaxHeight()
            .width(Sizes.subjectBarWidth)
            .background(color),
    )
}

@RollPreviews
@Composable
private fun HairlineDividerPreview() {
    RollPreviewSurface {
        Column(Modifier.padding(vertical = Spacing.lg)) {
            HairlineDivider()
            Spacer(Modifier.height(Spacing.lg))
            HairlineDivider(startIndent = Spacing.gutter)
        }
    }
}
