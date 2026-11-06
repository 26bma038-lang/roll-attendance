package app.roll.screenshot

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.roll.core.designsystem.component.RollBottomBar
import app.roll.core.designsystem.component.RollNavItem
import app.roll.core.designsystem.icon.RollIcons
import app.roll.core.designsystem.theme.RollTheme
import app.roll.data.preferences.ReminderSettings
import app.roll.data.session.ClassContext
import app.roll.data.session.Rejection
import app.roll.domain.college.NithIdentity
import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.model.Mark
import app.roll.domain.model.Member
import app.roll.domain.model.RollClass
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.model.ThemeMode
import app.roll.domain.model.TimetableSlot
import app.roll.domain.model.UserProfile
import app.roll.feature.admin.AdminClassScreen
import app.roll.feature.admin.AdminClassUiState
import app.roll.feature.admin.AdminHomeScreen
import app.roll.feature.admin.AdminHomeUiState
import app.roll.feature.admin.AdminDepartmentScreen
import app.roll.feature.department.DepartmentScreen
import app.roll.feature.department.DepartmentUiState
import app.roll.domain.model.Department
import app.roll.domain.model.DepartmentLink
import app.roll.domain.deadlines.Deadlines
import app.roll.domain.model.Assignment
import app.roll.domain.model.AssignmentType
import app.roll.feature.deadlines.AssignmentDetailScreen
import app.roll.feature.deadlines.AssignmentDetailUiState
import app.roll.feature.deadlines.DeadlinesScreen
import app.roll.feature.deadlines.DeadlinesUiState
import app.roll.feature.deadlines.deadlineItem
import app.roll.domain.model.Notice
import app.roll.feature.notices.CollegeNoticesScreen
import app.roll.feature.notices.CollegeNoticesUiState
import app.roll.feature.auth.SignInScreen
import app.roll.feature.auth.SignInUiState
import app.roll.feature.common.sessionItems
import app.roll.feature.history.Dot
import app.roll.feature.history.HistoryScreen
import app.roll.feature.history.HistoryUiState
import app.roll.feature.manage.ClassSettingsScreen
import app.roll.feature.manage.ClassSettingsUiState
import app.roll.feature.manage.ManageScreen
import app.roll.feature.manage.ManageUiState
import app.roll.feature.manage.ManagedSession
import app.roll.feature.manage.SessionCounts
import app.roll.feature.manage.SessionDetailScreen
import app.roll.feature.manage.SessionDetailUiState
import app.roll.feature.manage.TimetableScreen
import app.roll.feature.manage.TimetableUiState
import app.roll.feature.onboarding.ProfileSetupScreen
import app.roll.feature.onboarding.ProfileSetupUiState
import app.roll.feature.settings.SettingsScreen
import app.roll.feature.settings.SettingsUiState
import app.roll.feature.subjects.SessionLine
import app.roll.feature.subjects.SubjectDetailScreen
import app.roll.feature.subjects.SubjectDetailUiState
import app.roll.feature.subjects.SubjectsScreen
import app.roll.feature.subjects.SubjectsUiState
import app.roll.feature.subjects.summarize
import app.roll.feature.today.TodayScreen
import app.roll.feature.today.TodayUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Every main screen with realistic sample state, light and dark, for design review without a
 * device. Record with `./gradlew recordRoborazziDebug`; images in app/build/outputs/roborazzi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w392dp-h860dp-xxhdpi", application = Application::class)
class ScreensScreenshotTest {

    private val today = LocalDate.of(2026, 11, 5)
    private val now = today.atTime(12, 30)

    private val subjects = listOf(
        Subject("daa", "Design and Analysis of Algorithms", "DAA", "21CSC204J", "Dr. R. Iyer", 0, false),
        Subject("dbms", "Database Management Systems", "DBMS", "21CSC205P", "Prof. Mehta", 1, false),
        Subject("cn", "Computer Networks", "CN", "21CSC302J", "Dr. Kaur", 2, false),
        Subject("os", "Operating Systems Laboratory", "OS", "21CSC206L", "Dr. Thomas", 4, false),
        Subject("ml", "Machine Learning", "ML", "21CSE356T", "Dr. Bose", 3, false),
    )

