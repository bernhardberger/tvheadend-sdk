@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType

internal fun createTvheadendLiveMediaSource(
    target: CoordinatorLiveTarget,
    options: SubscriptionOptions = SubscriptionOptions(),
    timeshiftControls: LiveTimeshiftControlBridge? = null,
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): MediaSource = TvheadendLiveMediaSource(
    target,
    options,
    timeshiftControls,
    onUnsupportedStream,
)

private class TvheadendLiveMediaSource(
    private val target: CoordinatorLiveTarget,
    private val options: SubscriptionOptions,
    private val timeshiftControls: LiveTimeshiftControlBridge?,
    private val onUnsupportedStream: (SubscriptionStreamType) -> Unit,
) : BaseMediaSource() {
    private val mediaItem = MediaItem.Builder()
        .setMediaId("tvheadend-live")
        .build()

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        refreshSourceInfo(
            SinglePeriodTimeline(
                C.TIME_UNSET,
                false,
                true,
                true,
                null,
                mediaItem,
            ),
        )
    }

    override fun maybeThrowSourceInfoRefreshError(): Unit = Unit

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = TvheadendLiveMediaPeriod(
        target = target,
        options = options,
        allocator = allocator,
        timeshiftControls = timeshiftControls?.newAttachment(),
        onUnsupportedStream = onUnsupportedStream,
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as TvheadendLiveMediaPeriod).release()
    }

    override fun releaseSourceInternal(): Unit = Unit
}
