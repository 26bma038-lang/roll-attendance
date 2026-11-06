package app.roll.testing

import app.roll.core.common.TimeSource
import app.roll.data.auth.AuthRepository
import app.roll.data.auth.AuthUser
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.ClassResult
import app.roll.data.classes.JoinResult
import app.roll.data.marks.MarkRepository
import app.roll.data.marks.MyMarks
import app.roll.data.profile.ProfileRepository
import app.roll.data.profile.ProfileSnapshot
import app.roll.data.schedule.ScheduleRepository
import app.roll.data.schedule.SessionsSnapshot
import app.roll.domain.model.Mark
import app.roll.domain.model.MarkRecord
import app.roll.domain.model.Member
import app.roll.domain.model.MemberMark
import app.roll.domain.model.RollClass
import app.roll.domain.model.Session
import app.roll.domain.model.Subject
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
    override fun saveProfile(uid: String, name: String, rollNo: String, email: String, isNew: Boolean, classId: String?) {
        profile.value = UserProfile(uid, name, rollNo, email, classId)
    }
    override fun clearClassId(uid: String) {
        profile.value = profile.value?.copy(classId = null)
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
    override suspend fun createClass(profile: UserProfile, name: String, semester: String, requiredPercent: Int) = rollClass.value
    override suspend fun joinClass(profile: UserProfile, code: String): JoinResult = JoinResult.Joined(rollClass.value.id)
    override fun leaveClass(uid: String, classId: String) = Unit
    override suspend fun deleteMembership(uid: String, classId: String) = Unit
    override fun removeMember(classId: String, uid: String) = Unit
    override fun transferCr(classId: String, newCrUid: String) = Unit
    override fun updateSettings(classId: String, name: String, semester: String, requiredPercent: Int) = Unit
    override suspend fun regenerateJoinCode(rollClass: RollClass) = "ABCDEF"
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
    override fun observeSessionMarks(classId: String, sessionId: String): Flow<List<MemberMark>> = flowOf(emptyList())
}
