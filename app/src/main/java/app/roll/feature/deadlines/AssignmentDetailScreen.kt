package app.roll.feature.deadlines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.roll.core.common.TimeSource
import app.roll.core.common.UserMessages
import app.roll.core.common.zoneOf
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.openUrl
import app.roll.data.assignments.AssignmentDraft
import app.roll.data.assignments.AssignmentRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.model.Assignment
import app.roll.domain.model.Subject
import app.roll.navigation.AssignmentDetailDest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class AssignmentDetailUiState(
    val loading: Boolean = true,
    val item: DeadlineItem? = null,
    val subjects: List<Subject> = emptyList(),
    val canEdit: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
)

@HiltViewModel
class AssignmentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    session: AppSession,
    classData: ClassData,
    time: TimeSource,
    private val assignments: AssignmentRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val assignmentId = savedStateHandle.toRoute<AssignmentDetailDest>().assignmentId
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<AssignmentDetailUiState> = combine(
        combine(session.context, time.ticks(), ::Pair),
        classData.assignments,
        classData.doneIds,
        classData.subjects,
    ) { (ctx, now), list, done, subjects ->
        val zone = zoneOf(ctx.rollClass.timezone)
        val assignment = list?.firstOrNull { it.id == assignmentId }
        AssignmentDetailUiState(
            loading = list == null,
            item = assignment?.let { deadlineItem(it, subjects.orEmpty().associateBy { s -> s.id }, done.orEmpty(), now, zone) },
            subjects = subjects.orEmpty().filter { !it.archived },
            canEdit = ctx.isCR || ctx.isAdmin,
            today = now.atZone(zone).toLocalDate(),
            zone = zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssignmentDetailUiState())

    fun setDone(done: Boolean) {
        val ctx = context.value ?: return
        assignments.setDone(ctx.uid, assignmentId, done)
    }

    fun save(draft: AssignmentDraft) {
        val ctx = context.value ?: return
        val assignment = uiState.value.item?.assignment ?: return
        assignments.update(ctx.classId, assignment, draft)
    }

    fun delete(assignment: Assignment) {
        val ctx = context.value ?: return
        assignments.delete(ctx.classId, assignment)
        messages.show("Deleted ${assignment.title}")
    }
}

private enum class DetailDialog { None, Edit, Delete }

@Composable
fun AssignmentDetailRoute(
    onBack: () -> Unit,
    viewModel: AssignmentDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AssignmentDetailScreen(
        uiState = uiState,
        onBack = onBack,
        onDone = viewModel::setDone,
        onSave = viewModel::save,
        onDelete = {
            viewModel.delete(it)
            onBack()
        },
    )
}

@Composable
fun AssignmentDetailScreen(
    uiState: AssignmentDetailUiState,
    onBack: () -> Unit,
    onDone: (Boolean) -> Unit,
    onSave: (AssignmentDraft) -> Unit,
    onDelete: (Assignment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val context = LocalContext.current
    val item = uiState.item
    var dialog by rememberSaveable { mutableStateOf(DetailDialog.None) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = item?.subject?.shortName ?: "Assignment", onBack = onBack) {
            if (item != null && uiState.canEdit) {
                RollIconButton(icon = RollIcons.Edit, contentDescription = "Edit assignment", onClick = { dialog = DetailDialog.Edit })
                RollIconButton(icon = RollIcons.Delete, contentDescription = "Delete assignment", onClick = { dialog = DetailDialog.Delete })
            }
        }
        if (item == null) {
            if (!uiState.loading) EmptyState(message = "This assignment was removed.")
            return@Column
        }
        val assignment = item.assignment
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl),
        ) {
            Row(Modifier.padding(end = Spacing.gutter)) {
                SubjectBar(colors.subject(item.subject?.colorIndex ?: 0), Modifier.height(48.dp))
                Column(Modifier.padding(start = Spacing.gutter - Sizes.subjectBarWidth)) {
                    Text(
                        text = assignment.title,
                        style = RollTheme.typography.title,
                        color = colors.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = listOfNotNull(assignment.type.label, item.subject?.name).joinToString(" · "),
                        style = RollTheme.typography.caption,
                        color = colors.inkSecondary,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                Text("Due", style = RollTheme.typography.label, color = colors.inkSecondary)
                Text(item.dueText, style = RollTheme.typography.bodyStrong, color = colors.ink)
                Text(
                    text = if (item.done) "Done" else item.relative.text,
                    style = RollTheme.typography.label,
                    color = if (!item.done && item.relative.overdue) colors.warning else colors.inkSecondary,
                )
                if (assignment.description.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.lg))
                    Text("Details", style = RollTheme.typography.label, color = colors.inkSecondary)
                    Text(assignment.description, style = RollTheme.typography.body, color = colors.ink)
                }
            }
            if (assignment.link.isNotBlank()) {
                Spacer(Modifier.height(Spacing.lg))
                HairlineDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.buttonHeight)
                        .clickable(role = Role.Button, onClickLabel = "Open in browser") { context.openUrl(assignment.link) }
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Open link", style = RollTheme.typography.body, color = colors.ink)
                        Text(assignment.link, style = RollTheme.typography.caption, color = colors.inkSecondary, maxLines = 1)
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Icon(painterResource(RollIcons.OpenExternal), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(20.dp))
                }
                HairlineDivider()
            }
            Spacer(Modifier.height(Spacing.xl))
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                if (item.done) {
                    RollOutlinedButton(text = "Mark as not done", onClick = { onDone(false) }, modifier = Modifier.fillMaxWidth())
                } else {
                    RollButton(text = "Mark as done", onClick = { onDone(true) }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "Only you see whether it's done.${if (assignment.createdByName.isNotBlank()) " Posted by ${assignment.createdByName}." else ""}",
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }

    when (dialog) {
        DetailDialog.None -> Unit
        DetailDialog.Edit -> if (item != null) {
            RollBottomSheet(onDismiss = { dialog = DetailDialog.None }) {
                AssignmentEditor(
                    title = "Edit assignment",
                    confirmLabel = "Save",
                    subjects = uiState.subjects,
                    initial = with(item.assignment) { AssignmentDraft(title, subjectId, type, description, link, dueAt) },
                    today = uiState.today,
                    zone = uiState.zone,
                    onSave = {
                        onSave(it)
                        dialog = DetailDialog.None
                    },
                )
            }
        }
        DetailDialog.Delete -> if (item != null) {
            RollDialog(
                title = "Delete ${item.assignment.title}?",
                text = "It disappears for everyone in the class, along with its reminders.",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = {
                    dialog = DetailDialog.None
                    onDelete(item.assignment)
                },
                onDismiss = { dialog = DetailDialog.None },
            )
        }
    }
}
