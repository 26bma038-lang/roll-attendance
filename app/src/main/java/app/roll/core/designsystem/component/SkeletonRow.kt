package app.roll.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.roll.R
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing

private val BlockShape = RoundedCornerShape(2.dp)

/**
 * Placeholder with the same geometry as [SessionRow], drawn in the hairline colour.
 * Static on purpose: no shimmer. Shown only until the offline cache answers.
 */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    showTrailing: Boolean = true,
) {
    val hairline = RollTheme.colors.hairline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.rowMinHeight)
            .clearAndSetSemantics { },
    ) {
        Spacer(Modifier.fillMaxHeight().width(Sizes.subjectBarWidth).background(hairline))
        Spacer(Modifier.width(Spacing.gutter - Sizes.subjectBarWidth))
        Column(Modifier.width(Sizes.timeColumnMinWidth)) {
            Block(width = 32.dp, height = 14.dp)
            Spacer(Modifier.height(Spacing.sm))
            Block(width = 24.dp, height = 10.dp)
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Block(width = 72.dp, height = 14.dp)
            Spacer(Modifier.height(Spacing.sm))
            Block(width = 168.dp, height = 10.dp)
        }
        if (showTrailing) {
            Box(
                Modifier
                    .size(width = Sizes.toggleSegmentWidth * 2 - 1.dp, height = Sizes.toggleSegmentHeight)
                    .background(hairline, RollShapes.toggle),
            )
        }
        Spacer(Modifier.width(Spacing.gutter))
    }
}

/** A few skeleton rows separated by hairlines, announced once as "Loading". */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    showTrailing: Boolean = true,
) {
    val loading = stringResource(R.string.loading)
    Column(modifier.semantics { contentDescription = loading }) {
        repeat(rows) { index ->
            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
            SkeletonRow(showTrailing = showTrailing)
        }
    }
}

@Composable
private fun Block(width: Dp, height: Dp) {
    Spacer(
        Modifier
            .size(width = width, height = height)
            .background(RollTheme.colors.hairline, BlockShape),
    )
}

@RollPreviews
@Composable
private fun SkeletonPreview() {
    RollPreviewSurface {
        SkeletonList(
            rows = 3,
            modifier = Modifier
                .background(RollTheme.colors.surface)
                .padding(vertical = Spacing.xs),
        )
    }
}
