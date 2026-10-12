@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)
@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/** Playback-thread-only scratch objects; the mutable threshold publishes independently. */
internal class LiveStartLoadControl(
    private val delegate: LoadControl,
    private val liveStartBuffer: TvheadendLiveStartBuffer,
) : LoadControl {
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!isLive(parameters) || parameters.rebuffering) return delegate.shouldStartPlayback(parameters)
        var requiredUs = liveStartBuffer.duration.inWholeMicroseconds
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            requiredUs = minOf(requiredUs, parameters.targetLiveOffsetUs / 2)
        }
        return requiredUs <= 0 ||
            Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed) >= requiredUs
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
}
