package app.roll.feature.classend

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roll.MainActivity
import app.roll.R
import app.roll.core.common.ApplicationScope
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.common.zoneOf
import app.roll.core.util.formatClock
import app.roll.data.firebase.F
import app.roll.data.firebase.toSession
import app.roll.data.firebase.toSubject
import app.roll.data.firebase.toTimetableSlot
import app.roll.data.marks.MarkRepository
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.domain.model.Mark
import app.roll.domain.model.SessionStatus
import app.roll.domain.planning.ClassEndPlanner
import app.roll.domain.planning.ClassEndPrompt
import app.roll.feature.widget.RollWidgetUpdater
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val TAG = "class-end"
private const val DAILY_PLAN = "class-end-daily-plan"
private const val CHANNEL_ID = "class-end"

private const val KEY_DATE = "date"
private const val KEY_SUBJECT = "subjectId"
private const val KEY_START = "start"
private const val KEY_END = "end"
private const val KEY_SESSION = "sessionId"
private const val KEY_CLASS = "classId"
private const val KEY_MARK = "mark"
private const val KEY_NOTIFICATION = "notificationId"

/**
 * "DS · 10:00–10:50 — mark attendance", posted when each class ends, with Present and Absent
 * buttons that write the mark directly. Inexact timing (WorkManager), no exact-alarm permission.
 */
