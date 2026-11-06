package app.roll.feature.auth

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.auth.AuthRepository
import app.roll.data.session.AppSession
import app.roll.data.session.Rejection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val busy: Boolean = false,
    val error: String? = null,
    /** The last account was turned away (not a college account, or branch not supported). */
    val rejection: Rejection? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val google: GoogleSignIn,
    private val auth: AuthRepository,
    private val session: AppSession,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SignInUiState> = combine(busy, error, session.rejection) { isBusy, message, rejection ->
        SignInUiState(isBusy, message, rejection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SignInUiState())

    fun signIn(activityContext: Context) {
        if (busy.value) return
        busy.value = true
        error.value = null
        session.clearRejection()
        viewModelScope.launch {
            // Show the account picker every time, so a rejected account can be swapped for another.
            google.clearState()
            when (val result = google.requestIdToken(activityContext)) {
                is GoogleTokenResult.Success -> runCatching { auth.signInWithGoogle(result.idToken) }
                    .onFailure { error.value = "Couldn't sign in. Check your connection and try again." }
                GoogleTokenResult.Cancelled -> Unit
                is GoogleTokenResult.Failure -> error.value = result.message
            }
            busy.value = false
        }
    }
}

@Composable
fun SignInRoute(viewModel: SignInViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SignInScreen(uiState = uiState, onContinue = { viewModel.signIn(context) })
}

@Composable
fun SignInScreen(
    uiState: SignInUiState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xxl),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "Roll",
            style = RollTheme.typography.display,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Attendance, deadlines and notices for NIT Hamirpur students. Keep your own record, and know how many classes you can miss.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.weight(1f))
        val rejection = uiState.rejection
        if (rejection != null) {
            RejectionBlock(rejection, Modifier.padding(bottom = Spacing.lg))
        } else if (uiState.error != null) {
            Text(
                text = uiState.error,
                style = RollTheme.typography.caption,
                color = colors.warning,
                modifier = Modifier
                    .padding(bottom = Spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        RollButton(
            text = when {
                uiState.busy -> "Signing in"
                rejection != null -> "Try another account"
                else -> "Continue with your NITH account"
            },
            onClick = onContinue,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Use your @nith.ac.in Google account. Your roll number and branch come from it.",
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
    }
}

/** A quiet bordered block, not a card: what went wrong and with which account. */
@Composable
private fun RejectionBlock(rejection: Rejection, modifier: Modifier = Modifier) {
    val colors = RollTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, colors.outline, RollShapes.control)
            .padding(Spacing.lg)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = when (rejection) {
                is Rejection.WrongDomain -> "Use your @nith.ac.in college account."
                is Rejection.UnsupportedBranch -> "Roll isn't available for your branch yet."
            },
            style = RollTheme.typography.bodyStrong,
            color = colors.warning,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = when (rejection) {
                is Rejection.WrongDomain -> "You signed in as ${rejection.email}, which isn't a college account."
                is Rejection.UnsupportedBranch -> "You signed in as ${rejection.email}. Roll covers B.Tech MA, CS, CH, CE, ME and EC for now."
            },
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
    }
}

@RollPreviewsWithFontScale
@Composable
private fun SignInPreview() {
    RollPreviewSurface { SignInScreen(uiState = SignInUiState(), onContinue = {}) }
}
