@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.consumer

import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.media3.LivePlaybackOptions
import at.bernhardberger.tvheadend.sdk.media3.LiveTimeshiftState
import at.bernhardberger.tvheadend.sdk.media3.PlaybackTargetResult
import at.bernhardberger.tvheadend.sdk.media3.RecordingPlaybackStart
import at.bernhardberger.tvheadend.sdk.media3.TvheadendPlaybackCoordinator
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandResult
import at.bernhardberger.tvheadend.sdk.media3.createTvheadendPlaybackCoordinator
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

public class StagedSdkConsumer(
    private val session: TvheadendSession,
    player: ExoPlayer,
) {
    private val coordinator: TvheadendPlaybackCoordinator =
        createTvheadendPlaybackCoordinator(session, player)

    public suspend fun run(): Unit = coordinator.run()

    public suspend fun playLive(channelId: ChannelId): PlaybackTargetResult =
        coordinator.setLiveTarget(channelId)

    public suspend fun streamProfiles(): StreamProfilesResult = session.getStreamProfiles()

    public suspend fun playLive(
        channelId: ChannelId,
        streamProfileId: StreamProfileId?,
        timeshiftPeriod: Duration,
    ): PlaybackTargetResult = coordinator.setLiveTarget(
        channelId,
        LivePlaybackOptions(streamProfileId, timeshiftPeriod),
    )

    public suspend fun resume(recordingId: DvrEntryId): PlaybackTargetResult =
        coordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.RESUME)

    public suspend fun startGrowing(recordingId: DvrEntryId): PlaybackTargetResult =
        coordinator.setRecordingTarget(recordingId, RecordingPlaybackStart.START_OVER)

    public val timeshiftState: StateFlow<LiveTimeshiftState>
        get() = coordinator.timeshiftState

    public suspend fun seekTimeshift(offset: Duration): TimeshiftCommandResult =
        coordinator.seekTimeshift(offset)

    public suspend fun returnToLive(): TimeshiftCommandResult = coordinator.returnToLive()

    public suspend fun pauseTimeshift(): TimeshiftCommandResult = coordinator.pauseTimeshift()

    public suspend fun resumeTimeshift(): TimeshiftCommandResult = coordinator.resumeTimeshift()

    public fun isGrowingResumeUnsupported(result: PlaybackTargetResult): Boolean =
        result == PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED
}
