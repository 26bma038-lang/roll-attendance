package app.roll.feature.subjects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatPercent
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.data.session.ClassContext
import app.roll.domain.model.Subject
import app.roll.domain.planning.LeaveImpact
import app.roll.domain.planning.LeavePlanner
import app.roll.feature.common.AttendanceCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class LeaveRow(val subject: Subject, val impact: LeaveImpact)

data class LeavePlannerUiState(
    val today: LocalDate = LocalDate.now(),
    val month: YearMonth = YearMonth.now(),
    val selected: Set<LocalDate> = emptySet(),
    val requiredPercent: Int = 75,
    /** Subjects with classes on the leave days first. */
    val rows: List<LeaveRow> = emptyList(),
)

private data class PlannerInputs(
    val context: ClassContext,
    val now: Instant,
    val dates: Set<LocalDate>,
    val month: YearMonth?,
)

@HiltViewModel
class LeavePlannerViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    time: TimeSource,
) : ViewModel() {
    private val selected = MutableStateFlow<Set<LocalDate>>(emptySet())
    private val month = MutableStateFlow<YearMonth?>(null)

    val uiState: StateFlow<LeavePlannerUiState> = combine(
        combine(session.context, time.ticks(), selected, month, ::PlannerInputs),
        classData.sessions,
        classData.timetable,
        classData.subjects,
        classData.myMarks,
    ) { (ctx, instant, dates, shownMonth), sessions, slots, subjects, marks ->
        val now = instant.localDateTimeIn(ctx.rollClass.timezone)
        val active = subjects.orEmpty().filter { !it.archived }
        val impacts = LeavePlanner.plan(
            leaveDates = dates,
            subjectIds = active.map { it.id },
            sessions = sessions?.sessions.orEmpty(),
            slots = slots.orEmpty(),
            marks = marks?.marks.orEmpty().mapValues { it.value.mark },
            now = now,
            requiredPercent = ctx.rollClass.requiredPercent,
        )
        LeavePlannerUiState(
            today = now.toLocalDate(),
            month = shownMonth ?: YearMonth.from(now),
            selected = dates,
            requiredPercent = ctx.rollClass.requiredPercent,
            rows = active.zip(impacts) { s, i -> LeaveRow(s, i) }
                .sortedWith(compareByDescending<LeaveRow> { it.impact.missed > 0 }.thenBy { it.subject.name.lowercase() }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LeavePlannerUiState())

    fun toggle(date: LocalDate) {
        selected.update { if (date in it) it - date else it + date }
    }

    fun clear() {
        selected.value = emptySet()
    }

    fun showMonth(target: YearMonth) {
        month.value = target
    }
}

@Composable
fun LeavePlannerRoute(
    onBack: () -> Unit,
    viewModel: LeavePlannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LeavePlannerScreen(uiState, onBack, viewModel::toggle, viewModel::clear, viewModel::showMonth)
}

@Composable
fun LeavePlannerScreen(
    uiState: LeavePlannerUiState,
    onBack: () -> Unit,
    onToggle: (LocalDate) -> Unit,
    onClear: () -> Unit,
    onMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val month = uiState.month
    val thisMonth = YearMonth.from(uiState.today)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "Plan leave", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "intro") {
                Text(
                    text = "Pick the days you'll be away. Roll assumes you attend every other class until then.",
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
            item(key = "month") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.xs, top = Spacing.md),
                ) {
                    Text(
                        text = AttendanceCopy.monthYear(month.atDay(1)),
                        style = RollTheme.typography.bodyStrong,
                        color = colors.ink,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    val canGoBack = month > thisMonth
                    val canGoForward = month < thisMonth.plusMonths(3)
                    RollIconButton(
                        icon = RollIcons.ChevronLeft,
                        contentDescription = "Previous month",
                        onClick = { if (canGoBack) onMonth(month.minusMonths(1)) },
                        tint = if (canGoBack) colors.ink else colors.cancelled,
                    )
                    RollIconButton(
                        icon = RollIcons.ChevronRight,
                        contentDescription = "Next month",
                        onClick = { if (canGoForward) onMonth(month.plusMonths(1)) },
                        tint = if (canGoForward) colors.ink else colors.cancelled,
                    )
                }
                SelectableMonth(month, uiState.today, uiState.selected, onToggle)
            }
            item(key = "summaryHeader") {
                SectionHeader(
                    if (uiState.selected.isEmpty()) {
                        "No days picked"
                    } else {
                        "${uiState.selected.size} ${if (uiState.selected.size == 1) "day" else "days"} away"
                    },
                ) {
                    if (uiState.selected.isNotEmpty()) RollTextButton(text = "Clear", onClick = onClear)
                }
            }
            if (uiState.rows.isEmpty()) {
                item { EmptyState(message = "No subjects yet. Your CR adds them.") }
            }
            itemsIndexed(uiState.rows, key = { _, it -> it.subject.id }) { index, row ->
                Column(Modifier.background(colors.surface)) {
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    ImpactRow(row, uiState.requiredPercent, anyPicked = uiState.selected.isNotEmpty())
                }
            }
        }
    }
}

