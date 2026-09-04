@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.consumer

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.android.TvheadendArtworkLoadException
import at.bernhardberger.tvheadend.sdk.android.ServerProfileEditReadResult
import at.bernhardberger.tvheadend.sdk.android.ServerProfileOperationResult
import at.bernhardberger.tvheadend.sdk.android.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.android.TvheadendServerProfileStore
import at.bernhardberger.tvheadend.sdk.android.addTvheadendArtwork
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity
import at.bernhardberger.tvheadend.sdk.core.RetainedMetadataAuthority
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.channelCatalogAuthority
import at.bernhardberger.tvheadend.sdk.core.channelCatalogForDisplay
import at.bernhardberger.tvheadend.sdk.core.dvrSnapshotAuthority
import at.bernhardberger.tvheadend.sdk.core.dvrSnapshotForDisplay
import at.bernhardberger.tvheadend.sdk.core.epgSnapshotAuthority
import at.bernhardberger.tvheadend.sdk.core.epgSnapshotForDisplay
import at.bernhardberger.tvheadend.sdk.media3.LivePlaybackOptions
import at.bernhardberger.tvheadend.sdk.media3.LiveTimeshiftState
import at.bernhardberger.tvheadend.sdk.media3.PlaybackTargetResult
import at.bernhardberger.tvheadend.sdk.media3.RecordingPlaybackStart
import at.bernhardberger.tvheadend.sdk.media3.TvheadendPlaybackCoordinator
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandResult
import at.bernhardberger.tvheadend.sdk.media3.createTvheadendPlaybackCoordinator
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import coil3.ComponentRegistry
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

