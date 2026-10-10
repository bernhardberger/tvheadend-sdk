@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.microseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class TvheadendRecordingResumeTest {
    @Test
    fun `resume helper notifies progress floor settlement without another player callback`() {
        for (mode in RecordingResumeMode.entries) {
            val identity = RecordingMediaIdentity()
            val player = FakeRecordingResumePlayer(currentMediaItem = tvheadendRecordingMediaItem(identity))
            val floors = mutableListOf<Duration?>()
            val resume = TvheadendRecordingResume(player, identity, mode, onProgressFloorChanged = floors::add)

            resume.beginPlaybackTarget(80_000.milliseconds)
            assertEquals(listOf(80_000.milliseconds), floors)
            player.timeouts.fireAll()
            assertEquals(listOf(80_000.milliseconds, null), floors)
            assertNull(resume.progressFloor)
            resume.close()
        }
    }

    @Test
    fun `listener waits for a known seekable timeline and applies resume once`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(identity),
        )
        val resume = TvheadendRecordingResume(player, identity)

        assertNotNull(player.listener)
        resume.beginPlaybackTarget(180_000.milliseconds)
        assertEquals(emptyList<Long>(), player.seeks)

        player.duration = 3_600_000
        player.listener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        assertEquals(emptyList<Long>(), player.seeks)

        player.isCurrentMediaItemSeekable = true
        player.listener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        player.listener?.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(listOf(180_000L), player.seeks)
    }

    @Test
    fun `an application item reusing the media id under another uri never receives the pending seek`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(currentMediaItem = tvheadendRecordingMediaItem(identity))
        val resume = TvheadendRecordingResume(player, identity)
        resume.beginPlaybackTarget(180_000.milliseconds)

        player.currentMediaItem = MediaItem.Builder()
            .setMediaId(identity.uri)
            .setUri("https://media.invalid/application")
            .build()
        player.duration = 3_600_000
        player.isCurrentMediaItemSeekable = true
        player.listener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        player.listener?.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(emptyList<Long>(), player.seeks)
        assertEquals(180_000.milliseconds, resume.pendingPosition)
    }

    @Test
    fun `known media applies immediately and survives a metadata-only item update`() {
        val identity = RecordingMediaIdentity()
        // Media3 updates a progressive source in place: a new item instance keeps the target's URI.
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(identity).buildUpon()
                .setMediaId("application-id")
                .build(),
            duration = 3_600_000,
            isCurrentMediaItemSeekable = true,
        )
        val resume = TvheadendRecordingResume(player, identity)

        resume.beginPlaybackTarget(180_000.milliseconds)

        assertEquals(listOf(180_000L), player.seeks)
    }

    @Test
    fun `begin validates installed identity and whole millisecond position`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(identity),
        )
        val resume = TvheadendRecordingResume(player, identity)

        assertThrows(IllegalArgumentException::class.java) {
            resume.beginPlaybackTarget((-1).milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resume.beginPlaybackTarget(1_500.microseconds)
        }
        player.currentMediaItem = tvheadendRecordingMediaItem(RecordingMediaIdentity())
        assertThrows(IllegalStateException::class.java) {
            resume.beginPlaybackTarget(180_000.milliseconds)
        }
    }

    @Test
    fun `coordinator ownership loss fences resume even when the original item returns`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(currentMediaItem = tvheadendRecordingMediaItem(identity))
        var owned = true
        val resume = TvheadendRecordingResume(player, identity, isTargetCurrent = { owned })
        resume.beginPlaybackTarget(180_000.milliseconds)

        owned = false
        player.duration = 3_600_000
        player.isCurrentMediaItemSeekable = true
        player.listener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)

        assertEquals(emptyList<Long>(), player.seeks)
        resume.close()
    }

    @Test
    fun `callbacks enforce the application looper and close only detaches the listener`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(identity),
        )
        val resume = TvheadendRecordingResume(player, identity)
        val listener = requireNotNull(player.listener)

        player.onApplicationLooper = false
        assertThrows(IllegalStateException::class.java) {
            listener.onPlaybackStateChanged(Player.STATE_READY)
        }

        player.onApplicationLooper = true
        resume.close()
        resume.close()

        assertNull(player.listener)
        assertEquals(1, player.removeListenerCalls)
        assertThrows(IllegalStateException::class.java) {
            resume.beginPlaybackTarget(180_000.milliseconds)
        }
    }

    @Test
    fun `growing resume exposes its pending position until its own seek applies`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(identity),
            duration = 12_000,
        )
        val resume = TvheadendRecordingResume(player, identity, RecordingResumeMode.GROWING)

        resume.beginPlaybackTarget(8_000.milliseconds)
        assertEquals(8_000.milliseconds, resume.pendingPosition)
        assertNull(resume.outcome)

        player.isCurrentMediaItemSeekable = true
        player.listener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)

        assertEquals(listOf(8_000L), player.seeks)
        assertNull(resume.pendingPosition)
        assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
        assertEquals(listOf(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS), player.timeouts.scheduledDelays)
        assertEquals(0, player.timeouts.active)
    }

    @Test
    fun `a viewer seek before the growing timeline is seekable cancels resume`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(currentMediaItem = tvheadendRecordingMediaItem(identity))
        val resume = TvheadendRecordingResume(player, identity, RecordingResumeMode.GROWING)
        resume.beginPlaybackTarget(8_000.milliseconds)

        player.listener?.onPositionDiscontinuity(POSITION, POSITION, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(8_000.milliseconds, resume.pendingPosition)
        player.listener?.onPositionDiscontinuity(POSITION, POSITION, Player.DISCONTINUITY_REASON_SEEK)
        player.duration = 12_000
        player.isCurrentMediaItemSeekable = true
        player.listener?.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)

        assertEquals(emptyList<Long>(), player.seeks)
        assertNull(resume.pendingPosition)
        assertEquals(RecordingResumeOutcome.CANCELLED, resume.outcome)
    }

    @Test
    fun `an unseekable growing timeline abandons resume at the settle bound`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(identity),
            duration = 12_000,
        )
        val resume = TvheadendRecordingResume(player, identity, RecordingResumeMode.GROWING)
        resume.beginPlaybackTarget(8_000.milliseconds)

        player.timeouts.fireAll()

        assertEquals(emptyList<Long>(), player.seeks)
        assertNull(resume.pendingPosition)
        assertEquals(RecordingResumeOutcome.ABANDONED, resume.outcome)
    }

    @Test
    fun `close cancels a pending growing resume and its timeout`() {
        val identity = RecordingMediaIdentity()
        val player = FakeRecordingResumePlayer(currentMediaItem = tvheadendRecordingMediaItem(identity))
        val resume = TvheadendRecordingResume(player, identity, RecordingResumeMode.GROWING)
        resume.beginPlaybackTarget(8_000.milliseconds)

        resume.close()

        assertEquals(0, player.timeouts.active)
        assertEquals(RecordingResumeOutcome.CANCELLED, resume.outcome)
    }
}

