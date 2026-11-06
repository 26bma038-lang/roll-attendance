package app.roll.data.firebase

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "RollFirestore"

/** Live document. Errors close the flow with the [FirebaseFirestoreException]. */
fun DocumentReference.snapshots(
    metadataChanges: MetadataChanges = MetadataChanges.EXCLUDE,
): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener(metadataChanges) { snapshot, error ->
        when {
            error != null -> close(error)
            snapshot != null -> trySend(snapshot)
        }
    }
    awaitClose { registration.remove() }
}

/** Live query. Errors close the flow with the [FirebaseFirestoreException]. */
fun Query.snapshots(
    metadataChanges: MetadataChanges = MetadataChanges.EXCLUDE,
): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener(metadataChanges) { snapshot, error ->
        when {
            error != null -> close(error)
            snapshot != null -> trySend(snapshot)
        }
    }
    awaitClose { registration.remove() }
}

val Throwable.firestoreCode: FirebaseFirestoreException.Code?
    get() = (this as? FirebaseFirestoreException)?.code

/**
 * Fire-and-forget write: Firestore applies it locally at once and syncs when online.
 * If the server later rejects it, [onFailure] runs (e.g. to show a snackbar).
 */
fun <T> Task<T>.reportFailure(onFailure: (Exception) -> Unit): Task<T> = addOnFailureListener { e ->
    Log.w(TAG, "Write failed", e)
    onFailure(e)
}

internal fun logWarning(message: String, error: Throwable? = null) {
    Log.w(TAG, message, error)
}
