package app.roll.testing

import app.roll.core.common.TimeSource
import app.roll.data.assignments.AssignmentDraft
import app.roll.data.assignments.AssignmentRepository
import app.roll.data.auth.AuthRepository
import app.roll.data.auth.AuthUser
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.ClassResult
import app.roll.data.marks.MarkRepository
import app.roll.data.marks.MyMarks
import app.roll.data.materials.MaterialRepository
import app.roll.data.materials.MaterialsLoad
import app.roll.domain.materials.MaterialSource
import app.roll.data.notices.NoticeDraft
import app.roll.data.notices.NoticeRepository
import app.roll.data.preferences.ReminderSettings
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.data.profile.ProfileRepository
import app.roll.data.profile.ProfileSnapshot
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.schedule.SessionsSnapshot
import app.roll.domain.college.NithIdentity
import app.roll.domain.model.Assignment
import app.roll.domain.model.Mark
import app.roll.domain.model.MarkRecord
import app.roll.domain.model.Member
import app.roll.domain.model.MemberMark
import app.roll.domain.model.Notice
import app.roll.domain.model.RollClass
import app.roll.domain.model.Session
import app.roll.domain.model.Subject
import app.roll.domain.model.ThemeMode
import app.roll.domain.model.TimetableSlot
import app.roll.domain.model.UserProfile
import app.roll.domain.schedule.SessionDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

class FakeTimeSource(var instant: Instant) : TimeSource {
    override fun now(): Instant = instant
    override fun ticks(periodMillis: Long): Flow<Instant> = flowOf(instant)
}

class FakeAuthRepository(user: AuthUser?) : AuthRepository {
    val user = MutableStateFlow(user)
    override val authUser: Flow<AuthUser?> = this.user
    override val currentUid: String? get() = user.value?.uid
    override suspend fun signInWithGoogle(idToken: String) = Unit
    override suspend fun reauthenticateWithGoogle(idToken: String) = Unit
    override suspend fun deleteAuthUser() = Unit
    override fun signOut() {
        user.value = null
    }
}

class FakeProfileRepository(profile: UserProfile?) : ProfileRepository {
    val profile = MutableStateFlow(profile)
    override fun observe(uid: String): Flow<ProfileSnapshot> = profile.map { ProfileSnapshot(it, fromCache = false) }
    override fun updateName(uid: String, name: String, classId: String?) {
        profile.value = profile.value?.copy(name = name)
    }
    override suspend fun deleteProfile(uid: String) {
        profile.value = null
    }
}

class FakeClassRepository(rollClass: RollClass) : ClassRepository {
    val rollClass = MutableStateFlow(rollClass)
    val members = MutableStateFlow<List<Member>>(emptyList())
    override fun observeClass(classId: String): Flow<ClassResult> = this.rollClass.map { ClassResult.Loaded(it, false) }
    override fun observeMembers(classId: String): Flow<List<Member>> = members
    override suspend fun joinOwnClass(uid: String, identity: NithIdentity, name: String, today: LocalDate) = Unit
    override fun updateSettings(classId: String, name: String, semester: String, requiredPercent: Int) = Unit
    override fun observeAllClasses(): Flow<List<RollClass>> = this.rollClass.map { listOf(it) }
    override fun setCRs(classId: String, crUids: List<String>, crNames: List<String>) {
        rollClass.value = rollClass.value.copy(crUids = crUids, crNames = crNames)
    }
    override suspend fun deleteMembership(uid: String, classId: String) = Unit
}

class FakeScheduleRepository(sessions: List<Session>, subjects: List<Subject>) : ScheduleRepository {
    val sessions = MutableStateFlow(sessions)
    val subjects = MutableStateFlow(subjects)
    val timetable = MutableStateFlow<List<TimetableSlot>>(emptyList())
    override fun observeSubjects(classId: String): Flow<List<Subject>> = subjects
    override fun saveSubject(classId: String, subject: Subject) = Unit
    override fun observeTimetable(classId: String): Flow<List<TimetableSlot>> = timetable
    override fun saveSlot(classId: String, slot: TimetableSlot) = Unit
    override fun deleteSlot(classId: String, slotId: String) = Unit
    override fun observeSessions(classId: String): Flow<SessionsSnapshot> =
        sessions.map { SessionsSnapshot(it, emptySet(), loaded = true) }
    override fun postSession(classId: String, draft: SessionDraft, crUid: String, crName: String) = Unit
    override fun updateSession(classId: String, session: Session, draft: SessionDraft) = Unit
    override fun setCancelled(classId: String, session: Session, cancelled: Boolean, note: String) = Unit
    override suspend fun deleteSession(classId: String, sessionId: String) = Unit
    override fun postFromTimetable(
        classId: String,
        date: LocalDate,
        slots: List<TimetableSlot>,
        existing: List<Session>,
        deletedIds: Set<String>,
        crUid: String,
        crName: String,
    ) = 0
    override suspend fun refreshDay(classId: String, date: LocalDate) = true
}

