package app.roll.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import app.roll.MainActivity
import app.roll.core.common.ApplicationScope
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.theme.DarkRollColors
import app.roll.core.designsystem.theme.LightRollColors
import app.roll.core.util.formatClock
import app.roll.data.firebase.F
import app.roll.data.firebase.toAssignment
import app.roll.data.firebase.toSession
import app.roll.data.firebase.toSubject
import app.roll.data.marks.MarkRepository
import app.roll.data.session.ClassData
import app.roll.domain.attendance.EditWindow
import app.roll.domain.deadlines.Deadlines
import app.roll.domain.model.Mark
import app.roll.domain.model.SessionStatus
import app.roll.feature.common.AttendanceCopy
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun auth(): FirebaseAuth
    fun firestore(): FirebaseFirestore
    fun marks(): MarkRepository
    fun time(): TimeSource
}

private fun entryPoint(context: Context) =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/** What the widget shows. Loaded fresh (cache first when offline) on every update. */
data class WidgetData(
    val signedIn: Boolean,
    val dateLabel: String,
    val classId: String = "",
    val classes: List<WidgetClass> = emptyList(),
    val nextDeadline: String? = null,
)

data class WidgetClass(
    val sessionId: String,
    val label: String,
    val time: String,
    val mark: Mark?,
    val cancelled: Boolean,
    val editable: Boolean,
)

private suspend fun loadWidgetData(context: Context): WidgetData {
    if (FirebaseApp.getApps(context).isEmpty()) return WidgetData(signedIn = false, dateLabel = "Roll")
    val entry = entryPoint(context)
    val uid = entry.auth().currentUser?.uid ?: return WidgetData(signedIn = false, dateLabel = "Roll")
    val db = entry.firestore()
    return withTimeoutOrNull(8_000) {
        runCatching {
            val classId = db.collection(F.USERS).document(uid).get().await().getString(F.CLASS_ID)
                ?: return@runCatching WidgetData(signedIn = false, dateLabel = "Roll")
            val classDoc = db.collection(F.CLASSES).document(classId)
            val timezone = classDoc.get().await().getString("timezone") ?: "Asia/Kolkata"
            val now = entry.time().now()
            val today = now.localDateTimeIn(timezone).toLocalDate()
            val subjects = classDoc.collection(F.SUBJECTS).get().await().documents.mapNotNull { it.toSubject() }.associateBy { it.id }
            val sessions = classDoc.collection(F.SESSIONS).whereEqualTo(F.DATE, today.toString()).get().await()
                .documents.mapNotNull { it.toSession() }
                .filter { it.status != SessionStatus.Deleted }
                .sortedBy { it.startMinutes }
            val classes = sessions.map { session ->
                val status = classDoc.collection(F.SESSIONS).document(session.id).collection(F.MARKS).document(uid)
                    .get().await().getString("status")
                WidgetClass(
                    sessionId = session.id,
                    label = subjects[session.subjectId]?.shortName ?: "Class",
                    time = formatClock(session.startMinutes),
                    mark = Mark.entries.firstOrNull { it.name.lowercase() == status },
                    cancelled = session.status == SessionStatus.Cancelled,
                    editable = session.status == SessionStatus.Scheduled && EditWindow.isEditable(session.date, today),
                )
            }
            val done = db.collection(F.USERS).document(uid).collection(F.ASSIGNMENT_STATUS).get().await()
                .documents.filter { it.getBoolean("done") == true }.map { it.id }.toSet()
            val next = classDoc.collection(F.ASSIGNMENTS)
                .whereGreaterThanOrEqualTo("dueAt", Timestamp(Date.from(now)))
                .orderBy("dueAt")
                .limit(10)
                .get().await()
                .documents.mapNotNull { it.toAssignment() }
                .firstOrNull { it.id !in done }
            WidgetData(
                signedIn = true,
                dateLabel = AttendanceCopy.shortDate(today),
                classId = classId,
                classes = classes,
                nextDeadline = next?.let {
                    listOfNotNull(subjects[it.subjectId]?.shortName, it.title, Deadlines.relative(it.dueAt, now).text).joinToString(" · ")
                },
            )
        }.getOrNull()
    } ?: WidgetData(signedIn = true, dateLabel = "Roll")
}

/** Home-screen widget: today's classes with P / A, and the next deadline. */
class RollWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { WidgetContent(data) }
    }
}

class RollWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RollWidget()
}

internal val SessionKey = ActionParameters.Key<String>("sessionId")
internal val ClassKey = ActionParameters.Key<String>("classId")
internal val MarkKey = ActionParameters.Key<String>("mark")

