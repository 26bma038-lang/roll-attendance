package app.roll.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.roll.core.designsystem.component.JoinCodeDisplay
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.copyText
import app.roll.core.util.joinMessage
import app.roll.core.util.shareText
import app.roll.domain.model.RollClass

@Composable
fun ClassCreatedScreen(
    rollClass: RollClass,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = RollTheme.colors
    val share = { context.shareText(joinMessage(rollClass.name, rollClass.joinCode), "Share join code") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
    ) {
        Text(
            text = "${rollClass.name} is ready",
            style = RollTheme.typography.title,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Students join with this code. You can find it later in Manage, under Class settings.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(Spacing.xl))
        JoinCodeDisplay(
            code = rollClass.joinCode,
            onCopy = { context.copyText("Join code", rollClass.joinCode) },
        )
        Spacer(Modifier.height(Spacing.lg))
        RollOutlinedButton(text = "Share code", onClick = share, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        RollButton(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

@RollPreviewsWithFontScale
@Composable
private fun ClassCreatedPreview() {
    RollPreviewSurface {
        ClassCreatedScreen(
            rollClass = RollClass("c", "CSE DS — C", "Odd 2026-27", "u", "K7MP2X", 75, "Asia/Kolkata"),
            onContinue = {},
        )
    }
}