@Composable
private fun ImpactRow(row: LeaveRow, requiredPercent: Int, anyPicked: Boolean) {
    val colors = RollTheme.colors
    val impact = row.impact
    val below = impact.projectedPercent != null && !impact.meetsRequirement
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = Sizes.rowTallHeight)
            .semantics(mergeDescendants = true) { },
    ) {
        SubjectBar(colors.subject(row.subject.colorIndex))
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.gutter - Sizes.subjectBarWidth, top = Spacing.md, bottom = Spacing.md),
        ) {
            Text(row.subject.name, style = RollTheme.typography.bodyStrong, color = colors.ink)
            Text(
                text = when {
                    !anyPicked -> "Now ${impact.current.percent?.let(::formatPercent) ?: "—"}"
                    impact.missed == 0 -> "No classes on those days"
                    else -> "Misses ${AttendanceCopy.classes(impact.missed)}" +
                        (if (below) " · below $requiredPercent%" else "")
                },
                style = RollTheme.typography.caption,
                color = if (below && anyPicked) colors.warning else colors.inkSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.md, end = Spacing.gutter)) {
            Text(
                text = impact.projectedPercent?.let(::formatPercent) ?: "—",
                style = RollTheme.typography.bodyStrong,
                color = if (below) colors.warning else colors.ink,
                textAlign = TextAlign.End,
            )
            Text(
                text = "now ${impact.current.percent?.let(::formatPercent) ?: "—"}",
                style = RollTheme.typography.caption,
                color = colors.inkSecondary,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A month grid where future days toggle on and off. Selected days are filled with ink. */
@Composable
private fun SelectableMonth(
    month: YearMonth,
    today: LocalDate,
    selected: Set<LocalDate>,
    onToggle: (LocalDate) -> Unit,
) {
    val colors = RollTheme.colors
    val leading = month.atDay(1).dayOfWeek.value - 1
    val rows = (leading + month.lengthOfMonth() + 6) / 7
    Column(
        Modifier
            .background(colors.surface)
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
    ) {
        Row(Modifier.clearAndSetSemantics { }) {
            DayOfWeek.entries.forEach {
                Text(
                    text = it.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Spacing.xs),
                )
            }
        }
        repeat(rows) { row ->
            Row {
                repeat(7) { column ->
                    val day = row * 7 + column - leading + 1
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Sizes.minTouchTarget),
                    ) {
                        if (day in 1..month.lengthOfMonth()) {
                            val date = month.atDay(day)
                            val past = date.isBefore(today)
                            val isSelected = date in selected
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .then(
                                        when {
                                            isSelected -> Modifier.background(colors.ink, RollShapes.small)
                                            date == today -> Modifier.border(1.dp, colors.ink, RollShapes.small)
                                            else -> Modifier
                                        },
                                    )
                                    .toggleable(value = isSelected, enabled = !past, role = Role.Checkbox, onValueChange = { onToggle(date) })
                                    .semantics { contentDescription = "${AttendanceCopy.shortDate(date)}${if (isSelected) ", away" else ""}" },
                            ) {
                                Text(
                                    text = day.toString(),
                                    style = RollTheme.typography.label,
                                    color = when {
                                        isSelected -> colors.background
                                        past -> colors.cancelled
                                        else -> colors.ink
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
    }
}