@Singleton
class ClassEndScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun sync(prompts: List<ClassEndPrompt>, zone: ZoneId) {
        val wanted = prompts.map { it.key }.toSet()
        workManager.getWorkInfosByTagFlow(TAG).first()
            .filter { it.state == WorkInfo.State.ENQUEUED && it.tags.none { tag -> tag in wanted } }
            .forEach { workManager.cancelWorkById(it.id) }
        val now = time.now()
        for (prompt in prompts) {
            val endsAt = prompt.date.atStartOfDay(zone).plusMinutes(prompt.endMinutes.toLong()).toInstant()
            val request = OneTimeWorkRequestBuilder<ClassEndWorker>()
                .setInitialDelay(Duration.between(now, endsAt).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .addTag(prompt.key)
                .setInputData(
                    workDataOf(
                        KEY_DATE to prompt.date.toString(),
                        KEY_SUBJECT to prompt.subjectId,
                        KEY_START to prompt.startMinutes,
                        KEY_END to prompt.endMinutes,
                        KEY_SESSION to prompt.sessionId,
                    ),
                )
                .build()
            workManager.enqueueUniqueWork(prompt.key, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
        workManager.cancelUniqueWork(DAILY_PLAN)
    }

    /** A daily job at 6:00 that plans the day's prompts even if the app isn't opened. */
    fun ensureDailyPlan(zone: ZoneId) {
        val now = ZonedDateTime.ofInstant(time.now(), zone)
        var next = now.toLocalDate().atTime(6, 0).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ClassEndPlanWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(DAILY_PLAN, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Re-plans today's prompts whenever posted classes, the timetable or marks change in the app. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class ClassEndSync @Inject constructor(
    private val classData: ClassData,
    private val session: AppSession,
    private val preferences: UserPreferencesRepository,
    private val scheduler: ClassEndScheduler,
    private val time: TimeSource,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                classData.sessions.filterNotNull(),
                classData.timetable.filterNotNull(),
                classData.myMarks.filterNotNull(),
                preferences.classEndPrompts,
                session.context,
            ) { sessions, slots, marks, enabled, ctx -> Triple(sessions, slots, marks) to (enabled to ctx) }
                .debounce(1_500)
                .collect { (data, settings) ->
                    val (sessions, slots, marks) = data
                    val (enabled, ctx) = settings
                    val zone = zoneOf(ctx.rollClass.timezone)
                    appScope.launch {
                        if (!enabled) {
                            scheduler.cancelAll()
                        } else {
                            scheduler.ensureDailyPlan(zone)
                            scheduler.sync(
                                ClassEndPlanner.forToday(
                                    sessions = sessions.sessions,
                                    slots = slots,
                                    marks = marks.marks.mapValues { it.value.mark },
                                    now = time.now().localDateTimeIn(ctx.rollClass.timezone),
                                ),
                                zone,
                            )
                        }
                    }
                }
        }
    }
}

/** Morning job: plans today's prompts from Firestore (offline cache when there's no signal). */
@HiltWorker
class ClassEndPlanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val auth: Provider<FirebaseAuth>,
    private val firestore: Provider<FirebaseFirestore>,
    private val preferences: UserPreferencesRepository,
    private val scheduler: ClassEndScheduler,
    private val time: TimeSource,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!preferences.classEndPrompts.first()) return Result.success()
        val uid = runCatching { auth.get().currentUser?.uid }.getOrNull() ?: return Result.success()
        return runCatching {
            val db = firestore.get()
            val classId = db.collection(F.USERS).document(uid).get().await().getString(F.CLASS_ID) ?: return Result.success()
            val classDoc = db.collection(F.CLASSES).document(classId)
            val timezone = classDoc.get().await().getString("timezone") ?: "Asia/Kolkata"
            val now = time.now().localDateTimeIn(timezone)
            val sessions = classDoc.collection(F.SESSIONS).whereEqualTo(F.DATE, now.toLocalDate().toString()).get().await()
                .documents.mapNotNull { it.toSession() }
            val slots = classDoc.collection(F.TIMETABLE).get().await().documents.mapNotNull { it.toTimetableSlot() }
            val marks = sessions.associate { s ->
                s.id to classDoc.collection(F.SESSIONS).document(s.id).collection(F.MARKS).document(uid).get().await().getString("status")
            }.mapNotNull { (id, status) -> Mark.entries.firstOrNull { it.name.lowercase() == status }?.let { id to it } }.toMap()
            scheduler.sync(ClassEndPlanner.forToday(sessions, slots, marks, now), zoneOf(timezone))
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/**
 * Fires at a class's end. Finds the posted session (the CR may have posted it after planning),
 * and posts the prompt only if it's scheduled and not yet marked.
 */
@HiltWorker
class ClassEndWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val auth: Provider<FirebaseAuth>,
    private val firestore: Provider<FirebaseFirestore>,
    private val preferences: UserPreferencesRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!preferences.classEndPrompts.first()) return Result.success()
        val uid = runCatching { auth.get().currentUser?.uid }.getOrNull() ?: return Result.success()
        val date = inputData.getString(KEY_DATE) ?: return Result.success()
        val subjectId = inputData.getString(KEY_SUBJECT) ?: return Result.success()
        val start = inputData.getInt(KEY_START, -1)
        val end = inputData.getInt(KEY_END, -1)
        val knownSessionId = inputData.getString(KEY_SESSION)
        return runCatching {
            val db = firestore.get()
            val classId = db.collection(F.USERS).document(uid).get().await().getString(F.CLASS_ID) ?: return Result.success()
            val classDoc = db.collection(F.CLASSES).document(classId)
            val sessions = classDoc.collection(F.SESSIONS).whereEqualTo(F.DATE, date).get().await().documents.mapNotNull { it.toSession() }
            val session = sessions.firstOrNull { it.id == knownSessionId }
                ?: sessions.firstOrNull { it.subjectId == subjectId && it.startMinutes == start }
                ?: return Result.success() // not posted: nothing to mark
            if (session.status != SessionStatus.Scheduled) return Result.success()
            val existing = classDoc.collection(F.SESSIONS).document(session.id).collection(F.MARKS).document(uid).get().await().getString("status")
            if (existing == "present" || existing == "absent") return Result.success()
            val subjectName = classDoc.collection(F.SUBJECTS).document(subjectId).get().await().toSubject()?.shortName ?: "Class"
            notify(classId, session.id, subjectName, start, end)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun notify(classId: String, sessionId: String, subject: String, start: Int, end: Int) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mark attendance", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a class ends, mark it present or absent from the notification"
            },
        )
        val notificationId = "class-end-$sessionId".hashCode()
        fun action(mark: Mark): PendingIntent = PendingIntent.getBroadcast(
            context,
            "$sessionId-${mark.name}".hashCode(),
            Intent(context, MarkActionReceiver::class.java)
                .putExtra(KEY_CLASS, classId)
                .putExtra(KEY_SESSION, sessionId)
                .putExtra(KEY_MARK, mark.name)
                .putExtra(KEY_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_roll)
            .setContentTitle("$subject · ${formatClock(start)}–${formatClock(end)} — mark attendance")
            .setContentText("Tap Present or Absent. It saves even without signal.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Present", action(Mark.Present))
            .addAction(0, "Absent", action(Mark.Absent))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

/** Handles the Present / Absent buttons by handing the write to a background job. */
class MarkActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val classId = intent.getStringExtra(KEY_CLASS) ?: return
        val sessionId = intent.getStringExtra(KEY_SESSION) ?: return
        val mark = intent.getStringExtra(KEY_MARK) ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(KEY_NOTIFICATION, 0))
        val request = OneTimeWorkRequestBuilder<MarkFromNotificationWorker>()
            .setInputData(workDataOf(KEY_CLASS to classId, KEY_SESSION to sessionId, KEY_MARK to mark))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("mark-$sessionId", ExistingWorkPolicy.REPLACE, request)
    }
}

/** Writes a mark through the same repository as the app: offline-safe, queued until online. */
@HiltWorker
class MarkFromNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val auth: Provider<FirebaseAuth>,
    private val marks: Provider<MarkRepository>,
    private val widget: RollWidgetUpdater,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uid = runCatching { auth.get().currentUser?.uid }.getOrNull() ?: return Result.success()
        val classId = inputData.getString(KEY_CLASS) ?: return Result.success()
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.success()
        val mark = inputData.getString(KEY_MARK)?.let { name -> Mark.entries.firstOrNull { it.name == name } } ?: return Result.success()
        marks.get().setMarkAndWait(classId, sessionId, uid, mark)
        widget.updateNow()
        return Result.success()
    }
}