/** P / A tap on the widget. Tapping the filled one again clears it, like in the app. */
class MarkFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entry = entryPoint(context)
        val uid = entry.auth().currentUser?.uid ?: return
        val classId = parameters[ClassKey] ?: return
        val sessionId = parameters[SessionKey] ?: return
        val mark = parameters[MarkKey]?.let { name -> Mark.entries.firstOrNull { it.name == name } }
        entry.marks().setMarkAndWait(classId, sessionId, uid, mark, timeoutMillis = 3_000)
        RollWidget().update(context, glanceId)
    }
}

// ---------- colours: the app's tokens, day and night ----------

private val Background = ColorProvider(LightRollColors.surface, DarkRollColors.surface)
private val Ink = ColorProvider(LightRollColors.ink, DarkRollColors.ink)
private val InkSecondary = ColorProvider(LightRollColors.inkSecondary, DarkRollColors.inkSecondary)
private val Hairline = ColorProvider(LightRollColors.hairline, DarkRollColors.hairline)
private val Accent = ColorProvider(LightRollColors.accent, DarkRollColors.accent)
private val Warning = ColorProvider(LightRollColors.warning, DarkRollColors.warning)
private val OnFill = ColorProvider(LightRollColors.onAccent, DarkRollColors.onAccent)
private val Cancelled = ColorProvider(LightRollColors.cancelled, DarkRollColors.cancelled)

@Composable
private fun WidgetContent(data: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Background)
            .cornerRadius(10.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = if (data.signedIn) "Today · ${data.dateLabel}" else "Roll",
            style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(6.dp))
        when {
            !data.signedIn -> Text("Open Roll to sign in.", style = TextStyle(color = InkSecondary, fontSize = 13.sp))
            data.classes.isEmpty() -> Text("No classes posted yet for today.", style = TextStyle(color = InkSecondary, fontSize = 13.sp))
            else -> data.classes.take(5).forEach { ClassLine(data.classId, it) }
        }
        if (data.nextDeadline != null) {
            Spacer(GlanceModifier.height(6.dp))
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Hairline)) {}
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "Next: ${data.nextDeadline}",
                style = TextStyle(color = InkSecondary, fontSize = 12.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ClassLine(classId: String, item: WidgetClass) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            text = item.time,
            style = TextStyle(color = if (item.cancelled) Cancelled else InkSecondary, fontSize = 13.sp),
            modifier = GlanceModifier.width(44.dp),
        )
        Text(
            text = item.label,
            style = TextStyle(
                color = if (item.cancelled) Cancelled else Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (item.cancelled) TextDecoration.LineThrough else TextDecoration.None,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        if (!item.cancelled) {
            Segment("P", item.mark == Mark.Present, Accent, classId, item, Mark.Present)
            Spacer(GlanceModifier.width(4.dp))
            Segment("A", item.mark == Mark.Absent, Warning, classId, item, Mark.Absent)
        }
    }
}

@Composable
private fun Segment(
    letter: String,
    selected: Boolean,
    fill: androidx.glance.unit.ColorProvider,
    classId: String,
    item: WidgetClass,
    mark: Mark,
) {
    val base = GlanceModifier
        .size(width = 44.dp, height = 36.dp)
        .semantics { contentDescription = "${item.label} ${item.time}: ${if (mark == Mark.Present) "present" else "absent"}${if (selected) ", selected" else ""}" }
        .cornerRadius(6.dp)
        .background(if (selected) fill else Hairline)
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (item.editable) {
            base.clickable(
                actionRunCallback<MarkFromWidget>(
                    actionParametersOf(
                        ClassKey to classId,
                        SessionKey to item.sessionId,
                        // Tapping the selected one again clears it.
                        MarkKey to if (selected) "" else mark.name,
                    ),
                ),
            )
        } else {
            base
        },
    ) {
        Text(
            text = letter,
            style = TextStyle(color = if (selected) OnFill else InkSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/** Refreshes every placed widget. */
@Singleton
class RollWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun updateNow() {
        runCatching { RollWidget().updateAll(context) }
    }
}

/** Keeps the widget current while the app runs: marks, today's classes and deadlines. */
@OptIn(FlowPreview::class)
@Singleton
class RollWidgetSync @Inject constructor(
    private val classData: ClassData,
    private val updater: RollWidgetUpdater,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(classData.myMarks, classData.sessions, classData.assignments, classData.doneIds) { a, b, c, d -> listOf(a, b, c, d) }
                .debounce(2_000)
                .collect { appScope.launch { updater.updateNow() } }
        }
    }
}
