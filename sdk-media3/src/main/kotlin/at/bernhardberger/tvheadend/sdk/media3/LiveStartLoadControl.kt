@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)
@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.AbstractConcatenatedTimeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.util.concurrent.atomic.AtomicReference

/** Playback-thread-only scratch objects; the mutable threshold publishes independently. */
internal class LiveStartLoadControl(
    private val delegate: LoadControl,
    private val liveStartBuffer: TvheadendLiveStartBuffer,
    private val elapsedRealtimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) : LoadControl {
    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private val skip = AtomicReference<SkipMark?>(null)

    /** Application-looper publication; the playback thread consumes with CAS only on a restart. */
    internal fun beginSkip(token: PlaybackTargetToken, periodUid: Any, buffering: Boolean): SkipMark =
        SkipMark(token, periodUid, elapsedRealtimeMs(), buffering).also { skip.set(it) }

    internal fun finishSkip(mark: SkipMark, disposition: TimeshiftCommandDisposition?) {
        if (disposition == null || disposition == TimeshiftCommandDisposition.NOT_ACCEPTED) {
            cancelSkip(mark)
        } else {
            skip.compareAndSet(mark, mark.completed(elapsedRealtimeMs()))
        }
    }

    internal fun cancelSkip(mark: SkipMark) {
        val current = skip.get() ?: return
        if (current.attempt === mark.attempt) skip.compareAndSet(current, null)
    }

    internal fun retireTarget(token: PlaybackTargetToken) {
        val current = skip.get() ?: return
        if (current.token === token) skip.compareAndSet(current, null)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val live = isLive(parameters)
        val mark = eligibleSkip(parameters, live)
        if (!live || (parameters.rebuffering && mark == null)) return delegate.shouldStartPlayback(parameters)
        var requiredUs = liveStartBuffer.duration.inWholeMicroseconds
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            requiredUs = minOf(requiredUs, parameters.targetLiveOffsetUs / 2)
        }
        val ready = requiredUs <= 0 ||
            Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed) >= requiredUs
        if (ready && mark != null && !skip.compareAndSet(mark, null)) {
            // Completion, cancellation or replacement raced this decision. Re-read the current mark.
            return shouldStartPlayback(parameters)
        }
        return ready
    }

    private fun eligibleSkip(parameters: LoadControl.Parameters, live: Boolean): SkipMark? {
        val mark = skip.get() ?: return null
        if (!live || !mark.token.isActive() || !mark.token.ownsPlayerItem()) {
            skip.compareAndSet(mark, null)
            return null
        }
        val periodUid = if (parameters.timeline is AbstractConcatenatedTimeline) {
            AbstractConcatenatedTimeline.getChildPeriodUidFromConcatenatedUid(parameters.mediaPeriodId.periodUid)
        } else parameters.mediaPeriodId.periodUid
        val started = parameters.lastRebufferRealtimeMs
        val eligible = mark.periodUid == periodUid &&
            if (parameters.rebuffering && started != C.TIME_UNSET) {
                (mark.alreadyBuffering || started >= mark.startedMs) &&
                    (mark.completedMs == null || started <= mark.completedMs + SKIP_GRACE_MS)
            } else mark.completedMs == null || elapsedRealtimeMs() <= mark.completedMs + SKIP_GRACE_MS
        if (!eligible) skip.compareAndSet(mark, null)
        return mark.takeIf { eligible }
    }

    private fun isLive(parameters: LoadControl.Parameters): Boolean {
        val index = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return false
        parameters.timeline.getPeriod(index, period)
        return parameters.timeline.getWindow(period.windowIndex, window).mediaItem.isTvheadendLive()
    }

    // Java default methods must be explicitly forwarded, including preload and lifecycle callbacks.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    internal class SkipMark(
        val token: PlaybackTargetToken,
        val periodUid: Any,
        val startedMs: Long,
        val alreadyBuffering: Boolean,
        val completedMs: Long? = null,
        val attempt: Any = Any(),
    ) {
        fun completed(at: Long): SkipMark = SkipMark(token, periodUid, startedMs, alreadyBuffering, at, attempt)
        override fun toString(): String = "LiveSkipMark"
    }

    private companion object {
        const val SKIP_GRACE_MS = 3_000L
    }
}