public class StagedSdkConsumer(
    context: Context,
    private val session: TvheadendSession,
    player: ExoPlayer,
) {
    private val profileStore: TvheadendServerProfileStore = TvheadendServerProfileStore(context)
    private val coordinator: TvheadendPlaybackCoordinator =
        createTvheadendPlaybackCoordinator(player)

    public suspend fun loadServerProfile(): ServerProfileReadResult = profileStore.loadProfile()

    public suspend fun loadServerProfileForEditing(): ServerProfileEditReadResult =
        profileStore.loadProfileForEditing()

    public fun hasExpectedEditableFields(result: ServerProfileEditReadResult): Boolean = when (result) {
        ServerProfileEditReadResult.Missing,
        ServerProfileEditReadResult.Unavailable,
        -> true
        is ServerProfileEditReadResult.Anonymous -> result.host.isNotEmpty() && result.port > 0
        is ServerProfileEditReadResult.Password ->
            result.host.isNotEmpty() &&
                result.port > 0 &&
                result.username.isNotEmpty() &&
                result.password.isNotEmpty()
    }

    public suspend fun storeAnonymousServerProfile(
        host: String,
        port: Int,
    ): ServerProfileOperationResult = profileStore.storeAnonymous(host, port)

    public suspend fun run(): Unit = coordinator.run()

    public val observation: StateFlow<SessionObservation>
        get() = session.observation

    public fun isCurrent(currentSession: CurrentSessionObservation): Boolean =
        session.isCurrent(currentSession)

    public suspend fun awaitReplacement(
        currentSession: CurrentSessionObservation,
    ): CurrentSessionObservation = session.awaitCurrentSession(currentSession)

    public fun generationIdentity(
        currentSession: CurrentSessionObservation,
    ): SessionGenerationIdentity = currentSession.generationIdentity

    public fun registerArtwork(
        components: ComponentRegistry.Builder,
    ): ComponentRegistry.Builder = components.addTvheadendArtwork()

    public fun artworkFailure(failure: TvheadendArtworkLoadException): ArtworkFailure =
        failure.failure

    public fun channel(id: ChannelId): Channel? = observation.value.channel(id)

    public fun channelCatalogForDisplay(state: ChannelRepositoryState): ChannelCatalog? =
        state.channelCatalogForDisplay

    public fun epgSnapshotForDisplay(state: EpgRepositoryState): EpgSnapshot? =
        state.epgSnapshotForDisplay

    public fun dvrSnapshotForDisplay(state: DvrRepositoryState): DvrSnapshot? =
        state.dvrSnapshotForDisplay

    public fun observationChannelCatalogForDisplay(observation: SessionObservation): ChannelCatalog? =
        observation.channelCatalogForDisplay

    public fun observationEpgSnapshotForDisplay(observation: SessionObservation): EpgSnapshot? =
        observation.epgSnapshotForDisplay

    public fun observationDvrSnapshotForDisplay(observation: SessionObservation): DvrSnapshot? =
        observation.dvrSnapshotForDisplay

    public fun retainedMetadataIsCurrent(observation: SessionObservation): Boolean =
        observation.channelCatalogAuthority == RetainedMetadataAuthority.CURRENT &&
            observation.epgSnapshotAuthority == RetainedMetadataAuthority.CURRENT &&
            observation.dvrSnapshotAuthority == RetainedMetadataAuthority.CURRENT &&
            observation.channelState.channelCatalogAuthority == RetainedMetadataAuthority.CURRENT &&
            observation.epgState.epgSnapshotAuthority == RetainedMetadataAuthority.CURRENT &&
            observation.dvrState.dvrSnapshotAuthority == RetainedMetadataAuthority.CURRENT

    public fun authorityDescription(authority: RetainedMetadataAuthority): String = when (authority) {
        RetainedMetadataAuthority.ABSENT -> "absent"
        RetainedMetadataAuthority.SYNCHRONIZING_WITHOUT_RETAINED_DATA -> "synchronizing-empty"
        RetainedMetadataAuthority.SYNCHRONIZING_WITH_RETAINED_DATA -> "synchronizing-retained"
        RetainedMetadataAuthority.CURRENT -> "current"
        RetainedMetadataAuthority.STALE -> "stale"
    }

    public fun eventAt(channelId: ChannelId, at: Instant): EpgEvent? =
        observation.value.eventAt(channelId, at)

    public fun nextEvent(channelId: ChannelId, at: Instant): EpgEvent? =
        observation.value.nextEvent(channelId, at)

    public suspend fun playLive(channelId: ChannelId): PlaybackTargetResult {
        val currentSession = observation.value.currentSession ?: return PlaybackTargetResult.NOT_READY
        return usePlaybackBinding(session.bindLivePlayback(currentSession, channelId)) { binding ->
            coordinator.setLiveTarget(binding)
        }
    }

    public suspend fun streamProfiles(): StreamProfilesResult = session.getStreamProfiles(
        requireNotNull(observation.value.currentSession),
    )

    public fun streamProfileProvenance(
        result: StreamProfilesResult,
    ): CurrentSessionObservation? = (result as? StreamProfilesResult.Available)?.originatingSession

    public suspend fun searchEpg(request: EpgSearchRequest): EpgSearchResult =
        session.epgRepository.search(
            requireNotNull(observation.value.currentSession),
            request,
        )

    public fun epgSearchProvenance(
        result: EpgSearchResult,
    ): CurrentSessionObservation? = (result as? EpgSearchResult.Available)?.originatingSession

    public suspend fun playLive(
        channelId: ChannelId,
        streamProfileId: StreamProfileId?,
        timeshiftPeriod: Duration,
    ): PlaybackTargetResult {
        val currentSession = observation.value.currentSession ?: return PlaybackTargetResult.NOT_READY
        return usePlaybackBinding(session.bindLivePlayback(currentSession, channelId)) { binding ->
            coordinator.setLiveTarget(
                binding,
                LivePlaybackOptions(streamProfileId, timeshiftPeriod),
            )
        }
    }

    public suspend fun resume(recordingId: DvrEntryId): PlaybackTargetResult {
        val currentSession = observation.value.currentSession ?: return PlaybackTargetResult.NOT_READY
        return usePlaybackBinding(
            session.bindRecordingPlayback(currentSession, recordingId),
        ) { binding ->
            coordinator.setRecordingTarget(binding, RecordingPlaybackStart.RESUME)
        }
    }

    public suspend fun startGrowing(recordingId: DvrEntryId): PlaybackTargetResult {
        val currentSession = observation.value.currentSession ?: return PlaybackTargetResult.NOT_READY
        return usePlaybackBinding(
            session.bindRecordingPlayback(currentSession, recordingId),
        ) { binding ->
            coordinator.setRecordingTarget(binding, RecordingPlaybackStart.START_OVER)
        }
    }

    public val timeshiftState: StateFlow<LiveTimeshiftState>
        get() = coordinator.timeshiftState

    public val subscriptionIssue: StateFlow<SubscriptionIssue?>
        get() = coordinator.subscriptionIssue

    public val liveDiagnostics: StateFlow<LiveSubscriptionDiagnostics?>
        get() = coordinator.liveDiagnostics

    public fun currentLiveServiceName(): String? = liveDiagnostics.value?.source?.serviceName

    public fun currentLiveSignalPercent(): Double? =
        liveDiagnostics.value?.frontend?.relativeSignalPercent

    public fun currentLiveQueuePackets(): Long? = liveDiagnostics.value?.queue?.packetCount

    public fun currentLiveQueueSpan(): Duration? = liveDiagnostics.value?.queue?.mediaSpan

    public suspend fun seekTimeshift(offset: Duration): TimeshiftCommandResult =
        coordinator.seekTimeshift(offset)

    public suspend fun returnToLive(): TimeshiftCommandResult = coordinator.returnToLive()

    public suspend fun pauseTimeshift(): TimeshiftCommandResult = coordinator.pauseTimeshift()

    public suspend fun resumeTimeshift(): TimeshiftCommandResult = coordinator.resumeTimeshift()

    public fun isGrowingResumeUnsupported(result: PlaybackTargetResult): Boolean =
        when (result) {
            PlaybackTargetResult.GROWING_RECORDING_RESUME_UNSUPPORTED -> true
            else -> false
        }

    public fun targetStarted(result: PlaybackTargetResult): Boolean = result.isStarted

    public fun targetMayChange(result: PlaybackTargetResult): Boolean = result.isTransient

    public fun targetUnsupported(result: PlaybackTargetResult): Boolean = result.isUnsupported

    public fun timeshiftAccepted(result: TimeshiftCommandResult): Boolean = result.isAccepted

    public fun timeshiftMayChange(result: TimeshiftCommandResult): Boolean = result.isTransient

    public fun timeshiftTerminal(result: TimeshiftCommandResult): Boolean = result.isTerminal

    public fun timeshiftUnsupported(result: TimeshiftCommandResult): Boolean = result.isUnsupported

    public fun timeshiftNeedsConfigurationOrAccess(result: TimeshiftCommandResult): Boolean =
        result.isConfigurationOrAccessRelated

    public fun timeshiftOutcomeUncertain(result: TimeshiftCommandResult): Boolean =
        result.isOutcomeUncertain

    public fun issueNeedsConfigurationOrAccess(issue: SubscriptionIssue): Boolean =
        issue.isConfigurationOrAccessRelated

    private suspend fun <T : PlaybackBinding> usePlaybackBinding(
        result: PlaybackBindingResult<T>,
        play: suspend (T) -> PlaybackTargetResult,
    ): PlaybackTargetResult = when (result) {
        is PlaybackBindingResult.Bound -> play(result.binding)
        PlaybackBindingResult.ObservationExpired -> PlaybackTargetResult.NOT_READY
        PlaybackBindingResult.TargetUnavailable -> PlaybackTargetResult.TARGET_UNAVAILABLE
    }
}
