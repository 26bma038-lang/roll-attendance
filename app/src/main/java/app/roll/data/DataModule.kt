package app.roll.data

import app.roll.data.auth.AuthRepository
import app.roll.data.auth.FirebaseAuthRepository
import app.roll.data.classes.ClassRepository
import app.roll.data.classes.FirestoreClassRepository
import app.roll.data.marks.FirestoreMarkRepository
import app.roll.data.marks.MarkRepository
import app.roll.data.profile.FirestoreProfileRepository
import app.roll.data.profile.ProfileRepository
import app.roll.data.schedule.FirestoreScheduleRepository
import app.roll.data.schedule.ScheduleRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun bindsAuth(impl: FirebaseAuthRepository): AuthRepository
    @Binds abstract fun bindsProfile(impl: FirestoreProfileRepository): ProfileRepository
    @Binds abstract fun bindsClasses(impl: FirestoreClassRepository): ClassRepository
    @Binds abstract fun bindsSchedule(impl: FirestoreScheduleRepository): ScheduleRepository
    @Binds abstract fun bindsMarks(impl: FirestoreMarkRepository): MarkRepository
}
