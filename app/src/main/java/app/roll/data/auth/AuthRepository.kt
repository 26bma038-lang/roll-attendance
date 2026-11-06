package app.roll.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class AuthUser(
    val uid: String,
    val displayName: String,
    val email: String,
)

interface AuthRepository {
    /** The signed-in user, or null. Emits on sign-in and sign-out. */
    val authUser: Flow<AuthUser?>
    val currentUid: String?

    suspend fun signInWithGoogle(idToken: String)

    /** Needed before deleting the account: Firebase requires a recent sign-in. */
    suspend fun reauthenticateWithGoogle(idToken: String)

    suspend fun deleteAuthUser()

    fun signOut()
}

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
) : AuthRepository {

    override val authUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(
                firebaseAuth.currentUser?.let {
                    AuthUser(uid = it.uid, displayName = it.displayName.orEmpty(), email = it.email.orEmpty())
                },
            )
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    override val currentUid: String? get() = auth.currentUser?.uid

    override suspend fun signInWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun reauthenticateWithGoogle(idToken: String) {
        val user = checkNotNull(auth.currentUser) { "Not signed in" }
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    override suspend fun deleteAuthUser() {
        checkNotNull(auth.currentUser) { "Not signed in" }.delete().await()
    }

    override fun signOut() {
        auth.signOut()
    }
}
