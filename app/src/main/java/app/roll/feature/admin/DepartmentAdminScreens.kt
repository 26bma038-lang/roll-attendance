package app.roll.feature.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.roll.core.common.UserMessages
import app.roll.core.designsystem.component.HairlineDivider
import app.roll.core.designsystem.component.ListGroup
import app.roll.core.designsystem.component.ListRow
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollIconButton
import app.roll.core.designsystem.component.RollTextButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.component.RollTopAppBar
import app.roll.core.designsystem.component.SectionHeader
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.college.DepartmentRepository
import app.roll.domain.college.NithConfig
import app.roll.domain.model.Department
import app.roll.domain.model.DepartmentLink
import app.roll.navigation.AdminDepartmentDest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The six supported departments; tap one to edit it. */
@Composable
fun AdminDepartmentsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(RollTheme.colors.background),
    ) {
        RollTopAppBar(title = "Departments", onBack = onBack)
        Text(
            text = "Everything here is shown to students exactly as you enter it. Leave a field blank rather than guess.",
            style = RollTheme.typography.caption,
            color = RollTheme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )
        SectionHeader("Branches")
        ListGroup {
            NithConfig.branches.entries.forEachIndexed { index, (code, name) ->
                if (index > 0) HairlineDivider(startIndent = Spacing.gutter)
                ListRow(title = name, subtitle = code, onClick = { onOpen(code) })
            }
        }
    }
}

@HiltViewModel
class AdminDepartmentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val departments: DepartmentRepository,
    private val messages: UserMessages,
) : ViewModel() {
    val code: String = savedStateHandle.toRoute<AdminDepartmentDest>().code

    val department: StateFlow<Department?> = departments.observe(code)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(department: Department) {
        departments.save(department)
        messages.show("${department.code} saved")
    }
}

@Composable
fun AdminDepartmentRoute(
    onBack: () -> Unit,
    viewModel: AdminDepartmentViewModel = hiltViewModel(),
) {
    val department by viewModel.department.collectAsStateWithLifecycle()
    AdminDepartmentScreen(
        department = department,
        onBack = onBack,
        onSave = {
            viewModel.save(it)
            onBack()
        },
    )
}

@Composable
fun AdminDepartmentScreen(
    department: Department?,
    onBack: () -> Unit,
    onSave: (Department) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RollTheme.colors
    var loaded by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf("") }
    var hod by rememberSaveable { mutableStateOf("") }
    var office by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    val links = remember { mutableStateListOf<DepartmentLink>() }

    LaunchedEffect(department) {
        if (department != null && !loaded) {
            about = department.about
            hod = department.hod
            office = department.office
            email = department.email
            phone = department.phone
            links.clear()
            links.addAll(department.links)
            loaded = true
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        RollTopAppBar(title = department?.code ?: "Department", onBack = onBack)
        if (department == null) return@Column
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter),
        ) {
            Text(department.name, style = RollTheme.typography.body, color = colors.ink)
            Spacer(Modifier.height(Spacing.lg))
            RollTextField(value = about, onValueChange = { about = it.take(1000) }, label = "About", singleLine = false)
            Spacer(Modifier.height(Spacing.md))
            RollTextField(value = hod, onValueChange = { hod = it.take(80) }, label = "Head of department")
            Spacer(Modifier.height(Spacing.md))
            RollTextField(value = office, onValueChange = { office = it.take(120) }, label = "Department office location")
            Spacer(Modifier.height(Spacing.md))
            RollTextField(
                value = email,
                onValueChange = { email = it.take(80) },
                label = "Office email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(Spacing.md))
            RollTextField(
                value = phone,
                onValueChange = { phone = it.take(40) },
                label = "Office phone",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Spacer(Modifier.height(Spacing.lg))
            Text("Useful links", style = RollTheme.typography.label, color = colors.inkSecondary)
            links.forEachIndexed { index, link ->
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = Spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        RollTextField(value = link.title, onValueChange = { links[index] = link.copy(title = it.take(60)) }, label = "Title")
                        Spacer(Modifier.height(Spacing.xs))
                        RollTextField(
                            value = link.url,
                            onValueChange = { links[index] = link.copy(url = it.take(300)) },
                            label = "Link",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    RollIconButton(icon = RollIcons.Delete, contentDescription = "Remove link ${index + 1}", onClick = { links.removeAt(index) })
                }
            }
            if (links.size < 10) {
                RollTextButton(text = "+ Add link", onClick = { links.add(DepartmentLink("", "")) }, alignTextToStart = true)
            }
            Spacer(Modifier.height(Spacing.xl))
            RollButton(
                text = "Save",
                onClick = {
                    onSave(
                        department.copy(
                            about = about, hod = hod, office = office, email = email, phone = phone,
                            links = links.filter { it.url.isNotBlank() },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
