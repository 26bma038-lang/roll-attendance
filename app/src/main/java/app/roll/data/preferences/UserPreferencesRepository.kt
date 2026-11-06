package app.roll.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.roll.core.common.ApplicationScope
import app.roll.core.common.IoDispatcher
import app.roll.domain.model.ThemeMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.plus
import javax.inject.Inject
import javax.inject.Singleton

data class ReminderSettings(
    val enabled: Boolean = false,
    /** Minutes from midnight, local time. Default 5:00 pm. */
    val minutes: Int = 17 * 60,
)

/** Device-local settings. Nothing here is synced. */
interface UserPreferencesRepository {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    val reminder: Flow<ReminderSettings>
    suspend fun setReminder(settings: ReminderSettings)

    /** Notifications 24 h and 2 h before each deadline. On by default. */
    val deadlineReminders: Flow<Boolean>
    suspend fun setDeadlineReminders(enabled: Boolean)

    /** "Mark attendance" notification at the end of each class. On by default. */
    val classEndPrompts: Flow<Boolean>
    suspend fun setClassEndPrompts(enabled: Boolean)

    /** The one-time "turn on notifications" explanation on Today was answered. */
    val notificationPromptDone: Flow<Boolean>
    suspend fun setNotificationPromptDone()
}

class DataStoreUserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[Keys.ThemeMode]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.System
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.ThemeMode] = mode.name }
    }

    override val reminder: Flow<ReminderSettings> = dataStore.data.map { prefs ->
        ReminderSettings(
            enabled = prefs[Keys.ReminderEnabled] ?: false,
            minutes = prefs[Keys.ReminderMinutes] ?: ReminderSettings().minutes,
        )
    }

    override suspend fun setReminder(settings: ReminderSettings) {
        dataStore.edit {
            it[Keys.ReminderEnabled] = settings.enabled
            it[Keys.ReminderMinutes] = settings.minutes
        }
    }

    override val deadlineReminders: Flow<Boolean> = dataStore.data.map { it[Keys.DeadlineReminders] ?: true }

    override suspend fun setDeadlineReminders(enabled: Boolean) {
        dataStore.edit { it[Keys.DeadlineReminders] = enabled }
    }

    override val classEndPrompts: Flow<Boolean> = dataStore.data.map { it[Keys.ClassEndPrompts] ?: true }

    override suspend fun setClassEndPrompts(enabled: Boolean) {
        dataStore.edit { it[Keys.ClassEndPrompts] = enabled }
    }

    override val notificationPromptDone: Flow<Boolean> = dataStore.data.map { it[Keys.NotificationPromptDone] ?: false }

    override suspend fun setNotificationPromptDone() {
        dataStore.edit { it[Keys.NotificationPromptDone] = true }
    }

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
        val ReminderEnabled = booleanPreferencesKey("reminder_enabled")
        val ReminderMinutes = intPreferencesKey("reminder_minutes")
        val DeadlineReminders = booleanPreferencesKey("deadline_reminders")
        val ClassEndPrompts = booleanPreferencesKey("class_end_prompts")
        val NotificationPromptDone = booleanPreferencesKey("notification_prompt_done")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {
    @Provides
    @Singleton
    fun providesPreferencesDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope + ioDispatcher,
        produceFile = { context.preferencesDataStoreFile("user_preferences") },
    )

    @Provides
    @Singleton
    fun providesUserPreferencesRepository(
        impl: DataStoreUserPreferencesRepository,
    ): UserPreferencesRepository = impl
}
