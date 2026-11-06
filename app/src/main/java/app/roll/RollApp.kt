package app.roll

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.LocalRollSnackbar
import app.roll.core.designsystem.component.RollSnackbarHost
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.data.session.AppSession
import app.roll.data.session.AppState
import app.roll.feature.auth.SignInRoute
import app.roll.feature.onboarding.ProfileSetupRoute
import app.roll.feature.settings.ReminderScheduler
import app.roll.navigation.RollNavHost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Only created once Firebase is known to be configured. */
@HiltViewModel
class RootViewModel @Inject constructor(
    session: AppSession,
    messages: UserMessages,
    preferences: UserPreferencesRepository,
    reminders: ReminderScheduler,
) : ViewModel() {
    val state: StateFlow<AppState> = session.state
    val messages: SharedFlow<String> = messages.messages

    init {
        // Re-arm the daily reminder at its exact time on every start.
        viewModelScope.launch { reminders.apply(preferences.reminder.first()) }
    }
}

@Composable
fun RollApp(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    CompositionLocalProvider(LocalRollSnackbar provides snackbar) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RollTheme.colors.background),
        ) {
            when (val current = state) {
                AppState.Loading -> Unit // the splash screen covers the first load
                AppState.SignedOut -> SignInRoute()
                is AppState.NeedsProfile -> ProfileSetupRoute(user = current.user, identity = current.identity, existing = current.existing)
                is AppState.Ready -> RollNavHost(isCR = current.context.isCR, isAdmin = current.context.isAdmin)
            }
            if (state !is AppState.Ready) {
                RollSnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }
        }
    }
}

/** Shown on builds without google-services.json, instead of crashing. */
@Composable
fun FirebaseSetupNeededScreen() {
    val colors = RollTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.gutter),
    ) {
        Text("Firebase isn't set up", style = RollTheme.typography.title, color = colors.ink)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "This build has no google-services.json, so it can't sign in or sync. " +
                "Follow \"Firebase setup\" in the project README, put google-services.json in the app folder, " +
                "and build again.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
        )
    }
}
