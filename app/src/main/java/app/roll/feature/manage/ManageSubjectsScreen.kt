package app.roll.feature.manage

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.data.session.ClassData
import app.roll.domain.model.Subject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ManageSubjectsUiState(
    val loading: Boolean = true,
    val subjects: List<Subject> = emptyList(),
)

@HiltViewModel
class ManageSubjectsViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    private val schedule: ScheduleRepository,
) : ViewModel() {
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<ManageSubjectsUiState> = classData.subjects
        .map { ManageSubjectsUiState(loading = it == null, subjects = it.orEmpty().sortedWith(compareBy({ s -> s.archived }, { s -> s.name.lowercase() }))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageSubjectsUiState())

    fun save(subject: Subject) {
        val ctx = context.value ?: return
        schedule.saveSubject(ctx.classId, subject)
    }
}

@Composable
fun ManageSubjectsRoute(
    onBack: () -> Unit,
    viewModel: ManageSubjectsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ManageSubjectsScreen(uiState, onBack, onSave = viewModel::save)
}

@Composable
fun ManageSubjectsScreen(
    uiState: ManageSubjectsUiState,
    onBack: () -> Unit,
    onSave: (Subject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // id, "" for new
    val firstArchived = uiState.subjects.indexOfFirst { it.archived }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "Subjects", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), rows = 3, showTrailing = false) }
                uiState.subjects.isEmpty() -> item { EmptyState(message = "No subjects yet.") }
                else -> itemsIndexed(uiState.subjects, key = { _, it -> it.id }) { index, subject ->
                    Column(Modifier.background(colors.surface)) {
                        if (index == firstArchived) {
                            SectionHeader("Archived", Modifier.background(colors.background))
                        } else if (index > 0) {
                            HairlineDivider(startIndent = Spacing.gutter)
                        }
                        SubjectListRow(subject, onClick = { editing = subject.id })
                    }
                }
            }
            item {
                RollOutlinedButton(
                    text = "+ Add subject",
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
        val existing = uiState.subjects.firstOrNull { it.id == editingId }
        RollBottomSheet(onDismiss = { editing = null }) {
            SubjectEditor(
                initial = existing ?: Subject(
                    id = "",
                    name = "",
                    shortName = "",
                    code = "",
                    faculty = "",
                    // Next unused colour, so new subjects are told apart by default.
                    colorIndex = uiState.subjects.size % colors.subjects.size,
                    archived = false,
                ),
                onSave = {
                    onSave(it)
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun SubjectListRow(subject: Subject, onClick: () -> Unit) {
    val colors = RollTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Sizes.rowMinHeight)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { },
    ) {
        SubjectBar(if (subject.archived) colors.cancelled else colors.subject(subject.colorIndex))
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.gutter - Sizes.subjectBarWidth, end = Spacing.gutter, top = Spacing.md, bottom = Spacing.md),
        ) {
            Text(text = "${subject.shortName}  ·  ${subject.name}", style = RollTheme.typography.bodyStrong, color = colors.ink)
            val meta = listOf(subject.code, subject.faculty).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(text = meta, style = RollTheme.typography.caption, color = colors.inkSecondary)
        }
    }
}
