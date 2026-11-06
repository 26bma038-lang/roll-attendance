package app.roll.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.tasks.await

/** What a delta-synced query currently holds. */
internal data class DeltaState(
    val docs: List<DocumentSnapshot>,
    /** Local writes not yet confirmed by the server ("Will sync when online"). */
    val hasPendingWrites: Boolean,
    /** False until the server has answered at least once. */
    val fromServer: Boolean,
)

/** Server timestamps aren't perfectly ordered with commits; re-read a window before the newest one. */
private const val OVERLAP_SECONDS = 10 * 60L

/**
 * Keeps the Spark plan's daily read quota safe as a semester grows.
 *
 * A plain listener re-reads every matching document from the server on each cold start.
 * Instead: load everything from the offline cache, then listen only for documents whose
 * [field] (always set with serverTimestamp on every write) is newer than the newest cached
 * one. Deletions are modelled as tombstone updates so they arrive through the same listener.
 *
 * @param fullQuery the whole data set; read from cache only.
 * @param newerThan builds the live query from a cut-off, or the full live query when null.
 */
internal fun deltaSync(
    fullQuery: Query,
    field: String,
    newerThan: (Timestamp?) -> Query,
): Flow<DeltaState> = channelFlow {
    val docs = LinkedHashMap<String, DocumentSnapshot>()
    var newest: Timestamp? = null

    val cached = runCatching { fullQuery.get(Source.CACHE).await() }.getOrNull()
    for (doc in cached?.documents.orEmpty()) {
        docs[doc.reference.path] = doc
        val stamp = doc.getTimestamp(field) ?: continue
        val current = newest
        if (current == null || stamp > current) newest = stamp
    }
    if (docs.isNotEmpty()) send(DeltaState(docs.values.toList(), hasPendingWrites = false, fromServer = false))

    val since = newest?.let { Timestamp(it.seconds - OVERLAP_SECONDS, 0) }
    newerThan(since).snapshots(MetadataChanges.INCLUDE)
        .catch { error ->
            // Missing composite index (not deployed yet): fall back to the full listener.
            if (since != null && error.firestoreCode == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                logWarning("Delta query needs an index; falling back to a full listen", error)
                emitAll(newerThan(null).snapshots(MetadataChanges.INCLUDE))
            } else {
                throw error
            }
        }
        .collect { snapshot ->
            for (change in snapshot.getDocumentChanges(MetadataChanges.INCLUDE)) {
                val path = change.document.reference.path
                when (change.type) {
                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> docs[path] = change.document
                    DocumentChange.Type.REMOVED -> docs.remove(path)
                }
            }
            send(
                DeltaState(
                    docs = docs.values.toList(),
                    hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                    fromServer = !snapshot.metadata.isFromCache,
                ),
            )
        }
}
