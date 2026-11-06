package app.roll.core.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import app.roll.core.designsystem.theme.RollTheme

/** Light and dark, plus the largest font scale we support. */
@PreviewLightDark
@Preview(name = "Font 200%", fontScale = 2f, widthDp = 360)
annotation class RollPreviewsWithFontScale

/** Light and dark. */
@PreviewLightDark
annotation class RollPreviews

/** Wraps preview content in the theme on the app background. */
@Composable
fun RollPreviewSurface(content: @Composable () -> Unit) {
    RollTheme {
        Box(Modifier.background(RollTheme.colors.background)) {
            content()
        }
    }
}
