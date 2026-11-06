package app.roll.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateIn
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.auth.AuthRepository
import app.roll.data.auth.AuthUser
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.OfflineException
import app.roll.domain.college.NithConfig
import app.roll.domain.college.NithIdentity
import app.roll.domain.model.UserProfile
import app.roll.feature.auth.GoogleSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileSetupUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val classes: ClassRepository,
    private val auth: AuthRepository,
    private val google: GoogleSignIn,
    private val time: TimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileSetupUiState())
    val uiState: StateFlow<ProfileSetupUiState> = _uiState.asStateFlow()

    /** Creates the profile and joins the class; the app moves on by itself once the profile doc lands. */
    fun confirm(user: AuthUser, identity: NithIdentity, name: String) {
        if (_uiState.value.busy) return
        _uiState.value = ProfileSetupUiState(busy = true)
        viewModelScope.launch {
            val error = try {
                classes.joinOwnClass(user.uid, identity, name.trim(), time.now().localDateIn(NithConfig.TIMEZONE))
                null
            } catch (_: OfflineException) {
                "You're offline. Setting up needs a connection once."
            } catch (_: Exception) {
                "Couldn't set up your account. Try again."
            }
            _uiState.value = ProfileSetupUiState(error = error)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            google.clearState()
            auth.signOut()
        }
    }
}

@Composable
fun ProfileSetupRoute(
    user: AuthUser,
    identity: NithIdentity,
    existing: UserProfile?,
    viewModel: ProfileSetupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileSetupScreen(
        uiState = uiState,
        identity = identity,
        initialName = existing?.name?.ifBlank { null } ?: user.displayName,
        onContinue = { viewModel.confirm(user, identity, it) },
        onSignOut = viewModel::signOut,
    )
}

/** One confirmation screen: editable name, everything else read-only from the college email. */
@Composable
fun ProfileSetupScreen(
    uiState: ProfileSetupUiState,
    identity: NithIdentity,
    initialName: String,
    onContinue: (name: String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val valid = name.isNotBlank()
    val colors = RollTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
    ) {
        Text(
            text = "Confirm your details",
            style = RollTheme.typography.title,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Your roll number, branch and batch come from your college email. Your CR sees your name next to your marks.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(Spacing.xl))
        RollTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = "Name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onContinue(name) }),
        )
        Spacer(Modifier.height(Spacing.lg))
        ReadOnlyLine(label = "Roll number") {
            Text(
                text = identity.rollNo,
                style = RollTheme.typography.rollNo.copy(fontSize = RollTheme.typography.body.fontSize, lineHeight = RollTheme.typography.body.lineHeight),
                color = colors.ink,
            )
        }
        HairlineDivider()
        ReadOnlyLine(label = "Class") {
            Text(text = identity.description, style = RollTheme.typography.body, color = colors.ink)
        }
        HairlineDivider()
        Spacer(Modifier.height(Spacing.xl))
        if (uiState.error != null) {
            Text(
                text = uiState.error,
                style = RollTheme.typography.caption,
                color = colors.warning,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
        }
        RollButton(
            text = if (uiState.busy) "Setting up" else "Continue",
            onClick = { onContinue(name) },
            enabled = valid && !uiState.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(text = "Signed in as ${identity.email}", style = RollTheme.typography.caption, color = colors.inkSecondary)
        RollTextButton(text = "Use another account", onClick = onSignOut, alignTextToStart = true)
    }
}

@Composable
private fun ReadOnlyLine(label: String, value: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md)
            .semantics(mergeDescendants = true) { },
    ) {
        Text(text = label, style = RollTheme.typography.label, color = RollTheme.colors.inkSecondary)
        Spacer(Modifier.height(Spacing.xxs))
        value()
    }
}

@RollPreviewsWithFontScale
@Composable
private fun ProfileSetupPreview() {
    RollPreviewSurface {
        ProfileSetupScreen(
            uiState = ProfileSetupUiState(),
            identity = NithIdentity("26bma038@nith.ac.in", 2026, "MA", "26BMA038"),
            initialName = "Sahil",
            onContinue = {},
            onSignOut = {},
        )
    }
}
