package app.roll.feature.deadlines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.R
import app.roll.core.common.TimeSource
import app.roll.core.common.zoneOf
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.data.assignments.AssignmentDraft
import app.roll.data.assignments.AssignmentRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.deadlines.DeadlineGroup
import app.roll.domain.deadlines.Deadlines
import app.roll.domain.deadlines.RelativeDue
import app.roll.domain.model.Assignment
import app.roll.domain.model.AssignmentType
import app.roll.domain.model.Subject
import app.roll.feature.common.AttendanceCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One deadline as a row needs it. */
data class DeadlineItem(
    val assignment: Assignment,
    val subject: Subject?,
    val done: Boolean,
    val relative: RelativeDue,
    /** "Thu 5 Nov · 11:59 pm". */
    val dueText: String,
)

fun dueText(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return "${AttendanceCopy.shortDate(local.toLocalDate())} · ${formatClock(local.hour * 60 + local.minute, meridiem = true)}"
}

fun deadlineItem(assignment: Assignment, subjects: Map<String, Subject>, doneIds: Set<String>, now: Instant, zone: ZoneId) =
    DeadlineItem(
        assignment = assignment,
        subject = subjects[assignment.subjectId],
        done = assignment.id in doneIds,
        relative = Deadlines.relative(assignment.dueAt, now),
        dueText = dueText(assignment.dueAt, zone),
    )

data class DeadlinesUiState(
    val loading: Boolean = true,
    val groups: Map<DeadlineGroup, List<DeadlineItem>> = emptyMap(),
    val subjects: List<Subject> = emptyList(),
    val filterSubjectId: String? = null,
    val canPost: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
)

@HiltViewModel
class DeadlinesViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    time: TimeSource,
    private val assignments: AssignmentRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<String?>(null)
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<DeadlinesUiState> = combine(
        combine(session.context, time.ticks(), filter, ::Triple),
        classData.assignments,
        classData.doneIds,
        classData.subjects,
    ) { (ctx, now, filterId), list, done, subjects ->
        val zone = zoneOf(ctx.rollClass.timezone)
        if (list == null) return@combine DeadlinesUiState(canPost = ctx.isCR || ctx.isAdmin, zone = zone)
        val subjectMap = subjects.orEmpty().associateBy { it.id }
        val doneIds = done.orEmpty()
        val shown = if (filterId == null) list else list.filter { it.subjectId == filterId }
        DeadlinesUiState(
            loading = false,
            groups = Deadlines.group(shown, doneIds, now, zone).mapValues { (_, items) ->
                items.map { deadlineItem(it, subjectMap, doneIds, now, zone) }
            },
            subjects = subjects.orEmpty().filter { !it.archived },
            filterSubjectId = filterId,
            canPost = ctx.isCR || ctx.isAdmin,
            today = now.atZone(zone).toLocalDate(),
            zone = zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeadlinesUiState())

    fun setFilter(subjectId: String?) {
        filter.value = subjectId
    }

    fun setDone(assignment: Assignment, done: Boolean) {
        val ctx = context.value ?: return
        assignments.setDone(ctx.uid, assignment.id, done)
    }

    fun post(draft: AssignmentDraft) {
        val ctx = context.value ?: return
        if (!(ctx.isCR || ctx.isAdmin)) return
        assignments.post(ctx.classId, draft, ctx.uid, ctx.profile.name)
    }
}

@Composable
fun DeadlinesRoute(
    onOpenAssignment: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DeadlinesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DeadlinesScreen(
        uiState = uiState,
        onOpenAssignment = onOpenAssignment,
        onOpenSettings = onOpenSettings,
        onFilter = viewModel::setFilter,
        onDone = viewModel::setDone,
        onPost = viewModel::post,
    )
}

