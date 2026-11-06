package app.roll.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTabRow
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SessionRow
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.model.Subject
import app.roll.domain.model.TimetableSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TimetableUiState(
    val loading: Boolean = true,
    val slots: List<TimetableSlot> = emptyList(),
    val subjects: List<Subject> = emptyList(),
)

@HiltViewModel
class TimetableViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    private val schedule: ScheduleRepository,
) : ViewModel() {
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<TimetableUiState> = combine(classData.timetable, classData.subjects) { slots, subjects ->
        TimetableUiState(loading = slots == null, slots = slots.orEmpty(), subjects = subjects.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimetableUiState())

    fun save(draft: SlotDraft) {
        val ctx = context.value ?: return
        schedule.saveSlot(
            ctx.classId,
            TimetableSlot(draft.id, draft.dayOfWeek, draft.startMinutes, draft.endMinutes, draft.subjectId, draft.type, draft.room.trim()),
        )
    }

    fun delete(slotId: String) {
        val ctx = context.value ?: return
        schedule.deleteSlot(ctx.classId, slotId)
    }
}

private val Weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

@Composable
fun TimetableRoute(
    onBack: () -> Unit,
    viewModel: TimetableViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TimetableScreen(uiState, onBack, onSave = viewModel::save, onDelete = viewModel::delete)
}

@Composable
fun TimetableScreen(
    uiState: TimetableUiState,
    onBack: () -> Unit,
    onSave: (SlotDraft) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var day by rememberSaveable { mutableIntStateOf(1) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // slot id, "" for new
    val subjectMap = uiState.subjects.associateBy { it.id }
    val slots = uiState.slots.filter { it.dayOfWeek == day }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "Timetable", onBack = onBack)
        RollTabRow(tabs = Weekdays, selectedIndex = day - 1, onSelect = { day = it + 1 })
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), rows = 3, showTrailing = false) }
                slots.isEmpty() -> item { EmptyState(message = "No classes on ${Weekdays[day - 1]}.") }
                else -> itemsIndexed(slots, key = { _, it -> it.id }) { index, slot ->
                    val subject = subjectMap[slot.subjectId]
                    Column(Modifier.background(colors.surface)) {
                        if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                        SessionRow(
                            startMinutes = slot.startMinutes,
                            endMinutes = slot.endMinutes,
                            shortName = subject?.shortName ?: "Class",
                            fullName = subject?.name ?: "Subject removed",
                            room = slot.room,
                            type = slot.type,
                            subjectColor = colors.subject(subject?.colorIndex ?: 0),
                            onClick = { editing = slot.id },
                        )
                    }
                }
            }
            item {
                RollOutlinedButton(
                    text = "+ Add slot on ${Weekdays[day - 1]}",
                    onClick = { editing = "" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.lg),
                )
            }
        }
    }

    val editingId = editing
    if (editingId != null) {
        val existing = uiState.slots.firstOrNull { it.id == editingId }
        RollBottomSheet(onDismiss = { editing = null }) {
            SlotEditor(
                title = if (existing == null) "Add slot" else "Edit slot",
                subjects = uiState.subjects,
                initial = existing?.let {
                    SlotDraft(it.id, it.subjectId, it.dayOfWeek, it.startMinutes, it.endMinutes, it.type, it.room)
                } ?: SlotDraft(
                    subjectId = uiState.subjects.firstOrNull { !it.archived }?.id.orEmpty(),
                    dayOfWeek = day,
                    // Start where the day's last slot ends.
                    startMinutes = slots.maxOfOrNull { it.endMinutes } ?: (9 * 60),
                    endMinutes = (slots.maxOfOrNull { it.endMinutes } ?: (9 * 60)) + 50,
                ),
                onSave = {
                    onSave(it)
                    editing = null
                },
                onDelete = existing?.let {
                    {
                        onDelete(it.id)
                        editing = null
                    }
                },
            )
        }
    }
}
