package app.roll.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateIn
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollNumberText
import app.roll.core.designsystem.component.RollTabRow
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.data.classes.ClassRepository
import app.roll.data.marks.MarkRepository
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.model.Mark
import app.roll.domain.model.Member
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.schedule.SessionDraft
import app.roll.feature.common.AttendanceCopy
import app.roll.navigation.SessionDetailDest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class SessionDetailUiState(
    val loading: Boolean = true,
    val session: Session? = null,
    val subject: Subject? = null,
    val subjects: List<Subject> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val present: List<Member> = emptyList(),
    val absent: List<Member> = emptyList(),
    val notMarked: List<Member> = emptyList(),
    val deleted: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    session: AppSession,
    classData: ClassData,
    classes: ClassRepository,
    marks: MarkRepository,
    time: TimeSource,
    private val schedule: ScheduleRepository,
) : ViewModel() {
    private val sessionId = savedStateHandle.toRoute<SessionDetailDest>().sessionId
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val classId = session.context.map { it.classId }.distinctUntilChanged()
    private val members = classId.flatMapLatest { classes.observeMembers(it) }.onStart { emit(emptyList()) }
    private val sessionMarks = classId.flatMapLatest { marks.observeSessionMarks(it, sessionId) }.onStart { emit(emptyList()) }

    val uiState: StateFlow<SessionDetailUiState> = combine(
        combine(session.context, time.ticks()) { ctx, now -> now.localDateIn(ctx.rollClass.timezone) },
        classData.sessions,
        classData.subjects,
        members,
        sessionMarks,
    ) { today, sessions, subjects, memberList, markList ->
        if (sessions == null) return@combine SessionDetailUiState(today = today)
        val found = sessions.sessions.firstOrNull { it.id == sessionId }
        val byUid = markList.associate { it.uid to it.mark }
        SessionDetailUiState(
            loading = false,
            session = found,
            subject = subjects?.firstOrNull { it.id == found?.subjectId },
            subjects = subjects.orEmpty(),
            today = today,
            present = memberList.filter { byUid[it.uid] == Mark.Present },
            absent = memberList.filter { byUid[it.uid] == Mark.Absent },
            notMarked = memberList.filter { it.uid !in byUid },
            deleted = found == null && sessionId in sessions.deletedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUiState())

    fun save(draft: SessionDraft) {
        val ctx = context.value ?: return
        val current = uiState.value.session ?: return
        schedule.updateSession(ctx.classId, current, draft)
    }

    fun setCancelled(cancelled: Boolean, note: String) {
        val ctx = context.value ?: return
        val current = uiState.value.session ?: return
        schedule.setCancelled(ctx.classId, current, cancelled, note.trim())
    }

    fun delete(onDone: () -> Unit) {
        val ctx = context.value ?: return
        viewModelScope.launch {
            schedule.deleteSession(ctx.classId, sessionId)
            onDone()
        }
    }
}

private enum class SessionDialog { None, Edit, Cancel, Restore, Delete }

@Composable
fun SessionDetailRoute(
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SessionDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onSave = viewModel::save,
        onSetCancelled = viewModel::setCancelled,
        onDelete = { viewModel.delete(onBack) },
    )
}

