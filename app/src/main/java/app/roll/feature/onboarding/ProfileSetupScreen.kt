package app.roll.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.roll.core.designsystem.component.RollButton
import app.roll.core.designsystem.component.RollTextField
import app.roll.core.designsystem.preview.RollPreviewSurface
import app.roll.core.designsystem.preview.RollPreviewsWithFontScale
import app.roll.core.designsystem.theme.RollTheme
import app.roll.core.designsystem.theme.Spacing
import app.roll.data.auth.AuthUser
import app.roll.data.profile.ProfileRepository
import app.roll.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val profiles: ProfileRepository,
) : ViewModel() {
    /** The app moves on by itself once the profile doc updates (instantly, even offline). */
    fun save(user: AuthUser, existing: UserProfile?, name: String, rollNo: String) {
        profiles.saveProfile(
            uid = user.uid,
            name = name.trim(),
            rollNo = rollNo.trim().uppercase(),
            email = user.email,
            isNew = existing == null,
            classId = existing?.classId,
        )
    }
}

@Composable
fun ProfileSetupRoute(
    user: AuthUser,
    existing: UserProfile?,
    viewModel: ProfileSetupViewModel = hiltViewModel(),
) {
    ProfileSetupScreen(
        initialName = existing?.name?.ifBlank { null } ?: user.displayName,
        initialRollNo = existing?.rollNo.orEmpty(),
        onContinue = { name, rollNo -> viewModel.save(user, existing, name, rollNo) },
    )
}

@Composable
fun ProfileSetupScreen(
    initialName: String,
    initialRollNo: String,
    onContinue: (name: String, rollNo: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var rollNo by rememberSaveable { mutableStateOf(initialRollNo) }
    val valid = name.isNotBlank() && rollNo.isNotBlank()
    val colors = RollTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
    ) {
        Text(
            text = "Your details",
            style = RollTheme.typography.title,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Your CR sees these next to your marks.",
            style = RollTheme.typography.body,
            color = colors.inkSecondary,
        )
        Spacer(Modifier.height(Spacing.xl))
        RollTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = "Name",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(Spacing.lg))
        RollTextField(
            value = rollNo,
            onValueChange = { rollNo = it.take(20) },
            label = "Roll number",
            mono = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onContinue(name, rollNo) }),
        )
        Spacer(Modifier.height(Spacing.xl))
        RollButton(
            text = "Continue",
            onClick = { onContinue(name, rollNo) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@RollPreviewsWithFontScale
@Composable
private fun ProfileSetupPreview() {
    RollPreviewSurface { ProfileSetupScreen(initialName = "Sahil", initialRollNo = "", onContinue = { _, _ -> }) }
}
