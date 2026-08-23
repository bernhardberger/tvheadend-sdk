@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Applies one recording resume position to an application-owned [Player].
 *
 * Call [beginPlaybackTarget] after installing a TVHeadend recording media item and before preparing
 * it. Resume remains pending until the selected recording exposes a known, seekable timeline, which
 * keeps progressive MP4 and MKV playback from losing an early seek while their extractors discover
 * the seek map. Closing this coordinator never releases or changes the player.
 */
@SubscriptionInfrastructureApi
public class TvheadendRecordingResume internal constructor(
    private val player: RecordingResumePlayer,
) : AutoCloseable {
    internal constructor(player: Player) : this(Media3RecordingResumePlayer(player))

    private val stateMachine = RecordingResumeStateMachine(player::seekTo)
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            requirePlayerLooper()
            evaluateResume()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            requirePlayerLooper()
            evaluateResume()
        }
    }
    private var closed = false

    init {
        requirePlayerLooper()
        player.addListener(listener)
    }

    /**
     * Replaces any pending target with [recordingId] and its optional [resumePosition].
     *
     * A null or zero position starts over. A position at or beyond the eventual media duration also
     * starts over rather than issuing an invalid seek. The seek is applied at most once.
     */
    public fun beginPlaybackTarget(recordingId: RecordingId, resumePosition: Duration?) {
        requirePlayerLooper()
        check(!closed) { "Recording resume is closed" }
        check(currentRecordingId() == recordingId) {
            "Recording resume target must already be installed on the player"
        }
        val positionMillis = resumePosition?.let { position ->
            require(
                position.isFinite() &&
                    !position.isNegative() &&
                    position == position.inWholeMilliseconds.milliseconds,
            ) {
                "Recording resume position must be a finite non-negative whole-millisecond duration"
            }
            position.inWholeMilliseconds
        }
        stateMachine.beginPlaybackTarget(recordingId, positionMillis)
        evaluateResume()
    }

    /** Detaches resume handling without releasing the application-owned player. */
    override fun close() {
        requirePlayerLooper()
        if (closed) return
        closed = true
        player.removeListener(listener)
        stateMachine.close()
    }

    private fun evaluateResume() {
        val recordingId = currentRecordingId()
        val durationMillis = player.duration.takeIf { duration ->
            duration != C.TIME_UNSET && duration >= 0L
        }
        stateMachine.onMediaState(recordingId, durationMillis, player.isCurrentMediaItemSeekable)
    }

    private fun currentRecordingId(): RecordingId? = player.currentMediaItem?.let { mediaItem ->
        parseRecordingId(mediaItem.mediaId)
            ?: mediaItem.localConfiguration?.uri?.toString()?.let(::parseRecordingId)
    }

    private fun requirePlayerLooper() {
        player.requireApplicationLooper()
    }
}

/** Creates one duration-gated resume coordinator for an application-owned [Player]. */
@SubscriptionInfrastructureApi
@androidx.media3.common.util.UnstableApi
public fun createTvheadendRecordingResume(player: Player): TvheadendRecordingResume =
    TvheadendRecordingResume(player)

internal class RecordingResumeStateMachine(
    private val seekTo: (Long) -> Unit,
) {
    private var pending: PendingResume? = null
    private var closed = false

    fun beginPlaybackTarget(recordingId: RecordingId, positionMillis: Long?) {
        check(!closed) { "Recording resume is closed" }
        pending = positionMillis
            ?.takeIf { position -> position > 0L }
            ?.let { position -> PendingResume(recordingId, position) }
    }

    fun onMediaState(
        recordingId: RecordingId?,
        durationMillis: Long?,
        isSeekable: Boolean,
    ) {
        val target = pending ?: return
        if (recordingId != target.recordingId || durationMillis == null) return
        if (target.positionMillis >= durationMillis) {
            pending = null
            return
        }
        if (!isSeekable) return
        pending = null
        seekTo(target.positionMillis)
    }

    fun close() {
        closed = true
        pending = null
    }

    private data class PendingResume(
        val recordingId: RecordingId,
        val positionMillis: Long,
    )
}

internal interface RecordingResumePlayer {
    public val currentMediaItem: MediaItem?
    public val duration: Long
    public val isCurrentMediaItemSeekable: Boolean

    public fun addListener(listener: Player.Listener)

    public fun removeListener(listener: Player.Listener)

    public fun seekTo(positionMillis: Long)

    public fun requireApplicationLooper()
}

private class Media3RecordingResumePlayer(
    private val player: Player,
) : RecordingResumePlayer {
    override val currentMediaItem: MediaItem?
        get() = player.currentMediaItem
    override val duration: Long
        get() = player.duration
    override val isCurrentMediaItemSeekable: Boolean
        get() = player.isCurrentMediaItemSeekable

    override fun addListener(listener: Player.Listener) {
        player.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        player.removeListener(listener)
    }

    override fun seekTo(positionMillis: Long) {
        player.seekTo(positionMillis)
    }

    override fun requireApplicationLooper() {
        check(Looper.myLooper() === player.applicationLooper) {
            "Recording resume must be called on the player's application looper"
        }
    }
}