@Composable
fun SessionDetailScreen(
    uiState: SessionDetailUiState,
    onBack: () -> Unit,
    onSave: (SessionDraft) -> Unit,
    onSetCancelled: (cancelled: Boolean, note: String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val session = uiState.session
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by rememberSaveable { mutableStateOf(SessionDialog.None) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = uiState.subject?.shortName ?: "Class", onBack = onBack) {
            if (session != null) {
                RollIconButton(icon = RollIcons.Edit, contentDescription = "Edit class", onClick = { dialog = SessionDialog.Edit })
                RollIconButton(icon = RollIcons.Delete, contentDescription = "Delete class", onClick = { dialog = SessionDialog.Delete })
            }
        }
        if (session == null) {
            if (!uiState.loading) EmptyState(message = if (uiState.deleted) "This class was deleted." else "This class isn't available.")
            return@Column
        }
        val cancelled = session.status == SessionStatus.Cancelled
        Column(Modifier.padding(horizontal = Spacing.gutter)) {
            Text(text = uiState.subject?.name ?: "Subject removed", style = RollTheme.typography.body, color = colors.ink)
            Text(
                text = listOfNotNull(
                    AttendanceCopy.shortDate(session.date),
                    "${formatClock(session.startMinutes, meridiem = true)}–${formatClock(session.endMinutes, meridiem = true)}",
                    AttendanceCopy.typeLabel(session.type),
                    session.room.ifBlank { null },
                    if (session.weight > 1) "counts as ${session.weight}" else null,
                ).joinToString(" · "),
                style = RollTheme.typography.caption,
                color = colors.inkSecondary,
            )
            if (cancelled) {
                Text(
                    text = if (session.note.isBlank()) "Cancelled" else "Cancelled — ${session.note}",
                    style = RollTheme.typography.label,
                    color = colors.warning,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Row(Modifier.padding(top = Spacing.xs)) {
                if (cancelled) {
                    RollTextButton(text = "Restore class", onClick = { dialog = SessionDialog.Restore }, alignTextToStart = true)
                } else {
                    RollTextButton(text = "Cancel class", onClick = { dialog = SessionDialog.Cancel }, destructive = true, alignTextToStart = true)
                }
            }
        }
        RollTabRow(
            tabs = listOf(
                "Present ${uiState.present.size}",
                "Absent ${uiState.absent.size}",
                "Not marked ${uiState.notMarked.size}",
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
        )
        val list = when (tab) {
            0 -> uiState.present
            1 -> uiState.absent
            else -> uiState.notMarked
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            if (list.isEmpty()) {
                item {
                    EmptyState(
                        message = when (tab) {
                            0 -> "Nobody has marked present yet."
                            1 -> "Nobody has marked absent."
                            else -> "Everyone has marked."
                        },
                    )
                }
            }
            itemsIndexed(list, key = { _, it -> it.uid }) { index, member ->
                Column(Modifier.background(colors.surface)) {
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    MemberRow(member)
                }
            }
        }
    }

    when (dialog) {
        SessionDialog.None -> Unit
        SessionDialog.Edit -> RollBottomSheet(onDismiss = { dialog = SessionDialog.None }) {
            SessionEditor(
                title = "Edit class",
                confirmLabel = "Save class",
                subjects = uiState.subjects,
                initial = SessionDraft(
                    subjectId = session?.subjectId.orEmpty(),
                    date = session?.date ?: uiState.today,
                    startMinutes = session?.startMinutes ?: 540,
                    endMinutes = session?.endMinutes ?: 590,
                    type = session?.type ?: SessionType.Lecture,
                    room = session?.room.orEmpty(),
                    weight = session?.weight ?: 1,
                    note = session?.note.orEmpty(),
                ),
                today = uiState.today,
                onSave = {
                    onSave(it)
                    dialog = SessionDialog.None
                },
            )
        }
        SessionDialog.Cancel -> {
            var note by rememberSaveable { mutableStateOf("") }
            RollDialog(
                title = "Cancel this class?",
                text = "It stays in the list, struck through, and doesn't count toward anyone's percentage.",
                confirmLabel = "Cancel class",
                dismissLabel = "Keep",
                destructive = true,
                onConfirm = {
                    onSetCancelled(true, note)
                    dialog = SessionDialog.None
                },
                onDismiss = { dialog = SessionDialog.None },
            ) {
                RollTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = "Note (optional)",
                    supporting = "For example, faculty on leave",
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }
        }
        SessionDialog.Restore -> RollDialog(
            title = "Restore this class?",
            text = "It will count toward percentages again.",
            confirmLabel = "Restore",
            onConfirm = {
                onSetCancelled(false, "")
                dialog = SessionDialog.None
            },
            onDismiss = { dialog = SessionDialog.None },
        )
        SessionDialog.Delete -> RollDialog(
            title = "Delete this class?",
            text = "Everyone's marks for it are deleted too. To keep a record that it didn't happen, cancel it instead.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                dialog = SessionDialog.None
                onDelete()
            },
            onDismiss = { dialog = SessionDialog.None },
        )
    }
}

@Composable
fun MemberRow(
    member: Member,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinHeight)
            .semantics(mergeDescendants = true) { }
            .padding(start = Spacing.gutter, end = if (trailing != null) Spacing.xs else Spacing.gutter),
    ) {
        RollNumberText(rollNo = member.rollNo, modifier = Modifier.widthIn(min = 104.dp))
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = member.name,
            style = RollTheme.typography.body,
            color = RollTheme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
