package app.roll.data.session

import app.roll.core.common.ApplicationScope
import app.roll.data.auth.AuthRepository
import app.roll.data.auth.AuthUser
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.ClassResult
import app.roll.data.marks.MarkRepository
import app.roll.data.marks.MyMarks
import app.roll.data.profile.ProfileRepository
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.schedule.SessionsSnapshot
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject
import javax.inject.Singleton

/** Who is using the app, in which class, and whether they are its CR. */
data class ClassContext(
    val uid: String,
    val profile: UserProfile,
    val rollClass: RollClass,
) {
    val isCR: Boolean get() = rollClass.crUid == uid
    val classId: String get() = rollClass.id
}

/** Where the user is in the sign-in → profile → class flow. */
sealed interface AppState {
    data object Loading : AppState
    data object SignedOut : AppState
    data class NeedsProfile(val user: AuthUser, val existing: UserProfile?) : AppState
    data class NeedsClass(val profile: UserProfile, val lostAccess: Boolean) : AppState
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
    private val lostAccess = MutableStateFlow(false)

    private val rawState: Flow<AppState> = auth.authUser.flatMapLatest { user ->
        if (user == null) flowOf(AppState.SignedOut) else profileState(user)
    }

    val state: StateFlow<AppState> = combine(rawState, lostAccess) { state, lost ->
        if (state is AppState.NeedsClass) state.copy(lostAccess = lost) else state
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), AppState.Loading)

    /** The current class context, or nothing while signed out / onboarding. */
    val context: Flow<ClassContext> = state
        .map { (it as? AppState.Ready)?.context }
        .filterNotNull()
        .distinctUntilChanged()

    fun acknowledgeLostAccess() {
        lostAccess.value = false
    }

    private fun profileState(user: AuthUser): Flow<AppState> =
        profiles.observe(user.uid).transformLatest { snapshot ->
            val profile = snapshot.profile
            when {
                profile == null && snapshot.fromCache -> {
                    // New device, nothing cached yet: give the server a moment before
                    // asking a returning user to fill in their profile again.
                    emit(AppState.Loading)
                    delay(CACHE_GRACE_MILLIS)
                    emit(AppState.NeedsProfile(user, null))
                }
                profile == null || !profile.isComplete -> emit(AppState.NeedsProfile(user, profile))
                profile.classId == null -> emit(AppState.NeedsClass(profile, lostAccess = false))
                else -> {
                    lostAccess.value = false
                    classState(profile, profile.classId).collect { emit(it) }
                }
            }
        }

    private fun classState(profile: UserProfile, classId: String): Flow<AppState> =
        classes.observeClass(classId).transformLatest { result ->
            when (result) {
                is ClassResult.Loaded -> emit(AppState.Ready(ClassContext(profile.uid, profile, result.rollClass)))
                is ClassResult.Missing -> {
                    if (result.fromCache) {
                        emit(AppState.Loading)
                        delay(CACHE_GRACE_MILLIS)
                    }
                    emit(AppState.NeedsClass(profile, lostAccess = false))
                }
                ClassResult.AccessLost -> {
                    lostAccess.value = true
                    profiles.clearClassId(profile.uid)
                    emit(AppState.NeedsClass(profile, lostAccess = true))
                }
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
}

/** A join code from a roll://join/{code} link, waiting for the user to reach the join screen. */
@Singleton
class PendingJoin @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    fun offer(code: String) {
        _code.value = code
    }

    fun consume() {
        _code.value = null
    }
}

/** Set right after the CR creates a class, so the join code screen shows before the app. */
@Singleton
class JustCreated @Inject constructor() {
    private val _rollClass = MutableStateFlow<RollClass?>(null)
    val rollClass: StateFlow<RollClass?> = _rollClass.asStateFlow()

    fun set(rollClass: RollClass?) {
        _rollClass.value = rollClass
    }
}
