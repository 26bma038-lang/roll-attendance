package app.roll.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing

/** A roll number in Plex Mono, secondary ink, for member lists and profile details. */
@Composable
fun RollNumberText(
    rollNo: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = rollNo.uppercase(),
        style = RollTheme.typography.rollNo,
        color = RollTheme.colors.inkSecondary,
        modifier = modifier,
    )
}

@RollPreviews
@Composable
private fun RollNumberPreview() {
    RollPreviewSurface { RollNumberText(rollNo = "26bma038", modifier = Modifier.padding(Spacing.lg)) }
}
