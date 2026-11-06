package app.roll.feature.deadlines

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roll.MainActivity
import app.roll.R
import app.roll.core.common.ApplicationScope
import app.roll.core.common.TimeSource
import app.roll.core.common.zoneOf
import app.roll.data.firebase.F
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.domain.deadlines.Deadlines
import app.roll.domain.deadlines.PlannedReminder
import app.roll.domain.model.Assignment
import app.roll.domain.model.Subject
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "deadline-reminder"
private const val CHANNEL_ID = "deadlines"

private const val KEY_ID = "assignmentId"
private const val KEY_TITLE = "title"
private const val KEY_SUBJECT = "subject"
private const val KEY_DUE = "dueAtMillis"
private const val KEY_HOURS = "hoursBefore"

/**
 * Local notifications 24 h and 2 h before each deadline that isn't done. No server: the plan
 * is recomputed on the phone whenever assignments, done ticks or the setting change, and
 * WorkManager jobs are added, replaced or cancelled to match it.
 */
@Singleton
class DeadlineReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun sync(plan: List<PlannedReminder>, assignments: Map<String, Assignment>, subjects: Map<String, Subject>) {
        val wanted = plan.associateBy { it.key }
        // Cancel jobs for reminders no longer in the plan (done, deleted, moved, or setting off).
        workManager.getWorkInfosByTagFlow(TAG).first()
            .filter { it.state == WorkInfo.State.ENQUEUED && it.tags.none { tag -> tag in wanted } }
            .forEach { workManager.cancelWorkById(it.id) }
        val now = time.now()
        for (reminder in plan) {
            val assignment = assignments[reminder.assignmentId] ?: continue
            val request = OneTimeWorkRequestBuilder<DeadlineReminderWorker>()
                .setInitialDelay(Duration.between(now, reminder.at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .addTag(reminder.key)
                .setInputData(
                    workDataOf(
                        KEY_ID to assignment.id,
                        KEY_TITLE to assignment.title,
                        KEY_SUBJECT to subjects[assignment.subjectId]?.shortName.orEmpty(),
                        KEY_DUE to assignment.dueAt.toEpochMilli(),
                        KEY_HOURS to reminder.hoursBefore,
                    ),
                )
                .build()
            // REPLACE keeps one job per assignment and offset, updated when the deadline moves.
            workManager.enqueueUniqueWork(reminder.key, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
    }
}

/**
 * Keeps the scheduled reminders in step with the class's assignments while the app runs.
 * Started once from the root view model.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class DeadlineReminderSync @Inject constructor(
    private val classData: ClassData,
    private val session: AppSession,
    private val preferences: UserPreferencesRepository,
    private val scheduler: DeadlineReminderScheduler,
    private val time: TimeSource,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                classData.assignments.filterNotNull(),
                classData.doneIds.filterNotNull(),
                classData.subjects.map { it.orEmpty() },
                preferences.deadlineReminders,
                session.context,
            ) { assignments, done, subjects, enabled, _ -> Triple(assignments, done, subjects) to enabled }
                .debounce(1_500)
                .collect { (data, enabled) ->
                    val (assignments, done, subjects) = data
                    // Scheduling must finish even if the screen goes away mid-sync.
                    appScope.launch {
                        if (!enabled) {
                            scheduler.cancelAll()
                        } else {
                            scheduler.sync(
                                plan = Deadlines.plannedReminders(assignments, done, time.now()),
                                assignments = assignments.associateBy { it.id },
                                subjects = subjects.associateBy { it.id },
                            )
                        }
                    }
                }
        }
    }
}

/** Posts one deadline reminder, unless the student has ticked it done in the meantime. */
@HiltWorker
class DeadlineReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val auth: Provider<FirebaseAuth>,
    private val firestore: Provider<FirebaseFirestore>,
    private val preferences: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val uid = runCatching { auth.get().currentUser?.uid }.getOrNull() ?: return Result.success()
        if (!preferences.deadlineReminders.first()) return Result.success()
        val done = runCatching {
            firestore.get().collection(F.USERS).document(uid).collection(F.ASSIGNMENT_STATUS).document(id)
                .get().await().getBoolean("done") == true
        }.getOrDefault(false)
        if (done) return Result.success()
        notify(
            id = id,
            title = inputData.getString(KEY_TITLE).orEmpty(),
            subject = inputData.getString(KEY_SUBJECT).orEmpty(),
            dueAtMillis = inputData.getLong(KEY_DUE, 0),
            hoursBefore = inputData.getLong(KEY_HOURS, 24),
        )
        return Result.success()
    }

    private fun notify(id: String, title: String, subject: String, dueAtMillis: Long, hoursBefore: Long) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Deadlines", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders 24 hours and 2 hours before a deadline"
            },
        )
        val due = dueText(Instant.ofEpochMilli(dueAtMillis), zoneOf("Asia/Kolkata"))
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_roll)
            .setContentTitle(listOf(subject, title).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentText(if (hoursBefore <= 2) "Due in $hoursBefore h, $due" else "Due tomorrow, $due")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("deadline-$id".hashCode(), notification)
    }
}
