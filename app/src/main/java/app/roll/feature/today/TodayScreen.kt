package app.roll.feature.today

import androidx.compose.foundation.background
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
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.OfflineLine
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.core.util.formatDayHeader
import app.roll.data.marks.MarkRepository
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.attendance.SessionState
import app.roll.domain.model.Mark
import app.roll.feature.common.AttendanceCopy
import app.roll.feature.common.ClearedMark
import app.roll.feature.common.MarkClearedSnackbar
import app.roll.feature.common.MarkController
import app.roll.feature.common.SessionItem
import app.roll.feature.common.SessionItemRow
import app.roll.feature.common.sessionItems
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
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    marks: MarkRepository,
    time: TimeSource,
    private val schedule: ScheduleRepository,
    private val messages: UserMessages,
) : ViewModel() {

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
            postedBy = latest?.let {
                val posted = it.createdAt!!.atZone(ZoneId.of(tz)).toLocalTime()
                "Posted by ${it.createdByName.ifBlank { "your CR" }} · ${formatClock(posted.hour * 60 + posted.minute, meridiem = true)}"
            },
        )
    }

    val uiState: StateFlow<TodayUiState> = combine(content, refreshing, controller.cleared) { state, isRefreshing, cleared ->
        state.copy(refreshing = isRefreshing, clearedMark = cleared)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun onMark(item: SessionItem, mark: Mark?) {
        context.value?.let { controller.set(it, item, mark) }
    }

    fun onUndo() {
        context.value?.let { controller.undo(it) }
    }

    fun onUndoDismissed() = controller.dismiss()

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
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MarkClearedSnackbar(uiState.clearedMark, onUndo = viewModel::onUndo, onDismiss = viewModel::onUndoDismissed)
    TodayScreen(
        uiState = uiState,
        onMark = viewModel::onMark,
        onRefresh = viewModel::onRefresh,
        onOpenSettings = onOpenSettings,
        onOpenDay = onOpenDay,
        onOpenAdmin = onOpenAdmin,
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
                if (uiState.yesterdayUnmarked > 0 && date != null) {
                    item(key = "yesterday") {
                        YesterdayRow(count = uiState.yesterdayUnmarked, onClick = { onOpenDay(date.minusDays(1)) })
                    }
                }
                when {
                    uiState.loading -> item(key = "skeleton") {
                        SkeletonList(Modifier.background(colors.surface))
                    }
                    uiState.items.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            message = uiState.noCrClassName
                                ?.let { "No CR yet for $it. Classes appear here once the admin appoints one." }
                                ?: "No classes posted yet for today.",
                        )
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
