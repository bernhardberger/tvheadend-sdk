@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.consumer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import at.bernhardberger.tvheadend.sdk.android.DiscoveredTvheadendServer
import at.bernhardberger.tvheadend.sdk.android.TvheadendConnectivity
import at.bernhardberger.tvheadend.sdk.android.TvheadendConnectivityStatus
import at.bernhardberger.tvheadend.sdk.android.TvheadendDiscovery
import at.bernhardberger.tvheadend.sdk.android.TvheadendDiscoveryState
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import at.bernhardberger.tvheadend.sdk.media3.TvheadendAudioOutputProvider
import at.bernhardberger.tvheadend.sdk.android.TvheadendArtwork
import at.bernhardberger.tvheadend.sdk.android.TvheadendArtworkLoadException
import at.bernhardberger.tvheadend.sdk.android.ServerProfileEditReadResult
import at.bernhardberger.tvheadend.sdk.android.TvheadendServerProfileStore
import at.bernhardberger.tvheadend.sdk.android.addTvheadendArtwork
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelIcon
import at.bernhardberger.tvheadend.sdk.core.ChannelMediaKind
import at.bernhardberger.tvheadend.sdk.core.mediaKind
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEventEntries
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream
import at.bernhardberger.tvheadend.sdk.core.AutorecRule
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRule
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.AutorecTime
import at.bernhardberger.tvheadend.sdk.core.BroadcastType
import at.bernhardberger.tvheadend.sdk.core.DuplicateDetection
import at.bernhardberger.tvheadend.sdk.core.DvrPriority
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrRemoval
import at.bernhardberger.tvheadend.sdk.core.DvrRetention
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.PlaybackBinding
import at.bernhardberger.tvheadend.sdk.core.PlaybackBindingResult
import at.bernhardberger.tvheadend.sdk.core.RecordingPlaybackAdmission
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import at.bernhardberger.tvheadend.sdk.core.SessionRecoveryDisposition
import at.bernhardberger.tvheadend.sdk.core.RetainedMetadataAuthority
import at.bernhardberger.tvheadend.sdk.core.ServerProfileReadResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileStore
import at.bernhardberger.tvheadend.sdk.core.ServerProfileId
import at.bernhardberger.tvheadend.sdk.core.ServerProfileSummary
import at.bernhardberger.tvheadend.sdk.core.ServerProfileListResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult
import at.bernhardberger.tvheadend.sdk.core.ServerProfileAuthenticationMode
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.channelCatalogAuthority
import at.bernhardberger.tvheadend.sdk.core.channelCatalogForDisplay
import at.bernhardberger.tvheadend.sdk.core.dvrSnapshotAuthority
import at.bernhardberger.tvheadend.sdk.core.dvrSnapshotForDisplay
import at.bernhardberger.tvheadend.sdk.core.epgSnapshotAuthority
import at.bernhardberger.tvheadend.sdk.core.epgSnapshotForDisplay
import at.bernhardberger.tvheadend.sdk.media3.LivePlaybackOptions
import at.bernhardberger.tvheadend.sdk.media3.LivePlaybackObservation
import at.bernhardberger.tvheadend.sdk.media3.LivePlaybackTargetResult
import at.bernhardberger.tvheadend.sdk.media3.LiveTimeshiftState
import at.bernhardberger.tvheadend.sdk.media3.PlaybackCoordinatorLifetime
import at.bernhardberger.tvheadend.sdk.media3.PlaybackShutdownResult
import at.bernhardberger.tvheadend.sdk.media3.PlaybackTargetResult
import at.bernhardberger.tvheadend.sdk.media3.RecordingPlaybackStart
import at.bernhardberger.tvheadend.sdk.media3.TvheadendPlaybackCoordinator
import at.bernhardberger.tvheadend.sdk.media3.TvheadendLiveStartBuffer
import at.bernhardberger.tvheadend.sdk.media3.TvheadendLoadControl
import at.bernhardberger.tvheadend.sdk.media3.createTvheadendLoadControl
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftCommandResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftContentTarget
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftContentSeekResult
import at.bernhardberger.tvheadend.sdk.media3.TimeshiftPlaybackPosition
import at.bernhardberger.tvheadend.sdk.media3.createTvheadendPlaybackCoordinator
import at.bernhardberger.tvheadend.sdk.media3.isTvheadendLive
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.LiveRetuneBudget
import at.bernhardberger.tvheadend.sdk.playback.LiveRetuneDecision
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.testing.FakeTvheadendSession
import at.bernhardberger.tvheadend.sdk.testing.ChannelCatalogFixture
import at.bernhardberger.tvheadend.sdk.testing.DvrRecordingFixture
import at.bernhardberger.tvheadend.sdk.testing.FakeServerProfileStore
import coil3.ComponentRegistry
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// These consumer roots retain real implementations and cache serializers under R8, not just fakes.
public fun stagedConsumer(context: Context): StagedSdkConsumer {
    val handle = createTvheadendLoadControl(TvheadendLiveStartBuffer())
    val player = handle.configure(ExoPlayer.Builder(context)).build()
    return StagedSdkConsumer(context, stagedSession(context), player, handle)
}

