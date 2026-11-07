package app.roll.feature.study

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.roll.core.common.UserMessages
import app.roll.data.auth.AuthUser
import app.roll.data.materials.MaterialsLoad
import app.roll.data.session.AppSession
import app.roll.domain.materials.MaterialItem
import app.roll.domain.materials.MaterialSource
import app.roll.domain.materials.StudyGroup
import app.roll.domain.model.RollClass
import app.roll.domain.model.UserProfile
import app.roll.testing.FakeAuthRepository
import app.roll.testing.FakeClassRepository
import app.roll.testing.FakeMaterialRepository
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
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class StudyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val today = LocalDate.of(2026, 11, 7)
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun item(
        id: String,
        section: String,
        title: String,
        code: String? = "MA-111",
        name: String = "Eng. Mathematics-1",
        itemTitle: String = "MA-111(Eng. Mathematics-1)",
        exam: String? = null,
        year: String? = null,
    ) = MaterialItem(id, section, section, code, name, itemTitle, title, exam, year, "https://drive.google.com/file/d/$id/view")

    private val firstYearItems = listOf(
        item("n1", "notes", "Unit 1 Calculus"),
        item("b1", "books", "Thomas Calculus"),
        item("s1", "solutions", "Solution 1"),
        item("a1", "assignments", "Assignment 1"),
        // Papers in a jumbled order on purpose.
        item("p1", "pyq", "PAPER 1"),
        item("p2", "pyq", "MID SEM 2024-25", exam = "MID", year = "2024-25"),
        item("p3", "pyq", "END SEM 2025-26", exam = "END", year = "2025-26"),
        item("p4", "pyq", "END SEM 2024-25", exam = "END", year = "2024-25"),
        item("p5", "pyq", "MID SEM 2023 (NEW)", exam = "MID", year = "2023"),
        item("c1", "notes", "Pointers", code = "CS-101", name = "Programming Fundamentals", itemTitle = "CS-101(Programming Fundamentals)"),
        item("l1", "practicals", "C lab file", code = "CS-102", name = "CS-102", itemTitle = "CS-102"),
        item("f1", "notes", "Fourier Series notes", code = null, name = "Sunil Sir's notes", itemTitle = "Fourier Series"),
        item("w1", "workshop", "Civil workshop", code = null, name = "CIVIL", itemTitle = "CIVIL"),
        item("y1", "syllabus", "First year scheme", code = null, name = "Course structure", itemTitle = "Course structure"),
        item("d1", "drawing", "Sheet 1", code = null, name = "Assignment", itemTitle = "Assignment"),
        item("k1", "calendar", "Calendar 2026-27", code = null, name = "Academic Calendar", itemTitle = "Academic Calendar", year = "2026-27"),
    )

    /** As tools/classroom-sync writes it: every branch, first year only. */
    private val firstYear = MaterialSource(
        id = "first-year",
        title = "First year",
        credit = "Material shared by Krish Bhutani",
        classroomUrl = "https://classroom.google.com/w/NjI2OTQwMTM5NDAy/t/all",
        years = listOf(1),
        branches = listOf(MaterialSource.ALL_BRANCHES),
        items = firstYearItems,
    )

    /** A source for one branch and year only, to show branch lists still work next to "*". */
    private val secondYearCs = MaterialSource(
        id = "sy-cs", title = "Second year", credit = "Material shared by someone", classroomUrl = "",
        years = listOf(2), branches = listOf("CS"),
        items = listOf(item("x1", "notes", "Data structures", code = "CS-201", name = "DSA", itemTitle = "CS-201(DSA)")),
    )

    private var repository = FakeMaterialRepository(MaterialsLoad.Loaded(listOf(firstYear)))

    /** Every branch Roll supports, as in NithConfig.branches. */
    private val branches = listOf("ma", "cs", "ch", "ce", "me", "ec")

    /** A signed-in student from [email], e.g. 26bma038 = 2026 batch, MA. */
    private fun materials(scope: TestScope, email: String = "26bma038@nith.ac.in"): StudyMaterials {
        val appScope = TestScope(UnconfinedTestDispatcher(scope.testScheduler))
        val local = email.substringBefore('@')
        val batch = 2000 + local.take(2).toInt()
        val branch = local.substring(3, 5).uppercase()
        val classId = "nith-${local.take(2)}-${branch.lowercase()}"
        val profile = UserProfile("me", "Student", local.uppercase(), email, batch, branch, classId)
        val session = AppSession(
            auth = FakeAuthRepository(AuthUser("me", "Student", email)),
            profiles = FakeProfileRepository(profile),
            classes = FakeClassRepository(RollClass(classId, "$branch $batch", "Semester 1", batch, branch, emptyList(), emptyList(), 75, "Asia/Kolkata", 60)),
            scope = appScope.backgroundScope,
        )
        return StudyMaterials(session, repository, FakeTimeSource(today.atTime(12, 0).atZone(zone).toInstant()))
    }

    private fun home(scope: TestScope, email: String = "26bma038@nith.ac.in") =
        StudyViewModel(materials(scope, email), UserMessages())

    private fun group(scope: TestScope, key: String) =
        StudyGroupViewModel(SavedStateHandle(mapOf("key" to key)), materials(scope), UserMessages())

    // ---------- who sees what ----------

    @Test
    fun firstYearMaStudent_seesTheFirstYearSource_withItsCredit() = runTest {
        home(this).uiState.test {
            val state = expectMostRecentItem()
            assertEquals(StudyStatus.Ready, state.status)
            assertEquals(listOf(StudyCredit("Material shared by Krish Bhutani", firstYear.classroomUrl)), state.credits)
            assertEquals(listOf("MA"), repository.branchesAsked.distinct())
        }
    }

    @Test
    fun firstYearStudentOfEveryBranch_seesTheMaterial() = runTest {
        for (branch in branches) {
            home(this, "26b${branch}001@nith.ac.in").uiState.test {
                val state = expectMostRecentItem()
                assertEquals("26b${branch}001", StudyStatus.Ready, state.status)
                assertEquals("26b${branch}001", listOf("CS-101", "CS-102", "MA-111"), state.subjects.map { (it.group as StudyGroup.Subject).code })
            }
        }
    }

    @Test
    fun secondYearStudentOfEveryBranch_seesTheEmptyState() = runTest {
        // 2025 batch in November 2026 = semester 3 = second year.
        for (branch in branches) {
            home(this, "25b${branch}010@nith.ac.in").uiState.test {
                val state = expectMostRecentItem()
                assertEquals("25b${branch}010", StudyStatus.NotHereYet, state.status)
                assertTrue(state.subjects.isEmpty())
            }
        }
    }

    @Test
    fun aBranchListedSource_reachesOnlyThatBranchAndYear() = runTest {
        repository = FakeMaterialRepository(MaterialsLoad.Loaded(listOf(firstYear, secondYearCs)))
        home(this, "25bcs001@nith.ac.in").uiState.test {
            val state = expectMostRecentItem()
            assertEquals(StudyStatus.Ready, state.status)
            assertEquals(listOf("CS-201"), state.subjects.map { (it.group as StudyGroup.Subject).code })
        }
        home(this, "25bma001@nith.ac.in").uiState.test {
            assertEquals(StudyStatus.NotHereYet, expectMostRecentItem().status)
        }
        // First-years of CS still get only the "*" material, not the second-year CS source.
        home(this, "26bcs001@nith.ac.in").uiState.test {
            val codes = expectMostRecentItem().subjects.map { (it.group as StudyGroup.Subject).code }
            assertEquals(listOf("CS-101", "CS-102", "MA-111"), codes)
        }
    }

    @Test
    fun loadingOfflineAndFailures_passThrough() = runTest {
        val vm = home(this)
        vm.uiState.test {
            repository.load.value = MaterialsLoad.Loading
            assertEquals(StudyStatus.Loading, expectMostRecentItem().status)
            repository.load.value = MaterialsLoad.Offline
            assertEquals(StudyStatus.Offline, expectMostRecentItem().status)
            repository.load.value = MaterialsLoad.Failed
            assertEquals(StudyStatus.Failed, expectMostRecentItem().status)
        }
    }

    // ---------- home rows ----------

    @Test
    fun home_listsSubjectsByCode_andTheOtherGroupsInTheirFixedOrder() = runTest {
        home(this).uiState.test {
            val state = expectMostRecentItem()
            assertEquals(listOf("CS-101", "CS-102", "MA-111"), state.subjects.map { (it.group as StudyGroup.Subject).code })
            assertEquals(9, state.subjects.first { it.group.key == "s:MA-111" }.fileCount)
            assertEquals(
                listOf("Sunil Sir's notes", "Syllabus", "Academic calendar", "Workshop", "Engineering drawing"),
                state.others.map { it.group.title },
            )
        }
    }

    @Test
    fun subjectRow_namesItsSections_inSubjectScreenOrder() = runTest {
        home(this).uiState.test {
            val ma = expectMostRecentItem().subjects.first { it.group.key == "s:MA-111" }
            assertEquals(listOf("Previous year papers", "Notes", "Books", "Assignments", "Solutions"), ma.sectionLabels)
        }
    }

    // ---------- subject screen ----------

    @Test
    fun subjectScreen_showsSectionsInOrder_onlyThoseWithFiles() = runTest {
        group(this, "s:MA-111").uiState.test {
            val state = expectMostRecentItem()
            assertEquals(StudyGroup.Subject("MA-111", "Eng. Mathematics-1"), state.group)
            assertEquals(
                listOf("Previous year papers", "Notes", "Books", "Assignments", "Solutions"),
                state.blocks.map { it.title },
            )
        }
    }

    @Test
    fun papers_newestYearFirst_endBeforeMid_untaggedLast() = runTest {
        group(this, "s:MA-111").uiState.test {
            val papers = expectMostRecentItem().blocks.first().items
            assertEquals(listOf("p3", "p4", "p2", "p5", "p1"), papers.map { it.id })
        }
    }

    @Test
    fun sunilSirsNotes_areGroupedByChapter() = runTest {
        group(this, "sunil").uiState.test {
            val state = expectMostRecentItem()
            assertEquals(StudyGroup.SunilSir, state.group)
            assertEquals(listOf("Fourier Series"), state.blocks.map { it.title })
        }
    }

    @Test
    fun unknownKey_hasNoGroup() = runTest {
        group(this, "s:XX-999").uiState.test {
            val state = expectMostRecentItem()
            assertEquals(StudyStatus.Ready, state.status)
            assertNull(state.group)
        }
    }

    // ---------- search ----------

    @Test
    fun search_matchesCodeNameAndTitle() = runTest {
        val vm = home(this)
        vm.uiState.test {
            vm.setQuery("ma-111")
            assertEquals(9, expectMostRecentItem().results.size)
            vm.setQuery("MA111")
            assertEquals(9, expectMostRecentItem().results.size)
            vm.setQuery("programming")
            assertEquals(listOf("c1"), expectMostRecentItem().results.map { it.id })
            vm.setQuery("end sem 2024")
            assertEquals(listOf("p4"), expectMostRecentItem().results.map { it.id })
            vm.setQuery("fourier")
            assertEquals(listOf("f1"), expectMostRecentItem().results.map { it.id })
        }
    }

    @Test
    fun search_blankOrUnmatched_returnsNothing() = runTest {
        val vm = home(this)
        vm.uiState.test {
            vm.setQuery("   ")
            val blank = expectMostRecentItem()
            assertTrue(!blank.searching && blank.results.isEmpty())
            vm.setQuery("organic chemistry")
            val none = expectMostRecentItem()
            assertTrue(none.searching && none.results.isEmpty())
        }
    }
}
