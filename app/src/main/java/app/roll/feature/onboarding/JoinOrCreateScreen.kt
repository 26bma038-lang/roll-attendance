package app.roll.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.designsystem.component.CodeInput
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.auth.AuthRepository
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.JoinResult
import app.roll.data.classes.OfflineException
import app.roll.data.session.AppSession
import app.roll.data.session.JustCreated
import app.roll.data.session.PendingJoin
import app.roll.domain.JoinCode
import app.roll.domain.model.UserProfile
import app.roll.feature.auth.GoogleSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JoinOrCreateUiState(
    val busy: Boolean = false,
    val joinError: String? = null,
    val createError: String? = null,
)

@HiltViewModel
class JoinOrCreateViewModel @Inject constructor(
    private val classes: ClassRepository,
    private val auth: AuthRepository,
    private val google: GoogleSignIn,
    private val session: AppSession,
    private val justCreated: JustCreated,
    private val pendingJoin: PendingJoin,
) : ViewModel() {
    private val _uiState = MutableStateFlow(JoinOrCreateUiState())
    val uiState: StateFlow<JoinOrCreateUiState> = _uiState.asStateFlow()

    /** A code from a roll://join link, if one arrived. */
    val pendingCode: StateFlow<String?> = pendingJoin.code

    fun join(profile: UserProfile, code: String) {
        if (_uiState.value.busy || !JoinCode.isValid(code)) return
        _uiState.value = JoinOrCreateUiState(busy = true)
        viewModelScope.launch {
            val error = try {
                when (classes.joinClass(profile, code)) {
                    is JoinResult.Joined -> {
                        pendingJoin.consume()
                        session.acknowledgeLostAccess()
                        null
                    }
                    JoinResult.CodeNotFound -> "No class uses $code. Check the code with your CR."
                }
            } catch (_: OfflineException) {
                "You're offline. Joining needs a connection."
            } catch (_: Exception) {
                "Couldn't join. Try again."
            }
            _uiState.update { it.copy(busy = false, joinError = error) }
        }
    }

    fun create(profile: UserProfile, name: String, semester: String, requiredPercent: Int) {
        if (_uiState.value.busy) return
        _uiState.value = JoinOrCreateUiState(busy = true)
        viewModelScope.launch {
            val error = try {
                val created = classes.createClass(profile, name.trim(), semester.trim(), requiredPercent)
                justCreated.set(created)
                session.acknowledgeLostAccess()
                null
            } catch (_: OfflineException) {
                "You're offline. Creating a class needs a connection."
            } catch (_: Exception) {
                "Couldn't create the class. Try again."
            }
            _uiState.update { it.copy(busy = false, createError = error) }
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
fun JoinOrCreateRoute(
    profile: UserProfile,
    lostAccess: Boolean,
    viewModel: JoinOrCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingCode by viewModel.pendingCode.collectAsStateWithLifecycle()
    JoinOrCreateScreen(
        uiState = uiState,
        email = profile.email,
        lostAccess = lostAccess,
        linkCode = pendingCode,
        onJoin = { viewModel.join(profile, it) },
        onCreate = { name, semester, percent -> viewModel.create(profile, name, semester, percent) },
        onSignOut = viewModel::signOut,
    )
}

@Composable
fun JoinOrCreateScreen(
    uiState: JoinOrCreateUiState,
    email: String,
    lostAccess: Boolean,
    linkCode: String?,
    onJoin: (String) -> Unit,
    onCreate: (name: String, semester: String, requiredPercent: Int) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val typography = RollTheme.typography
    var code by rememberSaveable { mutableStateOf(linkCode.orEmpty()) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var className by rememberSaveable { mutableStateOf("") }
    var semester by rememberSaveable { mutableStateOf("") }
    var required by rememberSaveable { mutableStateOf("75") }
    val requiredValue = required.toIntOrNull()
    val createValid = className.isNotBlank() && requiredValue != null && requiredValue in 1..100

    LaunchedEffect(linkCode) {
        if (linkCode != null) code = linkCode
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.xl),
    ) {
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            if (lostAccess) {
                Text(
                    text = "You're no longer a member of your previous class.",
                    style = typography.body,
                    color = colors.warning,
                    modifier = Modifier.padding(bottom = Spacing.lg),
                )
            }
            Text(
                text = "Join with a code",
                style = typography.title,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(text = "Ask your CR for the 6-character code.", style = typography.body, color = colors.inkSecondary)
            Spacer(Modifier.height(Spacing.lg))
            CodeInput(code = code, onCodeChange = { code = it }, onDone = { onJoin(code) })
            if (uiState.joinError != null) {
                Text(
                    text = uiState.joinError,
                    style = typography.caption,
                    color = colors.warning,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            RollButton(
                text = "Join",
                onClick = { onJoin(code) },
                enabled = JoinCode.isValid(code) && !uiState.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HairlineDivider(Modifier.padding(vertical = Spacing.xl))

        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            Text(
                text = "I'm the CR — create a class",
                style = typography.title,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "You'll add subjects and the timetable, and post each day's classes.",
                style = typography.body,
                color = colors.inkSecondary,
            )
            Spacer(Modifier.height(Spacing.lg))
            if (!creating) {
                RollOutlinedButton(text = "Create a class", onClick = { creating = true }, modifier = Modifier.fillMaxWidth())
            } else {
                RollTextField(value = className, onValueChange = { className = it.take(80) }, label = "Class name", supporting = "For example, CSE DS — C")
                Spacer(Modifier.height(Spacing.lg))
                RollTextField(value = semester, onValueChange = { semester = it.take(40) }, label = "Semester", supporting = "For example, Odd 2026-27")
                Spacer(Modifier.height(Spacing.lg))
                RollTextField(
                    value = required,
                    onValueChange = { required = it.filter(Char::isDigit).take(3) },
                    label = "Required attendance, %",
                    isError = required.isNotEmpty() && (requiredValue == null || requiredValue !in 1..100),
                    supporting = "Usually 75",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                if (uiState.createError != null) {
                    Text(
                        text = uiState.createError,
                        style = typography.caption,
                        color = colors.warning,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
                RollOutlinedButton(
                    text = if (uiState.busy) "Creating" else "Create class",
                    onClick = { onCreate(className, semester, requiredValue ?: 75) },
                    enabled = createValid && !uiState.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xxl))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.gutter),
        ) {
            Text(
                text = "Signed in as $email",
                style = typography.caption,
                color = colors.inkSecondary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Spacing.xs))
            RollTextButton(text = "Sign out", onClick = onSignOut)
        }
    }
}

@RollPreviewsWithFontScale
@Composable
private fun JoinOrCreatePreview() {
    RollPreviewSurface {
        JoinOrCreateScreen(
            uiState = JoinOrCreateUiState(joinError = "No class uses ABCDEF. Check the code with your CR."),
            email = "student@college.edu",
            lostAccess = false,
            linkCode = null,
            onJoin = {},
            onCreate = { _, _, _ -> },
            onSignOut = {},
        )
    }
}
