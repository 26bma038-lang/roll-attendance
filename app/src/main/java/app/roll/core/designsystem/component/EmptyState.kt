package app.roll.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing

/** One line of secondary text and, optionally, one text button. No illustration. */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
    ) {
        Text(
            text = message,
            style = RollTheme.typography.body,
            color = RollTheme.colors.inkSecondary,
        )
        if (actionLabel != null && onAction != null) {
            RollTextButton(
                text = actionLabel,
                onClick = onAction,
                // Pull the button's own padding back so its label lines up with the message.
                modifier = Modifier.padding(top = Spacing.xs),
                alignTextToStart = true,
            )
        }
    }
}

@RollPreviews
@Composable
private fun EmptyStatePreview() {
    RollPreviewSurface {
        Column {
            EmptyState(message = "No classes posted yet for today.")
            HairlineDivider()
            EmptyState(
                message = "No subjects yet.",
                actionLabel = "Add a subject",
                onAction = {},
            )
        }
    }
}