    private fun s(id: String, subject: String, date: LocalDate, start: Int, end: Int, type: SessionType = SessionType.Lecture, status: SessionStatus = SessionStatus.Scheduled, note: String = "", weight: Int = 1, room: String = "LH-204") =
        Session(id, subject, date, start, end, type, status, note, room, weight, "cr", "Sahil", null)

    private val todaySessions = listOf(
        s("t1", "daa", today, 540, 590),
        s("t2", "dbms", today, 590, 640),
        s("t3", "cn", today, 650, 700, status = SessionStatus.Cancelled, note = "faculty on leave"),
        s("t4", "os", today, 700, 800, SessionType.Lab, weight = 2, room = "Lab 3"),
        s("t5", "ml", today, 850, 900, SessionType.Tutorial, room = "T-12"),
    )

    /** Six weeks of history, deterministic. */
    private val pastSessions: List<Session> = buildList {
        var day = today.minusDays(42)
        var n = 0
        while (day.isBefore(today)) {
            if (day.dayOfWeek.value <= 5) {
                subjects.forEachIndexed { i, subject ->
                    if ((day.dayOfMonth + i) % 2 == 0) add(s("p${n++}", subject.id, day, 540 + i * 60, 590 + i * 60))
                }
            }
            day = day.plusDays(1)
        }
    }

    private val marks: Map<String, Mark> = pastSessions.mapIndexedNotNull { i, session ->
        when {
            session.date == today.minusDays(1) && i % 2 == 0 -> null // two unmarked yesterday
            session.subjectId == "cn" && i % 3 == 0 -> Mark.Absent
            session.subjectId == "os" && i % 2 == 0 -> Mark.Absent
            i % 7 == 0 -> Mark.Absent
            else -> Mark.Present
        }?.let { session.id to it }
    }.toMap() + mapOf("t1" to Mark.Present, "t4" to Mark.Absent)

    private val rollClass = RollClass("nith-26-ma", "MA 2026", "Semester 1 · Odd 2026-27", 2026, "MA", listOf("me"), listOf("Sahil Verma"), 75, "Asia/Kolkata", 58)
    private val identity = NithIdentity("26bma038@nith.ac.in", 2026, "MA", "26BMA038")
    private val profile = UserProfile("me", "Sahil Verma", "26BMA038", "26bma038@nith.ac.in", 2026, "MA", "nith-26-ma")
    private val members = listOf(
        Member("a", "Aarav Mehta", "26BMA002", null),
        Member("b", "Diya Rao", "26BMA017", null),
        Member("me", "Sahil Verma", "26BMA038", null),
        Member("d", "Kabir Singh", "26BMA051", null),
        Member("e", "Meera Pillai", "26BMA063", null),
    )

