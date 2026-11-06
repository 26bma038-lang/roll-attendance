package app.roll

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roll.core.designsystem.theme.RollTheme
import app.roll.data.session.AppSession
import app.roll.data.session.AppState
import app.roll.domain.model.ThemeMode
import com.google.firebase.FirebaseApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider

/** Longest the splash waits for the first sign-in state before showing the app anyway. */
private const val MAX_SPLASH_MILLIS = 2_500L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModels()

    /** Provider, so Firebase is only touched when it is configured. */
    @Inject lateinit var appSession: Provider<AppSession>

    private val firebaseConfigured: Boolean by lazy { FirebaseApp.getApps(this).isNotEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val started = SystemClock.uptimeMillis()
        // Hold the splash until the theme and first auth state are known, so the first
        // frame is the right screen in the right colours.
        splash.setKeepOnScreenCondition {
            val themeLoading = viewModel.uiState.value is MainActivityUiState.Loading
            val sessionLoading = firebaseConfigured && appSession.get().state.value is AppState.Loading
            (themeLoading || sessionLoading) && SystemClock.uptimeMillis() - started < MAX_SPLASH_MILLIS
        }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val darkTheme = shouldUseDarkTheme(uiState)

            // Status and navigation bar icons follow the app theme, not just the system one.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }

            RollTheme(darkTheme = darkTheme) {
                if (firebaseConfigured) RollApp() else FirebaseSetupNeededScreen()
            }
        }
    }
}

@Composable
private fun shouldUseDarkTheme(uiState: MainActivityUiState): Boolean = when (uiState) {
    MainActivityUiState.Loading -> isSystemInDarkTheme()
    is MainActivityUiState.Ready -> when (uiState.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}
