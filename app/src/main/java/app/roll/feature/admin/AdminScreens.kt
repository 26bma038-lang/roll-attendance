package app.roll.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.EmptyState
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.ListRow
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.component.SkeletonList
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.classes.ClassRepository
import app.roll.domain.college.NithConfig
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
import app.roll.feature.manage.MemberRow
import app.roll.navigation.AdminClassDest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------
// Admin home: classes, departments, college notices.

data class AdminHomeUiState(
    val loading: Boolean = true,
    val classes: List<RollClass> = emptyList(),
)

@HiltViewModel
class AdminHomeViewModel @Inject constructor(
    classes: ClassRepository,
) : ViewModel() {
    val uiState: StateFlow<AdminHomeUiState> = classes.observeAllClasses()
        .map { AdminHomeUiState(loading = false, classes = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminHomeUiState())
}

@Composable
fun AdminHomeRoute(
    onBack: () -> Unit,
    onOpenClass: (String) -> Unit,
    onOpenDepartments: () -> Unit,
    onOpenCollegeNotices: () -> Unit,
    viewModel: AdminHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AdminHomeScreen(uiState, onBack, onOpenClass, onOpenDepartments, onOpenCollegeNotices)
}

@Composable
fun AdminHomeScreen(
    uiState: AdminHomeUiState,
    onBack: () -> Unit,
    onOpenClass: (String) -> Unit,
    onOpenDepartments: () -> Unit,
    onOpenCollegeNotices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "Admin", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "college") {
                SectionHeader("College")
                ListGroup {
                    ListRow(title = "Departments", subtitle = "About, HOD, office, links", onClick = onOpenDepartments)
                    HairlineDivider(startIndent = Spacing.gutter)
                    ListRow(title = "College notices", subtitle = "Shown to every student", onClick = onOpenCollegeNotices)
                }
            }
            item(key = "classesHeader") { SectionHeader("Classes · appoint CRs") }
            when {
                uiState.loading -> item { SkeletonList(Modifier.background(colors.surface), rows = 3, showTrailing = false) }
                uiState.classes.isEmpty() -> item {
                    EmptyState(message = "No classes yet. A class appears when its first student signs in.")
                }
                else -> itemsIndexed(uiState.classes, key = { _, it -> it.id }) { index, rollClass ->
                    Column(Modifier.background(colors.surface)) {
                        if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                        ListRow(
                            title = rollClass.name,
                            subtitle = listOf(
                                NithConfig.branchName(rollClass.branch),
                                if (rollClass.memberCount == 1) "1 student" else "${rollClass.memberCount} students",
                                if (rollClass.crNames.isEmpty()) "no CR yet" else "CR: ${rollClass.crNames.joinToString(", ")}",
                            ).joinToString(" · "),
                            onClick = { onOpenClass(rollClass.id) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// One class: appoint and remove CRs.

data class AdminClassUiState(
    val loading: Boolean = true,
    val rollClass: RollClass? = null,
    val members: List<Member> = emptyList(),
)

@HiltViewModel
class AdminClassViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val classes: ClassRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val classId = savedStateHandle.toRoute<AdminClassDest>().classId

    val uiState: StateFlow<AdminClassUiState> = combine(
        classes.observeAllClasses().map { list -> list.firstOrNull { it.id == classId } },
        classes.observeMembers(classId).onStart { emit(emptyList()) },
    ) { rollClass, members ->
        AdminClassUiState(loading = rollClass == null, rollClass = rollClass, members = members)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdminClassUiState())

    fun makeCR(member: Member) {
        val rollClass = uiState.value.rollClass ?: return
        if (member.uid in rollClass.crUids) return
        if (rollClass.crUids.size >= NithConfig.MAX_CRS) {
            messages.show("A class can have at most ${NithConfig.MAX_CRS} CRs. Remove one first.")
            return
        }
        classes.setCRs(rollClass.id, rollClass.crUids + member.uid, rollClass.crNames + member.name)
        messages.show("${member.name} is now a CR of ${rollClass.name}")
    }

    fun removeCR(member: Member) {
        val rollClass = uiState.value.rollClass ?: return
        val index = rollClass.crUids.indexOf(member.uid)
        if (index < 0) return
        classes.setCRs(
            rollClass.id,
            rollClass.crUids.filterIndexed { i, _ -> i != index },
            rollClass.crNames.filterIndexed { i, _ -> i != index },
        )
        messages.show("${member.name} is no longer a CR")
    }
}

@Composable
fun AdminClassRoute(
    onBack: () -> Unit,
    viewModel: AdminClassViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AdminClassScreen(uiState, onBack, onMakeCR = viewModel::makeCR, onRemoveCR = viewModel::removeCR)
}

@Composable
fun AdminClassScreen(
    uiState: AdminClassUiState,
    onBack: () -> Unit,
    onMakeCR: (Member) -> Unit,
    onRemoveCR: (Member) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val rollClass = uiState.rollClass
    var search by rememberSaveable { mutableStateOf("") }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val query = search.trim().uppercase()
    val shown = uiState.members.filter { query.isEmpty() || query in it.rollNo.uppercase() || query in it.name.uppercase() }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = rollClass?.name ?: "Class", onBack = onBack)
        if (rollClass == null) {
            if (!uiState.loading) EmptyState(message = "This class isn't available.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xxl), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    Text(
                        text = "${NithConfig.branchName(rollClass.branch)} · ${rollClass.batch} batch",
                        style = RollTheme.typography.body,
                        color = colors.ink,
                    )
                    Text(
                        text = if (rollClass.crNames.isEmpty()) {
                            "No CR yet. Up to ${NithConfig.MAX_CRS}."
                        } else {
                            "CRs: ${rollClass.crNames.joinToString(", ")} (${rollClass.crUids.size} of ${NithConfig.MAX_CRS})"
                        },
                        style = RollTheme.typography.caption,
                        color = colors.inkSecondary,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    RollTextField(
                        value = search,
                        onValueChange = { search = it.take(20) },
                        label = "Search by roll number or name",
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                    )
                }
            }
            item(key = "membersHeader") { SectionHeader("Members · ${shown.size}") }
            if (shown.isEmpty()) {
                item { EmptyState(message = if (query.isEmpty()) "No members yet." else "Nobody matches $search.") }
            }
            itemsIndexed(shown, key = { _, it -> it.uid }) { index, member ->
                val isCR = member.uid in rollClass.crUids
                Column(Modifier.background(colors.surface)) {
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    MemberRow(member) {
                        RollTextButton(
                            text = if (isCR) "Remove CR" else "Make CR",
                            onClick = { confirming = member.uid },
                            destructive = isCR,
                            enabled = isCR || rollClass.crUids.size < NithConfig.MAX_CRS,
                        )
                    }
                }
            }
        }
    }

    val member = uiState.members.firstOrNull { it.uid == confirming }
    if (member != null && rollClass != null) {
        val isCR = member.uid in rollClass.crUids
        RollDialog(
            title = if (isCR) "Remove ${member.name} as CR?" else "Make ${member.name} a CR?",
            text = if (isCR) {
                "${member.name} (${member.rollNo}) will no longer be able to post classes, assignments or notices for ${rollClass.name}."
            } else {
                "${member.name} (${member.rollNo}) will be able to post classes, assignments and notices for ${rollClass.name}."
            },
            confirmLabel = if (isCR) "Remove CR" else "Make CR",
            destructive = isCR,
            onConfirm = {
                confirming = null
                if (isCR) onRemoveCR(member) else onMakeCR(member)
            },
            onDismiss = { confirming = null },
        )
    }
}
