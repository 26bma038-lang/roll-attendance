package app.roll.data.feed

import app.roll.core.common.ApplicationScope
import app.roll.core.common.TimeSource
import app.roll.data.preferences.UserPreferencesRepository
import app.roll.domain.feed.NithFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The nith.ac.in feed shared by every screen (one listener), and which notices are new since the
 * student last opened College notices. The last-opened time lives on the phone only.
 */
@Singleton
class NithNoticeTracker @Inject constructor(
    feed: CollegeFeedRepository,
    private val preferences: UserPreferencesRepository,
    private val time: TimeSource,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val load: StateFlow<FeedLoad> = feed.observe().stateIn(scope, SharingStarted.WhileSubscribed(5_000), FeedLoad.Loading)

    val seenAt: Flow<Instant?> = preferences.nithNoticesSeenAt

    /**
     * How many notices arrived since the student last looked. The first time the feed shows up on
     * a phone, everything already there counts as seen, so a new install isn't 150 notices behind.
     */
    val newCount: Flow<Int> = combine(load, seenAt) { current, seen ->
        val notices = (current as? FeedLoad.Loaded)?.notices.orEmpty()
        if (seen == null) {
            if (notices.isNotEmpty()) scope.launch { preferences.setNithNoticesSeenAt(time.now()) }
            0
        } else {
            NithFeed.newSince(notices, seen).size
        }
    }.distinctUntilChanged()

    fun markSeen() {
        scope.launch { preferences.setNithNoticesSeenAt(time.now()) }
    }
}
