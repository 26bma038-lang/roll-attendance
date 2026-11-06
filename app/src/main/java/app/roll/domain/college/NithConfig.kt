package app.roll.domain.college

import java.time.LocalDate
import java.util.Locale

/**
 * Everything specific to NIT Hamirpur, in one place. firestore.rules mirrors the domain, the
 * email pattern, the branch codes and the admin list: change both together.
 *
 * Adding a branch = one row in [branches] + its code in `supportedBranch()` in firestore.rules.
 */
object NithConfig {
    const val DOMAIN = "nith.ac.in"

    /** Lower-cased email → batch (YY), branch code, serial number. Bachelor's (`b`) only for now. */
    private val EmailPattern = Regex("^([0-9]{2})b([a-z]{2})([0-9]{3})@nith\\.ac\\.in$")

    /** Branch code → name. Order is the order shown in admin lists. */
    val branches: Map<String, String> = linkedMapOf(
        "MA" to "Mathematics and Computing",
        "CS" to "Computer Science and Engineering",
        "CH" to "Chemical Engineering",
        "CE" to "Civil Engineering",
        "ME" to "Mechanical Engineering",
        "EC" to "Electronics and Communication Engineering",
    )

    /** Admin accounts. Also hard-coded in firestore.rules (`isAdmin()`). */
    val adminEmails: Set<String> = setOf("26bma038@nith.ac.in")

    const val DEFAULT_REQUIRED_PERCENT = 75
    const val TIMEZONE = "Asia/Kolkata"
    const val MAX_CRS = 3

    fun isAdmin(email: String?): Boolean = email != null && email.trim().lowercase(Locale.ROOT) in adminEmails

    fun branchName(code: String): String = branches[code.uppercase(Locale.ROOT)] ?: code.uppercase(Locale.ROOT)

    /** What an email means for Roll. */
    sealed interface Parsed {
        data class Student(val identity: NithIdentity) : Parsed
        /** Not a college account at all. */
        data object WrongDomain : Parsed
        /** A college account Roll doesn't cover yet (other branches, M.Tech, faculty). */
        data object Unsupported : Parsed
    }

    fun parse(email: String?): Parsed {
        val normalized = email?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (!normalized.endsWith("@$DOMAIN")) return Parsed.WrongDomain
        val match = EmailPattern.matchEntire(normalized) ?: return Parsed.Unsupported
        val (yy, branch, serial) = match.destructured
        val code = branch.uppercase(Locale.ROOT)
        if (code !in branches) return Parsed.Unsupported
        return Parsed.Student(
            NithIdentity(
                email = normalized,
                batch = 2000 + yy.toInt(),
                branch = code,
                rollNo = "${yy}B$code$serial",
            ),
        )
    }

    /**
     * `nith-26-ma`. Sections can later be appended (`nith-26-ma-a`) without touching
     * existing ids.
     */
    fun classId(batch: Int, branch: String): String =
        "nith-${(batch % 100).toString().padStart(2, '0')}-${branch.lowercase(Locale.ROOT)}"

    fun defaultClassName(batch: Int, branch: String): String = "${branch.uppercase(Locale.ROOT)} $batch"

    /**
     * Odd semesters run July–December, even ones January–June. A 2026 batch is in semester 1
     * from July 2026, semester 2 from January 2027, and so on. Clamped to 1..10.
     */
    fun semesterNumber(batch: Int, today: LocalDate): Int {
        val yearsIn = today.year - batch
        val number = if (today.monthValue >= 7) 2 * yearsIn + 1 else 2 * yearsIn
        return number.coerceIn(1, 10)
    }

    /** "Semester 1 · Odd 2026-27". */
    fun semesterLabel(batch: Int, today: LocalDate): String {
        val number = semesterNumber(batch, today)
        val startYear = if (today.monthValue >= 7) today.year else today.year - 1
        val parity = if (today.monthValue >= 7) "Odd" else "Even"
        return "Semester $number · $parity $startYear-${(startYear + 1) % 100}"
    }
}

/** A student as their college email describes them. */
data class NithIdentity(
    val email: String,
    /** Year of admission, e.g. 2026. */
    val batch: Int,
    /** Upper-case branch code, e.g. "MA". */
    val branch: String,
    /** Upper-case roll number, e.g. "26BMA038". */
    val rollNo: String,
) {
    val classId: String get() = NithConfig.classId(batch, branch)
    val branchName: String get() = NithConfig.branchName(branch)

    /** "Mathematics and Computing · 2026 batch". */
    val description: String get() = "$branchName · $batch batch"
}
