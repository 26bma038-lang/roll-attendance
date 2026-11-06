package app.roll.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.OfflineLine
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatDayHeader
import app.roll.data.marks.MarkRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.attendance.EditWindow
import app.roll.domain.model.Mark
import app.roll.feature.common.ClearedMark
import app.roll.feature.common.MarkClearedSnackbar
import app.roll.feature.common.MarkController
import app.roll.feature.common.SessionItem
import app.roll.feature.common.SessionItemRow
import app.roll.feature.common.sessionItems
import app.roll.navigation.DayDest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class DayUiState(
    val date: LocalDate,
    val loading: Boolean = true,
    val items: List<SessionItem> = emptyList(),
    val editable: Boolean = false,
    val pendingSync: Boolean = false,
    val clearedMark: ClearedMark? = null,
)

@HiltViewModel
class DayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    session: AppSession,
    classData: ClassData,
    marks: MarkRepository,
    time: TimeSource,
) : ViewModel() {
    private val date: LocalDate = LocalDate.parse(savedStateHandle.toRoute<DayDest>().date)
    private val controller = MarkController(marks)
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val content = combine(
        session.context,
        classData.sessions,
        classData.subjects,
        classData.myMarks,
        time.ticks(),
    ) { ctx, sessions, subjects, myMarks, now ->
        if (sessions == null || myMarks == null) return@combine DayUiState(date)
        val nowLocal = now.localDateTimeIn(ctx.rollClass.timezone)
        DayUiState(
            date = date,
            loading = false,
            items = sessionItems(
                sessions = sessions.sessions.filter { it.date == date }.sortedBy { it.startMinutes },
                subjects = subjects.orEmpty().associateBy { it.id },
                marks = myMarks.marks.mapValues { it.value.mark },
                now = nowLocal,
            ),
            editable = EditWindow.isEditable(date, nowLocal.toLocalDate()),
            pendingSync = myMarks.hasPendingWrites,
        )
    }

    val uiState: StateFlow<DayUiState> = combine(content, controller.cleared) { state, cleared ->
        state.copy(clearedMark = cleared)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(date))

    fun onMark(item: SessionItem, mark: Mark?) {
        context.value?.let { controller.set(it, item, mark) }
    }

    fun onUndo() {
        context.value?.let { controller.undo(it) }
    }

    fun onUndoDismissed() = controller.dismiss()
}

@Composable
fun DayRoute(
    onBack: () -> Unit,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MarkClearedSnackbar(uiState.clearedMark, onUndo = viewModel::onUndo, onDismiss = viewModel::onUndoDismissed)
    DayScreen(uiState = uiState, onMark = viewModel::onMark, onBack = onBack)
}

@Composable
fun DayScreen(
    uiState: DayUiState,
    onMark: (SessionItem, Mark?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = formatDayHeader(uiState.date), onBack = onBack)
        if (uiState.pendingSync) OfflineLine()
        if (!uiState.editable && uiState.items.isNotEmpty()) {
            Text(
                text = "Marks can only be changed for 2 days after a class.",
                style = RollTheme.typography.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.sm),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), rows = 3) }
                uiState.items.isEmpty() -> item { EmptyState(message = "No classes were posted for this day.") }
                else -> itemsIndexed(uiState.items, key = { _, it -> it.session.id }) { index, item ->
                    Column(Modifier.background(colors.surface)) {
                        if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                        SessionItemRow(item = item, onMarkChange = { onMark(item, it) })
                    }
                }
            }
        }
    }
}
