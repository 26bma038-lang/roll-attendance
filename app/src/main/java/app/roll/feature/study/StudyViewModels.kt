package app.roll.feature.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roll.core.common.TimeSource
import app.roll.core.common.UserMessages
import app.roll.core.common.localDateIn
import app.roll.data.materials.MaterialRepository
import app.roll.data.materials.MaterialsLoad
import app.roll.data.session.AppSession
import app.roll.domain.materials.MaterialItem
import app.roll.domain.materials.MaterialSource
import app.roll.domain.materials.Study
import app.roll.domain.materials.StudyBlock
import app.roll.domain.materials.StudyGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Where the Study tab stands. */
enum class StudyStatus {
    Loading,
    Ready,

    /** No source covers this student's branch and year. */
    NotHereYet,

    /** First use with no connection and nothing cached. */
    Offline,
    Failed,
}

/** The sources that apply to the signed-in student, with a status. */
data class StudyData(
    val status: StudyStatus,
    val sources: List<MaterialSource> = emptyList(),
) {
    val items: List<MaterialItem> get() = sources.flatMap { it.items }
}

/**
 * The material for the signed-in student: sources listing their branch (the query) and their
 * current year (from batch + today).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyMaterials @Inject constructor(
    session: AppSession,
    repository: MaterialRepository,
    time: TimeSource,
) {
    val data: Flow<StudyData> = session.context
        .map { Triple(it.identity.branch, it.identity.batch, it.rollClass.timezone) }
        .distinctUntilChanged()
        .flatMapLatest { (branch, batch, timezone) ->
            repository.observe(branch).map { load ->
                when (load) {
                    MaterialsLoad.Loading -> StudyData(StudyStatus.Loading)
                    MaterialsLoad.Offline -> StudyData(StudyStatus.Offline)
                    MaterialsLoad.Failed -> StudyData(StudyStatus.Failed)
                    is MaterialsLoad.Loaded -> {
                        val year = Study.yearOfStudy(batch, time.now().localDateIn(timezone))
                        val mine = Study.sourcesFor(load.sources, branch, year)
                        if (mine.isEmpty()) StudyData(StudyStatus.NotHereYet) else StudyData(StudyStatus.Ready, mine)
                    }
                }
            }
        }
}

/** "Material shared by Krish Bhutani", and where to open the class. */
data class StudyCredit(val credit: String, val classroomUrl: String)

/** A row on the Study home: a subject or a group, with how many files it holds. */
data class StudyGroupRow(
    val group: StudyGroup,
    val fileCount: Int,
    /** For subjects: the sections it has files in, in subject-screen order ("Previous year papers", "Notes"). */
    val sectionLabels: List<String> = emptyList(),
)

data class StudyUiState(
    val status: StudyStatus = StudyStatus.Loading,
    val credits: List<StudyCredit> = emptyList(),
    val subjects: List<StudyGroupRow> = emptyList(),
    val others: List<StudyGroupRow> = emptyList(),
    val query: String = "",
    /** Files matching [query]; empty while not searching. */
    val results: List<MaterialItem> = emptyList(),
) {
    val searching: Boolean get() = query.isNotBlank()
}

@HiltViewModel
class StudyViewModel @Inject constructor(
    materials: StudyMaterials,
    private val messages: UserMessages,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val uiState: StateFlow<StudyUiState> = combine(materials.data, query) { data, q ->
        val items = data.items
        val counts = items.groupingBy { Study.groupOf(it).key }.eachCount()
        StudyUiState(
            status = data.status,
            credits = data.sources.map { StudyCredit(it.credit, it.classroomUrl) }.distinct(),
            subjects = Study.subjects(items).map { subject ->
                StudyGroupRow(subject, counts[subject.key] ?: 0, Study.blocks(items, subject.key).map { it.title })
            },
            others = Study.otherGroups(items).map { StudyGroupRow(it, counts[it.key] ?: 0) },
            query = q,
            results = Study.search(items, q),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudyUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun cannotOpen() = messages.show(CANNOT_OPEN)
}

data class StudyGroupUiState(
    val status: StudyStatus = StudyStatus.Loading,
    val group: StudyGroup? = null,
    val blocks: List<StudyBlock> = emptyList(),
)

@HiltViewModel
class StudyGroupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    materials: StudyMaterials,
    private val messages: UserMessages,
) : ViewModel() {
    /** [app.roll.navigation.StudyGroupDest.key]. */
    private val key: String = savedStateHandle.get<String>("key").orEmpty()

    val uiState: StateFlow<StudyGroupUiState> = materials.data.map { data ->
        val items = data.items
        StudyGroupUiState(status = data.status, group = Study.group(items, key), blocks = Study.blocks(items, key))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudyGroupUiState())

    fun cannotOpen() = messages.show(CANNOT_OPEN)
}

private const val CANNOT_OPEN = "No app on this phone can open the link."