public fun stagedSession(context: Context): TvheadendSession = createTvheadendSession(
    EpgCoveragePolicy.create(),
    MetadataCachePolicy.create(java.io.File(context.cacheDir, "tvheadend")),
)

public fun stagedDiscovery(context: Context): Flow<TvheadendDiscoveryState> =
    TvheadendDiscovery(context).state

public fun stagedDiscoveredServer(name: String, host: String, port: Int): DiscoveredTvheadendServer =
    DiscoveredTvheadendServer(name, host, port)

public fun stagedConnectivity(context: Context): Flow<TvheadendConnectivityStatus> =
    TvheadendConnectivity(context).status

public fun stagedAudioOutput(context: Context): TvheadendAudioOutputProvider =
    TvheadendAudioOutputProvider(context)

public fun stagedRetuneDelay(budget: LiveRetuneBudget = LiveRetuneBudget()): Duration? =
    when (val decision = budget.nextAttempt()) {
        is LiveRetuneDecision.Admitted -> decision.delay
        LiveRetuneDecision.Exhausted -> null
    }

public fun recordingSnapshotForFake(): DvrSnapshot = DvrSnapshot.create(
    entries = listOf(DvrEntry.create(DvrEntryId(1), files = listOf(DvrRecordingFile.create(fileId = 1)))),
    autorecRules = listOf(AutorecRule.create(AutorecRuleId("autorec"))),
    timerecRules = listOf(TimerecRule.create(TimerecRuleId("timerec"))),
)

public fun radioCatalogForFake(): List<Channel> = ChannelCatalogFixture.create().channels.filter {
    it.mediaKind == ChannelMediaKind.RADIO && it.services.orEmpty().any { service ->
        service.mediaKind == ChannelMediaKind.RADIO
    }
}

public fun creditedEventForFake(start: Instant, stop: Instant): EpgEvent = EpgEvent.create(
    id = EventId(1), start = start, stop = stop,
    credits = listOf(EpgCredit("Cast member", EpgCreditRole.of("actor"))),
)

public fun dvrRejectionForFake(): DvrMutationResult.Failed =
    DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false, "Access denied")

public fun dvrRejectionText(result: DvrMutationResult.Failed): String? = result.serverMessage

public fun dvrWithAccountLabelsForFake(): DvrEntry =
    DvrEntry.create(DvrEntryId(1), owner = "fixture-owner", creator = "fixture-creator")

public fun programmeRecordings(observation: SessionObservation, eventId: Long): DvrEventEntries? =
    observation.dvrEntriesForEvent(EventId(eventId))

