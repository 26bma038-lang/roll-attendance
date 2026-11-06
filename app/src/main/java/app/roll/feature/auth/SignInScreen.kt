package app.roll.feature.auth

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val google: GoogleSignIn,
    private val auth: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun signIn(activityContext: Context) {
        if (_uiState.value.busy) return
        _uiState.value = SignInUiState(busy = true)
        viewModelScope.launch {
            when (val result = google.requestIdToken(activityContext)) {
                is GoogleTokenResult.Success -> runCatching { auth.signInWithGoogle(result.idToken) }
                    .onSuccess { _uiState.update { it.copy(busy = false) } }
                    .onFailure { _uiState.value = SignInUiState(error = "Couldn't sign in. Check your connection and try again.") }
                GoogleTokenResult.Cancelled -> _uiState.value = SignInUiState()
                is GoogleTokenResult.Failure -> _uiState.value = SignInUiState(error = result.message)
            }
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
            text = "Keep your own record of class attendance, and know how many you can miss.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.weight(1f))
        if (uiState.error != null) {
            Text(
                text = uiState.error,
                style = RollTheme.typography.caption,
                color = colors.warning,
                modifier = Modifier.padding(bottom = Spacing.md),
            )
        }
        RollButton(
            text = if (uiState.busy) "Signing in" else "Continue with Google",
            onClick = onContinue,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@RollPreviewsWithFontScale
@Composable
private fun SignInPreview() {
    RollPreviewSurface { SignInScreen(uiState = SignInUiState(), onContinue = {}) }
}
