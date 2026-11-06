package app.roll

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface MainActivityUiState {
    data object Loading : MainActivityUiState
    data class Ready(val themeMode: ThemeMode) : MainActivityUiState
}

/** Theme only; nothing here touches Firebase, so it is safe on unconfigured builds. */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    preferences: UserPreferencesRepository,
) : ViewModel() {
    val uiState: StateFlow<MainActivityUiState> = preferences.themeMode
        .map<ThemeMode, MainActivityUiState> { MainActivityUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainActivityUiState.Loading,
        )
}
