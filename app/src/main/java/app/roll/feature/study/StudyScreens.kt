package app.roll.feature.study

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roll.R
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.openUrl
import app.roll.domain.materials.MaterialItem
import app.roll.domain.materials.Sections
import app.roll.domain.materials.Study
import app.roll.domain.materials.StudyGroup

// ---------- Study home ----------

@Composable
fun StudyRoute(
    onOpenGroup: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: StudyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    StudyScreen(
        uiState = uiState,
        onQueryChange = viewModel::setQuery,
        onOpenGroup = onOpenGroup,
        onOpenUrl = { if (!context.openUrl(it)) viewModel.cannotOpen() },
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun StudyScreen(
    uiState: StudyUiState,
    onQueryChange: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = "Study") {
            RollIconButton(
                icon = RollIcons.Settings,
                contentDescription = stringResource(R.string.action_settings),
                onClick = onOpenSettings,
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            when (uiState.status) {
                StudyStatus.Loading -> item(key = "loading") { SkeletonList(rows = 6, showTrailing = false) }
                StudyStatus.NotHereYet -> item(key = "empty") { EmptyState("Study material for your year isn't here yet.") }
                StudyStatus.Offline -> item(key = "offline") {
                    EmptyState("You're offline. Study material loads the first time you connect, then works offline.")
                }
                StudyStatus.Failed -> item(key = "failed") { EmptyState("Couldn't load study material. Try again later.") }
                StudyStatus.Ready -> {
                    items(uiState.credits, key = { "credit:${it.classroomUrl}:${it.credit}" }) { credit ->
                        CreditLine(credit, onOpenUrl)
                    }
                    item(key = "search") {
                        RollTextField(
                            value = uiState.query,
                            onValueChange = onQueryChange,
                            label = "Search",
                            supporting = if (uiState.searching) null else "Subject code, subject or file name",
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        )
                    }
                    if (uiState.searching) {
                        searchResults(uiState, onOpenUrl)
                    } else {
                        groups(uiState, onOpenGroup)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditLine(credit: StudyCredit, onOpenUrl: (String) -> Unit) {
    val colors = RollTheme.colors
    Column(Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs)) {
        Text(text = credit.credit, style = RollTheme.typography.body, color = colors.ink)
        Text(
            text = "Files open in Google Drive. If one won't open, join the class first.",
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
        if (credit.classroomUrl.isNotBlank()) {
            RollTextButton(text = "Open Classroom", onClick = { onOpenUrl(credit.classroomUrl) }, alignTextToStart = true)
        }
    }
}

private fun LazyListScope.groups(uiState: StudyUiState, onOpenGroup: (String) -> Unit) {
    if (uiState.subjects.isNotEmpty()) {
        item(key = "subjects") {
            SectionHeader("Subjects")
            ListGroup {
                uiState.subjects.forEachIndexed { index, row ->
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    SubjectRow(row, onClick = { onOpenGroup(row.group.key) })
                }
            }
        }
    }
    if (uiState.others.isNotEmpty()) {
        item(key = "others") {
            SectionHeader("Also in the class")
            ListGroup {
                uiState.others.forEachIndexed { index, row ->
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    GroupRow(title = row.group.title, subtitle = filesLabel(row.fileCount), onClick = { onOpenGroup(row.group.key) })
                }
            }
        }
    }
}

private fun LazyListScope.searchResults(uiState: StudyUiState, onOpenUrl: (String) -> Unit) {
    if (uiState.results.isEmpty()) {
        item(key = "no-results") { EmptyState("Nothing matches “${uiState.query.trim()}”.") }
        return
    }
    item(key = "results-header") { SectionHeader(filesLabel(uiState.results.size)) }
    items(uiState.results, key = { "result:${it.id}" }) { item ->
        Column(Modifier.fillMaxWidth()) {
            FileRow(
                title = item.title,
                subtitle = listOfNotNull(
                    item.subjectCode ?: item.subjectName,
                    Sections.labels[item.section] ?: item.sectionLabel,
                ).distinct().joinToString(" · "),
                onClick = { onOpenUrl(item.url) },
            )
            HairlineDivider(startIndent = Spacing.gutter)
        }
    }
}

@Composable
private fun SubjectRow(row: StudyGroupRow, onClick: () -> Unit) {
    val colors = RollTheme.colors
    val subject = row.group as StudyGroup.Subject
    val sections = row.sectionLabels.joinToString(", ").lowercase().replaceFirstChar { it.uppercase() }
    // A bare code ("CS-102") has no name of its own: lead with what it holds instead.
    val name = if (subject.code != null && subject.name == subject.code) sections else subject.name
    val detail = if (name == sections) filesLabel(row.fileCount) else "${filesLabel(row.fileCount)} · ${sections.lowercase()}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(start = Spacing.gutter, end = Spacing.sm),
    ) {
        if (subject.code != null) {
            Text(
                text = subject.code,
                style = RollTheme.typography.rollNo,
                color = colors.ink,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = Spacing.md),
        ) {
            Text(text = name, style = RollTheme.typography.body, color = colors.ink)
            Text(text = detail, style = RollTheme.typography.caption, color = colors.inkSecondary)
        }
        Chevron()
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = RollTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(start = Spacing.gutter, end = Spacing.sm),
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = Spacing.md),
        ) {
            Text(text = title, style = RollTheme.typography.body, color = colors.ink)
            Text(text = subtitle, style = RollTheme.typography.caption, color = colors.inkSecondary)
        }
        Chevron()
    }
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(RollIcons.ChevronRight),
        contentDescription = null,
        tint = RollTheme.colors.inkSecondary,
        modifier = Modifier
            .padding(horizontal = Spacing.sm)
            .size(24.dp),
    )
}

/** One file. Opens in Drive (or the browser) on tap. */
@Composable
private fun FileRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val colors = RollTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(start = Spacing.gutter, end = Spacing.md),
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = Spacing.md),
        ) {
            Text(text = title, style = RollTheme.typography.body, color = colors.ink)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, style = RollTheme.typography.caption, color = colors.inkSecondary)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Icon(
            painter = painterResource(RollIcons.OpenExternal),
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun filesLabel(count: Int): String = if (count == 1) "1 file" else "$count files"

// ---------- one subject or group ----------

@Composable
fun StudyGroupRoute(
    onBack: () -> Unit,
    viewModel: StudyGroupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    StudyGroupScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenUrl = { if (!context.openUrl(it)) viewModel.cannotOpen() },
    )
}

@Composable
fun StudyGroupScreen(
    uiState: StudyGroupUiState,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val group = uiState.group
    Column(modifier.fillMaxSize()) {
        RollTopAppBar(title = group?.title ?: "Study", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            when {
                uiState.status == StudyStatus.Loading -> item(key = "loading") { SkeletonList(rows = 6, showTrailing = false) }
                group == null -> item(key = "gone") { EmptyState("These files are no longer in the class.") }
                else -> {
                    if (group is StudyGroup.Subject && group.code != null && group.name != group.code) {
                        item(key = "name") {
                            Text(
                                text = group.name,
                                style = RollTheme.typography.body,
                                color = colors.inkSecondary,
                                modifier = Modifier
                                    .padding(horizontal = Spacing.gutter)
                                    .semantics { heading() },
                            )
                        }
                    }
                    uiState.blocks.forEachIndexed { index, block ->
                        item(key = "block:$index:${block.title}") {
                            SectionHeader(block.title)
                            ListGroup {
                                block.items.forEachIndexed { i, file ->
                                    if (i > 0) HairlineDivider(startIndent = Spacing.gutter)
                                    val label = Study.paperLabel(file).takeIf { file.section == Sections.PYQ || file.exam != null }
                                    FileRow(
                                        title = label ?: file.title,
                                        subtitle = Study.paperDetail(file).takeIf { label != null },
                                        onClick = { onOpenUrl(file.url) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