public fun heldProgrammeRecordings(observation: SessionObservation, event: EpgEvent): DvrEventEntries? =
    observation.dvrEntriesForEvent(event)

public fun heldProgrammeRecording(observation: SessionObservation, event: EpgEvent): DvrEntry? =
    observation.dvrEntryForEvent(event)

public fun programmeRecordingTargets(observation: SessionObservation, eventId: Long): List<DvrEntry> =
    programmeRecordings(observation, eventId)?.let { listOf(it.selected) + it.otherActive }.orEmpty()

public fun timerecWithRemovalForFake(): TimerecRule =
    TimerecRule.create(TimerecRuleId("timerec"), removal = DvrRemoval.Days(30))

public fun timerecRemovalDays(rule: TimerecRule): Int? = (rule.removal as? DvrRemoval.Days)?.days

public fun keepUntilSpaceNeeded(event: EventId): DvrScheduleRequest = DvrScheduleRequest(
    schedule = DvrSchedule.Programme(event),
    startExtraMinutes = 2.minutes,
    stopExtraMinutes = 10.minutes,
    priority = DvrPriority.HIGH,
    retention = DvrRetention.UntilFilesRemoved,
    removal = DvrRemoval.UntilSpaceNeeded,
)

public fun keepForever(): DvrEntryUpdate = DvrEntryUpdate(removal = DvrRemoval.Forever)

public fun mergeTextSearch(query: String): EpgSearchRequest = EpgSearchRequest.create(query, mergeText = true)

public fun dvrAccountLabels(entry: DvrEntry, autorec: AutorecRule, timerec: TimerecRule): List<String?> =
    listOf(entry.owner, entry.creator, autorec.owner, autorec.creator, timerec.owner, timerec.creator)

public fun programmeCreditLabels(event: EpgEvent): List<String>? = event.credits?.map { credit ->
    val role = when (credit.role) {
        EpgCreditRole.ACTOR -> "Cast"
        EpgCreditRole.DIRECTOR -> "Director"
        else -> credit.role.raw
    }
    "${credit.name}: $role"
}

public fun freshnessForFake(acceptedAt: kotlin.time.Instant): SessionObservation = SessionObservation.create(
    channelRefreshTimes = MetadataRefreshTimes(lastUpdatedAt = acceptedAt),
    epgRefreshTimes = MetadataRefreshTimes(acceptedAt, acceptedAt),
    epgLastQueriedAt = mapOf(ChannelId(1) to acceptedAt),
)

public fun channelQueryTime(observed: SessionObservation, channelId: ChannelId): kotlin.time.Instant? =
    observed.epgLastQueriedAt[channelId]

public fun recordingStreamsForFake(): List<DvrRecordingStream> = DvrRecordingFixture.create().entries
    .flatMap { entry -> entry.files.orEmpty().flatMap { it.streams.orEmpty() } }

public fun recordingFileWithStreams(streams: List<DvrRecordingStream>): DvrRecordingFile =
    DvrRecordingFile.create(streams = streams)

public fun recordingFrameDurations(): List<Duration?> = recordingStreamsForFake().map { it.frameDuration }

public fun channelMediaLabel(channel: Channel): String = when (channel.mediaKind) {
    ChannelMediaKind.TV -> "TV"
    ChannelMediaKind.RADIO -> "Radio"
    ChannelMediaKind.OTHER -> "Other"
    else -> "Unknown"
}

public fun typedAutorecUpdate(): AutorecRuleUpdate = AutorecRuleUpdate(
    daysOfWeekMask = setOf(DayOfWeek.MONDAY),
    startMinutesSinceMidnight = AutorecTime.At(LocalTime.of(20, 0)),
    startWindowEndMinutesSinceMidnight = AutorecTime.AnyTime,
    startExtraMinutes = 2.minutes,
    priority = DvrPriority.HIGH,
    duplicateDetection = DuplicateDetection.DIFFERENT_EPISODE_NUMBER,
    broadcastType = BroadcastType.NEW,
)

