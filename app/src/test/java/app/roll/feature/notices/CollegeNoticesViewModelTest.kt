package app.roll.feature.notices

import app.cash.turbine.test
import app.roll.core.common.UserMessages
import app.roll.data.auth.AuthUser
import app.roll.data.feed.FeedLoad
import app.roll.data.feed.NithNoticeTracker
import app.roll.data.session.AppSession
import app.roll.domain.feed.CollegeEntry
import app.roll.domain.feed.NithCategory
import app.roll.domain.feed.NithNotice
import app.roll.domain.model.Notice
import app.roll.domain.model.RollClass
import app.roll.domain.model.UserProfile
import app.roll.testing.FakeAuthRepository
import app.roll.testing.FakeClassRepository
import app.roll.testing.FakeCollegeFeedRepository
import app.roll.testing.FakeNoticeRepository
import app.roll.testing.FakePreferences
import app.roll.testing.FakeProfileRepository
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
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CollegeNoticesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-07T06:00:00Z")
    private val lastVisit = Instant.parse("2026-11-06T00:00:00Z")

    private fun nith(id: String, category: NithCategory, date: String, firstSeen: Instant) =
        NithNotice(id, "Notice $id", "https://nith.ac.in/$id.pdf", category, "November 2026", LocalDate.parse(date), firstSeen)

    private val website = listOf(
        nith("fresh-exam", NithCategory.Exams, "2026-11-05", Instant.parse("2026-11-07T03:00:00Z")),
        nith("old-academic", NithCategory.Academic, "2026-10-01", Instant.parse("2026-11-01T00:00:00Z")),
        nith("old-students", NithCategory.Students, "2026-10-20", Instant.parse("2026-11-01T00:00:00Z")),
    )

    private val feed = FakeCollegeFeedRepository(FeedLoad.Loaded(website))
    private val preferences = FakePreferences()
    private val notices = FakeNoticeRepository().apply {
        collegeNotices.value = listOf(
            Notice("live", "Library open late", false, Instant.parse("2026-11-06T05:00:00Z"), null, "Admin", college = true),
            Notice("expired", "Old notice", false, Instant.parse("2026-10-01T05:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"), "Admin", college = true),
        )
    }

    private fun parts(scope: TestScope, email: String): Pair<AppSession, NithNoticeTracker> {
        val appScope = TestScope(UnconfinedTestDispatcher(scope.testScheduler))
        val local = email.substringBefore('@')
        val profile = UserProfile("me", "Student", local.uppercase(), email, 2026, "MA", "nith-26-ma")
        val session = AppSession(
            auth = FakeAuthRepository(AuthUser("me", "Student", email)),
            profiles = FakeProfileRepository(profile),
            classes = FakeClassRepository(RollClass("nith-26-ma", "MA 2026", "Semester 1", 2026, "MA", emptyList(), emptyList(), 75, "Asia/Kolkata", 60)),
            scope = appScope.backgroundScope,
        )
        val tracker = NithNoticeTracker(feed, preferences, FakeTimeSource(now), appScope.backgroundScope)
        return session to tracker
    }

    private fun viewModel(scope: TestScope, email: String = "26bma020@nith.ac.in"): CollegeNoticesViewModel {
        val (session, tracker) = parts(scope, email)
        return CollegeNoticesViewModel(session, FakeTimeSource(now), tracker, notices, UserMessages())
    }

    @Test
    fun student_seesLiveAdminNoticesAndTheWebsite_inDateOrder_withNewMarks() = runTest {
        preferences.nithNoticesSeenAt.value = lastVisit
        viewModel(this).uiState.test {
            val state = expectMostRecentItem()
            assertEquals(FeedStatus.Ready, state.feedStatus)
            assertEquals(
                listOf("admin:live", "nith:fresh-exam", "nith:old-students", "nith:old-academic"),
                state.entries.map { it.key },
            )
            val newOnes = state.entries.filterIsInstance<CollegeEntry.Website>().filter { it.isNew }.map { it.notice.id }
            assertEquals(listOf("fresh-exam"), newOnes)
        }
    }

    @Test
    fun openingTheScreen_marksEverythingSeen() = runTest {
        preferences.nithNoticesSeenAt.value = lastVisit
        viewModel(this).uiState.test {
            expectMostRecentItem()
            assertEquals(now, preferences.nithNoticesSeenAt.value)
        }
    }

    @Test
    fun theAdmin_alsoSeesExpiredNotices_toManageThem() = runTest {
        viewModel(this, "26bma038@nith.ac.in").uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.isAdmin)
            assertEquals(listOf("live", "expired"), state.adminNotices.map { it.id })
        }
    }

    @Test
    fun categoryChips_showOnlyThatCategoryFromTheWebsite() = runTest {
        val vm = viewModel(this)
        vm.uiState.test {
            vm.setCategory(NithCategory.Exams)
            assertEquals(listOf("nith:fresh-exam"), expectMostRecentItem().entries.map { it.key })
            vm.setCategory(NithCategory.Students)
            assertEquals(listOf("nith:old-students"), expectMostRecentItem().entries.map { it.key })
            vm.setCategory(null)
            assertEquals(4, expectMostRecentItem().entries.size)
        }
    }

    @Test
    fun offlineAndFailures_areReported_adminNoticesStillShow() = runTest {
        feed.load.value = FeedLoad.Offline
        val vm = viewModel(this)
        vm.uiState.test {
            val offline = expectMostRecentItem()
            assertEquals(FeedStatus.Offline, offline.feedStatus)
            assertEquals(listOf("admin:live"), offline.entries.map { it.key })
            feed.load.value = FeedLoad.Failed
            assertEquals(FeedStatus.Failed, expectMostRecentItem().feedStatus)
        }
    }

    // ---------- the tracker behind the nav dot ----------

    @Test
    fun tracker_countsNewSinceTheLastVisit() = runTest {
        preferences.nithNoticesSeenAt.value = lastVisit
        val (_, tracker) = parts(this, "26bma020@nith.ac.in")
        tracker.newCount.test {
            assertEquals(1, expectMostRecentItem())
        }
    }

    @Test
    fun tracker_firstTimeOnAPhone_nothingIsNew_andTheVisitIsRemembered() = runTest {
        assertNull(preferences.nithNoticesSeenAt.value)
        val (_, tracker) = parts(this, "26bma020@nith.ac.in")
        tracker.newCount.test {
            assertEquals(0, expectMostRecentItem())
            assertEquals(now, preferences.nithNoticesSeenAt.value)
        }
    }
}
