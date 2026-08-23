@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.microseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class TvheadendRecordingResumeTest {
    @Test
    fun `listener waits for a known seekable timeline and applies resume once`() {
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(RecordingId(7)),
        )
        val resume = TvheadendRecordingResume(player)

        assertNotNull(player.listener)
        resume.beginPlaybackTarget(RecordingId(7), 180_000.milliseconds)
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
    fun `known media applies immediately using identity preserved in media id`() {
        val player = FakeRecordingResumePlayer(
            currentMediaItem = MediaItem.Builder()
                .setMediaId(recordingUri(RecordingId(7)))
                .build(),
            duration = 3_600_000,
            isCurrentMediaItemSeekable = true,
        )
        val resume = TvheadendRecordingResume(player)

        resume.beginPlaybackTarget(RecordingId(7), 180_000.milliseconds)

        assertEquals(listOf(180_000L), player.seeks)
    }

    @Test
    fun `begin validates installed identity and whole millisecond position`() {
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(RecordingId(7)),
        )
        val resume = TvheadendRecordingResume(player)

        assertThrows(IllegalStateException::class.java) {
            resume.beginPlaybackTarget(RecordingId(8), 180_000.milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resume.beginPlaybackTarget(RecordingId(7), (-1).milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resume.beginPlaybackTarget(RecordingId(7), 1_500.microseconds)
        }
    }

    @Test
    fun `callbacks enforce the application looper and close only detaches the listener`() {
        val player = FakeRecordingResumePlayer(
            currentMediaItem = tvheadendRecordingMediaItem(RecordingId(7)),
        )
        val resume = TvheadendRecordingResume(player)
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
            resume.beginPlaybackTarget(RecordingId(7), 180_000.milliseconds)
        }
    }
}

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

    override fun seekTo(positionMillis: Long) {
        seeks += positionMillis
    }

    override fun requireApplicationLooper() {
        check(onApplicationLooper) {
            "Recording resume must be called on the player's application looper"
        }
    }
}
