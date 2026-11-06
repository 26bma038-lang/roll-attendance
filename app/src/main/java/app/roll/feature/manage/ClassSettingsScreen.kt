package app.roll.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.JoinCodeDisplay
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.RollDialog
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Sizes
import app.roll.core.designsystem.theme.Spacing
import app.roll.core.util.copyText
import app.roll.core.util.joinMessage
import app.roll.core.util.shareText
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.OfflineException
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClassSettingsUiState(
    val rollClass: RollClass? = null,
    val myUid: String = "",
    val isCR: Boolean = false,
    val members: List<Member> = emptyList(),
    val regenerating: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClassSettingsViewModel @Inject constructor(
    session: AppSession,
    private val classes: ClassRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val regenerating = MutableStateFlow(false)

    private val members = session.context.map { it.classId }.distinctUntilChanged()
        .flatMapLatest { classes.observeMembers(it) }
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<ClassSettingsUiState> = combine(session.context, members, regenerating) { ctx, list, busy ->
        ClassSettingsUiState(ctx.rollClass, ctx.uid, ctx.isCR, list, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClassSettingsUiState())

    fun save(name: String, semester: String, requiredPercent: Int) {
        val ctx = context.value ?: return
        classes.updateSettings(ctx.classId, name.trim(), semester.trim(), requiredPercent)
        messages.show("Class settings saved")
    }

    fun regenerateCode() {
        val ctx = context.value ?: return
        viewModelScope.launch {
            regenerating.value = true
            try {
                val code = classes.regenerateJoinCode(ctx.rollClass)
                messages.show("New join code: $code. The old one no longer works.")
            } catch (_: OfflineException) {
                messages.show("You're offline. A new code needs a connection.")
            } catch (_: Exception) {
                messages.show("Couldn't make a new code. Try again.")
            }
            regenerating.value = false
        }
    }

    fun remove(member: Member) {
        val ctx = context.value ?: return
        classes.removeMember(ctx.classId, member.uid)
    }

    fun transfer(member: Member) {
        val ctx = context.value ?: return
        classes.transferCr(ctx.classId, member.uid)
        messages.show("${member.name} is now the CR")
    }
}

@Composable
fun ClassSettingsRoute(
    onBack: () -> Unit,
    viewModel: ClassSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ClassSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onSave = viewModel::save,
        onRegenerate = viewModel::regenerateCode,
        onRemove = viewModel::remove,
        onTransfer = viewModel::transfer,
    )
}

@Composable
fun ClassSettingsScreen(
    uiState: ClassSettingsUiState,
    onBack: () -> Unit,
    onSave: (name: String, semester: String, requiredPercent: Int) -> Unit,
    onRegenerate: () -> Unit,
    onRemove: (Member) -> Unit,
    onTransfer: (Member) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val context = LocalContext.current
    val rollClass = uiState.rollClass
    var name by rememberSaveable { mutableStateOf("") }
    var semester by rememberSaveable { mutableStateOf("") }
    var required by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var confirmRegenerate by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var transferTo by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingTransfer by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(rollClass) {
        if (rollClass != null && !loaded) {
            name = rollClass.name
            semester = rollClass.semester
            required = rollClass.requiredPercent.toString()
            loaded = true
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        RollTopAppBar(title = "Class settings", onBack = onBack)
        if (rollClass == null) return@Column
        val requiredValue = required.toIntOrNull()
        val valid = name.isNotBlank() && requiredValue != null && requiredValue in 1..100
        val changed = name.trim() != rollClass.name || semester.trim() != rollClass.semester || requiredValue != rollClass.requiredPercent
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                RollTextField(value = name, onValueChange = { name = it.take(80) }, label = "Class name")
                Spacer(Modifier.height(Spacing.md))
                RollTextField(value = semester, onValueChange = { semester = it.take(40) }, label = "Semester")
                Spacer(Modifier.height(Spacing.md))
                RollTextField(
                    value = required,
                    onValueChange = { required = it.filter(Char::isDigit).take(3) },
                    label = "Required attendance, %",
                    isError = requiredValue == null || requiredValue !in 1..100,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(Spacing.md))
                RollOutlinedButton(
                    text = "Save changes",
                    onClick = { onSave(name, semester, requiredValue ?: rollClass.requiredPercent) },
                    enabled = valid && changed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader("Join code")
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                JoinCodeDisplay(
                    code = rollClass.joinCode,
                    onCopy = { context.copyText("Join code", rollClass.joinCode) },
                    onShare = { context.shareText(joinMessage(rollClass.name, rollClass.joinCode), "Share join code") },
                )
                RollTextButton(
                    text = if (uiState.regenerating) "Making a new code" else "Make a new code",
                    onClick = { confirmRegenerate = true },
                    enabled = !uiState.regenerating,
                    alignTextToStart = true,
                )
            }

            SectionHeader("Members · ${uiState.members.size}")
            ListGroup {
                uiState.members.forEachIndexed { index, member ->
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    val isMe = member.uid == uiState.myUid
                    MemberRow(member) {
                        if (isMe) {
                            Text(
                                text = "You, CR",
                                style = RollTheme.typography.caption,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(end = Spacing.md),
                            )
                        } else {
                            RollIconButton(
                                icon = RollIcons.RemoveMember,
                                contentDescription = "Remove ${member.name}",
                                onClick = { removing = member.uid },
                                tint = colors.inkSecondary,
                            )
                        }
                    }
                }
            }

            SectionHeader("CR role")
            Column(Modifier.padding(horizontal = Spacing.gutter)) {
                Text(
                    text = "Hand the CR role to another member. You stay in the class as a student.",
                    style = RollTheme.typography.caption,
                    color = colors.inkSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                RollOutlinedButton(
                    text = "Transfer CR role",
                    onClick = { pickingTransfer = true },
                    enabled = uiState.members.any { it.uid != uiState.myUid },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }

    if (confirmRegenerate) {
        RollDialog(
            title = "Make a new join code?",
            text = "The current code stops working. Members already in the class stay in it.",
            confirmLabel = "Make new code",
            onConfirm = {
                confirmRegenerate = false
                onRegenerate()
            },
            onDismiss = { confirmRegenerate = false },
        )
    }

    uiState.members.firstOrNull { it.uid == removing }?.let { member ->
        RollDialog(
            title = "Remove ${member.name}?",
            text = "They leave the class. Their own marks stay on record. They can rejoin with the code.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                removing = null
                onRemove(member)
            },
            onDismiss = { removing = null },
        )
    }

    if (pickingTransfer) {
        var picked by rememberSaveable { mutableStateOf<String?>(null) }
        RollDialog(
            title = "Transfer CR role to",
            confirmLabel = "Next",
            confirmEnabled = picked != null,
            onConfirm = {
                pickingTransfer = false
                transferTo = picked
            },
            onDismiss = { pickingTransfer = false },
        ) {
            Column(
                Modifier
                    .padding(top = Spacing.md)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                uiState.members.filter { it.uid != uiState.myUid }.forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.buttonHeight)
                            .selectable(selected = picked == member.uid, role = Role.RadioButton, onClick = { picked = member.uid }),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(member.name, style = RollTheme.typography.body, color = colors.ink)
                            Text(member.rollNo, style = RollTheme.typography.rollNo, color = colors.inkSecondary)
                        }
                        if (picked == member.uid) {
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(painterResource(RollIcons.Check), contentDescription = null, tint = colors.accent)
                        }
                    }
                }
            }
        }
    }

    uiState.members.firstOrNull { it.uid == transferTo }?.let { member ->
        var typed by rememberSaveable { mutableStateOf("") }
        RollDialog(
            title = "Make ${member.name} the CR?",
            text = "Only the CR can post classes and change the timetable. To confirm, type the class name: ${rollClass?.name}",
            confirmLabel = "Transfer",
            destructive = true,
            confirmEnabled = typed.trim() == rollClass?.name?.trim(),
            onConfirm = {
                transferTo = null
                onTransfer(member)
            },
            onDismiss = { transferTo = null },
        ) {
            RollTextField(
                value = typed,
                onValueChange = { typed = it },
                label = "Class name",
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }
    }
}
