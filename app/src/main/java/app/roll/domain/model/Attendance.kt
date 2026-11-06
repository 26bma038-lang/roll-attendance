package app.roll.domain.model

/** A student's own mark for one session. Absence of a mark is represented by `null`. */
enum class Mark { Present, Absent }

enum class SessionType { Lecture, Lab, Tutorial }

/**
 * [Deleted] is a tombstone: the CR's "delete" keeps the document so devices that sync
 * incrementally learn about the deletion. Deleted sessions are never shown or counted.
 */
enum class SessionStatus { Scheduled, Cancelled, Deleted }
