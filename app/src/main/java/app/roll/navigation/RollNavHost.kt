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
import app.roll.feature.deadlines.AssignmentDetailRoute
import app.roll.feature.deadlines.DeadlinesRoute
import app.roll.feature.department.DepartmentRoute
import app.roll.feature.history.DayRoute
import app.roll.feature.history.HistoryRoute
import app.roll.feature.manage.ClassSettingsRoute
import app.roll.feature.manage.ManageRoute
import app.roll.feature.manage.ManageSubjectsRoute
import app.roll.feature.manage.SessionDetailRoute
import app.roll.feature.manage.TimetableRoute
import app.roll.feature.notices.CollegeNoticesRoute
import app.roll.feature.settings.SettingsRoute
import app.roll.feature.study.StudyGroupRoute
import app.roll.feature.study.StudyRoute
import app.roll.feature.subjects.LeavePlannerRoute
import app.roll.feature.subjects.SubjectDetailRoute
import app.roll.feature.subjects.SubjectsRoute
import app.roll.feature.today.TodayRoute
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Type-safe destinations. Only reached once the user is signed in and in a class.

@Serializable data object MainDest
@Serializable data class SubjectDetailDest(val subjectId: String)
@Serializable data object HistoryDest
@Serializable data object LeavePlannerDest
@Serializable data class DayDest(val date: String)
@Serializable data class AssignmentDetailDest(val assignmentId: String)
@Serializable data class SessionDetailDest(val sessionId: String)
@Serializable data object TimetableDest
@Serializable data object ManageSubjectsDest
@Serializable data object ClassSettingsDest
@Serializable data object SettingsDest
@Serializable data object AdminDest
@Serializable data class AdminClassDest(val classId: String)
@Serializable data object AdminDepartmentsDest
@Serializable data class AdminDepartmentDest(val code: String)
@Serializable data object CollegeNoticesDest
/** A subject or group on the Study tab; [key] is [app.roll.domain.materials.StudyGroup.key]. */
@Serializable data class StudyGroupDest(val key: String)

/** Today · Deadlines · Subjects · Study · Department, plus Manage for CRs. History lives in Subjects. */
private enum class Tab(val label: String, val icon: Int) {
    Today("Today", RollIcons.Today),
    Deadlines("Deadlines", RollIcons.Deadlines),
    Subjects("Subjects", RollIcons.Subjects),
    Study("Study", RollIcons.Study),
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
    val back: () -> Unit = { navController.popBackStack() }
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
        composable<SubjectDetailDest> { Screen { SubjectDetailRoute(onBack = back) } }
        composable<HistoryDest> {
            Screen { HistoryRoute(onOpenDay = { navController.navigate(DayDest(it.toString())) }, onBack = back) }
        }
        composable<DayDest> { Screen { DayRoute(onBack = back) } }
        composable<LeavePlannerDest> { Screen { LeavePlannerRoute(onBack = back) } }
        composable<AssignmentDetailDest> { Screen { AssignmentDetailRoute(onBack = back) } }
        composable<SessionDetailDest> { Screen { SessionDetailRoute(onBack = back) } }
        composable<TimetableDest> { Screen { TimetableRoute(onBack = back) } }
        composable<ManageSubjectsDest> { Screen { ManageSubjectsRoute(onBack = back) } }
        composable<ClassSettingsDest> { Screen { ClassSettingsRoute(onBack = back) } }
        composable<SettingsDest> {
            Screen { SettingsRoute(onBack = back, onOpenAdmin = { navController.navigate(AdminDest) }) }
        }
        composable<AdminDest> {
            Screen {
                AdminHomeRoute(
                    onBack = back,
                    onOpenClass = { navController.navigate(AdminClassDest(it)) },
                    onOpenDepartments = { navController.navigate(AdminDepartmentsDest) },
                    onOpenCollegeNotices = { navController.navigate(CollegeNoticesDest) },
                )
            }
        }
        composable<AdminClassDest> { Screen { AdminClassRoute(onBack = back) } }
        composable<AdminDepartmentsDest> {
            Screen { AdminDepartmentsScreen(onBack = back, onOpen = { navController.navigate(AdminDepartmentDest(it)) }) }
        }
        composable<AdminDepartmentDest> { Screen { AdminDepartmentRoute(onBack = back) } }
        composable<CollegeNoticesDest> { Screen { CollegeNoticesRoute(onBack = back) } }
        composable<StudyGroupDest> { Screen { StudyGroupRoute(onBack = back) } }
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
    val openDay = { date: LocalDate -> navController.navigate(DayDest(date.toString())) }
    val openAssignment = { id: String -> navController.navigate(AssignmentDetailDest(id)) }

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
                    onOpenAssignment = openAssignment,
                )
                Tab.Deadlines -> DeadlinesRoute(onOpenAssignment = openAssignment, onOpenSettings = openSettings)
                Tab.Subjects -> SubjectsRoute(
                    onOpenSubject = { navController.navigate(SubjectDetailDest(it)) },
                    onOpenSettings = openSettings,
                    onManageSubjects = { navController.navigate(ManageSubjectsDest) },
                    onOpenHistory = { navController.navigate(HistoryDest) },
                    onPlanLeave = { navController.navigate(LeavePlannerDest) },
                )
                Tab.Study -> StudyRoute(
                    onOpenGroup = { navController.navigate(StudyGroupDest(it)) },
                    onOpenSettings = openSettings,
                )
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
