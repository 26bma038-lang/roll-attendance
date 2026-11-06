package app.roll.feature.settings

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.roll.MainActivity
import app.roll.R
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateIn
import app.roll.data.firebase.F
import app.roll.data.preferences.ReminderSettings
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val WORK_NAME = "daily-reminder"
private const val CHANNEL_ID = "reminders"
private const val NOTIFICATION_ID = 1

/** Schedules the once-a-day "Mark today's attendance" check with WorkManager. */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeSource,
) {
    fun apply(settings: ReminderSettings) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = ZonedDateTime.ofInstant(time.now(), ZoneId.systemDefault())
        var next = now.toLocalDate().atTime(settings.minutes / 60, settings.minutes % 60).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        // Re-enqueue on every change (and app start) so the time stays exact despite drift.
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }
}

/**
 * Posts "Mark today's attendance" only if today has scheduled classes the student
 * hasn't marked. Reads through Firestore's offline cache when there's no signal.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val auth: Provider<FirebaseAuth>,
    private val firestore: Provider<FirebaseFirestore>,
    private val time: TimeSource,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
        val uid = auth.get().currentUser?.uid ?: return Result.success()
        val db = firestore.get()
        return runCatching {
            val classId = db.collection(F.USERS).document(uid).get().await().getString(F.CLASS_ID)
                ?: return Result.success()
            val classDoc = db.collection(F.CLASSES).document(classId)
            val timezone = classDoc.get().await().getString("timezone") ?: "Asia/Kolkata"
            val today = time.now().localDateIn(timezone).toString()
            val sessions = classDoc.collection(F.SESSIONS).whereEqualTo(F.DATE, today).get().await()
                .documents.filter { it.getString("status") == "scheduled" }
            val unmarked = sessions.count { session ->
                val mark = session.reference.collection(F.MARKS).document(uid).get().await().getString("status")
                mark != "present" && mark != "absent"
            }
            if (unmarked > 0) notify(unmarked)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun notify(unmarked: Int) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A reminder when today's classes aren't marked"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_roll)
            .setContentTitle("Mark today's attendance")
            .setContentText(if (unmarked == 1) "1 class isn't marked yet" else "$unmarked classes aren't marked yet")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