@Composable
fun DeadlinesScreen(
    uiState: DeadlinesUiState,
    onOpenAssignment: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onFilter: (String?) -> Unit,
    onDone: (Assignment, Boolean) -> Unit,
    onPost: (AssignmentDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var picking by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val filterName = uiState.subjects.firstOrNull { it.id == uiState.filterSubjectId }?.shortName

    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = "Deadlines") {
            if (uiState.canPost) {
                RollIconButton(icon = RollIcons.Add, contentDescription = "Add an assignment", onClick = { adding = true })
            }
            RollIconButton(
                icon = RollIcons.Settings,
                contentDescription = stringResource(R.string.action_settings),
                onClick = onOpenSettings,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.xs),
        ) {
            Text(
                text = "Showing ${filterName ?: "all subjects"}",
                style = RollTheme.typography.label,
                color = colors.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            if (uiState.filterSubjectId != null) RollTextButton(text = "Clear", onClick = { onFilter(null) })
            RollTextButton(text = "Filter", onClick = { picking = true })
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), rows = 3, showTrailing = false) }
                uiState.groups.isEmpty() -> item {
                    EmptyState(
                        message = when {
                            uiState.filterSubjectId != null -> "Nothing for $filterName."
                            uiState.canPost -> "No deadlines yet. Add the first one with +."
                            else -> "No deadlines yet. Your CR posts them here."
                        },
                    )
                }
                else -> uiState.groups.forEach { (group, items) ->
                    item(key = "header-${group.name}") {
                        SectionHeader("${group.label} · ${items.size}")
                    }
                    itemsIndexed(items, key = { _, it -> "${group.name}-${it.assignment.id}" }) { index, item ->
                        Column(Modifier.background(colors.surface)) {
                            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                            DeadlineRow(
                                item = item,
                                onClick = { onOpenAssignment(item.assignment.id) },
                                onDoneChange = { onDone(item.assignment, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        SubjectFilterDialog(
            subjects = uiState.subjects,
            selectedId = uiState.filterSubjectId,
            onPick = {
                onFilter(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
    if (adding) {
        RollBottomSheet(onDismiss = { adding = false }) {
            AssignmentEditor(
                title = "Add assignment",
                confirmLabel = "Post",
                subjects = uiState.subjects,
                initial = AssignmentDraft(
                    title = "",
                    subjectId = uiState.filterSubjectId ?: uiState.subjects.firstOrNull()?.id.orEmpty(),
                    type = AssignmentType.Assignment,
                    description = "",
                    link = "",
                    dueAt = uiState.today.plusDays(7).atTime(23, 59).atZone(uiState.zone).toInstant(),
                ),
                today = uiState.today,
                zone = uiState.zone,
                onSave = {
                    onPost(it)
                    adding = false
                },
            )
        }
    }
}

/** Subject bar, title, type · subject, due date and relative time, done checkbox. */
@Composable
fun DeadlineRow(
    item: DeadlineItem,
    onClick: () -> Unit,
    onDoneChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val relativeColor = when {
        item.done -> colors.inkSecondary
        item.relative.overdue -> colors.warning
        else -> colors.inkSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Sizes.rowTallHeight),
    ) {
        SubjectBar(if (item.done) colors.cancelled else colors.subject(item.subject?.colorIndex ?: 0))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { }
                .padding(
                    start = Spacing.gutter - Sizes.subjectBarWidth,
                    top = Spacing.md,
                    bottom = Spacing.md,
                    end = if (onDoneChange == null) Spacing.gutter else Spacing.sm,
                ),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.assignment.title,
                    style = RollTheme.typography.bodyStrong,
                    color = if (item.done) colors.inkSecondary else colors.ink,
                )
                Text(
                    text = listOfNotNull(item.assignment.type.label, item.subject?.shortName).joinToString(" · "),
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.md)) {
                Text(text = item.dueText, style = RollTheme.typography.caption, color = colors.inkSecondary, textAlign = TextAlign.End)
                Text(
                    text = if (item.done) "Done" else item.relative.text,
                    style = RollTheme.typography.label,
                    color = relativeColor,
                    textAlign = TextAlign.End,
                )
            }
        }
        if (onDoneChange != null) {
            Checkbox(
                checked = item.done,
                onCheckedChange = onDoneChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    checkmarkColor = colors.onAccent,
                    uncheckedColor = colors.outline,
                ),
                modifier = Modifier
                    .padding(end = Spacing.xs)
                    .semantics { contentDescription = "Done: ${item.assignment.title}" },
            )
        }
    }
}

@Composable
private fun SubjectFilterDialog(
    subjects: List<Subject>,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RollTheme.colors
    RollDialog(
        title = "Show deadlines for",
        confirmLabel = "Close",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier
                .selectableGroup()
                .verticalScroll(rememberScrollState()),
        ) {
            (listOf<Subject?>(null) + subjects).forEach { subject ->
                val selected = subject?.id == selectedId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.buttonHeight)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(subject?.id) }),
                ) {
                    Text(
                        text = subject?.let { "${it.shortName} — ${it.name}" } ?: "All subjects",
                        style = RollTheme.typography.body,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) Icon(painterResource(RollIcons.Check), contentDescription = null, tint = colors.accent)
                }
            }
        }
    }
}
