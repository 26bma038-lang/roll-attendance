package app.roll.feature.today

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

    private fun viewModel(scope: TestScope): TodayViewModel {
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val appScope = TestScope(dispatcher)
        val profile = UserProfile("me", "Sahil", "26BMA038", "26bma038@nith.ac.in", 2026, "MA", "nith-26-ma")
        val session = AppSession(
            auth = FakeAuthRepository(AuthUser("me", "Sahil", "26bma038@nith.ac.in")),
            profiles = FakeProfileRepository(profile),
            classes = FakeClassRepository(RollClass("nith-26-ma", "MA 2026", "Semester 1", 2026, "MA", listOf("cr"), listOf("CR"), 75, "Asia/Kolkata", 60)),
            scope = appScope.backgroundScope,
        )
        val schedule = FakeScheduleRepository(sessions, listOf(Subject("daa", "Design and Analysis of Algorithms", "DAA", "", "", 0, false)))
        val classData = ClassData(session, schedule, marks, FakeAssignmentRepository(), FakeNoticeRepository(), appScope.backgroundScope)
        return TodayViewModel(
            session = session,
            classData = classData,
            marks = marks,
            time = FakeTimeSource(today.atTime(12, 0).atZone(zone).toInstant()),
            schedule = schedule,
            messages = UserMessages(),
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
}