public class StagedSdkConsumer(
    context: Context,
    private val session: TvheadendSession,
    player: ExoPlayer,
    loadControl: TvheadendLoadControl? = null,
) {
    private val profileStore: TvheadendServerProfileStore = TvheadendServerProfileStore(context)
    private val coordinator: TvheadendPlaybackCoordinator =
        if (loadControl == null) createTvheadendPlaybackCoordinator(player)
        else createTvheadendPlaybackCoordinator(player, loadControl)

    public suspend fun loadServerProfile(): ServerProfileReadResult = profileStore.loadActive()

    public suspend fun loadServerProfileForEditing(id: at.bernhardberger.tvheadend.sdk.core.ServerProfileId): ServerProfileEditReadResult =
        profileStore.loadForEditing(id)

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
    ): at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult = profileStore.addAnonymous(host, port)

    public suspend fun storeAndConnect(
        store: ServerProfileStore,
        host: String,
        port: Int,
    ): SessionCommandResult {
        val added = store.addAnonymous(host, port)
        if (added !is at.bernhardberger.tvheadend.sdk.core.ServerProfileWriteResult.Applied) {
            return SessionCommandResult.NO_ACTIVE_PROFILE
        }
        store.setActive(added.id)
        val summaries = store.profiles() as? ServerProfileListResult.Available
            ?: return SessionCommandResult.NO_ACTIVE_PROFILE
        session.cache.clear(summaries.profiles.first { it.id == added.id })
        return when (val loaded = store.loadActive()) {
            is ServerProfileReadResult.Available -> {
                session.connect(loaded.profile)
            }
            ServerProfileReadResult.Missing, ServerProfileReadResult.Unavailable -> SessionCommandResult.NO_ACTIVE_PROFILE
        }
    }

    public suspend fun exerciseProfileCollection(store: ServerProfileStore, id: ServerProfileId): Boolean {
        val restoredId = ServerProfileId.from(id.value)
        val loaded = store.load(restoredId)
        val anonymous = store.updateAnonymous(id, "test.invalid", 9982, null)
        val password = store.updatePassword(id, "test.invalid", 9982, "user", "password", "Label")
        val renamed = store.updateLabel(id, "Renamed")
        val copied = (loaded as? ServerProfileReadResult.Available)?.withLabel("Copied")
        val summary = ServerProfileSummary.create(id, null, "test.invalid", 9982,
            ServerProfileAuthenticationMode.PASSWORD, username = "user", credentialsAvailable = true)
        val available = ServerProfileListResult.Available(listOf(summary), id)
        val fields = summary.id == id && summary.label == null && summary.host.isNotEmpty() &&
            summary.port > 0 && summary.authenticationMode == ServerProfileAuthenticationMode.PASSWORD &&
            summary.username == "user" && summary.credentialsAvailable && available.activeId == id
        val readable = when (val listed = store.profiles()) {
            is ServerProfileListResult.Available -> listed.profiles.all { it.credentialsAvailable }
            ServerProfileListResult.Unavailable -> false
        }
        val removed = when (store.remove(id)) {
            is ServerProfileWriteResult.Applied -> true
            ServerProfileWriteResult.NotFound -> false
            ServerProfileWriteResult.Unavailable -> false
        }
        val cleared = when (val result = store.clearAll()) {
            is ServerProfileListResult.Available -> result.profiles.isEmpty() && result.activeId == null
            ServerProfileListResult.Unavailable -> false
        }
        return fields && readable && removed && cleared && loaded is ServerProfileReadResult.Available &&
            anonymous is ServerProfileWriteResult.Applied && password is ServerProfileWriteResult.Applied &&
            renamed is ServerProfileWriteResult.Applied && copied?.label == "Copied"
    }

    public suspend fun run(): Unit = coordinator.run()

    public fun isPostSeekSample(
        result: TimeshiftContentSeekResult.Completed,
        position: TimeshiftPlaybackPosition,
    ): Boolean = result.seek != null && position is TimeshiftPlaybackPosition.Estimate && position.seek === result.seek

    public fun launchCoordinator(scope: CoroutineScope): PlaybackCoordinatorLifetime =
        coordinator.launchIn(scope)

    public suspend fun shutdownCoordinator(
        lifetime: PlaybackCoordinatorLifetime,
        drainTimeout: Duration,
    ): PlaybackShutdownResult = lifetime.shutdown(drainTimeout)

    public suspend fun joinCoordinator(lifetime: PlaybackCoordinatorLifetime): Unit = lifetime.join()

    public suspend fun withCoordinatorLifetime(
        drainTimeout: Duration,
        block: suspend CoroutineScope.(TvheadendPlaybackCoordinator) -> Unit,
    ): PlaybackShutdownResult = coordinator.withLifetime(drainTimeout, block)

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

    public fun sdkRetriesSessionAutomatically(failure: SessionFailure): Boolean =
        failure.recoveryDisposition == SessionRecoveryDisposition.AUTOMATIC_BACKOFF

    public fun sessionAllowsExplicitRetry(failure: SessionFailure): Boolean =
        failure.recoveryDisposition == SessionRecoveryDisposition.EXPLICIT_RETRY

    public fun sessionRequiresProfileChange(failure: SessionFailure): Boolean =
        failure.recoveryDisposition == SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED

    public fun sessionHasNoRecovery(failure: SessionFailure): Boolean =
        when (failure.recoveryDisposition) {
            SessionRecoveryDisposition.AUTOMATIC_BACKOFF,
            SessionRecoveryDisposition.EXPLICIT_RETRY,
            SessionRecoveryDisposition.PROFILE_CHANGE_REQUIRED,
            -> false
            else -> true
        }

    public fun synchronizationFailure(failure: SessionFailure): TvheadendFailure? =
        failure.operationFailure

    public fun isAuthenticationRejected(failure: SessionFailure): Boolean =
        failure == SessionFailure.AUTHENTICATION_REJECTED

    public fun synchronizationTimeout(): SessionFailure =
        SessionFailure.synchronizationFailed(TvheadendFailure.TIMEOUT)

    public fun registerArtwork(
        components: ComponentRegistry.Builder,
    ): ComponentRegistry.Builder = components.addTvheadendArtwork()

    public fun artworkFailure(failure: TvheadendArtworkLoadException): TvheadendFailure =
        failure.failure

    public fun cachedArtwork(observation: SessionObservation, id: ArtworkId): TvheadendArtwork? =
        TvheadendArtwork.cached(session, observation, id)

    public fun channelIconModel(observation: SessionObservation, channel: Channel): Any? =
        when (val icon = channel.icon) {
            is ChannelIcon.ImageCache -> observation.currentSession
                ?.let { TvheadendArtwork.create(session, it, icon.artworkId) }
                ?: TvheadendArtwork.cached(session, observation, icon.artworkId)
            is ChannelIcon.RemoteUrl -> icon.url
            null -> null
        }

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

    public suspend fun playLive(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
    ): LivePlaybackTargetResult = coordinator.setLiveTarget(session, currentSession, channelId)

    public suspend fun resetResume(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
    ): DvrProgressResult =
        session.dvrRepository.resetResume(currentSession, recordingId)

    public suspend fun updateWatchState(
        currentSession: CurrentSessionObservation,
        recordingId: DvrEntryId,
        watched: Boolean,
    ): DvrProgressResult =
        session.dvrRepository.updateWatchState(currentSession, recordingId, watched)

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

    public suspend fun acquireGuideCoverage(
        currentSession: CurrentSessionObservation,
        channelIds: List<ChannelId>,
        through: Instant,
    ): EpgCoverageBatchResult = session.epgRepository.acquireCoverageBatch(
        currentSession,
        channelIds,
        through,
    )

    public fun acquiredGuideChannelIds(result: EpgCoverageBatchResult): List<ChannelId> =
        result.settlements.map(EpgCoverageBatchSettlement::channelId)

    public fun epgSearchProvenance(
        result: EpgSearchResult,
    ): CurrentSessionObservation? = (result as? EpgSearchResult.Available)?.originatingSession

    public suspend fun playLive(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        streamProfileId: StreamProfileId?,
        timeshiftPeriod: Duration,
    ): LivePlaybackTargetResult = coordinator.setLiveTarget(
        session,
        currentSession,
        channelId,
        LivePlaybackOptions(streamProfileId, timeshiftPeriod),
    )

    public suspend fun withLivePlayback(
        channelId: ChannelId,
        options: LivePlaybackOptions,
        drainTimeout: Duration,
        observe: suspend (LivePlaybackObservation) -> Unit,
        onTargetResult: (LivePlaybackTargetResult) -> Unit,
    ): PlaybackShutdownResult = coordinator.withLifetime(drainTimeout) { activeCoordinator ->
        val currentSession = session.observation.value.currentSession ?: return@withLifetime
        launch { activeCoordinator.livePlaybackObservation.collect(observe) }
        onTargetResult(activeCoordinator.setLiveTarget(session, currentSession, channelId, options))
    }

    public suspend fun resume(recordingId: DvrEntryId): PlaybackTargetResult {
        val currentSession = observation.value.currentSession ?: return PlaybackTargetResult.NOT_READY
        return usePlaybackBinding(
            session.bindRecordingPlayback(currentSession, recordingId),
        ) { binding ->
            coordinator.setRecordingTarget(binding, RecordingPlaybackStart.RESUME)
        }
    }

    public suspend fun recoverRecording(
        recordingId: DvrEntryId,
        position: Duration,
        previousDuration: Duration? = null,
    ): PlaybackTargetResult {
        val currentSession = observation.value.currentSession ?: return PlaybackTargetResult.NOT_READY
        return usePlaybackBinding(session.bindRecordingPlayback(currentSession, recordingId)) { binding ->
            coordinator.recoverRecordingTarget(binding, position, previousDuration, RecordingPlaybackStart.RESUME)
        }
    }

    public val pendingRecordingRecoveryPosition: StateFlow<Duration?>
        get() = coordinator.pendingRecordingRecoveryPosition

    public val declaredVideoFrameDuration: StateFlow<Duration?>
        get() = coordinator.declaredVideoFrameDuration

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

    public val livePlaybackObservation: StateFlow<LivePlaybackObservation>
        get() = coordinator.livePlaybackObservation

    public fun currentLiveServerStopped(): Boolean =
        (livePlaybackObservation.value as? LivePlaybackObservation.Active)?.serverStopped == true

    public fun isLiveMediaItem(item: MediaItem): Boolean = item.isTvheadendLive()

    public fun currentLiveServiceName(): String? = liveDiagnostics.value?.source?.serviceName

    public fun currentLiveSignalPercent(): Double? =
        liveDiagnostics.value?.frontend?.relativeSignalPercent

    public fun currentLiveQueuePackets(): Long? = liveDiagnostics.value?.queue?.packetCount

    public fun currentLiveQueueErrors(): Long? = liveDiagnostics.value?.queue?.errorCount

    public fun currentLiveQueueSpan(): Duration? = liveDiagnostics.value?.queue?.mediaSpan

    public suspend fun seekTimeshiftBy(offset: Duration): TimeshiftContentSeekResult =
        coordinator.seekTimeshiftBy(offset)

    public fun resolveTimeshiftSelection(
        anchor: TimeshiftContentTarget,
        previous: at.bernhardberger.tvheadend.sdk.media3.TimeshiftSeekSelection?,
        delta: Duration,
    ): at.bernhardberger.tvheadend.sdk.media3.TimeshiftSeekSelection? =
        (timeshiftState.value as? LiveTimeshiftState.Available)?.timeline?.resolveSelection(anchor, previous, delta)

    public suspend fun seekTimeshift(selection: at.bernhardberger.tvheadend.sdk.media3.TimeshiftSeekSelection): TimeshiftContentSeekResult =
        coordinator.seekTimeshift(selection)

    public fun selectTimeshiftContent(position: Duration): TimeshiftContentTarget? =
        (timeshiftState.value as? LiveTimeshiftState.Available)?.timeline?.select(position)

    public suspend fun seekTimeshift(target: TimeshiftContentTarget): TimeshiftContentSeekResult =
        coordinator.seekTimeshift(target)

    public suspend fun timeshiftPlaybackPosition(): TimeshiftPlaybackPosition =
        coordinator.timeshiftPlaybackPosition()

    public suspend fun returnToLive(): TimeshiftCommandResult = coordinator.returnToLive()

    public suspend fun pauseTimeshift(): TimeshiftCommandResult = coordinator.pauseTimeshift()

    public suspend fun resumeTimeshift(): TimeshiftCommandResult = coordinator.resumeTimeshift()

    public fun targetStarted(result: PlaybackTargetResult): Boolean = result.isStarted

    public fun isGrowingRecording(admission: RecordingPlaybackAdmission): Boolean =
        admission is RecordingPlaybackAdmission.Growing

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

