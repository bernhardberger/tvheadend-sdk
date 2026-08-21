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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType

/**
 * Creates a live Media3 source backed directly by the SDK subscription stream.
 *
 * [onUnsupportedStream] receives only a safe codec category and must return quickly without
 * throwing. Unsupported streams are omitted; a subscription with no supported streams fails.
 */
@SubscriptionInfrastructureApi
@androidx.media3.common.util.UnstableApi
public fun createTvheadendLiveMediaSource(
    subscriptions: SubscriptionOpener,
    channelId: SubscriptionChannelId,
    onUnsupportedStream: (SubscriptionStreamType) -> Unit = {},
): MediaSource = TvheadendLiveMediaSource(subscriptions, channelId, onUnsupportedStream)

private class TvheadendLiveMediaSource(
    private val subscriptions: SubscriptionOpener,
    private val channelId: SubscriptionChannelId,
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
        subscriptions = subscriptions,
        channelId = channelId,
        allocator = allocator,
        onUnsupportedStream = onUnsupportedStream,
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as TvheadendLiveMediaPeriod).release()
    }

    override fun releaseSourceInternal(): Unit = Unit
}
