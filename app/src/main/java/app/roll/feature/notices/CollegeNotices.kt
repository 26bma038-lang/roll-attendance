package app.roll.feature.notices

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.TimeSource
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.ChoiceRow
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.RollBottomSheet
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.openUrl
import app.roll.data.feed.FeedLoad
import app.roll.data.feed.NithNoticeTracker
import app.roll.data.notices.NoticeDraft
import app.roll.data.notices.NoticeRepository
import app.roll.data.session.AppSession
import app.roll.domain.college.NithConfig
import app.roll.domain.feed.CollegeEntry
import app.roll.domain.feed.NithCategory
import app.roll.domain.feed.NithFeed
import app.roll.domain.feed.NithNotice
import app.roll.domain.model.Notice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private val Ist: ZoneId = ZoneId.of(NithConfig.TIMEZONE)

/** How far the nith.ac.in part of the screen has got. */
enum class FeedStatus { Loading, Ready, Offline, Failed }

data class CollegeNoticesUiState(
    val isAdmin: Boolean = false,
    /** The admin's notices; every one for the admin, unexpired ones for students. */
    val adminNotices: List<Notice> = emptyList(),
    val entries: List<CollegeEntry> = emptyList(),
    /** null = everything; otherwise only nith.ac.in notices of this category. */
    val category: NithCategory? = null,
    val feedStatus: FeedStatus = FeedStatus.Loading,
    val today: LocalDate = LocalDate.now(),
)

/**
 * College notices for everyone: the admin's own notices and the academic, exam and student
 * notices copied from nith.ac.in, in one date order. Opening it clears the "new" marks.
 */
@HiltViewModel
class CollegeNoticesViewModel @Inject constructor(
    session: AppSession,
    time: TimeSource,
    tracker: NithNoticeTracker,
    private val notices: NoticeRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val category = MutableStateFlow<NithCategory?>(null)

    /** "New" means newer than the last visit, fixed when this screen opened. */
    private val newAfter = MutableStateFlow<Instant?>(null)

    init {
        viewModelScope.launch {
            newAfter.value = tracker.seenAt.first()
            tracker.markSeen()
        }
    }

    private val isAdmin = session.context.map { it.isAdmin }

    val uiState: StateFlow<CollegeNoticesUiState> = combine(
        combine(isAdmin, notices.observeCollege(), time.ticks()) { admin, list, now -> Triple(admin, list, now) },
        tracker.load,
        newAfter,
        category,
    ) { (admin, list, now), load, seenAt, filter ->
        val visible = if (admin) list else list.filter { it.isActive(now) }
        val website = (load as? FeedLoad.Loaded)?.notices.orEmpty()
        CollegeNoticesUiState(
            isAdmin = admin,
            adminNotices = visible,
            entries = NithFeed.entries(visible, website, seenAt, filter, Ist),
            category = filter,
            feedStatus = when (load) {
                FeedLoad.Loading -> FeedStatus.Loading
                FeedLoad.Offline -> FeedStatus.Offline
                FeedLoad.Failed -> FeedStatus.Failed
                is FeedLoad.Loaded -> FeedStatus.Ready
            },
            today = now.atZone(Ist).toLocalDate(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollegeNoticesUiState())

    fun setCategory(value: NithCategory?) {
        category.value = value
    }

    fun post(draft: NoticeDraft) {
        if (uiState.value.isAdmin) notices.post(null, draft, "Admin")
    }

    fun setPinned(notice: Notice, pinned: Boolean) = notices.setPinned(null, notice, pinned)

    fun delete(notice: Notice) = notices.delete(null, notice)

    fun cannotOpen() = messages.show("No app on this phone can open the link.")
}

@Composable
fun CollegeNoticesRoute(
    onBack: () -> Unit,
    viewModel: CollegeNoticesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CollegeNoticesScreen(
        uiState = uiState,
        onBack = onBack,
        onCategory = viewModel::setCategory,
        onOpenUrl = { if (!context.openUrl(it)) viewModel.cannotOpen() },
        onPost = viewModel::post,
        onPin = viewModel::setPinned,
        onDelete = viewModel::delete,
    )
}

private val Filters: List<NithCategory?> = listOf(null) + NithCategory.entries

@Composable
fun CollegeNoticesScreen(
    uiState: CollegeNoticesUiState,
    onBack: () -> Unit,
    onCategory: (NithCategory?) -> Unit,
    onOpenUrl: (String) -> Unit,
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
            if (uiState.isAdmin) {
                RollIconButton(icon = RollIcons.Add, contentDescription = "Post a college notice", onClick = { writing = true })
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "intro") {
                Text(
                    text = if (uiState.isAdmin) {
                        "Your notices reach every student. Below them, notices from nith.ac.in, checked every 3 hours."
                    } else {
                        "From the admin and from nith.ac.in. The website is checked every 3 hours."
                    },
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
            item(key = "filters") {
                ChoiceRow(
                    options = Filters,
                    selected = uiState.category,
                    onSelect = onCategory,
                    label = { it?.label ?: "All" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                )
            }
            if (uiState.feedStatus == FeedStatus.Failed || uiState.feedStatus == FeedStatus.Offline) {
                item(key = "feed-status") {
                    Text(
                        text = if (uiState.feedStatus == FeedStatus.Offline) {
                            "You're offline. Notices from nith.ac.in load the first time you connect."
                        } else {
                            "Couldn't load notices from nith.ac.in. Try again later."
                        },
                        style = RollTheme.typography.caption,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
                    )
                }
            }
            when {
                uiState.entries.isEmpty() && uiState.feedStatus == FeedStatus.Loading ->
                    item(key = "loading") { SkeletonList(rows = 5, showTrailing = false) }
                uiState.entries.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        message = when (val category = uiState.category) {
                            null -> "No college notices yet. Notices from nith.ac.in show up here within 3 hours of being posted."
                            else -> "No ${category.label.lowercase()} notices on nith.ac.in right now."
                        },
                    )
                }
                else -> itemsIndexed(uiState.entries, key = { _, entry -> entry.key }) { index, entry ->
                    Column(Modifier.background(colors.surface)) {
                        if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                        when (entry) {
                            is CollegeEntry.Admin -> NoticeRow(
                                notice = entry.notice,
                                onClick = if (uiState.isAdmin) ({ selectedId = entry.notice.id }) else null,
                            )
                            is CollegeEntry.Website -> WebsiteNoticeRow(entry.notice, entry.isNew, onClick = { onOpenUrl(entry.notice.url) })
                        }
                    }
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
    uiState.adminNotices.firstOrNull { it.id == selectedId }?.let { notice ->
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

/** A nith.ac.in notice: title, then the month (IBM Plex Mono), category and source. Opens the PDF. */
@Composable
fun WebsiteNoticeRow(notice: NithNotice, isNew: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RollTheme.colors
    val typography = RollTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinHeight)
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(start = Spacing.gutter, end = Spacing.md, top = Spacing.md, bottom = Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = notice.title, style = typography.body, color = colors.ink)
            Text(
                text = buildAnnotatedString {
                    if (isNew) {
                        withStyle(SpanStyle(color = colors.accent, fontWeight = typography.label.fontWeight)) { append("New") }
                        append(" · ")
                    }
                    notice.monthLabel?.let {
                        withStyle(SpanStyle(fontFamily = typography.rollNo.fontFamily)) { append(it) }
                        append(" · ")
                    }
                    append("${notice.category.label} · nith.ac.in")
                },
                style = typography.caption,
                color = colors.inkSecondary,
            )
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
