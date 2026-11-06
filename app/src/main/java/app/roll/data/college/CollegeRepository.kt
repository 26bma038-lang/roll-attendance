package app.roll.data.college

import app.roll.core.common.UserMessages
import app.roll.data.firebase.F
import app.roll.data.firebase.logWarning
import app.roll.data.firebase.reportFailure
import app.roll.data.firebase.snapshots
import app.roll.data.firebase.toDepartment
import app.roll.domain.college.NithConfig
import app.roll.domain.model.Department
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Department info, maintained by the admin. Six small documents, read with a plain listener:
 * too few to matter for the read quota.
 *
 * Nothing is invented: until the admin fills a field it stays blank and the UI says
 * "Not added yet". The branch name comes from [NithConfig] when the doc doesn't exist.
 */
interface DepartmentRepository {
    fun observe(code: String): Flow<Department>
    fun save(department: Department)
}

@Singleton
class FirestoreDepartmentRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val messages: UserMessages,
) : DepartmentRepository {

    override fun observe(code: String): Flow<Department> {
        val upper = code.uppercase()
        val fallback = Department(code = upper, name = NithConfig.branchName(upper))
        return firestore.collection(F.DEPARTMENTS).document(upper).snapshots()
            .map { it.toDepartment(upper, fallback.name) }
            .onStart { emit(fallback) }
            .catch {
                logWarning("Department listener failed", it)
                emit(fallback)
            }
    }

    override fun save(department: Department) {
        firestore.collection(F.DEPARTMENTS).document(department.code).set(
            mapOf(
                "code" to department.code,
                "name" to department.name,
                "about" to department.about.trim(),
                "hod" to department.hod.trim(),
                "office" to department.office.trim(),
                "email" to department.email.trim(),
                "phone" to department.phone.trim(),
                "links" to department.links
                    .filter { it.url.isNotBlank() }
                    .take(10)
                    .map { mapOf("title" to it.title.trim().ifBlank { it.url.trim() }, "url" to it.url.trim()) },
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).reportFailure { messages.show("Couldn't save ${department.code}. Try again.") }
    }
}
