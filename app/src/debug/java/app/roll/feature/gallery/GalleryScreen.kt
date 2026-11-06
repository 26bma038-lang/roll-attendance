package app.roll.feature.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roll.R
import app.roll.core.designsystem.component.BigStat
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.PAToggle
import app.roll.core.designsystem.component.RollBottomBar
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollNavItem
import app.roll.core.designsystem.component.RollNumberText
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollSnackbarHost
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SessionRow
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.component.Stat
import app.roll.core.designsystem.component.StatLine
import app.roll.core.designsystem.component.SubjectBar
import app.roll.core.designsystem.component.SubjectRow
import app.roll.core.designsystem.component.showTimed
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.formatDayHeader
import app.roll.core.util.formatPercent
import app.roll.domain.model.Mark
import app.roll.domain.model.ThemeMode
import java.time.LocalDate
import java.time.ZoneId

private val ClassZone: ZoneId = ZoneId.of("Asia/Kolkata")

@Composable
fun GalleryRoute(
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    GalleryScreen(
        uiState = uiState,
        today = LocalDate.now(ClassZone),
        onMarkChange = viewModel::onMarkChange,
        onUndoClear = viewModel::onUndoClear,
        onUndoDismissed = viewModel::onUndoDismissed,
        onNavSelect = viewModel::onNavSelect,
        onThemeModeChange = viewModel::onThemeModeChange,
    )
}

/** Stateless. Every component in the design system, light or dark, with sample data. */
@Composable
fun GalleryScreen(
    uiState: GalleryUiState,
    today: LocalDate,
    onMarkChange: (sessionId: String, mark: Mark?) -> Unit,
    onUndoClear: () -> Unit,
    onUndoDismissed: () -> Unit,
    onNavSelect: (Int) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val markCleared = stringResource(R.string.mark_cleared)
    val undo = stringResource(R.string.action_undo)
    val currentOnUndo by rememberUpdatedState(onUndoClear)
    val currentOnDismiss by rememberUpdatedState(onUndoDismissed)

    LaunchedEffect(uiState.clearedMark) {
        if (uiState.clearedMark != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            if (snackbarHostState.showTimed(markCleared, undo)) currentOnUndo() else currentOnDismiss()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = RollTheme.colors.background,
        topBar = {
            RollTopAppBar(title = "Design system") {
                RollIconButton(
                    icon = RollIcons.Settings,
                    contentDescription = stringResource(R.string.action_settings),
                    onClick = {},
                )
            }
        },
        bottomBar = {
            RollBottomBar(
                items = listOf(
                    RollNavItem(stringResource(R.string.nav_today), RollIcons.Today),
                    RollNavItem(stringResource(R.string.nav_subjects), RollIcons.Subjects),
                    RollNavItem(stringResource(R.string.nav_history), RollIcons.History),
                    RollNavItem(stringResource(R.string.nav_manage), RollIcons.Manage),
                ),
                selectedIndex = uiState.selectedNavIndex,
                onSelect = onNavSelect,
            )
        },
        snackbarHost = { RollSnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    text = "Every component with sample data. Sign-in replaces this screen in phase 3.",
                    style = RollTheme.typography.caption,
                    color = RollTheme.colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
            themeSection(uiState.themeMode, onThemeModeChange)
            todaySection(uiState.sessions, today, onMarkChange)
            subjectsSection(uiState.subjects)
            manageSection()
            toggleSection()
            stateSection()
            typeSection()
            colourSection()
        }
    }
}

private fun LazyListScope.themeSection(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    item { SectionHeader("Theme") }
    item {
        Column(
            Modifier
                .background(RollTheme.colors.surface)
                .selectableGroup(),
        ) {
            ThemeMode.entries.forEachIndexed { index, option ->
                if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.buttonHeight)
                        .selectable(selected = option == mode, role = Role.RadioButton, onClick = { onChange(option) })
                        .padding(horizontal = Spacing.gutter),
                ) {
                    Text(
                        text = option.name,
                        style = RollTheme.typography.body,
                        color = RollTheme.colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (option == mode) {
                        Icon(
                            painter = painterResource(RollIcons.Check),
                            contentDescription = null,
                            tint = RollTheme.colors.accent,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.todaySection(
    sessions: List<SampleSession>,
    today: LocalDate,
    onMarkChange: (String, Mark?) -> Unit,
) {
    item { SectionHeader("Session rows") }
    item {
        Text(
            text = formatDayHeader(today),
            style = RollTheme.typography.title,
            color = RollTheme.colors.ink,
            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.md),
        )
    }
    itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
        Column(Modifier.background(RollTheme.colors.surface)) {
            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
            SessionRow(
                startMinutes = session.startMinutes,
                endMinutes = session.endMinutes,
                shortName = session.shortName,
                fullName = session.fullName,
                room = session.room,
                type = session.type,
                subjectColor = RollTheme.colors.subject(session.colorIndex),
                mark = session.mark,
                onMarkChange = { onMarkChange(session.id, it) },
                cancelled = session.cancelled,
                note = session.note,
            )
        }
    }
}

private fun LazyListScope.subjectsSection(subjects: List<SampleSubject>) {
    item { SectionHeader("Subject rows") }
    item {
        BigStat(
            value = formatPercent(SampleData.OVERALL_PERCENT),
            caption = "Overall · ${SampleData.OVERALL_ATTENDED} of ${SampleData.OVERALL_TOTAL} · includes 2 unmarked",
            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.lg),
        )
    }
    itemsIndexed(subjects) { index, subject ->
        Column(Modifier.background(RollTheme.colors.surface)) {
            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
            SubjectRow(
                name = subject.name,
                attended = subject.attended,
                total = subject.total,
                percent = subject.percent,
                status = subject.status,
                belowRequirement = subject.belowRequirement,
                subjectColor = RollTheme.colors.subject(subject.colorIndex),
                onClick = {},
            )
        }
    }
}

private fun LazyListScope.manageSection() {
    item { SectionHeader("Counts and buttons") }
    item {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        ) {
            StatLine(listOf(Stat("38", "present"), Stat("4", "absent"), Stat("12", "not marked")))
            RollButton(text = "Post today from timetable", onClick = {}, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RollOutlinedButton(text = "Add class", onClick = {})
                RollTextButton(text = "Cancel class", onClick = {}, destructive = true)
            }
        }
    }
    item {
        Column(
            Modifier
                .padding(top = Spacing.lg)
                .background(RollTheme.colors.surface),
        ) {
            listOf("Aarav Mehta" to "23BCS1002", "Diya Rao" to "23BCS1017", "Kabir Singh" to "23BCS1042")
                .forEachIndexed { index, (name, rollNo) ->
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.rowMinHeight)
                            .padding(horizontal = Spacing.gutter),
                    ) {
                        RollNumberText(rollNo = rollNo, modifier = Modifier.widthIn(min = 96.dp))
                        Spacer(Modifier.width(Spacing.md))
                        Text(text = name, style = RollTheme.typography.body, color = RollTheme.colors.ink)
                    }
                }
        }
    }
}

private fun LazyListScope.toggleSection() {
    item { SectionHeader("P / A toggle") }
    item {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        ) {
            PAToggle(mark = null, onMarkChange = {})
            PAToggle(mark = Mark.Present, onMarkChange = {})
            PAToggle(mark = Mark.Absent, onMarkChange = {}, enabled = false)
        }
    }
    item {
        Text(
            text = "Unmarked, present, absent outside the edit window.",
            style = RollTheme.typography.caption,
            color = RollTheme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        )
    }
}

