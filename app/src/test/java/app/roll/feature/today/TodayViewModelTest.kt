package app.roll.feature.today

import app.roll.data.feed.FeedLoad
import app.roll.data.feed.NithNoticeTracker
import app.roll.domain.feed.NithCategory
import app.roll.domain.feed.NithNotice
import app.roll.domain.model.Assignment
import app.roll.domain.model.AssignmentType
import app.roll.domain.model.TimetableSlot
import app.roll.testing.FakeCollegeFeedRepository
import app.cash.turbine.test
import app.roll.core.common.UserMessages
import app.roll.data.auth.AuthUser
import app.roll.data.session.AppSession
import app.roll.data.session.ClassData
import app.roll.domain.model.Mark
import app.roll.domain.model.RollClass
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.SessionType
import app.roll.domain.model.Subject
import app.roll.domain.model.UserProfile
import app.roll.testing.FakeAssignmentRepository
import app.roll.testing.FakeAuthRepository
import app.roll.testing.FakeClassRepository
import app.roll.testing.FakeMarkRepository
import app.roll.testing.FakeNoticeRepository
import app.roll.testing.FakePreferences
import app.roll.testing.FakeProfileRepository
import app.roll.testing.FakeScheduleRepository
import app.roll.testing.FakeTimeSource
import app.roll.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.of(2026, 11, 5)
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun session(id: String, date: LocalDate, start: Int, status: SessionStatus = SessionStatus.Scheduled) =
        Session(id, "daa", date, start, start + 50, SessionType.Lecture, status, "", "LH-204", 1, "cr", "Sahil", null)

    private val sessions = listOf(
        session("y1", today.minusDays(1), 540),
        session("y2", today.minusDays(1), 600),
        session("y3", today.minusDays(1), 660, SessionStatus.Cancelled),
        session("t2", today, 600),
        session("t1", today, 540),
        session("tomorrow", today.plusDays(1), 540),
    )

    private val marks = FakeMarkRepository()

    private val assignments = FakeAssignmentRepository()
    private val feed = FakeCollegeFeedRepository()
    private var crUids = listOf("cr")
    private var classSessions = sessions
    private val slot = TimetableSlot("s1", 1, 540, 590, "daa", SessionType.Lecture, "LH-204")

    private fun viewModel(scope: TestScope, timetable: List<TimetableSlot> = listOf(slot)): TodayViewModel {
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val appScope = TestScope(dispatcher)
        val profile = UserProfile("me", "Sahil", "26BMA038", "26bma038@nith.ac.in", 2026, "MA", "nith-26-ma")
        val session = AppSession(
            auth = FakeAuthRepository(AuthUser("me", "Sahil", "26bma038@nith.ac.in")),
            profiles = FakeProfileRepository(profile),
            classes = FakeClassRepository(RollClass("nith-26-ma", "MA 2026", "Semester 1", 2026, "MA", crUids, crUids.map { "CR" }, 75, "Asia/Kolkata", 60)),
            scope = appScope.backgroundScope,
        )
        val schedule = FakeScheduleRepository(classSessions, listOf(Subject("daa", "Design and Analysis of Algorithms", "DAA", "", "", 0, false)))
        schedule.timetable.value = timetable
        val classData = ClassData(session, schedule, marks, assignments, FakeNoticeRepository(), appScope.backgroundScope)
        val time = FakeTimeSource(today.atTime(12, 0).atZone(zone).toInstant())
        val preferences = FakePreferences()
        return TodayViewModel(
            session = session,
            classData = classData,
            marks = marks,
            time = time,
            schedule = schedule,
            messages = UserMessages(),
            preferences = preferences,
            tracker = NithNoticeTracker(feed, preferences, time, appScope.backgroundScope),
        )
    }

    @Test
    fun showsOnlyTodaysSessions_inTimeOrder() = runTest {
        val vm = viewModel(this)
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertEquals(today, state.date)
            assertEquals(listOf("t1", "t2"), state.items.map { it.session.id })
            assertTrue(state.items.all { it.editable })
        }
    }

    @Test
    fun countsYesterdaysUnmarked_ignoringCancelled() = runTest {
        marks.marks.value = mapOf("y1" to Mark.Present)
        val vm = viewModel(this)
        vm.uiState.test {
            assertEquals(1, expectMostRecentItem().yesterdayUnmarked)
        }
    }

    @Test
    fun clearingAMark_offersUndo_andUndoRestoresIt() = runTest {
        marks.marks.value = mapOf("t1" to Mark.Present)
        val vm = viewModel(this)
        vm.uiState.test {
            val item = expectMostRecentItem().items.first { it.session.id == "t1" }
            assertEquals(Mark.Present, item.mark)

            vm.onMark(item, null)
            val cleared = expectMostRecentItem()
            assertNull(cleared.items.first { it.session.id == "t1" }.mark)
            assertEquals("t1", cleared.clearedMark?.sessionId)

            vm.onUndo()
            val restored = expectMostRecentItem()
            assertEquals(Mark.Present, restored.items.first { it.session.id == "t1" }.mark)
            assertNull(restored.clearedMark)
        }
        assertEquals(listOf("t1" to null, "t1" to Mark.Present), marks.writes)
    }

    @Test
    fun markingAnUnmarkedSession_writesIt() = runTest {
        val vm = viewModel(this)
        vm.uiState.test {
            val item = expectMostRecentItem().items.first { it.session.id == "t2" }
            vm.onMark(item, Mark.Absent)
            assertEquals(Mark.Absent, expectMostRecentItem().items.first { it.session.id == "t2" }.mark)
        }
    }

    // ---------- an empty day ----------

    private val noClassesToday = sessions.filter { it.date != today }

    private fun assignment(id: String, dueInDays: Long) = Assignment(
        id, "Assignment $id", "daa", AssignmentType.Assignment, "", "",
        today.plusDays(dueInDays).atTime(23, 59).atZone(zone).toInstant(), "cr", "CR", null,
    )

    private fun nith(id: String, category: NithCategory, date: LocalDate) =
        NithNotice(id, "Notice $id", "https://nith.ac.in/$id.pdf", category, null, date, null)

    @Test
    fun emptyDay_showsTheNextDeadline_andTheLatestThreeAcademicNotices() = runTest {
        classSessions = noClassesToday
        assignments.assignments.value = listOf(assignment("later", 9), assignment("next", 5), assignment("past", -1))
        feed.load.value = FeedLoad.Loaded(
            listOf(
                nith("a1", NithCategory.Exams, today.minusDays(1)),
                nith("s1", NithCategory.Students, today.minusDays(2)),
                nith("a2", NithCategory.Academic, today.minusDays(3)),
                nith("a3", NithCategory.Academic, today.minusDays(4)),
                nith("a4", NithCategory.Academic, today.minusDays(5)),
            ),
        )
        viewModel(this).uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.items.isEmpty())
            assertTrue(state.dueSoon.isEmpty())
            assertEquals("next", state.nextDeadline?.assignment?.id)
            assertEquals(listOf("a1", "a2", "a3"), state.latestNotices.map { it.id })
        }
    }

    @Test
    fun aDeadlineDueSoon_isNotRepeatedAsTheNextDeadline() = runTest {
        assignments.assignments.value = listOf(assignment("soon", 1))
        viewModel(this).uiState.test {
            val state = expectMostRecentItem()
            assertEquals(listOf("soon"), state.dueSoon.map { it.assignment.id })
            assertNull(state.nextDeadline)
        }
    }

    @Test
    fun noTimetable_andNoCr_areKnown_soTheEmptyStateCanSayWhy() = runTest {
        classSessions = noClassesToday
        crUids = emptyList()
        viewModel(this, timetable = emptyList()).uiState.test {
            val state = expectMostRecentItem()
            assertEquals("MA 2026", state.noCrClassName)
            assertTrue(!state.hasTimetable)
            assertTrue(!state.isCR)
        }
    }
}
