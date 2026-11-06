package app.roll.data.session

import app.roll.core.common.ApplicationScope
import app.roll.data.assignments.AssignmentRepository
import app.roll.data.auth.AuthRepository
import app.roll.data.auth.AuthUser
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.ClassResult
import app.roll.data.marks.MarkRepository
import app.roll.data.marks.MyMarks
import app.roll.data.notices.NoticeRepository
import app.roll.data.profile.ProfileRepository
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.schedule.SessionsSnapshot
import app.roll.domain.college.NithConfig
import app.roll.domain.college.NithIdentity
import app.roll.domain.model.Assignment
import app.roll.domain.model.Notice
import app.roll.domain.model.RollClass
import app.roll.domain.model.Subject
import app.roll.domain.model.TimetableSlot
import app.roll.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject
import javax.inject.Singleton

/** Who is using the app, in which class, and what they may do there. */
data class ClassContext(
    val uid: String,
    val profile: UserProfile,
    val rollClass: RollClass,
    val identity: NithIdentity,
    val isAdmin: Boolean,
) {
    val isCR: Boolean get() = rollClass.isCR(uid)
    val classId: String get() = rollClass.id
}

/** Why a signed-in account was turned away. */
sealed interface Rejection {
    val email: String

    data class WrongDomain(override val email: String) : Rejection
    data class UnsupportedBranch(override val email: String) : Rejection
}

/** Where the user is in the sign-in → confirm profile → class flow. */
sealed interface AppState {
    data object Loading : AppState
    data object SignedOut : AppState
    data class NeedsProfile(val user: AuthUser, val identity: NithIdentity, val existing: UserProfile?) : AppState
    data class Ready(val context: ClassContext) : AppState
}

/** How long to wait for the server before trusting an empty offline cache. */
private const val CACHE_GRACE_MILLIS = 4_000L

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AppSession @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val classes: ClassRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _rejection = MutableStateFlow<Rejection?>(null)

    /** Set when the last sign-in used an account Roll doesn't accept; shown on the sign-in screen. */
    val rejection: StateFlow<Rejection?> = _rejection.asStateFlow()

    val state: StateFlow<AppState> = auth.authUser
        .flatMapLatest { user -> if (user == null) flowOf(AppState.SignedOut) else stateFor(user) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AppState.Loading)

    /** The current class context, or nothing while signed out / confirming the profile. */
    val context: Flow<ClassContext> = state
        .map { (it as? AppState.Ready)?.context }
        .filterNotNull()
        .distinctUntilChanged()

    fun clearRejection() {
        _rejection.value = null
    }

    private fun stateFor(user: AuthUser): Flow<AppState> {
        val identity = when (val parsed = NithConfig.parse(user.email)) {
            is NithConfig.Parsed.Student -> parsed.identity
            NithConfig.Parsed.WrongDomain -> return reject(Rejection.WrongDomain(user.email))
            NithConfig.Parsed.Unsupported -> return reject(Rejection.UnsupportedBranch(user.email))
        }
        _rejection.value = null
        return profileState(user, identity)
    }

    /** Signs straight back out: only @nith.ac.in students of supported branches may use Roll. */
    private fun reject(rejection: Rejection): Flow<AppState> {
        _rejection.value = rejection
        auth.signOut()
        return flowOf(AppState.SignedOut)
    }

    private fun profileState(user: AuthUser, identity: NithIdentity): Flow<AppState> =
        profiles.observe(user.uid).transformLatest { snapshot ->
            val profile = snapshot.profile
            when {
                profile == null && snapshot.fromCache -> {
                    // New device, nothing cached yet: give the server a moment before asking
                    // a returning student to confirm their profile again.
                    emit(AppState.Loading)
                    delay(CACHE_GRACE_MILLIS)
                    emit(AppState.NeedsProfile(user, identity, null))
                }
                profile == null || !profile.isComplete -> emit(AppState.NeedsProfile(user, identity, profile))
                else -> classState(user, identity, profile).collect { emit(it) }
            }
        }

    private fun classState(user: AuthUser, identity: NithIdentity, profile: UserProfile): Flow<AppState> =
        classes.observeClass(identity.classId).transformLatest { result ->
            when (result) {
                is ClassResult.Loaded -> emit(
                    AppState.Ready(
                        ClassContext(
                            uid = profile.uid,
                            profile = profile,
                            rollClass = result.rollClass,
                            identity = identity,
                            isAdmin = NithConfig.isAdmin(user.email),
                        ),
                    ),
                )
                is ClassResult.Missing -> {
                    if (result.fromCache) {
                        emit(AppState.Loading)
                        delay(CACHE_GRACE_MILLIS)
                    }
                    // Profile without a class doc: run the join again from the confirm screen.
                    emit(AppState.NeedsProfile(user, identity, profile))
                }
                ClassResult.AccessLost -> emit(AppState.NeedsProfile(user, identity, profile))
            }
        }
}

/**
 * Class-wide data shared by every screen, so switching tabs never re-attaches listeners.
 * Each flow is null until its first answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ClassData @Inject constructor(
    session: AppSession,
    schedule: ScheduleRepository,
    marks: MarkRepository,
    assignments: AssignmentRepository,
    notices: NoticeRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val keys: Flow<Pair<String, String>?> = session.state
        .map { (it as? AppState.Ready)?.context?.let { ctx -> ctx.classId to ctx.uid } }
        .distinctUntilChanged()

    private fun <T> perClass(load: (classId: String, uid: String) -> Flow<T>): StateFlow<T?> =
        keys.flatMapLatest { key -> if (key == null) flowOf(null) else load(key.first, key.second) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val subjects: StateFlow<List<Subject>?> = perClass { classId, _ -> schedule.observeSubjects(classId) }
    val timetable: StateFlow<List<TimetableSlot>?> = perClass { classId, _ -> schedule.observeTimetable(classId) }
    val sessions: StateFlow<SessionsSnapshot?> = perClass { classId, _ -> schedule.observeSessions(classId) }
    val myMarks: StateFlow<MyMarks?> = perClass { classId, uid -> marks.observeMyMarks(uid, classId) }
    val assignments: StateFlow<List<Assignment>?> = perClass { classId, _ -> assignments.observe(classId) }
    /** Ids of assignments the student ticked done. Private to them. */
    val doneIds: StateFlow<Set<String>?> = perClass { _, uid -> assignments.observeDone(uid) }
    val classNotices: StateFlow<List<Notice>?> = perClass { classId, _ -> notices.observeClass(classId) }
    val collegeNotices: StateFlow<List<Notice>?> = perClass { _, _ -> notices.observeCollege() }
}