    @Composable
    private fun Frame(dark: Boolean, tabs: Boolean = false, selected: Int = 0, content: @Composable () -> Unit) {
        RollTheme(darkTheme = dark) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(RollTheme.colors.background),
            ) {
                if (tabs) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { content() }
                        RollBottomBar(
                            items = listOf(
                                RollNavItem("Today", RollIcons.Today),
                                RollNavItem("Deadlines", RollIcons.Deadlines),
                                RollNavItem("Subjects", RollIcons.Subjects),
                                RollNavItem("Department", RollIcons.Department),
                                RollNavItem("Manage", RollIcons.Manage),
                            ),
                            selectedIndex = selected,
                            onSelect = {},
                        )
                    }
                } else {
                    content()
                }
            }
        }
    }

    private fun shot(name: String, dark: Boolean = false, tabs: Boolean = false, selected: Int = 0, content: @Composable () -> Unit) =
        captureRoboImage("build/outputs/roborazzi/$name.png") { Frame(dark, tabs, selected, content) }

    private fun todayState() = TodayUiState(
        loading = false,
        date = today,
        items = sessionItems(todaySessions, subjects.associateBy { it.id }, marks, now),
        yesterdayUnmarked = 2,
        pendingSync = true,
        postedBy = "Posted by Sahil · 8:41 am",
    )

    @Test fun today_light() = shot("01_today_light", tabs = true) { TodayScreen(todayState(), { _, _ -> }, {}, {}, {}) }
    @Test fun today_dark() = shot("01_today_dark", dark = true, tabs = true) { TodayScreen(todayState(), { _, _ -> }, {}, {}, {}) }

    @Test
    @Config(qualifiers = "w392dp-h860dp-xxhdpi", fontScale = 2f)
    fun today_font200() = shot("01_today_font200", tabs = true) { TodayScreen(todayState(), { _, _ -> }, {}, {}, {}) }

    @Test fun today_empty() = shot("01_today_empty", tabs = true) {
        TodayScreen(TodayUiState(loading = false, date = today), { _, _ -> }, {}, {}, {})
    }

    private fun subjectsState(): SubjectsUiState {
        val (summaries, overall) = summarize(subjects, pastSessions + todaySessions, marks, now, 75)
        return SubjectsUiState(loading = false, requiredPercent = 75, overall = overall, subjects = summaries, isCR = true)
    }

    @Test fun subjects_light() = shot("02_subjects_light", tabs = true, selected = 2) { SubjectsScreen(subjectsState(), {}, {}, {}) }
    @Test fun subjects_dark() = shot("02_subjects_dark", dark = true, tabs = true, selected = 2) { SubjectsScreen(subjectsState(), {}, {}, {}) }

    private fun detailState(): SubjectDetailUiState {
        val mine = (pastSessions + todaySessions).filter { it.subjectId == "cn" }
        val tally = AttendanceCalculator.tally(mine, marks, now)
        return SubjectDetailUiState(
            loading = false,
            subject = subjects[2],
            tally = tally,
            status = AttendanceCalculator.status(tally, 75),
            requiredPercent = 75,
            months = mine.sortedByDescending { it.date }
                .map { SessionLine(it, AttendanceCalculator.stateOf(it, marks[it.id], now)) }
                .groupBy { YearMonth.from(it.session.date) }.toList(),
        )
    }

    @Test fun subjectDetail_light() = shot("03_subject_detail_light") { SubjectDetailScreen(detailState(), {}) }

    private fun historyState() = HistoryUiState(
        month = YearMonth.from(today),
        today = today,
        dots = (pastSessions + todaySessions).groupBy { it.date }.mapValues { (_, list) ->
            list.mapNotNull {
                when (AttendanceCalculator.stateOf(it, marks[it.id], now)) {
                    app.roll.domain.attendance.SessionState.Present -> Dot.Present
                    app.roll.domain.attendance.SessionState.Absent -> Dot.Absent
                    app.roll.domain.attendance.SessionState.UnmarkedPast -> Dot.Unmarked
                    else -> null
                }
            }
        },
        firstMonth = YearMonth.from(today.minusDays(42)),
    )

    @Test fun history_light() = shot("04_history_light") { HistoryScreen(historyState(), {}, {}, {}) }
    @Test fun history_dark() = shot("04_history_dark", dark = true) { HistoryScreen(historyState(), {}, {}, {}) }

    private fun manageState() = ManageUiState(
        loading = false,
        today = today,
        sessions = todaySessions.map { session ->
            ManagedSession(
                session,
                subjects.first { it.id == session.subjectId },
                if (session.status == SessionStatus.Scheduled) SessionCounts(38, 4, 12) else null,
            )
        },
        toPost = 1,
        hasTimetable = true,
        subjects = subjects,
        memberCount = 54,
        slotCount = 26,
        className = "CSE DS — C",
    )

    @Test fun manage_light() = shot("05_manage_light", tabs = true, selected = 4) { ManageScreen(manageState(), {}, {}, {}, {}, {}, {}, {}) }

    @Test fun sessionDetail_light() = shot("06_session_detail_light") {
        SessionDetailScreen(
            SessionDetailUiState(
                loading = false,
                session = todaySessions[0],
                subject = subjects[0],
                subjects = subjects,
                today = today,
                present = members.take(3),
                absent = members.drop(3).take(1),
                notMarked = members.drop(4),
            ),
            {}, {}, { _, _ -> }, {},
        )
    }

    @Test fun timetable_light() = shot("07_timetable_light") {
        TimetableScreen(
            TimetableUiState(
                loading = false,
                slots = todaySessions.mapIndexed { i, it -> TimetableSlot("s$i", 1, it.startMinutes, it.endMinutes, it.subjectId, it.type, it.room) },
                subjects = subjects,
            ),
            {}, {}, {},
        )
    }

    @Test fun classSettings_light() = shot("08_class_settings_light") {
        ClassSettingsScreen(ClassSettingsUiState(rollClass, "me", members), {}, { _, _, _ -> })
    }

    @Test fun settings_dark() = shot("09_settings_dark", dark = true) {
        SettingsScreen(
            SettingsUiState(ThemeMode.Dark, ReminderSettings(true, 17 * 60), ClassContext("me", profile, rollClass, identity, isAdmin = true)),
            {}, {}, {}, {}, {}, {}, {},
        )
    }

    private val otherClasses = listOf(
        rollClass,
        RollClass("nith-26-cs", "CS 2026", "Semester 1 · Odd 2026-27", 2026, "CS", emptyList(), emptyList(), 75, "Asia/Kolkata", 71),
        RollClass("nith-25-ec", "EC 2025", "Semester 3 · Odd 2026-27", 2025, "EC", listOf("x", "y"), listOf("Ritika Sharma", "Arjun Nair"), 75, "Asia/Kolkata", 64),
    )

    @Test fun adminHome_light() = shot("12_admin_home_light") { AdminHomeScreen(AdminHomeUiState(false, otherClasses), {}, {}, {}, {}) }
    @Test fun adminHome_dark() = shot("12_admin_home_dark", dark = true) { AdminHomeScreen(AdminHomeUiState(false, otherClasses), {}, {}, {}, {}) }
    @Test fun adminClass_light() = shot("13_admin_class_light") { AdminClassScreen(AdminClassUiState(false, rollClass, members), {}, {}, {}) }
    @Test fun adminClass_dark() = shot("13_admin_class_dark", dark = true) { AdminClassScreen(AdminClassUiState(false, rollClass, members), {}, {}, {}) }

    private fun departmentState() = DepartmentUiState(
        rollClass = rollClass,
        today = today,
        slots = todaySessions.mapIndexed { i, it -> TimetableSlot("s$i", today.dayOfWeek.value, it.startMinutes, it.endMinutes, it.subjectId, it.type, it.room) } +
            todaySessions.take(3).mapIndexed { i, it -> TimetableSlot("m$i", 1, it.startMinutes, it.endMinutes, it.subjectId, it.type, it.room) },
        subjects = subjects,
        department = Department(code = "MA", name = "Mathematics and Computing", office = "Ground floor, Block B", links = listOf(DepartmentLink("Department page", "https://nith.ac.in"))),
        classesHeld = 42,
    )

    @Test fun department_light() = shot("14_department_light", tabs = true, selected = 3) { DepartmentScreen(departmentState(), {}) }
    @Test fun department_dark() = shot("14_department_dark", dark = true, tabs = true, selected = 3) { DepartmentScreen(departmentState(), {}) }
    @Test fun adminDepartment_light() = shot("15_admin_department_light") {
        AdminDepartmentScreen(Department(code = "MA", name = "Mathematics and Computing", links = listOf(DepartmentLink("Department page", "https://nith.ac.in"))), {}, {})
    }

    private val zoneIst = java.time.ZoneId.of("Asia/Kolkata")
    private val nowInstant = now.atZone(zoneIst).toInstant()
    private fun assignment(id: String, title: String, subject: String, type: AssignmentType, dueAt: java.time.LocalDateTime, description: String = "", link: String = "") =
        Assignment(id, title, subject, type, description, link, dueAt.atZone(zoneIst).toInstant(), "me", "Sahil Verma", null)
    private val assignments = listOf(
        assignment("a1", "Problem set 4", "daa", AssignmentType.Assignment, today.minusDays(1).atTime(23, 59)),
        assignment("a2", "Lab report: page replacement", "os", AssignmentType.LabReport, today.atTime(17, 0)),
        assignment("a3", "Quiz 2", "dbms", AssignmentType.Quiz, today.plusDays(1).atTime(10, 0)),
        assignment("a4", "Mid-sem", "cn", AssignmentType.MidSem, today.plusDays(5).atTime(9, 30)),
        assignment("a5", "Project proposal", "ml", AssignmentType.Presentation, today.plusDays(16).atTime(23, 59), "Two slides: problem and dataset. Teams of three.", "classroom.google.com/c/abc"),
        assignment("a6", "Problem set 3", "daa", AssignmentType.Assignment, today.minusDays(6).atTime(23, 59)),
    )
    private val done = setOf("a6")
    private fun deadlinesState(): DeadlinesUiState {
        val subjectMap = subjects.associateBy { it.id }
        return DeadlinesUiState(
            loading = false,
            groups = Deadlines.group(assignments, done, nowInstant, zoneIst).mapValues { (_, list) -> list.map { deadlineItem(it, subjectMap, done, nowInstant, zoneIst) } },
            subjects = subjects,
            canPost = true,
            today = today,
            zone = zoneIst,
        )
    }

    @Test fun deadlines_light() = shot("16_deadlines_light", tabs = true, selected = 1) { DeadlinesScreen(deadlinesState(), {}, {}, {}, { _, _ -> }, {}) }
    @Test fun deadlines_dark() = shot("16_deadlines_dark", dark = true, tabs = true, selected = 1) { DeadlinesScreen(deadlinesState(), {}, {}, {}, { _, _ -> }, {}) }

    private fun assignmentDetailState() = AssignmentDetailUiState(
        loading = false,
        item = deadlineItem(assignments[4], subjects.associateBy { it.id }, done, nowInstant, zoneIst),
        subjects = subjects,
        canEdit = true,
        today = today,
        zone = zoneIst,
    )

    @Test fun assignmentDetail_light() = shot("17_assignment_light") { AssignmentDetailScreen(assignmentDetailState(), {}, {}, {}, {}) }
    @Test fun assignmentDetail_dark() = shot("17_assignment_dark", dark = true) { AssignmentDetailScreen(assignmentDetailState(), {}, {}, {}, {}) }

    @Test fun todayDueSoon_light() = shot("18_today_due_soon_light", tabs = true) {
        val subjectMap = subjects.associateBy { it.id }
        TodayScreen(
            todayState().copy(dueSoon = Deadlines.dueSoon(assignments, done, nowInstant).map { deadlineItem(it, subjectMap, done, nowInstant, zoneIst) }),
            { _, _ -> }, {}, {}, {},
        )
    }

    private val sampleNotices = listOf(
        Notice("n1", "Mid-sem timetable is out on the notice board outside the department office.", pinned = true, createdAt = nowInstant.minusSeconds(7200), expiresAt = null, createdByName = "Admin", college = true),
        Notice("n2", "DAA lab moves to Lab 3 this week.", pinned = true, createdAt = nowInstant.minusSeconds(86_400), expiresAt = nowInstant.plusSeconds(3 * 86_400), createdByName = "Sahil Verma", college = false),
        Notice("n3", "Bring your ID cards for the industrial visit on Monday.", pinned = false, createdAt = nowInstant.minusSeconds(2 * 86_400), expiresAt = null, createdByName = "Sahil Verma", college = false),
    )

    @Test fun todayNotices_dark() = shot("19_today_notices_dark", dark = true, tabs = true) {
        TodayScreen(todayState().copy(pinnedNotices = sampleNotices.filter { it.pinned }), { _, _ -> }, {}, {}, {})
    }

    @Test fun departmentNotices_light() = shot("20_department_notices_light", tabs = true, selected = 3) {
        DepartmentScreen(departmentState().copy(notices = sampleNotices, canPostNotices = true), {})
    }

    @Test fun collegeNotices_light() = shot("21_college_notices_light") {
        CollegeNoticesScreen(CollegeNoticesUiState(sampleNotices.filter { it.college }, today), {}, {}, { _, _ -> }, {})
    }

    @Test fun signIn_light() = shot("10_sign_in_light") { SignInScreen(SignInUiState(), {}) }

    @Test fun signInRejected_dark() = shot("10_sign_in_rejected_dark", dark = true) {
        SignInScreen(SignInUiState(rejection = Rejection.UnsupportedBranch("26bee001@nith.ac.in")), {})
    }

    @Test fun profileSetup_light() = shot("11_profile_light") {
        ProfileSetupScreen(ProfileSetupUiState(), identity, "Sahil Verma", {}, {})
    }

    @Test fun profileSetup_dark() = shot("11_profile_dark", dark = true) {
        ProfileSetupScreen(ProfileSetupUiState(), identity, "Sahil Verma", {}, {})
    }
}
