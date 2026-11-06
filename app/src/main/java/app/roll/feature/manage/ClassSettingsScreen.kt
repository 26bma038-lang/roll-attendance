package app.roll.feature.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.RollOutlinedButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.classes.ClassRepository
import app.roll.data.session.AppSession
import app.roll.data.session.ClassContext
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ClassSettingsUiState(
    val rollClass: RollClass? = null,
    val myUid: String = "",
    val members: List<Member> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ClassSettingsViewModel @Inject constructor(
    session: AppSession,
    private val classes: ClassRepository,
    private val messages: UserMessages,
) : ViewModel() {
    private val context: StateFlow<ClassContext?> = session.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val members = session.context.map { it.classId }.distinctUntilChanged()
        .flatMapLatest { classes.observeMembers(it) }
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<ClassSettingsUiState> = combine(session.context, members) { ctx, list ->
        ClassSettingsUiState(ctx.rollClass, ctx.uid, list)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClassSettingsUiState())

    fun save(name: String, semester: String, requiredPercent: Int) {
        val ctx = context.value ?: return
        classes.updateSettings(ctx.classId, name.trim(), semester.trim(), requiredPercent)
        messages.show("Class settings saved")
    }
}

@Composable
fun ClassSettingsRoute(
    onBack: () -> Unit,
    viewModel: ClassSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ClassSettingsScreen(uiState = uiState, onBack = onBack, onSave = viewModel::save)
}

/** CR: name, semester, required %. The member list is read-only: classes come from emails. */
@Composable
fun ClassSettingsScreen(
    uiState: ClassSettingsUiState,
    onBack: () -> Unit,
    onSave: (name: String, semester: String, requiredPercent: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    val rollClass = uiState.rollClass
    var name by rememberSaveable { mutableStateOf("") }
    var semester by rememberSaveable { mutableStateOf("") }
    var required by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }

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
                RollTextField(value = semester, onValueChange = { semester = it.take(60) }, label = "Semester")
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

            SectionHeader("CRs")
            Text(
                text = if (rollClass.crNames.isEmpty()) "None" else rollClass.crNames.joinToString(", "),
                style = RollTheme.typography.body,
                color = colors.ink,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
            Text(
                text = "Only the admin appoints or removes CRs.",
                style = RollTheme.typography.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
            )

            SectionHeader("Members · ${uiState.members.size}")
            ListGroup {
                uiState.members.forEachIndexed { index, member ->
                    if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                    MemberRow(member) {
                        if (member.uid in rollClass.crUids) {
                            Text(
                                text = if (member.uid == uiState.myUid) "You, CR" else "CR",
                                style = RollTheme.typography.caption,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(end = Spacing.md),
                            )
                        }
                    }
                }
            }
            Text(
                text = "Students join automatically when they sign in with their college account.",
                style = RollTheme.typography.caption,
                color = colors.inkSecondary,
                modifier = Modifier.padding(Spacing.gutter),
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
