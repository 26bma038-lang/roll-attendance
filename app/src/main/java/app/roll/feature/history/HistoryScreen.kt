package app.roll.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.TimeSource
import app.roll.core.common.localDateTimeIn
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.attendance.SessionState
import app.roll.feature.common.AttendanceCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/** One dot per counted session on a day. Cancelled and upcoming sessions get no dot. */
enum class Dot { Present, Absent, Unmarked }

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val today: LocalDate = LocalDate.now(),
    val dots: Map<LocalDate, List<Dot>> = emptyMap(),
    /** Earliest month with any session, so the back arrow stops there. */
    val firstMonth: YearMonth? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    session: AppSession,
    classData: ClassData,
    time: TimeSource,
) : ViewModel() {
    private val month = MutableStateFlow<YearMonth?>(null)

    val uiState: StateFlow<HistoryUiState> = combine(
        session.context,
        classData.sessions,
        classData.myMarks,
        time.ticks(),
        month,
    ) { ctx, sessions, myMarks, now, selected ->
        val nowLocal = now.localDateTimeIn(ctx.rollClass.timezone)
        val today = nowLocal.toLocalDate()
        val marks = myMarks?.marks.orEmpty().mapValues { it.value.mark }
        val all = sessions?.sessions.orEmpty()
        val dots = all.groupBy { it.date }.mapValues { (_, day) ->
            day.sortedBy { it.startMinutes }.mapNotNull { s ->
                when (AttendanceCalculator.stateOf(s, marks[s.id], nowLocal)) {
                    SessionState.Present -> Dot.Present
                    SessionState.Absent -> Dot.Absent
                    SessionState.UnmarkedPast -> Dot.Unmarked
                    else -> null
                }
            }
        }
        HistoryUiState(
            month = selected ?: YearMonth.from(today),
            today = today,
            dots = dots,
            firstMonth = all.minOfOrNull { it.date }?.let(YearMonth::from),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun showMonth(month: YearMonth) {
        this.month.value = month
    }
}

@Composable
fun HistoryRoute(
    onOpenDay: (LocalDate) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(uiState, onMonthChange = viewModel::showMonth, onOpenDay = onOpenDay, onBack = onBack)
}

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onMonthChange: (YearMonth) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val month = uiState.month
    val canGoBack = uiState.firstMonth == null || month > uiState.firstMonth
    val canGoForward = month < YearMonth.from(uiState.today)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        RollTopAppBar(title = "History", onBack = onBack)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.xs),
        ) {
            Text(
                text = AttendanceCopy.monthYear(month.atDay(1)),
                style = RollTheme.typography.bodyStrong,
                color = colors.ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            RollIconButton(
                icon = RollIcons.ChevronLeft,
                contentDescription = "Previous month",
                onClick = { if (canGoBack) onMonthChange(month.minusMonths(1)) },
                tint = if (canGoBack) colors.ink else colors.cancelled,
            )
            RollIconButton(
                icon = RollIcons.ChevronRight,
                contentDescription = "Next month",
                onClick = { if (canGoForward) onMonthChange(month.plusMonths(1)) },
                tint = if (canGoForward) colors.ink else colors.cancelled,
            )
        }
        MonthGrid(
            month = month,
            today = uiState.today,
            dots = uiState.dots,
            onDayClick = onOpenDay,
            modifier = Modifier.background(colors.surface),
        )
        Legend()
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    dots: Map<LocalDate, List<Dot>>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val leading = month.atDay(1).dayOfWeek.value - 1 // Monday first
    val cells = leading + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    Column(modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm)) {
        Row(Modifier.clearAndSetSemantics { }) {
            DayOfWeek.entries.forEach { day ->
                Text(
                    text = day.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
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
                    val dayNumber = row * 7 + column - leading + 1
                    Box(Modifier.weight(1f)) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            DayCell(
                                date = date,
                                isToday = date == today,
                                isFuture = date.isAfter(today),
                                dots = dots[date].orEmpty(),
                                onClick = { onDayClick(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isFuture: Boolean,
    dots: List<Dot>,
    onClick: () -> Unit,
) {
    val colors = RollTheme.colors
    val present = dots.count { it == Dot.Present }
    val absent = dots.count { it == Dot.Absent }
    val unmarked = dots.count { it == Dot.Unmarked }
    val description = buildString {
        append(AttendanceCopy.shortDate(date))
        if (dots.isEmpty()) append(", no classes") else {
            if (present > 0) append(", $present present")
            if (absent > 0) append(", $absent absent")
            if (unmarked > 0) append(", $unmarked not marked")
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = !isFuture, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = Spacing.xs),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .then(if (isToday) Modifier.border(1.dp, colors.ink, RollShapes.small) else Modifier),
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = if (isToday) RollTheme.typography.label.copy(fontWeight = FontWeight.Medium) else RollTheme.typography.label,
                color = if (isFuture) colors.cancelled else colors.ink,
            )
        }
        Spacer(Modifier.height(Spacing.xxs))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
            dots.take(6).forEach { DotMark(it) }
        }
    }
}

@Composable
private fun DotMark(dot: Dot) {
    val colors = RollTheme.colors
    val base = Modifier.size(5.dp)
    Box(
        when (dot) {
            Dot.Present -> base.background(colors.ink, CircleShape)
            Dot.Absent -> base.background(colors.warning, CircleShape)
            Dot.Unmarked -> base.border(1.dp, colors.inkSecondary, CircleShape)
        },
    )
}

@Composable
private fun Legend() {
    val colors = RollTheme.colors
    Column(Modifier.padding(Spacing.gutter)) {
        HairlineDivider(Modifier.padding(bottom = Spacing.md))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { },
        ) {
            listOf(Dot.Present to "Present", Dot.Absent to "Absent", Dot.Unmarked to "Not marked").forEach { (dot, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotMark(dot)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(text = label, style = RollTheme.typography.caption, color = colors.inkSecondary)
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Tap a day to see its classes. Marks can be changed for 2 days after a class.",
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
    }
}
