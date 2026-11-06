package app.roll.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.roll.R
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviews
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing

/**
 * The class join code, large, in Plex Mono. TalkBack spells it out letter by letter.
 */
@Composable
fun JoinCodeDisplay(
    code: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val spelled = code.toList().joinToString(" ")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            text = code,
            style = RollTheme.typography.code,
            color = RollTheme.colors.ink,
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { contentDescription = spelled },
        )
        if (onCopy != null) {
            RollIconButton(
                icon = RollIcons.Copy,
                contentDescription = stringResource(R.string.action_copy_code),
                onClick = onCopy,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        if (onShare != null) {
            RollIconButton(
                icon = RollIcons.Share,
                contentDescription = stringResource(R.string.action_share_code),
                onClick = onShare,
            )
        }
    }
}

/** A roll number in Plex Mono, secondary ink, for member lists. */
@Composable
fun RollNumberText(
    rollNo: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = rollNo,
        style = RollTheme.typography.rollNo,
        color = RollTheme.colors.inkSecondary,
        modifier = modifier,
    )
}

@RollPreviews
@Composable
private fun JoinCodePreview() {
    RollPreviewSurface {
        Column(Modifier.padding(Spacing.lg)) {
            JoinCodeDisplay(code = "K7MP2X", onCopy = {}, onShare = {})
            RollNumberText(rollNo = "23BCS1042")
        }
    }
}
