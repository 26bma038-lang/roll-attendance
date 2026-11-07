package app.roll.data.materials

import app.roll.data.firebase.logWarning
import app.roll.data.firebase.snapshots
import app.roll.domain.materials.MaterialItem
import app.roll.domain.materials.MaterialSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject
import javax.inject.Singleton

/** What the Study tab knows so far. */
sealed interface MaterialsLoad {
    data object Loading : MaterialsLoad

    /** Nothing cached and no answer from the server: offline on first use. */
    data object Offline : MaterialsLoad

    data object Failed : MaterialsLoad

    /** Sources listing this branch or all branches, from the server or the offline cache. Year filtering is up to the caller. */
    data class Loaded(val sources: List<MaterialSource>) : MaterialsLoad
}

/**
 * Study material indexes written by tools/classroom-sync (`materialSources`). One small document
 * per source, so a student costs one read per change, and the offline cache keeps it after that.
 */
interface MaterialRepository {
    fun observe(branch: String): Flow<MaterialsLoad>
}

private const val COLLECTION = "materialSources"
private const val PARTS = "parts"
private const val CACHE_GRACE_MILLIS = 4_000L

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreMaterialRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : MaterialRepository {

    override fun observe(branch: String): Flow<MaterialsLoad> =
        firestore.collection(COLLECTION)
            // A source lists its branches, or "*" for all of them (first-year material is the same
            // for every branch). One query covers both.
            .whereArrayContainsAny("branches", listOf(branch.uppercase(), MaterialSource.ALL_BRANCHES))
            // INCLUDE: an empty cache followed by an empty server answer must still tell us
            // "from the server", or a student with no material would look offline.
            .snapshots(MetadataChanges.INCLUDE)
            .transformLatest { snapshot ->
                val docs = snapshot.documents
                if (docs.isEmpty() && snapshot.metadata.isFromCache) {
                    emit(MaterialsLoad.Loading)
                    delay(CACHE_GRACE_MILLIS)
                    emit(MaterialsLoad.Offline)
                } else {
                    emitAll(withParts(docs).map { MaterialsLoad.Loaded(it) })
                }
            }
            .catch { error ->
                logWarning("Study material listener failed", error)
                emit(MaterialsLoad.Failed)
            }
            .onStart { emit(MaterialsLoad.Loading) }
            .distinctUntilChanged()

    /** Big sources keep their items in `parts/{n}`; read those too. */
    private fun withParts(docs: List<DocumentSnapshot>): Flow<List<MaterialSource>> {
        val flows = docs.map { doc ->
            val source = doc.toMaterialSource() ?: return@map flowOf(null)
            val partCount = doc.getLong("partCount")?.toInt() ?: 0
            if (partCount == 0) {
                flowOf(source)
            } else {
                doc.reference.collection(PARTS).snapshots().map { parts ->
                    val items = parts.documents
                        .sortedBy { it.getLong("n") ?: Long.MAX_VALUE }
                        .flatMap { it.itemList() }
                    source.copy(items = items)
                }
            }
        }
        if (flows.isEmpty()) return flowOf(emptyList())
        return combine(flows) { sources -> sources.filterNotNull() }
    }

}

internal fun DocumentSnapshot.toMaterialSource(): MaterialSource? {
    if (!exists()) return null
    return MaterialSource(
        id = id,
        title = getString("title").orEmpty(),
        credit = getString("credit").orEmpty(),
        classroomUrl = getString("classroomUrl").orEmpty(),
        years = (get("years") as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
        branches = (get("branches") as? List<*>).orEmpty().mapNotNull { it as? String },
        items = itemList(),
    )
}

private fun DocumentSnapshot.itemList(): List<MaterialItem> =
    (get("items") as? List<*>).orEmpty().mapNotNull { entry -> (entry as? Map<*, *>)?.toMaterialItem() }

internal fun Map<*, *>.toMaterialItem(): MaterialItem? {
    fun str(key: String): String? = (this[key] as? String)?.ifBlank { null }
    val id = str("id") ?: return null
    val url = str("url") ?: return null
    val section = str("section") ?: return null
    val title = str("title") ?: return null
    return MaterialItem(
        id = id,
        section = section,
        sectionLabel = str("sectionLabel") ?: section,
        subjectCode = str("subjectCode"),
        subjectName = str("subjectName") ?: title,
        itemTitle = str("itemTitle").orEmpty(),
        title = title,
        exam = str("exam"),
        academicYear = str("academicYear"),
        url = url,
    )
}
