package app.roll.feature.gallery

import app.cash.turbine.test
import app.roll.data.preferences.ReminderSettings
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.domain.model.Mark
import app.roll.domain.model.ThemeMode
import app.roll.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class GalleryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakeUserPreferencesRepository()
    private val viewModel = GalleryViewModel(preferences)

    @Test
    fun clearingAMark_recordsItForUndo() = runTest {
        viewModel.uiState.test {
            assertEquals(Mark.Present, awaitItem().session("s1").mark)

            viewModel.onMarkChange("s1", null)
            val cleared = awaitItem()
            assertNull(cleared.session("s1").mark)
            assertEquals(ClearedMark("s1", Mark.Present), cleared.clearedMark)

            viewModel.onUndoClear()
            val restored = awaitItem()
            assertEquals(Mark.Present, restored.session("s1").mark)
            assertNull(restored.clearedMark)
        }
    }

    @Test
    fun markingAnUnmarkedSession_doesNotOfferUndo() = runTest {
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onMarkChange("s2", Mark.Absent)
            val state = awaitItem()
            assertEquals(Mark.Absent, state.session("s2").mark)
            assertNull(state.clearedMark)
        }
    }

    @Test
    fun remarkingTheClearedSession_dropsThePendingUndo() = runTest {
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onMarkChange("s1", null)
            skipItems(1)
            viewModel.onMarkChange("s1", Mark.Absent)
            val state = awaitItem()
            assertEquals(Mark.Absent, state.session("s1").mark)
            assertNull(state.clearedMark)
        }
    }

    @Test
    fun themeModeChange_isPersistedAndReflected() = runTest {
        viewModel.uiState.test {
            assertEquals(ThemeMode.System, awaitItem().themeMode)
            viewModel.onThemeModeChange(ThemeMode.Dark)
            assertEquals(ThemeMode.Dark, awaitItem().themeMode)
            assertEquals(ThemeMode.Dark, preferences.themeMode.value)
        }
    }

    private fun GalleryUiState.session(id: String) = sessions.first { it.id == id }
}

private class FakeUserPreferencesRepository : UserPreferencesRepository {
    override val themeMode = MutableStateFlow(ThemeMode.System)
    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }
    override val reminder = MutableStateFlow(ReminderSettings())
    override suspend fun setReminder(settings: ReminderSettings) {
        reminder.value = settings
    }
    override val deadlineReminders = MutableStateFlow(true)
    override suspend fun setDeadlineReminders(enabled: Boolean) {
        deadlineReminders.value = enabled
    }
    override val classEndPrompts = MutableStateFlow(true)
    override suspend fun setClassEndPrompts(enabled: Boolean) {
        classEndPrompts.value = enabled
    }
    override val notificationPromptDone = MutableStateFlow(false)
    override suspend fun setNotificationPromptDone() {
        notificationPromptDone.value = true
    }
    override val nithNoticesSeenAt = MutableStateFlow<java.time.Instant?>(null)
    override suspend fun setNithNoticesSeenAt(at: java.time.Instant) {
        nithNoticesSeenAt.value = at
    }
}
