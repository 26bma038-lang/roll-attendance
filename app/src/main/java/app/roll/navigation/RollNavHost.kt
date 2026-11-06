package app.roll.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.roll.core.designsystem.component.LocalRollSnackbar
import app.roll.core.designsystem.component.RollBottomBar
import app.roll.core.designsystem.component.RollNavItem
import app.roll.core.designsystem.component.RollSnackbarHost
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.Motion
import app.roll.core.designsystem.theme.RollTheme
import app.roll.feature.admin.AdminClassRoute
import app.roll.feature.admin.AdminDepartmentRoute
import app.roll.feature.admin.AdminDepartmentsScreen
import app.roll.feature.admin.AdminHomeRoute
import app.roll.feature.department.DepartmentRoute
import app.roll.feature.history.DayRoute
import app.roll.feature.history.HistoryRoute
import app.roll.feature.manage.ClassSettingsRoute
import app.roll.feature.manage.ManageRoute
import app.roll.feature.manage.ManageSubjectsRoute
import app.roll.feature.manage.SessionDetailRoute
import app.roll.feature.manage.TimetableRoute
import app.roll.feature.settings.SettingsRoute
import app.roll.feature.subjects.SubjectDetailRoute
import app.roll.feature.subjects.SubjectsRoute
import app.roll.feature.today.TodayRoute
import kotlinx.serialization.Serializable

// Type-safe destinations. Only reached once the user is signed in and in a class.

@Serializable data object MainDest
@Serializable data class SubjectDetailDest(val subjectId: String)
@Serializable data class DayDest(val date: String)
@Serializable data class SessionDetailDest(val sessionId: String)
@Serializable data object TimetableDest
@Serializable data object ManageSubjectsDest
@Serializable data object ClassSettingsDest
@Serializable data object SettingsDest
@Serializable data object AdminDest
@Serializable data class AdminClassDest(val classId: String)
@Serializable data object AdminDepartmentsDest
@Serializable data class AdminDepartmentDest(val code: String)

private enum class Tab(val label: String, val icon: Int) {
    Today("Today", RollIcons.Today),
    Subjects("Subjects", RollIcons.Subjects),
    History("History", RollIcons.History),
    Department("Department", RollIcons.Department),
    Manage("Manage", RollIcons.Manage),
}

@Composable
fun RollNavHost(
    isCR: Boolean,
    isAdmin: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = MainDest,
        modifier = modifier,
        enterTransition = { fadeIn(Motion.medium()) },
        exitTransition = { fadeOut(Motion.short()) },
        popEnterTransition = { fadeIn(Motion.medium()) },
        popExitTransition = { fadeOut(Motion.short()) },
    ) {
        composable<MainDest> {
            MainTabs(isCR = isCR, isAdmin = isAdmin, navController = navController)
        }
        composable<SubjectDetailDest> {
            Screen { SubjectDetailRoute(onBack = navController::popBackStack) }
        }
        composable<DayDest> {
            Screen { DayRoute(onBack = navController::popBackStack) }
        }
        composable<SessionDetailDest> {
            Screen { SessionDetailRoute(onBack = navController::popBackStack) }
        }
        composable<TimetableDest> {
            Screen { TimetableRoute(onBack = navController::popBackStack) }
        }
        composable<ManageSubjectsDest> {
            Screen { ManageSubjectsRoute(onBack = navController::popBackStack) }
        }
        composable<ClassSettingsDest> {
            Screen { ClassSettingsRoute(onBack = navController::popBackStack) }
        }
        composable<SettingsDest> {
            Screen {
                SettingsRoute(
                    onBack = navController::popBackStack,
                    onOpenAdmin = { navController.navigate(AdminDest) },
                )
            }
        }
        composable<AdminDest> {
            Screen {
                AdminHomeRoute(
                    onBack = navController::popBackStack,
                    onOpenClass = { navController.navigate(AdminClassDest(it)) },
                    onOpenDepartments = { navController.navigate(AdminDepartmentsDest) },
                    onOpenCollegeNotices = {},
                )
            }
        }
        composable<AdminClassDest> {
            Screen { AdminClassRoute(onBack = navController::popBackStack) }
        }
        composable<AdminDepartmentsDest> {
            Screen {
                AdminDepartmentsScreen(
                    onBack = navController::popBackStack,
                    onOpen = { navController.navigate(AdminDepartmentDest(it)) },
                )
            }
        }
        composable<AdminDepartmentDest> {
            Screen { AdminDepartmentRoute(onBack = navController::popBackStack) }
        }
    }
}

/** A full-screen destination with its own snackbar host position. */
@Composable
private fun Screen(content: @Composable () -> Unit) {
    Scaffold(
        containerColor = RollTheme.colors.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { RollSnackbarHost(LocalRollSnackbar.current) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) { content() }
    }
}

@Composable
private fun MainTabs(isCR: Boolean, isAdmin: Boolean, navController: NavHostController) {
    val tabs = if (isCR) Tab.entries else Tab.entries - Tab.Manage
    var selected by rememberSaveable { mutableIntStateOf(0) }
    if (selected >= tabs.size) selected = 0
    val openSettings = { navController.navigate(SettingsDest) }
    val openDay = { date: java.time.LocalDate -> navController.navigate(DayDest(date.toString())) }

    Scaffold(
        containerColor = RollTheme.colors.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            RollBottomBar(
                items = tabs.map { RollNavItem(it.label, it.icon) },
                selectedIndex = selected,
                onSelect = { selected = it },
            )
        },
        snackbarHost = { RollSnackbarHost(LocalRollSnackbar.current) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tabs[selected]) {
                Tab.Today -> TodayRoute(
                    onOpenSettings = openSettings,
                    onOpenDay = openDay,
                    onOpenAdmin = if (isAdmin) ({ navController.navigate(AdminDest) }) else null,
                )
                Tab.Subjects -> SubjectsRoute(
                    onOpenSubject = { navController.navigate(SubjectDetailDest(it)) },
                    onOpenSettings = openSettings,
                    onManageSubjects = { navController.navigate(ManageSubjectsDest) },
                )
                Tab.History -> HistoryRoute(onOpenDay = openDay, onOpenSettings = openSettings)
                Tab.Department -> DepartmentRoute(onOpenSettings = openSettings)
                Tab.Manage -> ManageRoute(
                    onOpenSession = { navController.navigate(SessionDetailDest(it)) },
                    onOpenTimetable = { navController.navigate(TimetableDest) },
                    onOpenSubjects = { navController.navigate(ManageSubjectsDest) },
                    onOpenClassSettings = { navController.navigate(ClassSettingsDest) },
                    onOpenSettings = openSettings,
                )
            }
        }
    }
}
