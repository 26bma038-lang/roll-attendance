package app.roll.feature.department

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.R
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.ListRow
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.Stat
import app.roll.core.designsystem.component.StatLine
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.core.util.openUrl
import app.roll.data.college.DepartmentRepository
import app.roll.data.feed.NithNoticeTracker
import app.roll.data.notices.NoticeDraft
import app.roll.data.notices.NoticeRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.college.NithConfig
import app.roll.domain.model.Department
import app.roll.domain.model.Notice
import app.roll.domain.model.RollClass
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.model.TimetableSlot
import app.roll.feature.common.AttendanceCopy
import app.roll.feature.notices.NoticeActionsDialog
import app.roll.feature.notices.NoticeEditor
import app.roll.feature.notices.NoticesSection
import app.roll.feature.notices.activeNotices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class DepartmentUiState(
    val rollClass: RollClass? = null,
    val today: LocalDate = LocalDate.now(),
    val slots: List<TimetableSlot> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val department: Department? = null,
    /** Classes that have happened this semester (scheduled and over). No attendance averages: privacy. */
    val classesHeld: Int = 0,
    val notices: List<Notice> = emptyList(),
    val canPostNotices: Boolean = false,
    /** nith.ac.in notices since the student last opened College notices. */
    val newCollegeNotices: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DepartmentViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    departments: DepartmentRepository,
    time: TimeSource,
    tracker: NithNoticeTracker,
    private val notices: NoticeRepository,
) : ViewModel() {
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val noticeList = combine(classData.classNotices, classData.collegeNotices, time.ticks()) { classList, collegeList, now ->
        activeNotices(classList.orEmpty(), collegeList.orEmpty(), now)
    }
    private val department = session.context.map { it.rollClass.branch }.distinctUntilChanged()
        .flatMapLatest { departments.observe(it) }

    private val base = combine(
        combine(session.context, time.ticks()) { ctx, now -> ctx to now.localDateTimeIn(ctx.rollClass.timezone) },
        classData.timetable,
        classData.subjects,
        classData.sessions,
        department,
    ) { (ctx, now), slots, subjects, sessions, dept ->
        DepartmentUiState(
            rollClass = ctx.rollClass,
            today = now.toLocalDate(),
            slots = slots.orEmpty(),
            subjects = subjects.orEmpty().filter { !it.archived }.sortedBy { it.name.lowercase() },
            department = dept,
            classesHeld = sessions?.sessions.orEmpty().count {
                it.status == SessionStatus.Scheduled && AttendanceCalculator.isOver(it, now)
            },
            canPostNotices = ctx.isCR || ctx.isAdmin,
        )
    }

    val uiState: StateFlow<DepartmentUiState> = combine(base, noticeList, tracker.newCount) { state, list, newCount ->
        state.copy(notices = list, newCollegeNotices = newCount)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DepartmentUiState())

    fun postNotice(draft: NoticeDraft) {
        val ctx = context.value ?: return
        if (ctx.isCR || ctx.isAdmin) notices.post(ctx.classId, draft, ctx.profile.name)
    }

    fun setPinned(notice: Notice, pinned: Boolean) {
        val ctx = context.value ?: return
        notices.setPinned(ctx.classId, notice, pinned)
    }

    fun deleteNotice(notice: Notice) {
        val ctx = context.value ?: return
        notices.delete(ctx.classId, notice)
    }
}

@Composable
fun DepartmentRoute(
    onOpenSettings: () -> Unit,
    onOpenCollegeNotices: () -> Unit,
    viewModel: DepartmentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DepartmentScreen(
        uiState = uiState,
        onOpenSettings = onOpenSettings,
        onOpenCollegeNotices = onOpenCollegeNotices,
        onPostNotice = viewModel::postNotice,
        onPinNotice = viewModel::setPinned,
        onDeleteNotice = viewModel::deleteNotice,
    )
}

private val WeekdayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

