package app.roll.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.domain.model.Mark
import app.roll.domain.model.SessionType
import app.roll.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SampleSession(
    val id: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val shortName: String,
    val fullName: String,
    val room: String,
    val type: SessionType,
    val colorIndex: Int,
    val mark: Mark? = null,
    val cancelled: Boolean = false,
    val note: String? = null,
)

data class SampleSubject(
    val name: String,
    val attended: Int,
    val total: Int,
    val percent: Double,
    val status: String,
    val belowRequirement: Boolean,
    val colorIndex: Int,
)

/** The mark that was just cleared, kept so the snackbar can undo it. */
data class ClearedMark(val sessionId: String, val previous: Mark)

data class GalleryUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val sessions: List<SampleSession> = SampleData.sessions,
    val subjects: List<SampleSubject> = SampleData.subjects,
    val selectedNavIndex: Int = 0,
    val clearedMark: ClearedMark? = null,
)

/**
 * Drives the design-system gallery. Exists to prove the MVVM wiring (Hilt, StateFlow,
 * DataStore) before real features land; it is removed once sign-in exists.
 */
@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    private val local = MutableStateFlow(GalleryUiState())

    val uiState: StateFlow<GalleryUiState> = combine(local, preferences.themeMode) { state, theme ->
        state.copy(themeMode = theme)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GalleryUiState(),
    )

    fun onMarkChange(sessionId: String, mark: Mark?) {
        local.update { state ->
            val previous = state.sessions.firstOrNull { it.id == sessionId }?.mark
            state.copy(
                sessions = state.sessions.map { if (it.id == sessionId) it.copy(mark = mark) else it },
                clearedMark = when {
                    mark == null && previous != null -> ClearedMark(sessionId, previous)
                    state.clearedMark?.sessionId == sessionId -> null
                    else -> state.clearedMark
                },
            )
        }
    }

    fun onUndoClear() {
        local.update { state ->
            val cleared = state.clearedMark ?: return@update state
            state.copy(
                sessions = state.sessions.map {
                    if (it.id == cleared.sessionId) it.copy(mark = cleared.previous) else it
                },
                clearedMark = null,
            )
        }
    }

    fun onUndoDismissed() {
        local.update { it.copy(clearedMark = null) }
    }

    fun onNavSelect(index: Int) {
        local.update { it.copy(selectedNavIndex = index) }
    }

    fun onThemeModeChange(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }
}

/**
 * Sample content for the gallery only. Numbers are internally consistent at a 75% requirement
 * (e.g. 31 of 36 can miss 5 more: 31 / 41 = 75.6%, 31 / 42 = 73.8%).
 */
internal object SampleData {
    val sessions = listOf(
        SampleSession("s1", 9 * 60, 9 * 60 + 50, "DAA", "Design and Analysis of Algorithms", "LH-204", SessionType.Lecture, 0, mark = Mark.Present),
        SampleSession("s2", 9 * 60 + 50, 10 * 60 + 40, "DBMS", "Database Management Systems", "LH-204", SessionType.Lecture, 1),
        SampleSession("s3", 10 * 60 + 50, 11 * 60 + 40, "CN", "Computer Networks", "LH-204", SessionType.Lecture, 2, cancelled = true, note = "faculty on leave"),
        SampleSession("s4", 11 * 60 + 40, 13 * 60 + 20, "OS", "Operating Systems Laboratory", "Lab 3", SessionType.Lab, 4, mark = Mark.Absent),
        SampleSession("s5", 14 * 60 + 10, 15 * 60, "ML", "Machine Learning", "T-12", SessionType.Tutorial, 3),
    )

    val subjects = listOf(
        SampleSubject("Design and Analysis of Algorithms", 31, 36, 86.1, "Can miss 5 more", false, 0),
        SampleSubject("Database Management Systems", 27, 34, 79.4, "Can miss 2 more", false, 1),
        SampleSubject("Machine Learning", 18, 24, 75.0, "Can't miss the next one", false, 3),
        SampleSubject("Computer Networks", 22, 31, 71.0, "Attend the next 5 to reach 75%", true, 2),
        SampleSubject("Operating Systems Laboratory", 10, 16, 62.5, "Attend the next 8 to reach 75%", true, 4),
    )

    const val OVERALL_ATTENDED = 108
    const val OVERALL_TOTAL = 141
    const val OVERALL_PERCENT = 76.6
}
