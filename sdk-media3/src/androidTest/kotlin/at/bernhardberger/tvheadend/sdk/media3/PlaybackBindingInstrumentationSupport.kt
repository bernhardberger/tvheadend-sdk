@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions

internal fun TvheadendSession.livePlaybackBindingOrNull(
    channelId: ChannelId,
): PlaybackBinding.Live? {
    val currentSession = observation.value.currentSession ?: return null
    return (bindLivePlayback(currentSession, channelId) as? PlaybackBindingResult.Bound)?.binding
}

internal fun TvheadendSession.requireLivePlaybackBinding(
    channelId: ChannelId,
): PlaybackBinding.Live = checkNotNull(livePlaybackBindingOrNull(channelId)) {
    "The selected live target is not current"
}

internal fun TvheadendSession.requireRecordingPlaybackBinding(
    recordingId: DvrEntryId,
): PlaybackBinding.Recording {
    val currentSession = checkNotNull(observation.value.currentSession) {
        "No current session observation is available"
    }
    return when (val result = bindRecordingPlayback(currentSession, recordingId)) {
        is PlaybackBindingResult.Bound -> result.binding
        PlaybackBindingResult.ObservationExpired -> error("The session observation expired")
        PlaybackBindingResult.TargetUnavailable -> error("The recording target is unavailable")
    }
}

internal class FixedSubscriptionLiveTarget(
    private val opener: SubscriptionOpener,
    private val channelId: SubscriptionChannelId,
) : CoordinatorLiveTarget {
    override val isCurrent: Boolean = true

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = opener.open(channelId, consumer, options)
}