@Composable
fun DepartmentScreen(
    uiState: DepartmentUiState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCollegeNotices: () -> Unit = {},
    onPostNotice: (NoticeDraft) -> Unit = {},
    onPinNotice: (Notice, Boolean) -> Unit = { _, _ -> },
    onDeleteNotice: (Notice) -> Unit = {},
) {
    val colors = RollTheme.colors
    var writing by rememberSaveable { mutableStateOf(false) }
    var selectedNoticeId by rememberSaveable { mutableStateOf<String?>(null) }
    val rollClass = uiState.rollClass
    val subjectMap = uiState.subjects.associateBy { it.id }
    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = "Department") {
            RollIconButton(
                icon = RollIcons.Settings,
                contentDescription = stringResource(R.string.action_settings),
                onClick = onOpenSettings,
            )
        }
        if (rollClass == null) return@Column
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    Text(
                        text = NithConfig.branchName(rollClass.branch),
                        style = RollTheme.typography.title,
                        color = colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = "${rollClass.batch} batch · ${rollClass.semester}",
                        style = RollTheme.typography.caption,
                        color = colors.inkSecondary,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = if (rollClass.crNames.isEmpty()) "No CR yet" else "CR: ${rollClass.crNames.joinToString(", ")}",
                        style = RollTheme.typography.body,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    StatLine(
                        stats = listOf(
                            Stat(rollClass.memberCount.toString(), if (rollClass.memberCount == 1) "student" else "students"),
                            Stat(uiState.classesHeld.toString(), if (uiState.classesHeld == 1) "class held this semester" else "classes held this semester"),
                        ),
                    )
                }
            }
            item(key = "notices") {
                NoticesSection(
                    notices = uiState.notices,
                    canPost = uiState.canPostNotices,
                    onPost = { writing = true },
                    onSelect = { selectedNoticeId = it.id },
                )
                ListGroup {
                    HairlineDivider()
                    ListRow(
                        title = "College notices",
                        subtitle = when (uiState.newCollegeNotices) {
                            0 -> "From the admin and nith.ac.in: academic, exam and student notices"
                            1 -> "1 new from nith.ac.in"
                            else -> "${uiState.newCollegeNotices} new from nith.ac.in"
                        },
                        onClick = onOpenCollegeNotices,
                    )
                }
            }
            item(key = "week") {
                SectionHeader("This week")
                WeekTimetable(uiState.slots, subjectMap, uiState.today)
            }
            item(key = "subjects") {
                SectionHeader("Subjects")
                SubjectsWithFaculty(uiState.subjects)
            }
            item(key = "info") {
                SectionHeader("Department")
                DepartmentInfo(uiState.department)
            }
        }
    }

    if (writing) {
        RollBottomSheet(onDismiss = { writing = false }) {
            NoticeEditor(title = "Class notice", today = uiState.today) {
                onPostNotice(it)
                writing = false
            }
        }
    }
    uiState.notices.firstOrNull { it.id == selectedNoticeId }?.let { notice ->
        NoticeActionsDialog(
            notice = notice,
            onPin = {
                onPinNotice(notice, it)
                selectedNoticeId = null
            },
            onDelete = {
                onDeleteNotice(notice)
                selectedNoticeId = null
            },
            onDismiss = { selectedNoticeId = null },
        )
    }
}