private fun LazyListScope.stateSection() {
    item { SectionHeader("Empty and loading") }
    item {
        Column(Modifier.background(RollTheme.colors.surface)) {
            EmptyState(message = "No classes posted yet for today.")
            HairlineDivider()
            SkeletonList(rows = 3)
        }
    }
}

private fun LazyListScope.typeSection() {
    item { SectionHeader("Type") }
    item {
        val t = RollTheme.typography
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        ) {
            TypeSample("Display 40/44", "76.6%", t.display)
            TypeSample("Title 20/26", "Thursday, 5 Nov", t.title)
            TypeSample("Body 16/22", "You're at 71.4% in DAA", t.body)
            TypeSample("Label 13/18", "Posted by Sahil · 9:12 am", t.label)
            TypeSample("Caption 12/16", "Cancelled — faculty on leave", t.caption)
            TypeSample("Mono", "K7MP2X  23BCS1042", t.rollNo)
        }
    }
}

@Composable
private fun TypeSample(name: String, sample: String, style: TextStyle) {
    Column {
        Text(text = name, style = RollTheme.typography.caption, color = RollTheme.colors.inkSecondary)
        Text(text = sample, style = style, color = RollTheme.colors.ink)
    }
}

private fun LazyListScope.colourSection() {
    item { SectionHeader("Colour") }
    item {
        val c = RollTheme.colors
        val tokens = listOf(
            "Background" to c.background, "Surface" to c.surface, "Ink" to c.ink,
            "Ink secondary" to c.inkSecondary, "Hairline" to c.hairline, "Outline" to c.outline,
            "Accent" to c.accent, "Warning" to c.warning, "Cancelled" to c.cancelled,
        )
        Column(Modifier.background(c.surface)) {
            tokens.forEachIndexed { index, (name, color) ->
                if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                Swatch(name, color)
            }
        }
    }
    item {
        Text(
            text = "Subject palette, used only as the 3dp bar",
            style = RollTheme.typography.caption,
            color = RollTheme.colors.inkSecondary,
            modifier = Modifier.padding(start = Spacing.gutter, top = Spacing.lg, bottom = Spacing.sm),
        )
    }
    item {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        ) {
            RollTheme.colors.subjects.forEach { color ->
                Box(Modifier.size(width = Sizes.subjectBarWidth, height = Sizes.rowMinHeight)) {
                    SubjectBar(color = color)
                }
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.buttonHeight)
            .padding(horizontal = Spacing.gutter),
    ) {
        Box(
            Modifier
                .size(Sizes.icon)
                .background(color, RollShapes.small),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(text = name, style = RollTheme.typography.body, color = RollTheme.colors.ink, modifier = Modifier.weight(1f))
        Text(
            text = "#%06X".format(color.toArgb() and 0xFFFFFF),
            style = RollTheme.typography.label,
            color = RollTheme.colors.inkSecondary,
        )
    }
}

@Preview(heightDp = 2400)
@Composable
private fun GalleryScreenPreview() {
    RollPreviewSurface {
        GalleryScreen(
            uiState = GalleryUiState(),
            today = LocalDate.of(2026, 11, 5),
            onMarkChange = { _, _ -> },
            onUndoClear = {},
            onUndoDismissed = {},
            onNavSelect = {},
            onThemeModeChange = {},
        )
    }
}
