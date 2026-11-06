package app.roll.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.R
import app.roll.core.common.TimeSource
import app.roll.core.common.UserMessages
import app.roll.core.common.localDateIn
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.ListRow
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SessionRow
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.component.Stat
import app.roll.core.designsystem.component.StatLine
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.classes.ClassRepository
import app.roll.data.marks.MarkRepository
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.model.Mark
import app.roll.domain.model.Member
import app.roll.domain.model.MemberMark
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.schedule.SessionDraft
import app.roll.domain.schedule.TimetablePosting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/** Counts for one session, as the CR sees them. Only current members are counted. */
data class SessionCounts(val present: Int, val absent: Int, val notMarked: Int)

fun countsFor(marks: List<MemberMark>, members: List<Member>): SessionCounts {
    val memberIds = members.map { it.uid }.toSet()
    val counted = marks.filter { it.uid in memberIds }
    val present = counted.count { it.mark == Mark.Present }
    val absent = counted.count { it.mark == Mark.Absent }
    return SessionCounts(present, absent, (members.size - present - absent).coerceAtLeast(0))
}

data class ManagedSession(
    val session: Session,
    val subject: Subject?,
    val counts: SessionCounts?,
)

data class ManageUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val sessions: List<ManagedSession> = emptyList(),
    val toPost: Int = 0,
    val hasTimetable: Boolean = false,
    val subjects: List<Subject> = emptyList(),
    val memberCount: Int = 0,
    val slotCount: Int = 0,
    val className: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ManageViewModel @Inject constructor(
    session: AppSession,
    private val classData: ClassData,
    classes: ClassRepository,
    marks: MarkRepository,
    time: TimeSource,
    private val schedule: ScheduleRepository,
    private val messages: UserMessages,
) : ViewModel() {

    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val members: Flow<List<Member>> = session.context
        .map { it.classId }
        .distinctUntilChanged()
        .flatMapLatest { classes.observeMembers(it) }
        .onStart { emit(emptyList()) }

    private val today: Flow<LocalDate> = combine(session.context, time.ticks()) { ctx, now ->
        now.localDateIn(ctx.rollClass.timezone)
    }.distinctUntilChanged()

    private val todaysSessions: Flow<List<Session>> = combine(classData.sessions, today) { sessions, date ->
        sessions?.sessions.orEmpty().filter { it.date == date }.sortedBy { it.startMinutes }
    }

    /** sessionId → marks, for today's sessions only. */
    private val todaysMarks: Flow<Map<String, List<MemberMark>>> = combine(session.context, todaysSessions) { ctx, list ->
        ctx.classId to list.filter { it.status == SessionStatus.Scheduled }.map { it.id }
    }
        .distinctUntilChanged()
        .flatMapLatest { (classId, ids) ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> marks.observeSessionMarks(classId, id).map { id to it } }) { it.toMap() }
            }
        }
        .onStart { emit(emptyMap()) }

    val uiState: StateFlow<ManageUiState> = combine(
        combine(session.context, classData.sessions, classData.subjects, classData.timetable, ::Quad),
        today,
        todaysSessions,
        todaysMarks,
        members,
    ) { (ctx, sessions, subjects, timetable), date, todays, marksBySession, memberList ->
        if (sessions == null) return@combine ManageUiState(className = ctx.rollClass.name)
        val subjectMap = subjects.orEmpty().associateBy { it.id }
        ManageUiState(
            loading = false,
            today = date,
            sessions = todays.map { s ->
                ManagedSession(
                    session = s,
                    subject = subjectMap[s.subjectId],
                    counts = if (s.status == SessionStatus.Scheduled) countsFor(marksBySession[s.id].orEmpty(), memberList) else null,
                )
            },
            toPost = TimetablePosting.slotsToPost(date, timetable.orEmpty(), sessions.sessions).size,
            hasTimetable = timetable.orEmpty().any { it.dayOfWeek == date.dayOfWeek.value },
            subjects = subjects.orEmpty(),
            memberCount = memberList.size,
            slotCount = timetable.orEmpty().size,
            className = ctx.rollClass.name,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    fun postFromTimetable() {
        val ctx = context.value ?: return
        val sessions = classData.sessions.value ?: return
        val posted = schedule.postFromTimetable(
            classId = ctx.classId,
            date = uiState.value.today,
            slots = classData.timetable.value.orEmpty(),
            existing = sessions.sessions,
            deletedIds = sessions.deletedIds,
            crUid = ctx.uid,
            crName = ctx.profile.name,
        )
        messages.show(if (posted == 0) "Everything on today's timetable is already posted." else "Posted ${if (posted == 1) "1 class" else "$posted classes"}")
    }

    fun addClass(draft: SessionDraft) {
        val ctx = context.value ?: return
        schedule.postSession(ctx.classId, draft, ctx.uid, ctx.profile.name)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun ManageRoute(
    onOpenSession: (String) -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenSubjects: () -> Unit,
    onOpenClassSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ManageViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ManageScreen(
        uiState = uiState,
        onPostFromTimetable = viewModel::postFromTimetable,
        onAddClass = viewModel::addClass,
        onOpenSession = onOpenSession,
        onOpenTimetable = onOpenTimetable,
        onOpenSubjects = onOpenSubjects,
        onOpenClassSettings = onOpenClassSettings,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun ManageScreen(
    uiState: ManageUiState,
    onPostFromTimetable: () -> Unit,
    onAddClass: (SessionDraft) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenTimetable: () -> Unit,
    onOpenSubjects: () -> Unit,
    onOpenClassSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var adding by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = "Manage") {
            RollIconButton(
                icon = RollIcons.Settings,
                contentDescription = stringResource(R.string.action_settings),
                onClick = onOpenSettings,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "todayHeader") { SectionHeader("Today's classes") }
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), rows = 3, showTrailing = false) }
                uiState.sessions.isEmpty() -> item { EmptyState(message = "Nothing posted for today yet.") }
                else -> itemsIndexed(uiState.sessions, key = { _, it -> it.session.id }) { index, managed ->
                    Column(Modifier.background(colors.surface)) {
                        if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                        ManagedSessionRow(managed, onClick = { onOpenSession(managed.session.id) })
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.lg)) {
                    RollButton(
                        text = "Post today from timetable",
                        onClick = onPostFromTimetable,
                        enabled = uiState.toPost > 0,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = when {
                            !uiState.hasTimetable -> "Nothing on the timetable for today."
                            uiState.toPost == 0 -> "All of today's timetable is posted."
                            else -> "${if (uiState.toPost == 1) "1 class" else "${uiState.toPost} classes"} from the timetable not posted yet."
                        },
                        style = RollTheme.typography.caption,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                    Spacer(Modifier.height(Spacing.md))
                    RollOutlinedButton(text = "+ Add class", onClick = { adding = true }, modifier = Modifier.fillMaxWidth())
                }
            }
            item(key = "setup") {
                SectionHeader("Set up")
                ListGroup {
                    ListRow(
                        title = "Timetable",
                        subtitle = if (uiState.slotCount == 0) "No slots yet" else "${uiState.slotCount} weekly slots",
                        onClick = onOpenTimetable,
                    )
                    HairlineDivider(startIndent = Spacing.gutter)
                    ListRow(
                        title = "Subjects",
                        subtitle = if (uiState.subjects.isEmpty()) "None yet" else "${uiState.subjects.count { !it.archived }} active",
                        onClick = onOpenSubjects,
                    )
                    HairlineDivider(startIndent = Spacing.gutter)
                    ListRow(
                        title = "Class settings",
                        subtitle = "${uiState.className} · ${if (uiState.memberCount == 1) "1 member" else "${uiState.memberCount} members"}",
                        onClick = onOpenClassSettings,
                    )
                }
            }
        }
    }

    if (adding) {
        RollBottomSheet(onDismiss = { adding = false }) {
            SessionEditor(
                title = "Add class",
                confirmLabel = "Post class",
                subjects = uiState.subjects,
                initial = SessionDraft(
                    subjectId = uiState.subjects.firstOrNull { !it.archived }?.id.orEmpty(),
                    date = uiState.today,
                    startMinutes = 9 * 60,
                    endMinutes = 9 * 60 + 50,
                    type = SessionType.Lecture,
                    room = "",
                    weight = 1,
                ),
                today = uiState.today,
                onSave = {
                    onAddClass(it)
                    adding = false
                },
            )
        }
    }
}

@Composable
private fun ManagedSessionRow(managed: ManagedSession, onClick: () -> Unit) {
    val session = managed.session
    val cancelled = session.status == SessionStatus.Cancelled
    val counts = managed.counts
    SessionRow(
        startMinutes = session.startMinutes,
        endMinutes = session.endMinutes,
        shortName = managed.subject?.shortName ?: "Class",
        fullName = managed.subject?.name ?: "Subject removed",
        room = session.room,
        type = session.type,
        subjectColor = RollTheme.colors.subject(managed.subject?.colorIndex ?: 0),
        cancelled = cancelled,
        note = session.note.ifBlank { null },
        onClick = onClick,
        detail = counts?.let {
            {
                StatLine(
                    stats = listOf(
                        Stat(it.present.toString(), "present"),
                        Stat(it.absent.toString(), "absent"),
                        Stat(it.notMarked.toString(), "not marked"),
                    ),
                )
            }
        },
    )
}