@Composable
private fun WeekTimetable(slots: List<TimetableSlot>, subjects: Map<String, Subject>, today: LocalDate) {
    val colors = RollTheme.colors
    if (slots.isEmpty()) {
        NotAdded("No timetable yet. Your CR adds it.")
        return
    }
    ListGroup {
        WeekdayNames.forEachIndexed { index, name ->
            val day = index + 1
            val isToday = today.dayOfWeek.value == day
            val daySlots = slots.filter { it.dayOfWeek == day }.sortedBy { it.startMinutes }
            if (index > 0) HairlineDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .semantics(mergeDescendants = true) { },
            ) {
                // Today is marked by an ink bar and weight, not a colour fill.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(Sizes.subjectBarWidth)
                        .background(if (isToday) colors.ink else colors.surface),
                )
                Column(
                    Modifier
                        .widthIn(min = 56.dp)
                        .padding(start = Spacing.gutter - Sizes.subjectBarWidth, top = Spacing.md, bottom = Spacing.md),
                ) {
                    Text(
                        text = name,
                        style = if (isToday) RollTheme.typography.bodyStrong else RollTheme.typography.body,
                        color = colors.ink,
                    )
                    if (isToday) Text("Today", style = RollTheme.typography.caption, color = colors.inkSecondary)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.md, end = Spacing.gutter, top = Spacing.md, bottom = Spacing.md),
                ) {
                    if (daySlots.isEmpty()) {
                        Text("No classes", style = RollTheme.typography.caption, color = colors.inkSecondary)
                    }
                    daySlots.forEach { slot ->
                        val subject = subjects[slot.subjectId]
                        Text(
                            text = listOfNotNull(
                                "${formatClock(slot.startMinutes)}–${formatClock(slot.endMinutes)}",
                                subject?.shortName ?: "Class",
                                AttendanceCopy.typeLabel(slot.type).takeIf { slot.type != SessionType.Lecture },
                                slot.room.ifBlank { null },
                            ).joinToString("  ·  "),
                            style = RollTheme.typography.caption,
                            color = colors.ink,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectsWithFaculty(subjects: List<Subject>) {
    val colors = RollTheme.colors
    if (subjects.isEmpty()) {
        NotAdded("No subjects yet. Your CR adds them.")
        return
    }
    ListGroup {
        subjects.forEachIndexed { index, subject ->
            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .heightIn(min = Sizes.rowMinHeight)
                    .semantics(mergeDescendants = true) { },
            ) {
                SubjectBar(colors.subject(subject.colorIndex))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = Spacing.gutter - Sizes.subjectBarWidth, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.sm),
                ) {
                    Text(text = "${subject.shortName}  ·  ${subject.name}", style = RollTheme.typography.body, color = colors.ink)
                    Text(
                        text = listOf(subject.faculty.ifBlank { "Faculty not added yet" }, subject.code).filter { it.isNotBlank() }.joinToString(" · "),
                        style = RollTheme.typography.caption,
                        color = colors.inkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DepartmentInfo(department: Department?) {
    val colors = RollTheme.colors
    val context = LocalContext.current
    ListGroup {
        InfoRow("About", department?.about)
        HairlineDivider(startIndent = Spacing.gutter)
        InfoRow("Head of department", department?.hod)
        HairlineDivider(startIndent = Spacing.gutter)
        InfoRow("Department office", department?.office)
        HairlineDivider(startIndent = Spacing.gutter)
        InfoRow("Office email", department?.email, onClick = department?.email?.ifBlank { null }?.let { { context.openUrl("mailto:$it") } })
        HairlineDivider(startIndent = Spacing.gutter)
        InfoRow("Office phone", department?.phone, onClick = department?.phone?.ifBlank { null }?.let { { context.openUrl("tel:$it") } })
    }
    SectionHeader("Useful links")
    val links = department?.links.orEmpty()
    if (links.isEmpty()) {
        NotAdded("Not added yet")
    } else {
        ListGroup {
            links.forEachIndexed { index, link ->
                if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.buttonHeight)
                        .clickable(role = Role.Button, onClickLabel = "Open in browser") { context.openUrl(link.url) }
                        .padding(start = Spacing.gutter, end = Spacing.md),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                        Text(text = link.title, style = RollTheme.typography.body, color = colors.ink)
                        Text(text = link.url, style = RollTheme.typography.caption, color = colors.inkSecondary, maxLines = 1)
                    }
                    Icon(
                        painter = painterResource(RollIcons.OpenExternal),
                        contentDescription = null,
                        tint = colors.inkSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?, onClick: (() -> Unit)? = null) {
    val colors = RollTheme.colors
    val filled = !value.isNullOrBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.buttonHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
    ) {
        Text(text = label, style = RollTheme.typography.label, color = colors.inkSecondary)
        Text(
            text = if (filled) value.orEmpty() else "Not added yet",
            style = RollTheme.typography.body,
            color = if (filled) colors.ink else colors.cancelled,
            fontWeight = if (filled) null else FontWeight.Normal,
        )
    }
}

@Composable
private fun NotAdded(text: String) {
    Text(
        text = text,
        style = RollTheme.typography.body,
        color = RollTheme.colors.inkSecondary,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
    )
}
