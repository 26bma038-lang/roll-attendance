package app.roll.feature.notices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.TimeSource
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.PickerField
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollDatePickerDialog
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollShapes
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.notices.NoticeDraft
import app.roll.data.notices.NoticeRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.domain.college.NithConfig
import app.roll.domain.model.Notice
import app.roll.feature.common.AttendanceCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private val Ist: ZoneId = ZoneId.of(NithConfig.TIMEZONE)

/** "College · Admin · 4 Nov" or "Sahil · 4 Nov". */
fun noticeMeta(notice: Notice, includeSource: Boolean = true): String = listOfNotNull(
    if (includeSource && notice.college) "College" else null,
    notice.createdByName.ifBlank { null },
    notice.createdAt?.let { AttendanceCopy.shortDate(it.atZone(Ist).toLocalDate()) },
    notice.expiresAt?.let { "until ${AttendanceCopy.shortDate(it.atZone(Ist).toLocalDate())}" },
).joinToString(" · ")

/** Pinned, unexpired notices for the top of Today: college first, then class, newest first. */
fun pinnedNotices(classNotices: List<Notice>, collegeNotices: List<Notice>, now: Instant): List<Notice> =
    (collegeNotices + classNotices).filter { it.pinned && it.isActive(now) }

/** Every unexpired notice, newest first, for the Department tab. */
fun activeNotices(classNotices: List<Notice>, collegeNotices: List<Notice>, now: Instant): List<Notice> =
    (collegeNotices + classNotices).filter { it.isActive(now) }.sortedByDescending { it.createdAt ?: Instant.MAX }

/** A quiet bordered block (no shadow, not a card) for pinned notices on Today. */
@Composable
fun PinnedNoticesBlock(notices: List<Notice>, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    val colors = RollTheme.colors
    Column(
        modifier
            .padding(horizontal = Spacing.gutter, vertical = Spacing.sm)
            .fillMaxWidth()
            .border(1.dp, colors.outline, RollShapes.control),
    ) {
        notices.forEachIndexed { index, notice ->
            if (index > 0) HairlineDivider()
            Column(
                Modifier
                    .padding(Spacing.md)
                    .semantics(mergeDescendants = true) { },
            ) {
                Text(
                    text = if (notice.college) "College notice" else "Class notice",
                    style = RollTheme.typography.label,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(Spacing.xxs))
                Text(text = notice.text, style = RollTheme.typography.body, color = colors.ink)
                Text(text = noticeMeta(notice, includeSource = false), style = RollTheme.typography.caption, color = colors.inkSecondary)
            }
        }
    }
}

/** One notice in a list. [onClick] opens pin / delete for people who can post. */
@Composable
fun NoticeRow(notice: Notice, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = RollTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinHeight)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Notice options", onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
    ) {
        Text(text = notice.text, style = RollTheme.typography.body, color = colors.ink)
        Text(
            text = listOfNotNull(noticeMeta(notice), if (notice.pinned) "Pinned" else null).joinToString(" · "),
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
        )
    }
}

/** Notices section for the Department tab, with "+ Post" for CRs. */
@Composable
fun NoticesSection(
    notices: List<Notice>,
    canPost: Boolean,
    onPost: () -> Unit,
    onSelect: (Notice) -> Unit,
) {
    val colors = RollTheme.colors
    SectionHeader("Notices") {
        if (canPost) RollTextButton(text = "+ Post", onClick = onPost)
    }
    if (notices.isEmpty()) {
        Text(
            text = "No notices right now.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
        )
        return
    }
    Column(Modifier.background(colors.surface)) {
        notices.forEachIndexed { index, notice ->
            if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
            // CRs manage class notices; only the admin manages college notices.
            NoticeRow(notice, onClick = if (canPost && !notice.college) ({ onSelect(notice) }) else null)
        }
    }
}

