@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.consumer

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.media3.PlaybackTargetResult
import at.bernhardberger.tvheadend.sdk.media3.RecordingPlaybackStart
import at.bernhardberger.tvheadend.sdk.media3.TvheadendPlaybackCoordinator
import at.bernhardberger.tvheadend.sdk.media3.createTvheadendPlaybackCoordinator

public class StagedSdkConsumer(
    session: TvheadendSession,
    player: ExoPlayer,
) {
    private val coordinator: TvheadendPlaybackCoordinator =
        createTvheadendPlaybackCoordinator(session, player)

    public suspend fun run(): Unit = coordinator.run()

    public suspend fun playLive(channelId: ChannelId): PlaybackTargetResult =
        coordinator.setLiveTarget(channelId)

    public suspend fun resume(recordingId: DvrEntryId): PlaybackTargetResult =
        coordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.RESUME)
}
