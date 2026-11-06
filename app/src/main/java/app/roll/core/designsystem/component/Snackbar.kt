package app.roll.core.designsystem.component

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import kotlinx.coroutines.withTimeoutOrNull

/** Ink-on-paper snackbar, inverted for contrast, flat, 10dp corners. */
@Composable
fun RollSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = RollShapes.control,
            containerColor = colors.ink,
            contentColor = colors.background,
            actionColor = colors.background,
        )
    }
}

/**
 * Shows a snackbar for exactly [durationMillis] (Material only offers 4s/10s/indefinite).
 * Returns true if the action was tapped.
 */
suspend fun SnackbarHostState.showTimed(
    message: String,
    actionLabel: String?,
    durationMillis: Long = 3_000,
): Boolean {
    val result = withTimeoutOrNull(durationMillis) {
        showSnackbar(
            message = message,
            actionLabel = actionLabel,
            duration = SnackbarDuration.Indefinite,
        )
    }
    return result == SnackbarResult.ActionPerformed
}

/** The app's one snackbar host, provided by the main scaffold so any screen can use it. */
val LocalRollSnackbar = staticCompositionLocalOf { SnackbarHostState() }
