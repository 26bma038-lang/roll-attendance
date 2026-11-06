package app.roll.feature.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.BuildConfig
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.ListRow
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollTimePickerDialog
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.data.auth.AuthRepository
import app.roll.data.classes.ClassRepository
import app.roll.data.preferences.ReminderSettings
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.data.profile.ProfileRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.domain.model.ThemeMode
import app.roll.feature.auth.GoogleSignIn
import app.roll.feature.auth.GoogleTokenResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val reminder: ReminderSettings = ReminderSettings(),
    val context: ClassContext? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    session: AppSession,
    private val preferences: UserPreferencesRepository,
    private val reminders: ReminderScheduler,
    private val classes: ClassRepository,
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val google: GoogleSignIn,
    private val messages: UserMessages,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<SettingsUiState> = combine(preferences.themeMode, preferences.reminder, context, busy) { theme, reminder, ctx, isBusy ->
        SettingsUiState(theme, reminder, ctx, isBusy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setReminder(settings: ReminderSettings) {
        viewModelScope.launch {
            preferences.setReminder(settings)
            reminders.apply(settings)
        }
    }

    fun leaveClass() {
        val ctx = context.value ?: return
        if (ctx.isCR) return
        classes.leaveClass(ctx.uid, ctx.classId)
    }

    fun signOut() {
        viewModelScope.launch {
            google.clearState()
            auth.signOut()
        }
    }

    /** Re-signs in with Google (Firebase requires a recent sign-in), then deletes everything. */
    fun deleteAccount(activityContext: Context) {
        val ctx = context.value ?: return
        if (ctx.isCR) {
            messages.show("Transfer the CR role before deleting your account.")
            return
        }
        viewModelScope.launch {
            busy.value = true
            when (val token = google.requestIdToken(activityContext)) {
                is GoogleTokenResult.Success -> try {
                    auth.reauthenticateWithGoogle(token.idToken)
                    classes.deleteMembership(ctx.uid, ctx.classId)
                    profiles.deleteProfile(ctx.uid)
                    auth.deleteAuthUser()
                    google.clearState()
                    reminders.apply(ReminderSettings(enabled = false))
                } catch (_: Exception) {
                    messages.show("Couldn't delete your account. Check your connection and try again.")
                }
                GoogleTokenResult.Cancelled -> Unit
                is GoogleTokenResult.Failure -> messages.show(token.message)
            }
            busy.value = false
        }
    }
}

private enum class SettingsDialog { None, ReminderTime, Leave, LeaveBlocked, SignOut, Delete }

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenClassSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onTheme = viewModel::setTheme,
        onReminder = viewModel::setReminder,
        onLeave = viewModel::leaveClass,
        onSignOut = viewModel::signOut,
        onDelete = { viewModel.deleteAccount(context) },
        onOpenClassSettings = onOpenClassSettings,
    )
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onReminder: (ReminderSettings) -> Unit,
    onLeave: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onOpenClassSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.None) }
    val reminder = uiState.reminder
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onReminder(reminder.copy(enabled = true))
    }
    val isCR = uiState.context?.isCR == true

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "Settings", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionHeader("Theme")
            ListGroup(Modifier.selectableGroup()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    ListRow(
                        title = when (mode) {
                            ThemeMode.System -> "Same as system"
                            ThemeMode.Light -> "Light"
                            ThemeMode.Dark -> "Dark"
                        },
                        showChevron = false,
                        modifier = Modifier.selectable(selected = mode == uiState.themeMode, role = Role.RadioButton, onClick = { onTheme(mode) }),
                        trailing = if (mode == uiState.themeMode) {
                            {
                                Icon(
                                    painter = painterResource(RollIcons.Check),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.padding(end = Spacing.sm),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            SectionHeader("Daily reminder")
            ListGroup {
                ListRow(
                    title = "Remind me to mark",
                    subtitle = "Only on days with classes you haven't marked",
                    showChevron = false,
                    modifier = Modifier.selectable(
                        selected = reminder.enabled,
                        role = Role.Switch,
                        onClick = {
                            val enable = !reminder.enabled
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onReminder(reminder.copy(enabled = enable))
                            }
                        },
                    ),
                    trailing = {
                        Switch(
                            checked = reminder.enabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = colors.accent,
                                checkedThumbColor = colors.onAccent,
                                uncheckedTrackColor = colors.hairline,
                                uncheckedThumbColor = colors.inkSecondary,
                                uncheckedBorderColor = colors.outline,
                            ),
                            modifier = Modifier.padding(end = Spacing.sm),
                        )
                    },
                )
                if (reminder.enabled) {
                    HairlineDivider(startIndent = Spacing.gutter)
                    ListRow(
                        title = "Time",
                        subtitle = formatClock(reminder.minutes, meridiem = true),
                        onClick = { dialog = SettingsDialog.ReminderTime },
                    )
                }
            }

            SectionHeader("Class")
            ListGroup {
                ListRow(
                    title = "Leave ${uiState.context?.rollClass?.name ?: "class"}",
                    subtitle = if (isCR) "Transfer the CR role first" else null,
                    titleColor = colors.warning,
                    showChevron = false,
                    onClick = { dialog = if (isCR) SettingsDialog.LeaveBlocked else SettingsDialog.Leave },
                )
            }

            SectionHeader("Account")
            ListGroup {
                ListRow(
                    title = "Sign out",
                    subtitle = uiState.context?.profile?.email,
                    showChevron = false,
                    onClick = { dialog = SettingsDialog.SignOut },
                )
                HairlineDivider(startIndent = Spacing.gutter)
                ListRow(
                    title = if (uiState.busy) "Deleting account" else "Delete account",
                    titleColor = colors.warning,
                    showChevron = false,
                    onClick = { if (!uiState.busy) dialog = SettingsDialog.Delete },
                )
            }

            Text(
                text = "Roll is a self-tracking tool for students, not an official attendance record. Version ${BuildConfig.VERSION_NAME}.",
                style = RollTheme.typography.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(Spacing.gutter),
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    when (dialog) {
        SettingsDialog.None -> Unit
        SettingsDialog.ReminderTime -> RollTimePickerDialog(
            title = "Reminder time",
            initialMinutes = reminder.minutes,
            onConfirm = {
                onReminder(reminder.copy(minutes = it))
                dialog = SettingsDialog.None
            },
            onDismiss = { dialog = SettingsDialog.None },
        )
        SettingsDialog.Leave -> RollDialog(
            title = "Leave ${uiState.context?.rollClass?.name}?",
            text = "Your marks stay with the class. You can rejoin with the code.",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = {
                dialog = SettingsDialog.None
                onLeave()
            },
            onDismiss = { dialog = SettingsDialog.None },
        )
        SettingsDialog.LeaveBlocked -> RollDialog(
            title = "Transfer the CR role first",
            text = "A class always needs a CR. Hand the role to another member in Class settings, then leave.",
            confirmLabel = "Class settings",
            onConfirm = {
                dialog = SettingsDialog.None
                onOpenClassSettings()
            },
            onDismiss = { dialog = SettingsDialog.None },
        )
        SettingsDialog.SignOut -> RollDialog(
            title = "Sign out?",
            text = "Marks you made offline and haven't synced yet may be lost.",
            confirmLabel = "Sign out",
            onConfirm = {
                dialog = SettingsDialog.None
                onSignOut()
            },
            onDismiss = { dialog = SettingsDialog.None },
        )
        SettingsDialog.Delete -> RollDialog(
            title = "Delete your account?",
            text = if (isCR) {
                "Transfer the CR role first. A class always needs a CR."
            } else {
                "This removes your profile and takes you out of the class. You'll confirm with Google first. This can't be undone."
            },
            confirmLabel = if (isCR) "Class settings" else "Delete account",
            destructive = !isCR,
            onConfirm = {
                dialog = SettingsDialog.None
                if (isCR) onOpenClassSettings() else onDelete()
            },
            onDismiss = { dialog = SettingsDialog.None },
        )
    }
}