public fun stagedTestingFake(
    readyObservation: SessionObservation,
    retiredObservation: SessionObservation,
): FakeTvheadendSession = FakeTvheadendSession(readyObservation).apply {
    publish(readyObservation)
    replaceGeneration(readyObservation)
    captureCurrentSession()
    scriptConnect(SessionCommandResult.STARTED)
    scriptRetry(SessionCommandResult.NO_ACTIVE_PROFILE)
    scriptStreamProfilesFailure(StreamProfilesResult.Failed(TvheadendFailure.NOT_READY))
    scriptStreamProfiles(emptyList())
    epgRepository.scriptSearchFailure(EpgSearchResult.Failed(TvheadendFailure.NOT_SUPPORTED))
    epgRepository.scriptSearch(emptyList())
    epgRepository.scriptCoverage(
        at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult.Ineligible,
    )
    epgRepository.scriptCoverage(
        at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult.CapacityExhausted,
    )
    epgRepository.scriptCoverageBatch(
        EpgCoverageBatchResult.create(
            listOf(
                EpgCoverageBatchSettlement.Rejected(ChannelId(1)),
                EpgCoverageBatchSettlement.CapacityExhausted(ChannelId(2)),
            ),
        ),
    )
    dvrRepository.scriptScheduleEntry(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptUpdateEntry(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptStopEntry(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptCancelEntry(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptDeleteEntry(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptCreateAutorecRule(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptUpdateAutorecRule(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptDeleteAutorecRule(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptCreateTimerecRule(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptUpdateTimerecRule(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    dvrRepository.scriptDeleteTimerecRule(DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false))
    artwork.scriptLoad(
        at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult.Unavailable(TvheadendFailure.NOT_SUPPORTED),
    )
    scriptLivePlaybackFailure(PlaybackBindingResult.TargetUnavailable)
    scriptRecordingPlaybackFailure(PlaybackBindingResult.TargetUnavailable)
    scriptLivePlaybackSuccess()
    scriptRecordingPlaybackSuccess()
    calls
    retire(retiredObservation)
}

public fun stagedProfileStoreFake(): FakeServerProfileStore = FakeServerProfileStore().apply {
    scriptProfile(ServerProfileReadResult.anonymous(at.bernhardberger.tvheadend.sdk.core.ServerProfileId.generate(), "test.invalid"))
    scriptMutationUnavailable()
    scriptMutationSuccess()
    calls
}
