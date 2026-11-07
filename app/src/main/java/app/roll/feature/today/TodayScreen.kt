package app.roll.feature.today

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.R
import app.roll.core.common.TimeSource
import app.roll.core.common.UserMessages
import app.roll.core.common.localDateTimeIn
import app.roll.core.common.zoneOf
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.OfflineLine
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.core.util.openUrl
import app.roll.core.util.formatDayHeader
import app.roll.data.feed.FeedLoad
import app.roll.data.feed.NithNoticeTracker
import app.roll.data.marks.MarkRepository
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.attendance.SessionState
import app.roll.domain.deadlines.Deadlines
import app.roll.domain.feed.NithFeed
import app.roll.domain.feed.NithNotice
import app.roll.domain.model.Mark
import app.roll.domain.model.Notice
import app.roll.feature.common.AttendanceCopy
import app.roll.feature.common.ClearedMark
import app.roll.feature.common.MarkClearedSnackbar
import app.roll.feature.common.MarkController
import app.roll.feature.common.SessionItem
import app.roll.feature.common.SessionItemRow
import app.roll.feature.common.sessionItems
import app.roll.feature.deadlines.DeadlineItem
import app.roll.feature.deadlines.DeadlineRow
import app.roll.feature.deadlines.deadlineItem
import app.roll.feature.notices.PinnedNoticesBlock
import app.roll.feature.notices.WebsiteNoticeRow
import app.roll.feature.notices.pinnedNotices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class TodayUiState(
    val loading: Boolean = true,
    val date: LocalDate? = null,
    val items: List<SessionItem> = emptyList(),
    val yesterdayUnmarked: Int = 0,
    val pendingSync: Boolean = false,
    /** "Posted by Sahil · 9:12 am" for the latest posted class today. */
    val postedBy: String? = null,
    val refreshing: Boolean = false,
    val clearedMark: ClearedMark? = null,
    /** Set when the class has no CR yet, e.g. "MA 2026". */
    val noCrClassName: String? = null,
    val dueSoon: List<DeadlineItem> = emptyList(),
    val pinnedNotices: List<Notice> = emptyList(),
    val isCR: Boolean = false,
    /** Any weekly slot at all: without one, classes only appear when the CR posts them by hand. */
    val hasTimetable: Boolean = true,
    /** The next open deadline after the "due soon" window, so an empty day still says what's coming. */
    val nextDeadline: DeadlineItem? = null,
    /** Latest academic and exam notices from nith.ac.in, shown when there are no classes. */
    val latestNotices: List<NithNotice> = emptyList(),
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    marks: MarkRepository,
    time: TimeSource,
    private val schedule: ScheduleRepository,
    private val messages: UserMessages,
    private val preferences: UserPreferencesRepository,
    tracker: NithNoticeTracker,
) : ViewModel() {

    /** Whether the one-time notification explanation was answered. Starts true so it never flashes. */
    val notificationPromptDone: StateFlow<Boolean> =
        preferences.notificationPromptDone.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun onNotificationPromptAnswered() {
        viewModelScope.launch { preferences.setNotificationPromptDone() }
    }

    private val controller = MarkController(marks)
    private val refreshing = MutableStateFlow(false)
    private val context: StateFlow<ClassContext?> =
        session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val content = combine(
        context,
        classData.sessions,
        classData.subjects,
        classData.myMarks,
        time.ticks(),
    ) { ctx, sessions, subjects, myMarks, now ->
        if (ctx == null || sessions == null || myMarks == null) return@combine TodayUiState()
        val tz = ctx.rollClass.timezone
        val nowLocal = now.localDateTimeIn(tz)
        val today = nowLocal.toLocalDate()
        val subjectMap = subjects.orEmpty().associateBy { it.id }
        val markMap = myMarks.marks.mapValues { it.value.mark }
        val todays = sessions.sessions.filter { it.date == today }.sortedBy { it.startMinutes }
        val yesterday = sessionItems(sessions.sessions.filter { it.date == today.minusDays(1) }, subjectMap, markMap, nowLocal)
        val latest = todays.filter { it.createdAt != null }.maxByOrNull { it.createdAt!! }
        TodayUiState(
            loading = false,
            date = today,
            items = sessionItems(todays, subjectMap, markMap, nowLocal),
            yesterdayUnmarked = yesterday.count { it.state == SessionState.UnmarkedPast && it.editable },
            pendingSync = myMarks.hasPendingWrites,
            noCrClassName = ctx.rollClass.name.takeIf { ctx.rollClass.crUids.isEmpty() },
            isCR = ctx.isCR,
            postedBy = latest?.let {
                val posted = it.createdAt!!.atZone(ZoneId.of(tz)).toLocalTime()
                "Posted by ${it.createdByName.ifBlank { "your CR" }} · ${formatClock(posted.hour * 60 + posted.minute, meridiem = true)}"
            },
        )
    }

    /** Not done and due in the next 48 hours, for the strip above today's classes. */
    private val dueSoon = combine(
        combine(session.context, time.ticks(), ::Pair),
        classData.assignments,
        classData.doneIds,
        classData.subjects,
    ) { (ctx, now), assignments, done, subjects ->
        val zone = zoneOf(ctx.rollClass.timezone)
        val doneIds = done.orEmpty()
        val subjectMap = subjects.orEmpty().associateBy { it.id }
        val soon = Deadlines.dueSoon(assignments.orEmpty(), doneIds, now)
        val next = if (soon.isNotEmpty()) {
            null
        } else {
            assignments.orEmpty().filter { it.id !in doneIds && it.dueAt.isAfter(now) }.minByOrNull { it.dueAt }
        }
        soon.map { deadlineItem(it, subjectMap, doneIds, now, zone) } to next?.let { deadlineItem(it, subjectMap, doneIds, now, zone) }
    }

    private val extras = combine(classData.timetable, tracker.load) { slots, load ->
        val notices = (load as? FeedLoad.Loaded)?.notices.orEmpty()
        // Until the timetable has loaded, assume there is one: no "set up the timetable" flash.
        (slots == null || slots.isNotEmpty()) to NithFeed.latestAcademic(notices)
    }

    private val pinned = combine(classData.classNotices, classData.collegeNotices, time.ticks()) { classList, collegeList, now ->
        pinnedNotices(classList.orEmpty(), collegeList.orEmpty(), now)
    }

    val uiState: StateFlow<TodayUiState> = combine(
        combine(content, refreshing, controller.cleared, ::Triple),
        dueSoon,
        pinned,
        extras,
    ) { (state, isRefreshing, cleared), (soon, next), notices, (hasTimetable, latest) ->
        state.copy(
            refreshing = isRefreshing,
            clearedMark = cleared,
            dueSoon = soon,
            nextDeadline = next,
            pinnedNotices = notices,
            hasTimetable = hasTimetable,
            latestNotices = latest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun onMark(item: SessionItem, mark: Mark?) {
        context.value?.let { controller.set(it, item, mark) }
    }

    fun onUndo() {
        context.value?.let { controller.undo(it) }
    }

    fun onUndoDismissed() = controller.dismiss()

    fun cannotOpen() = messages.show("No app on this phone can open the link.")

    fun onRefresh() {
        val ctx = context.value ?: return
        val date = uiState.value.date ?: return
        viewModelScope.launch {
            refreshing.value = true
            if (!schedule.refreshDay(ctx.classId, date)) messages.show("You're offline. Showing saved classes.")
            refreshing.value = false
        }
    }
}

@Composable
fun TodayRoute(
    onOpenSettings: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenAdmin: (() -> Unit)?,
    onOpenAssignment: (String) -> Unit,
    onOpenTimetable: () -> Unit = {},
    onOpenCollegeNotices: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MarkClearedSnackbar(uiState.clearedMark, onUndo = viewModel::onUndo, onDismiss = viewModel::onUndoDismissed)
    val promptDone by viewModel.notificationPromptDone.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPromptAnswered()
    }
    TodayScreen(
        uiState = uiState,
        onMark = viewModel::onMark,
        onRefresh = viewModel::onRefresh,
        onOpenSettings = onOpenSettings,
        onOpenDay = onOpenDay,
        onOpenAdmin = onOpenAdmin,
        onOpenAssignment = onOpenAssignment,
        onOpenTimetable = onOpenTimetable,
        onOpenCollegeNotices = onOpenCollegeNotices,
        onOpenUrl = { if (!context.openUrl(it)) viewModel.cannotOpen() },
        showNotificationPrompt = needsPermission && !promptDone && !uiState.loading,
        onEnableNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onDismissNotifications = viewModel::onNotificationPromptAnswered,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onMark: (SessionItem, Mark?) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAdmin: (() -> Unit)? = null,
    onOpenAssignment: (String) -> Unit = {},
    onOpenTimetable: () -> Unit = {},
    onOpenCollegeNotices: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    showNotificationPrompt: Boolean = false,
    onEnableNotifications: () -> Unit = {},
    onDismissNotifications: () -> Unit = {},
) {
    val colors = RollTheme.colors
    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = uiState.date?.let(::formatDayHeader) ?: "Today") {
            if (onOpenAdmin != null) {
                RollIconButton(icon = RollIcons.Admin, contentDescription = "Admin panel", onClick = onOpenAdmin)
            }
            RollIconButton(
                icon = RollIcons.Settings,
                contentDescription = stringResource(R.string.action_settings),
                onClick = onOpenSettings,
            )
        }
        if (uiState.pendingSync) OfflineLine()
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = uiState.refreshing,
                    containerColor = colors.surface,
                    color = colors.ink,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = Spacing.xxl),
                modifier = Modifier.fillMaxSize(),
            ) {
                val date = uiState.date
                if (showNotificationPrompt) {
                    item(key = "notificationPrompt") { NotificationPrompt(onEnableNotifications, onDismissNotifications) }
                }
                if (uiState.pinnedNotices.isNotEmpty()) {
                    item(key = "notices") { PinnedNoticesBlock(uiState.pinnedNotices) }
                }
                if (uiState.yesterdayUnmarked > 0 && date != null) {
                    item(key = "yesterday") {
                        YesterdayRow(count = uiState.yesterdayUnmarked, onClick = { onOpenDay(date.minusDays(1)) })
                    }
                }
                if (uiState.dueSoon.isNotEmpty()) {
                    item(key = "dueSoonHeader") { SectionHeader("Due soon") }
                    itemsIndexed(uiState.dueSoon, key = { _, it -> "due-${it.assignment.id}" }) { index, item ->
                        Column(Modifier.background(colors.surface)) {
                            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                            DeadlineRow(item = item, onClick = { onOpenAssignment(item.assignment.id) }, onDoneChange = null)
                        }
                    }
                    item(key = "classesHeader") { SectionHeader("Classes") }
                }
                when {
                    uiState.loading -> item(key = "skeleton") {
                        SkeletonList(Modifier.background(colors.surface))
                    }
                    uiState.items.isEmpty() -> {
                        item(key = "empty") {
                            val setUp = uiState.isCR && !uiState.hasTimetable
                            EmptyState(
                                message = when {
                                    uiState.noCrClassName != null -> "Your class has no CR yet. The admin appoints one."
                                    setUp -> "No classes today. Set up the timetable and each day's classes are ready to post."
                                    !uiState.hasTimetable -> "Your CR hasn't set up the timetable yet."
                                    else -> "No classes posted yet for today."
                                },
                                actionLabel = if (setUp) "Set up timetable" else null,
                                onAction = if (setUp) onOpenTimetable else null,
                            )
                        }
                        // Something useful on an empty day: what's due next and the college's latest.
                        val next = uiState.nextDeadline
                        if (next != null) {
                            item(key = "nextHeader") { SectionHeader("Next deadline") }
                            item(key = "next-${next.assignment.id}") {
                                Column(Modifier.background(colors.surface)) {
                                    DeadlineRow(item = next, onClick = { onOpenAssignment(next.assignment.id) }, onDoneChange = null)
                                }
                            }
                        }
                        if (uiState.latestNotices.isNotEmpty()) {
                            item(key = "nithHeader") { SectionHeader("Latest from nith.ac.in") }
                            itemsIndexed(uiState.latestNotices, key = { _, it -> "nith-${it.id}" }) { index, notice ->
                                Column(Modifier.background(colors.surface)) {
                                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                                    WebsiteNoticeRow(notice, isNew = false, onClick = { onOpenUrl(notice.url) })
                                }
                            }
                            item(key = "nithAll") {
                                RollTextButton(
                                    text = "All college notices",
                                    onClick = onOpenCollegeNotices,
                                    modifier = Modifier.padding(start = Spacing.gutter),
                                    alignTextToStart = true,
                                )
                            }
                        }
                    }
                    else -> {
                        itemsIndexed(uiState.items, key = { _, it -> it.session.id }) { index, item ->
                            Column(Modifier.background(colors.surface)) {
                                if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                                SessionItemRow(item = item, onMarkChange = { onMark(item, it) })
                            }
                        }
                        if (uiState.postedBy != null) {
                            item(key = "posted") {
                                Text(
                                    text = uiState.postedBy,
                                    style = RollTheme.typography.caption,
                                    color = colors.inkSecondary,
                                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YesterdayRow(count: Int, onClick: () -> Unit) {
    val colors = RollTheme.colors
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.minTouchTarget)
                .clickable(role = Role.Button, onClickLabel = "Open yesterday", onClick = onClick)
                .semantics(mergeDescendants = true) { }
                .padding(start = Spacing.gutter, end = Spacing.sm),
        ) {
            Text(
                text = "${AttendanceCopy.classes(count)} from yesterday ${if (count == 1) "isn't" else "aren't"} marked",
                style = RollTheme.typography.label,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(RollIcons.ChevronRight),
                contentDescription = null,
                tint = colors.inkSecondary,
                modifier = Modifier.size(Sizes.icon),
            )
        }
        HairlineDivider()
    }
}

/** The one-time, plain explanation before Android asks for notification permission. */
@Composable
private fun NotificationPrompt(onEnable: () -> Unit, onDismiss: () -> Unit) {
    val colors = RollTheme.colors
    Column(
        Modifier
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
            .fillMaxWidth()
            .border(1.dp, colors.outline, RollShapes.control)
            .padding(start = Spacing.md, end = Spacing.md, top = Spacing.md),
    ) {
        Text(
            text = "Mark from a notification",
            style = RollTheme.typography.bodyStrong,
            color = colors.ink,
        )
        Text(
            text = "When a class ends, Roll can ask Present or Absent in a notification, and remind you before deadlines. Nothing else.",
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
        Row {
            RollTextButton(text = "Turn on", onClick = onEnable, alignTextToStart = true)
            RollTextButton(text = "Not now", onClick = onDismiss)
        }
    }
}