/** Applies marks instantly, like Firestore's latency compensation. */
class FakeMarkRepository : MarkRepository {
    val marks = MutableStateFlow<Map<String, Mark>>(emptyMap())
    val writes = mutableListOf<Pair<String, Mark?>>()
    override fun observeMyMarks(uid: String, classId: String): Flow<MyMarks> = marks.map { map ->
        MyMarks(map.mapValues { MarkRecord(it.key, it.value, null) }, hasPendingWrites = false, loaded = true)
    }
    override fun setMark(classId: String, sessionId: String, uid: String, mark: Mark?) {
        writes += sessionId to mark
        marks.value = if (mark == null) marks.value - sessionId else marks.value + (sessionId to mark)
    }
    override suspend fun setMarkAndWait(classId: String, sessionId: String, uid: String, mark: Mark?, timeoutMillis: Long) =
        setMark(classId, sessionId, uid, mark)
    override fun observeSessionMarks(classId: String, sessionId: String): Flow<List<MemberMark>> = flowOf(emptyList())
}

class FakeAssignmentRepository(assignments: List<Assignment> = emptyList()) : AssignmentRepository {
    val assignments = MutableStateFlow(assignments)
    val done = MutableStateFlow<Set<String>>(emptySet())
    override fun observe(classId: String): Flow<List<Assignment>> = assignments
    override fun post(classId: String, draft: AssignmentDraft, uid: String, name: String) = Unit
    override fun update(classId: String, assignment: Assignment, draft: AssignmentDraft) = Unit
    override fun delete(classId: String, assignment: Assignment) = Unit
    override fun observeDone(uid: String): Flow<Set<String>> = done
    override fun setDone(uid: String, assignmentId: String, done: Boolean) {
        this.done.value = if (done) this.done.value + assignmentId else this.done.value - assignmentId
    }
}

class FakeNoticeRepository : NoticeRepository {
    val classNotices = MutableStateFlow<List<Notice>>(emptyList())
    val collegeNotices = MutableStateFlow<List<Notice>>(emptyList())
    override fun observeClass(classId: String): Flow<List<Notice>> = classNotices
    override fun observeCollege(): Flow<List<Notice>> = collegeNotices
    override fun post(classId: String?, draft: NoticeDraft, authorName: String) = Unit
    override fun setPinned(classId: String?, notice: Notice, pinned: Boolean) = Unit
    override fun delete(classId: String?, notice: Notice) = Unit
}

class FakePreferences : UserPreferencesRepository {
    override val themeMode = MutableStateFlow(ThemeMode.System)
    override suspend fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }
    override val reminder = MutableStateFlow(ReminderSettings())
    override suspend fun setReminder(settings: ReminderSettings) {
        reminder.value = settings
    }
    override val deadlineReminders = MutableStateFlow(true)
    override suspend fun setDeadlineReminders(enabled: Boolean) {
        deadlineReminders.value = enabled
    }
    override val classEndPrompts = MutableStateFlow(true)
    override suspend fun setClassEndPrompts(enabled: Boolean) {
        classEndPrompts.value = enabled
    }
    override val notificationPromptDone = MutableStateFlow(false)
    override suspend fun setNotificationPromptDone() {
        notificationPromptDone.value = true
    }
}

class FakeMaterialRepository(load: MaterialsLoad) : MaterialRepository {
    val load = MutableStateFlow(load)
    val branchesAsked = mutableListOf<String>()

    /** Like the Firestore array-contains-any query: sources that list the branch or "*". */
    override fun observe(branch: String): Flow<MaterialsLoad> {
        branchesAsked += branch
        return load.map { current ->
            if (current is MaterialsLoad.Loaded) {
                MaterialsLoad.Loaded(current.sources.filter { branch in it.branches || MaterialSource.ALL_BRANCHES in it.branches })
            } else {
                current
            }
        }
    }
}
