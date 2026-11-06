package app.roll.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing

private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = Spacing.md)

/** The one filled button on a screen. Accent fill, flat, 10dp corners. */
@Composable
fun RollButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RollTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RollShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.hairline,
            disabledContentColor = colors.cancelled,
        ),
        elevation = null,
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = Sizes.buttonHeight),
    ) {
        Text(text = text, style = RollTheme.typography.bodyStrong)
    }
}

/** Secondary action. Ink text, 1dp outline. */
@Composable
fun RollOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RollTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RollShapes.control,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = colors.ink,
            disabledContentColor = colors.cancelled,
        ),
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.hairline),
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = Sizes.buttonHeight),
    ) {
        Text(text = text, style = RollTheme.typography.bodyStrong)
    }
}

/** Low-emphasis action. Accent text, no container. */
@Composable
fun RollTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    /** Removes the start padding so the label lines up with text above it. */
    alignTextToStart: Boolean = false,
) {
    val colors = RollTheme.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RollShapes.control,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) colors.warning else colors.accent,
            disabledContentColor = colors.cancelled,
        ),
        contentPadding = if (alignTextToStart) {
            PaddingValues(end = Spacing.md, top = Spacing.md, bottom = Spacing.md)
        } else {
            PaddingValues(horizontal = Spacing.md, vertical = Spacing.md)
        },
        modifier = modifier.heightIn(min = Sizes.buttonHeight),
    ) {
        Text(text = text, style = RollTheme.typography.bodyStrong)
    }
}

/** 48dp icon button. [contentDescription] is required: every icon button is labelled. */
@Composable
fun RollIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = RollTheme.colors.ink,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(Sizes.icon),
        )
    }
}

@RollPreviews
@Composable
private fun ButtonsPreview() {
    RollPreviewSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(Spacing.lg),
        ) {
            RollButton(text = "Post today from timetable", onClick = {}, modifier = Modifier.fillMaxWidth())
            RollOutlinedButton(text = "Add class", onClick = {})
            RollButton(text = "Join", onClick = {}, enabled = false)
            RollTextButton(text = "Regenerate code", onClick = {})
            RollTextButton(text = "Remove from class", onClick = {}, destructive = true)
            RollIconButton(icon = RollIcons.Settings, contentDescription = "Settings", onClick = {})
        }
    }
}
