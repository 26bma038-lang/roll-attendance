package app.roll.feature.subjects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.component.BigStat
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.Stepper
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatClock
import app.roll.core.util.formatPercent
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.attendance.AttendanceStatus
import app.roll.domain.attendance.SessionState
import app.roll.domain.attendance.Tally
import app.roll.domain.model.Session
import app.roll.domain.model.Subject
import app.roll.feature.common.AttendanceCopy
import app.roll.navigation.SubjectDetailDest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class SessionLine(val session: Session, val state: SessionState)

data class SubjectDetailUiState(
    val loading: Boolean = true,
    val subject: Subject? = null,
    val tally: Tally = Tally(),
    val status: AttendanceStatus = AttendanceStatus.NoClasses,
    val requiredPercent: Int = 75,
    /** Most recent month first; sessions within a month newest first. */
    val months: List<Pair<YearMonth, List<SessionLine>>> = emptyList(),
)

@HiltViewModel
class SubjectDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    session: AppSession,
    classData: ClassData,
    time: TimeSource,
) : ViewModel() {
    private val subjectId = savedStateHandle.toRoute<SubjectDetailDest>().subjectId

    val uiState: StateFlow<SubjectDetailUiState> = combine(
        session.context,
        classData.sessions,
        classData.subjects,
        classData.myMarks,
        time.ticks(),
    ) { ctx, sessions, subjects, myMarks, now ->
        val subject = subjects?.firstOrNull { it.id == subjectId }
        if (sessions == null || myMarks == null || subject == null) return@combine SubjectDetailUiState(loading = subjects == null)
        val nowLocal = now.localDateTimeIn(ctx.rollClass.timezone)
        val marks = myMarks.marks.mapValues { it.value.mark }
        val mine = sessions.sessions.filter { it.subjectId == subjectId }
        val tally = AttendanceCalculator.tally(mine, marks, nowLocal)
        SubjectDetailUiState(
            loading = false,
            subject = subject,
            tally = tally,
            status = AttendanceCalculator.status(tally, ctx.rollClass.requiredPercent),
            requiredPercent = ctx.rollClass.requiredPercent,
            months = mine
                .sortedWith(compareByDescending<Session> { it.date }.thenByDescending { it.startMinutes })
                .map { SessionLine(it, AttendanceCalculator.stateOf(it, marks[it.id], nowLocal)) }
                .groupBy { YearMonth.from(it.session.date) }
                .toList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubjectDetailUiState())
}

@Composable
fun SubjectDetailRoute(
    onBack: () -> Unit,
    viewModel: SubjectDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SubjectDetailScreen(uiState = uiState, onBack = onBack)
}

@Composable
fun SubjectDetailScreen(
    uiState: SubjectDetailUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val subject = uiState.subject
    var missNext by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = subject?.shortName ?: "Subject", onBack = onBack)
        if (subject == null) {
            if (!uiState.loading) EmptyState(message = "This subject was removed.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                val tally = uiState.tally
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    Text(text = subject.name, style = RollTheme.typography.body, color = colors.ink)
                    val meta = listOf(subject.code, subject.faculty).filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(text = meta, style = RollTheme.typography.caption, color = colors.inkSecondary)
                    }
                    Spacer(Modifier.height(Spacing.lg))
                    BigStat(
                        value = tally.percent?.let(::formatPercent) ?: "—",
                        caption = listOfNotNull(
                            "${tally.attended} of ${tally.total}",
                            AttendanceCopy.unmarkedNote(tally.unmarkedPastCount),
                        ).joinToString(" · "),
                        warning = AttendanceCopy.isBelow(uiState.status),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = AttendanceCopy.status(uiState.status, uiState.requiredPercent),
                        style = RollTheme.typography.bodyStrong,
                        color = if (AttendanceCopy.isBelow(uiState.status)) colors.warning else colors.ink,
                    )
                }
            }
            item(key = "projection") {
                Projection(
                    tally = uiState.tally,
                    requiredPercent = uiState.requiredPercent,
                    missNext = missNext,
                    onMissNextChange = { missNext = it },
                )
            }
            if (uiState.months.isEmpty()) {
                item(key = "empty") { EmptyState(message = "No classes posted for ${subject.shortName} yet.") }
            }
            uiState.months.forEach { (month, lines) ->
                item(key = "month-$month") {
                    SectionHeader(AttendanceCopy.monthYear(month.atDay(1)))
                }
                itemsIndexed(lines, key = { _, it -> it.session.id }) { index, line ->
                    Column(Modifier.background(colors.surface)) {
                        if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                        HistoryLine(line, colors.subject(subject.colorIndex))
                    }
                }
            }
        }
    }
}

@Composable
private fun Projection(
    tally: Tally,
    requiredPercent: Int,
    missNext: Int,
    onMissNextChange: (Int) -> Unit,
) {
    val colors = RollTheme.colors
    val projected = AttendanceCalculator.projectedPercent(tally.attended, tally.total, missNext)
    val stillMeets = AttendanceCalculator.meets(tally.attended, tally.total + missNext, requiredPercent)
    Column(Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xl)) {
        Text(text = "If I miss the next", style = RollTheme.typography.label, color = colors.inkSecondary)
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper(
                value = missNext,
                onValueChange = onMissNextChange,
                range = 0..50,
                valueDescription = { "Miss the next ${AttendanceCopy.classes(it)}" },
            )
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(
                    text = projected?.let(::formatPercent) ?: "—",
                    style = RollTheme.typography.title,
                    color = if (projected != null && !stillMeets) colors.warning else colors.ink,
                )
                Text(
                    text = "after missing ${AttendanceCopy.classes(missNext)}",
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                )
            }
        }
    }
}

@Composable
private fun HistoryLine(line: SessionLine, subjectColor: Color) {
    val colors = RollTheme.colors
    val session = line.session
    val stateColor = when (line.state) {
        SessionState.Present -> colors.accent
        SessionState.Absent, SessionState.UnmarkedPast -> colors.warning
        else -> colors.inkSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { },
    ) {
        SubjectBar(if (line.state == SessionState.Cancelled) colors.cancelled else subjectColor)
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.gutter - Sizes.subjectBarWidth, top = Spacing.sm, bottom = Spacing.sm),
        ) {
            Text(
                text = AttendanceCopy.shortDate(session.date),
                style = RollTheme.typography.body,
                color = colors.ink,
            )
            val details = listOfNotNull(
                "${formatClock(session.startMinutes)}–${formatClock(session.endMinutes)}",
                AttendanceCopy.typeLabel(session.type),
                if (session.weight > 1) "counts as ${session.weight}" else null,
                session.note.ifBlank { null },
            ).joinToString(" · ")
            Text(text = details, style = RollTheme.typography.caption, color = colors.inkSecondary)
        }
        Text(
            text = AttendanceCopy.stateLabel(line.state),
            style = RollTheme.typography.label,
            color = stateColor,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .padding(end = Spacing.gutter),
        )
    }
}