private val POSITION = Player.PositionInfo(null, 0, null, null, 0, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET)

private class FakeRecordingResumePlayer(
    override var currentMediaItem: MediaItem? = null,
    override var duration: Long = C.TIME_UNSET,
    override var isCurrentMediaItemSeekable: Boolean = false,
) : RecordingResumePlayer {
    var listener: Player.Listener? = null
    var onApplicationLooper: Boolean = true
    var removeListenerCalls: Int = 0
    val seeks: MutableList<Long> = mutableListOf()

    override fun addListener(listener: Player.Listener) {
        check(this.listener == null)
        this.listener = listener
    }

    override fun removeListener(listener: Player.Listener) {
        check(this.listener === listener)
        this.listener = null
        removeListenerCalls += 1
    }

    val timeouts = FakeResumeTimeouts()

    override fun seekTo(positionMillis: Long) {
        seeks += positionMillis
        // ExoPlayer reports its own seek synchronously as a seek discontinuity.
        listener?.onPositionDiscontinuity(POSITION, POSITION, Player.DISCONTINUITY_REASON_SEEK)
    }

    override fun postDelayed(delayMillis: Long, action: () -> Unit): AutoCloseable =
        timeouts.schedule(delayMillis, action)

    override fun requireApplicationLooper() {
        check(onApplicationLooper) {
            "Recording resume must be called on the player's application looper"
        }
    }
}
