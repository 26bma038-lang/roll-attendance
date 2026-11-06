package app.roll.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import app.roll.core.designsystem.component.LocalRollSnackbar
import app.roll.core.designsystem.component.SessionRow
import app.roll.core.designsystem.component.showTimed
import app.roll.core.designsystem.theme.RollTheme
import app.roll.data.marks.MarkRepository
import app.roll.data.session.ClassContext
import app.roll.domain.attendance.AttendanceCalculator
import app.roll.domain.attendance.EditWindow
import app.roll.domain.attendance.SessionState
import app.roll.domain.model.Mark
import app.roll.domain.model.Session
import app.roll.domain.model.SessionStatus
import app.roll.domain.model.Subject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalDateTime

/** One session as a student sees it: subject, their mark, and whether they can still change it. */
data class SessionItem(
    val session: Session,
    val subject: Subject?,
    val mark: Mark?,
    val state: SessionState,
    val editable: Boolean,
)

data class ClearedMark(val sessionId: String, val previous: Mark)

fun sessionItems(
    sessions: List<Session>,
    subjects: Map<String, Subject>,
    marks: Map<String, Mark>,
    now: LocalDateTime,
): List<SessionItem> {
    val today: LocalDate = now.toLocalDate()
    return sessions.map { session ->
        val mark = marks[session.id]
        SessionItem(
            session = session,
            subject = subjects[session.subjectId],
            mark = mark,
            state = AttendanceCalculator.stateOf(session, mark, now),
            editable = session.status == SessionStatus.Scheduled && EditWindow.isEditable(session.date, today),
        )
    }
}

/**
 * Setting and clearing the signed-in student's marks, with the 3-second undo for clears.
 * Shared by Today and the day view in History.
 */
class MarkController(private val marks: MarkRepository) {
    private val _cleared = MutableStateFlow<ClearedMark?>(null)
    val cleared: StateFlow<ClearedMark?> = _cleared.asStateFlow()

    fun set(context: ClassContext, item: SessionItem, mark: Mark?) {
        if (!item.editable) return
        _cleared.value = if (mark == null && item.mark != null) ClearedMark(item.session.id, item.mark) else null
        marks.setMark(context.classId, item.session.id, context.uid, mark)
    }

    fun undo(context: ClassContext) {
        val cleared = _cleared.value ?: return
        marks.setMark(context.classId, cleared.sessionId, context.uid, cleared.previous)
        _cleared.value = null
    }

    fun dismiss() {
        _cleared.value = null
    }
}

/** Shows "Mark cleared · Undo" for three seconds whenever a mark is cleared. */
@Composable
fun MarkClearedSnackbar(
    cleared: ClearedMark?,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val host = LocalRollSnackbar.current
    val currentUndo by rememberUpdatedState(onUndo)
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(cleared) {
        if (cleared != null) {
            host.currentSnackbarData?.dismiss()
            if (host.showTimed("Mark cleared", "Undo")) currentUndo() else currentDismiss()
        }
    }
}

/** A [SessionRow] for a [SessionItem] with the P / A toggle. */
@Composable
fun SessionItemRow(
    item: SessionItem,
    onMarkChange: (Mark?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = item.session
    SessionRow(
        startMinutes = session.startMinutes,
        endMinutes = session.endMinutes,
        shortName = item.subject?.shortName ?: "Class",
        fullName = item.subject?.name ?: "Subject removed",
        room = session.room,
        type = session.type,
        subjectColor = RollTheme.colors.subject(item.subject?.colorIndex ?: 0),
        mark = item.mark,
        onMarkChange = onMarkChange,
        modifier = modifier,
        cancelled = session.status == SessionStatus.Cancelled,
        note = session.note.ifBlank { null },
        markEnabled = item.editable,
    )
}