/** Write a notice: text (500 max), pinned, optional end date. */
@Composable
fun NoticeEditor(
    title: String,
    today: LocalDate,
    onPost: (NoticeDraft) -> Unit,
) {
    val colors = RollTheme.colors
    var text by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    var until by remember { mutableStateOf<LocalDate?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(title, style = RollTheme.typography.title, color = colors.ink)
        Spacer(Modifier.height(Spacing.lg))
        RollTextField(
            value = text,
            onValueChange = { text = it.take(NoticeDraft.MAX_LENGTH) },
            label = "Notice",
            singleLine = false,
            supporting = "${text.length} / ${NoticeDraft.MAX_LENGTH}",
        )
        Spacer(Modifier.height(Spacing.md))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.buttonHeight)
                .toggleable(value = pinned, role = Role.Checkbox, onValueChange = { pinned = it }),
        ) {
            Checkbox(
                checked = pinned,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.outline),
            )
            Column(Modifier.padding(start = Spacing.sm)) {
                Text("Pin to the top of Today", style = RollTheme.typography.body, color = colors.ink)
                Text("For things everyone must see", style = RollTheme.typography.caption, color = colors.inkSecondary)
            }
        }
        Spacer(Modifier.height(Spacing.md))
        PickerField(
            label = "Show until (optional)",
            value = until?.let { AttendanceCopy.shortDate(it) }.orEmpty(),
            placeholder = "No end date",
            onClick = { pickingDate = true },
        )
        if (until != null) {
            RollTextButton(text = "Remove end date", onClick = { until = null }, alignTextToStart = true)
        }
        Spacer(Modifier.height(Spacing.xl))
        RollButton(
            text = "Post notice",
            onClick = {
                onPost(NoticeDraft(text, pinned, until?.plusDays(1)?.atStartOfDay(Ist)?.toInstant()))
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (pickingDate) {
        RollDatePickerDialog(
            initial = until ?: today.plusDays(7),
            selectable = today..today.plusDays(365),
            onConfirm = {
                until = it
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Pin / unpin / delete for someone who can post. */
@Composable
fun NoticeActionsDialog(
    notice: Notice,
    onPin: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    RollDialog(
        title = "Notice",
        text = notice.text,
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = onDelete,
        dismissLabel = "Close",
        onDismiss = onDismiss,
    ) {
        RollTextButton(
            text = if (notice.pinned) "Unpin from Today" else "Pin to Today",
            onClick = { onPin(!notice.pinned) },
            alignTextToStart = true,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Admin: college notices.

data class CollegeNoticesUiState(
    val notices: List<Notice> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class CollegeNoticesViewModel @Inject constructor(
    session: AppSession,
    time: TimeSource,
    private val notices: NoticeRepository,
) : ViewModel() {
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<CollegeNoticesUiState> = combine(notices.observeCollege(), time.ticks()) { list, now ->
        CollegeNoticesUiState(list, now.atZone(Ist).toLocalDate())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollegeNoticesUiState())

    fun post(draft: NoticeDraft) {
        val ctx = context.value ?: return
        if (ctx.isAdmin) notices.post(null, draft, "Admin")
    }

    fun setPinned(notice: Notice, pinned: Boolean) = notices.setPinned(null, notice, pinned)

    fun delete(notice: Notice) = notices.delete(null, notice)
}

@Composable
fun CollegeNoticesRoute(
    onBack: () -> Unit,
    viewModel: CollegeNoticesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CollegeNoticesScreen(uiState, onBack, viewModel::post, viewModel::setPinned, viewModel::delete)
}

@Composable
fun CollegeNoticesScreen(
    uiState: CollegeNoticesUiState,
    onBack: () -> Unit,
    onPost: (NoticeDraft) -> Unit,
    onPin: (Notice, Boolean) -> Unit,
    onDelete: (Notice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var writing by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "College notices", onBack = onBack) {
            RollIconButton(icon = RollIcons.Add, contentDescription = "Post a college notice", onClick = { writing = true })
        }
        Text(
            text = "Every student of every supported branch sees these, in Department, and on Today when pinned.",
            style = RollTheme.typography.caption,
            color = colors.inkSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (uiState.notices.isEmpty()) item { EmptyState(message = "No college notices yet.") }
            itemsIndexed(uiState.notices, key = { _, it -> it.id }) { index, notice ->
                Column(Modifier.background(colors.surface)) {
                    if (index == 0) Spacer(Modifier.height(0.dp)) else HairlineDivider(startIndent = Spacing.gutter)
                    NoticeRow(notice, onClick = { selectedId = notice.id })
                }
            }
        }
    }
    if (writing) {
        RollBottomSheet(onDismiss = { writing = false }) {
            NoticeEditor(title = "College notice", today = uiState.today) {
                onPost(it)
                writing = false
            }
        }
    }
    uiState.notices.firstOrNull { it.id == selectedId }?.let { notice ->
        NoticeActionsDialog(
            notice = notice,
            onPin = {
                onPin(notice, it)
                selectedId = null
            },
            onDelete = {
                onDelete(notice)
                selectedId = null
            },
            onDismiss = { selectedId = null },
        )
    }
}
