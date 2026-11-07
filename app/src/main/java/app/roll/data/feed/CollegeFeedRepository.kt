package app.roll.data.feed

import app.roll.data.firebase.logWarning
import app.roll.data.firebase.snapshots
import app.roll.domain.feed.NithCategory
import app.roll.domain.feed.NithNotice
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about the nith.ac.in feed so far. */
sealed interface FeedLoad {
    data object Loading : FeedLoad

    /** Nothing cached and no answer from the server: offline on first use. */
    data object Offline : FeedLoad

    /** The listener failed. */
    data object Failed : FeedLoad

    /** Newest first. Empty when the sync hasn't run yet. */
    data class Loaded(val notices: List<NithNotice>) : FeedLoad
}

/**
 * Academic and exam notices from nith.ac.in, written by tools/nith-notices into one document
 * (`collegeFeed/nith`): one read per change for every student, offline from the cache after that.
 */
interface CollegeFeedRepository {
    fun observe(): Flow<FeedLoad>
}

private const val CACHE_GRACE_MILLIS = 4_000L

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreCollegeFeedRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : CollegeFeedRepository {

    override fun observe(): Flow<FeedLoad> =
        firestore.collection("collegeFeed").document("nith")
            // INCLUDE: "not in the cache" followed by "not on the server either" must still tell
            // us the second answer came from the server.
            .snapshots(MetadataChanges.INCLUDE)
            .transformLatest { snapshot ->
                if (!snapshot.exists() && snapshot.metadata.isFromCache) {
                    emit(FeedLoad.Loading)
                    delay(CACHE_GRACE_MILLIS)
                    emit(FeedLoad.Offline)
                } else {
                    emit(FeedLoad.Loaded(snapshot.toNithNotices()))
                }
            }
            .catch { error ->
                logWarning("NITH feed listener failed", error)
                emit(FeedLoad.Failed)
            }
            .onStart { emit(FeedLoad.Loading) }
            .distinctUntilChanged()
}

internal fun DocumentSnapshot.toNithNotices(): List<NithNotice> {
    if (!exists()) return emptyList()
    return (get("items") as? List<*>).orEmpty()
        .mapNotNull { (it as? Map<*, *>)?.toNithNotice() }
        .sortedByDescending { it.sortDate }
}

internal fun Map<*, *>.toNithNotice(): NithNotice? {
    fun str(key: String): String? = (this[key] as? String)?.ifBlank { null }
    val id = str("id") ?: return null
    val url = str("url") ?: return null
    val title = str("title") ?: return null
    val firstSeenAt = str("firstSeenAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val sortDate = str("sortDate")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        ?: firstSeenAt?.atZone(ZoneOffset.UTC)?.toLocalDate()
        ?: return null
    return NithNotice(
        id = id,
        title = title,
        url = url,
        category = NithCategory.of(str("category")),
        monthLabel = str("monthLabel"),
        sortDate = sortDate,
        firstSeenAt = firstSeenAt,
    )
}
