package app.roll.feature.subjects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.R
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.component.BigStat
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.component.SubjectRow
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatPercent
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.attendance.AttendanceStatus
import app.roll.domain.attendance.Tally
import app.roll.domain.model.Mark
import app.roll.domain.model.Session
import app.roll.domain.model.Subject
import app.roll.feature.common.AttendanceCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDateTime
import javax.inject.Inject

data class SubjectSummary(
    val subject: Subject,
    val tally: Tally,
    val status: AttendanceStatus,
)

/** Per-subject tallies plus the overall one, at [requiredPercent]. Archived subjects with no classes are dropped. */
fun summarize(
    subjects: List<Subject>,
    sessions: List<Session>,
    marks: Map<String, Mark>,
    now: LocalDateTime,
    requiredPercent: Int,
): Pair<List<SubjectSummary>, Tally> {
    val bySubject = sessions.groupBy { it.subjectId }
    val summaries = subjects.mapNotNull { subject ->
        val tally = AttendanceCalculator.tally(bySubject[subject.id].orEmpty(), marks, now)
        if (subject.archived && tally.total == 0) return@mapNotNull null
        SubjectSummary(subject, tally, AttendanceCalculator.status(tally, requiredPercent))
    }.sortedWith(compareBy({ it.subject.archived }, { it.subject.name.lowercase() }))
    val overall = AttendanceCalculator.tally(sessions, marks, now)
    return summaries to overall
}

data class SubjectsUiState(
    val loading: Boolean = true,
    val requiredPercent: Int = 75,
    val overall: Tally = Tally(),
    val subjects: List<SubjectSummary> = emptyList(),
    val isCR: Boolean = false,
)

@HiltViewModel
class SubjectsViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    time: TimeSource,
) : ViewModel() {
    val uiState: StateFlow<SubjectsUiState> = combine(
        session.context,
        classData.sessions,
        classData.subjects,
        classData.myMarks,
        time.ticks(),
    ) { ctx, sessions, subjects, myMarks, now ->
        if (sessions == null || subjects == null || myMarks == null) return@combine SubjectsUiState(isCR = ctx.isCR)
        val required = ctx.rollClass.requiredPercent
        val (summaries, overall) = summarize(
            subjects = subjects,
            sessions = sessions.sessions,
            marks = myMarks.marks.mapValues { it.value.mark },
            now = now.localDateTimeIn(ctx.rollClass.timezone),
            requiredPercent = required,
        )
        SubjectsUiState(loading = false, requiredPercent = required, overall = overall, subjects = summaries, isCR = ctx.isCR)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubjectsUiState())
}

@Composable
fun SubjectsRoute(
    onOpenSubject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onManageSubjects: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: SubjectsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SubjectsScreen(uiState, onOpenSubject, onOpenSettings, onManageSubjects, onOpenHistory = onOpenHistory)
}

@Composable
fun SubjectsScreen(
    uiState: SubjectsUiState,
    onOpenSubject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onManageSubjects: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHistory: () -> Unit = {},
) {
    val colors = RollTheme.colors
    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = "Subjects") {
            RollIconButton(icon = RollIcons.History, contentDescription = "History calendar", onClick = onOpenHistory)
            RollIconButton(
                icon = RollIcons.Settings,
                contentDescription = stringResource(R.string.action_settings),
                onClick = onOpenSettings,
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), showTrailing = false) }
                uiState.subjects.isEmpty() -> item {
                    EmptyState(
                        message = if (uiState.isCR) "No subjects yet." else "No subjects yet. Your CR adds them.",
                        actionLabel = if (uiState.isCR) "Add subjects" else null,
                        onAction = if (uiState.isCR) onManageSubjects else null,
                    )
                }
                else -> {
                    item(key = "overall") {
                        val percent = uiState.overall.percent
                        val below = percent != null &&
                            !AttendanceCalculator.meets(uiState.overall.attended, uiState.overall.total, uiState.requiredPercent)
                        BigStat(
                            value = percent?.let(::formatPercent) ?: "—",
                            caption = listOfNotNull(
                                "Overall",
                                "${uiState.overall.attended} of ${uiState.overall.total}",
                                "${uiState.requiredPercent}% required",
                                AttendanceCopy.unmarkedNote(uiState.overall.unmarkedPastCount),
                            ).joinToString(" · "),
                            warning = below,
                            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.lg),
                        )
                    }
                    val firstArchived = uiState.subjects.indexOfFirst { it.subject.archived }
                    itemsIndexed(uiState.subjects, key = { _, it -> it.subject.id }) { index, summary ->
                        Column(Modifier.background(colors.surface)) {
                            if (index == firstArchived) {
                                SectionHeader("Archived", Modifier.background(colors.background))
                            } else if (index > 0) {
                                HairlineDivider(startIndent = Spacing.gutter)
                            }
                            val percent = summary.tally.percent
                            SubjectRow(
                                name = summary.subject.name,
                                attended = summary.tally.attended,
                                total = summary.tally.total,
                                percent = percent ?: 0.0,
                                percentText = percent?.let(::formatPercent) ?: "—",
                                status = AttendanceCopy.status(summary.status, uiState.requiredPercent),
                                belowRequirement = AttendanceCopy.isBelow(summary.status),
                                subjectColor = colors.subject(summary.subject.colorIndex),
                                onClick = { onOpenSubject(summary.subject.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
