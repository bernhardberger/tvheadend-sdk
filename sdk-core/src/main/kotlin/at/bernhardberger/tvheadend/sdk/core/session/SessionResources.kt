@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.CommandBackedDvrRepository
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointCommands
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrMutationCommands
import at.bernhardberger.tvheadend.sdk.core.DvrProgressCommands
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EpgSearchResult
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionObservationStore
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.ChannelTagReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.DvrReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryFence
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.ReducedEpgEvent
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

internal interface SessionMetadata {
    public val observation: StateFlow<SessionObservation>

    public val channelsAndTags: StateFlow<ChannelRepositoryState>

    public val epgRepository: EpgRepository

    public val dvrRepository: DvrRepository

    public fun resolveGeneration(currentSession: CurrentSessionObservation): GatewayGeneration?

    public fun currentObservation(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
    ): SessionObservation?

    public fun bindPlaybackRecording(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): PlaybackRecordingLookup

    public fun currentPlaybackRecording(
        target: PlaybackRecordingTarget,
    ): PlaybackRecordingLookup

    public suspend fun reportProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = DvrProgressResult.NotReady

    public suspend fun reportProgress(
        target: PlaybackRecordingTarget,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = DvrProgressResult.NotReady

    public suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = DvrProgressResult.NotReady

    public suspend fun getCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrCutpointsResult = DvrCutpointsResult.NotReady

    public suspend fun getCutpoints(
        target: PlaybackRecordingTarget,
    ): DvrCutpointsResult = DvrCutpointsResult.NotReady

    public fun publishSessionState(
        state: SessionState,
        progressCapability: RecordingProgressCapability,
        generation: GatewayGeneration?,
    )

    public fun resetWorkingStateRetainingPublishedSnapshot()

    public fun clearAllState()

    public fun bindGeneration(generation: GatewayGeneration)

    public fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?)

    public fun applyDvrMutationProof(
        generation: GatewayGeneration,
        allowed: Boolean,
    ): SessionCapabilitiesSnapshot?

    public fun applyDvrConfigurations(
        generation: GatewayGeneration,
        result: GatewayResult<List<DvrConfiguration>>,
    ): SessionCapabilitiesSnapshot?

    public fun applyDvrDiskSpace(
        generation: GatewayGeneration,
        result: GatewayResult<DvrDiskSpace>,
    )

    public fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts)

    public fun acceptMetadata(event: MetadataEvent)

    public suspend fun awaitMetadataCurrent(generation: GatewayGeneration)

    public fun isKnownChannel(
        generation: GatewayGeneration,
        channelId: ChannelId,
    ): Boolean?

    public fun currentDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): CurrentDvrEntryLookup

    public fun bindEpgCoverageRequester(
        generation: GatewayGeneration,
        requester: EpgCoverageRequester,
    ): Boolean

    public fun clearEpgCoverageRequester(
        generation: GatewayGeneration,
        requester: EpgCoverageRequester,
    )

    public fun recordSuccessfulEpgQuery(
        generation: GatewayGeneration,
        channelId: ChannelId,
        queriedTo: Instant,
    )

    public fun currentEpgSnapshot(generation: GatewayGeneration): EpgSnapshot?

    public fun beginEpgQuery(
        generation: GatewayGeneration,
        channelId: ChannelId,
    ): EpgQueryFence?

    public fun abandonEpgQuery(generation: GatewayGeneration, query: EpgQueryFence)

    public fun applySuccessfulEpgQuery(
        generation: GatewayGeneration,
        query: EpgQueryFence,
        queriedTo: Instant,
        events: List<GatewayEpgQueryEvent>,
    )

    public fun retainEpgEvents(generation: GatewayGeneration, from: Instant, to: Instant)

    public fun capabilitySnapshot(generation: GatewayGeneration): SessionCapabilitiesSnapshot

    public fun capabilities(generation: GatewayGeneration): ServerCapabilities =
        capabilitySnapshot(generation).capabilities
}

internal class SessionCapabilitiesSnapshot(
    internal val generation: GatewayGeneration,
    internal val revision: Long,
    internal val capabilities: ServerCapabilities,
)

internal sealed interface CurrentDvrEntryLookup {
    public data object GenerationLost : CurrentDvrEntryLookup

    public data object NotCurrent : CurrentDvrEntryLookup

    public class Current(
        internal val state: DvrRepositoryState.Current,
        internal val entry: DvrEntry?,
        internal val matchCount: Int,
        internal val incarnation: DvrEntryIncarnation?,
    ) : CurrentDvrEntryLookup
}

internal class DvrEntryIncarnation(initialEntry: DvrEntry? = null) {
    private var completedIdentity = initialEntry?.let(CompletedPlaybackIdentity::create)

    internal fun preservesUpdate(previous: DvrEntry, next: DvrEntry): Boolean {
        if (previous.state == DvrEntryState.COMPLETED && next.state == DvrEntryState.COMPLETED) {
            val established = completedIdentity
                ?: CompletedPlaybackIdentity.create(previous)
                ?: return false
            val candidate = CompletedPlaybackIdentity.create(next) ?: return false
            if (!established.isCompatibleWith(candidate)) return false
            completedIdentity = established.mergedWith(candidate)
            return true
        }

        val preserved = previous.preservesGrowingContinuity(next)
        if (preserved) completedIdentity = CompletedPlaybackIdentity.create(next)
        return preserved
    }

    override fun toString(): String = "DvrEntryIncarnation(<redacted>)"
}

internal class PlaybackRecordingTarget(
    internal val generation: GatewayGeneration,
    internal val currentSession: CurrentSessionObservation,
    internal val recordingId: DvrEntryId,
    internal val incarnation: DvrEntryIncarnation,
) {
    override fun toString(): String = "PlaybackRecordingTarget(<redacted>)"
}

internal sealed interface PlaybackRecordingLookup {
    public data object ObservationExpired : PlaybackRecordingLookup

    public data object TargetUnavailable : PlaybackRecordingLookup

    public class Current(
        internal val observation: SessionObservation,
        internal val state: DvrRepositoryState.Current,
        internal val entry: DvrEntry,
        internal val target: PlaybackRecordingTarget,
    ) : PlaybackRecordingLookup
}

internal fun interface EpgCoverageRequester {
    public suspend fun acquireCoverage(
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageAcquisitionResult
}

internal fun interface EpgSearchCommands {
    public suspend fun search(
        generation: GatewayGeneration,
        request: EpgSearchRequest,
    ): GatewayResult<List<GatewayEpgQueryEvent>>

    public data object None : EpgSearchCommands {
        override suspend fun search(
            generation: GatewayGeneration,
            request: EpgSearchRequest,
        ): GatewayResult<List<GatewayEpgQueryEvent>> = GatewayResult.NotSupported
    }
}

private class EpgSearchGenerationFence(
    internal val generation: GatewayGeneration,
    internal val bindRevision: Long,
)

internal class PhaseOneSessionMetadata(
    mutationCommands: DvrMutationCommands = DvrMutationCommands.None,
    private val searchCommands: EpgSearchCommands = EpgSearchCommands.None,
    private val progressCommands: DvrProgressCommands = DvrProgressCommands.None,
    private val cutpointCommands: DvrCutpointCommands = DvrCutpointCommands.None,
    private val onDvrMetadataAccepted: (MetadataEvent) -> Unit = {},
    private val observationStore: SessionObservationStore = SessionObservationStore(),
    epgCoveragePolicy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
) : SessionMetadata {
    private val lock = Any()
    private val reducer = ChannelTagReducer()
    private val epgReducer = EpgReducer(epgCoveragePolicy.maximumRetainedEvents)
    private val dvrReducer = DvrReducer()
    private val mutableChannelsAndTags = MutableStateFlow<ChannelRepositoryState>(
        ChannelRepositoryState.Empty,
    )
    private val mutableEpg = MutableStateFlow<EpgRepositoryState>(EpgRepositoryState.Empty)
    private val mutableDvr = MutableStateFlow<DvrRepositoryState>(DvrRepositoryState.Empty)
    private val mutableConfigurations = MutableStateFlow<DvrConfigurationsState>(
        DvrConfigurationsState.Unknown,
    )
    private val mutableDiskSpace = MutableStateFlow<DvrDiskSpaceState>(DvrDiskSpaceState.Unknown)
    private val stateBackedEpgRepository = object : EpgRepository {
        override suspend fun search(
            currentSession: CurrentSessionObservation,
            request: EpgSearchRequest,
        ): EpgSearchResult {
            currentCoroutineContext().ensureActive()
            val generationFence = resolveSearchGeneration(currentSession)
                ?: return EpgSearchResult.ObservationExpired
            val result = searchCommands.search(generationFence.generation, request)
            currentCoroutineContext().ensureActive()
            val mappedResult = result.toEpgSearchResult(currentSession)
            val connectionChanged = hasReplacementGeneration(generationFence)
            currentCoroutineContext().ensureActive()
            return if (connectionChanged) EpgSearchResult.ConnectionChanged else mappedResult
        }

        override suspend fun acquireCoverage(
            currentSession: CurrentSessionObservation,
            channelId: ChannelId,
            through: Instant,
        ): EpgCoverageAcquisitionResult {
            val expectedGeneration = resolveGeneration(currentSession)
                ?: return EpgCoverageAcquisitionResult.ObservationExpired
            return acquireEpgCoverage(expectedGeneration, currentSession, channelId, through)
        }
    }
    private val stateBackedDvrRepository = object : CommandBackedDvrRepository(
        mutations = mutationCommands,
        resolveGeneration = ::resolveGeneration,
    ) {}
    private var generation: GatewayGeneration? = null
    private var generationBindRevision = 0L
    private var initialSync = CompletableDeferred<Unit>()
    private var publishedCatalog: ChannelCatalog? = null
    private var publishedEpgSnapshot: EpgSnapshot? = null
    private var publishedDvrSnapshot: DvrSnapshot? = null
    private var publishedConfigurations: List<DvrConfiguration>? = null
    private var publishedDiskSpace: DvrDiskSpace? = null
    private var synchronizedCurrent = false
    private var serverFacts: GatewayServerFacts? = null
    private var dvrAccess: CapabilityAccess = CapabilityAccess.UNKNOWN
    private var capabilityRevision = 0L
    private var epgCoverageRequester: EpgCoverageRequester? = null
    private val dvrEntryIncarnations = HashMap<DvrEntryId, DvrEntryIncarnation>()

    override val observation: StateFlow<SessionObservation> = observationStore.observation
    override val channelsAndTags: StateFlow<ChannelRepositoryState> =
        mutableChannelsAndTags.asStateFlow()
    override val epgRepository: EpgRepository = stateBackedEpgRepository
    override val dvrRepository: DvrRepository = stateBackedDvrRepository

    override fun resolveGeneration(
        currentSession: CurrentSessionObservation,
    ): GatewayGeneration? = synchronized(lock) {
        val expectedGeneration = generation ?: return@synchronized null
        observationStore.resolve(currentSession, expectedGeneration) as? GatewayGeneration
    }

    private fun resolveSearchGeneration(
        currentSession: CurrentSessionObservation,
    ): EpgSearchGenerationFence? = synchronized(lock) {
        val expectedGeneration = generation ?: return@synchronized null
        val resolvedGeneration = observationStore.resolve(currentSession, expectedGeneration)
            as? GatewayGeneration ?: return@synchronized null
        EpgSearchGenerationFence(resolvedGeneration, generationBindRevision)
    }

    override fun currentObservation(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
    ): SessionObservation? = synchronized(lock) {
        if (this.generation !== generation) return@synchronized null
        observationStore.currentObservation(currentSession, generation)
    }

    private fun hasReplacementGeneration(generationFence: EpgSearchGenerationFence): Boolean =
        synchronized(lock) {
            generationBindRevision != generationFence.bindRevision
        }

    override fun bindPlaybackRecording(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
    ): PlaybackRecordingLookup = synchronized(lock) {
        playbackRecordingLocked(generation, currentSession, id, expectedTarget = null)
    }

    override fun currentPlaybackRecording(
        target: PlaybackRecordingTarget,
    ): PlaybackRecordingLookup = synchronized(lock) {
        playbackRecordingLocked(
            target.generation,
            target.currentSession,
            target.recordingId,
            expectedTarget = target,
        )
    }

    override suspend fun reportProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = progressCommands.reportProgress(generation, id, progress)

    override suspend fun reportProgress(
        target: PlaybackRecordingTarget,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = progressCommands.reportProgress(
        generation = target.generation,
        id = target.recordingId,
        progress = progress,
        targetIsCurrent = { currentPlaybackRecording(target) is PlaybackRecordingLookup.Current },
    )

    override suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = progressCommands.reportProgress(lease, progress)

    override suspend fun getCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrCutpointsResult = cutpointCommands.getCutpoints(generation, id)

    override suspend fun getCutpoints(
        target: PlaybackRecordingTarget,
    ): DvrCutpointsResult = cutpointCommands.getCutpoints(
        generation = target.generation,
        id = target.recordingId,
        targetIsCurrent = { currentPlaybackRecording(target) is PlaybackRecordingLookup.Current },
    )

    override fun publishSessionState(
        state: SessionState,
        progressCapability: RecordingProgressCapability,
        generation: GatewayGeneration?,
    ) {
        observationStore.publishSessionState(state, progressCapability, generation)
    }

    override fun resetWorkingStateRetainingPublishedSnapshot() {
        resetState(retainPublishedCatalog = true)
    }

    override fun clearAllState() {
        resetState(retainPublishedCatalog = false)
    }

    private fun resetState(retainPublishedCatalog: Boolean) {
        val retiredFence = synchronized(lock) {
            generation = null
            val previousFence = initialSync
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            serverFacts = null
            dvrAccess = CapabilityAccess.UNKNOWN
            capabilityRevision += 1
            epgCoverageRequester = null
            reducer.clear()
            epgReducer.clear()
            dvrReducer.clear()
            dvrEntryIncarnations.clear()
            if (!retainPublishedCatalog) {
                publishedCatalog = null
                publishedEpgSnapshot = null
                publishedDvrSnapshot = null
                publishedConfigurations = null
                publishedDiskSpace = null
            }
            mutableChannelsAndTags.value = publishedCatalog?.let { catalog ->
                ChannelRepositoryState.Stale(catalog)
            } ?: ChannelRepositoryState.Empty
            mutableEpg.value = publishedEpgSnapshot?.let(EpgRepositoryState::Stale)
                ?: EpgRepositoryState.Empty
            mutableDvr.value = publishedDvrSnapshot?.let(DvrRepositoryState::Stale)
                ?: DvrRepositoryState.Empty
            mutableConfigurations.value = publishedConfigurationsState()
            mutableDiskSpace.value = publishedDiskSpace?.let(DvrDiskSpaceState::Stale)
                ?: DvrDiskSpaceState.Unknown
            publishMetadataObservation()
            previousFence
        }
        retiredFence.cancel(CancellationException("Session generation is no longer current"))
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        val retiredFence = synchronized(lock) {
            val previousFence = initialSync
            generationBindRevision += 1
            this.generation = generation
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            serverFacts = null
            dvrAccess = CapabilityAccess.UNKNOWN
            capabilityRevision += 1
            epgCoverageRequester = null
            reducer.clear()
            epgReducer.clear()
            dvrReducer.clear()
            dvrEntryIncarnations.clear()
            mutableChannelsAndTags.value = ChannelRepositoryState.Synchronizing(publishedCatalog)
            mutableEpg.value = EpgRepositoryState.Synchronizing(publishedEpgSnapshot)
            mutableDvr.value = DvrRepositoryState.Synchronizing(publishedDvrSnapshot)
            mutableConfigurations.value = DvrConfigurationsState.Synchronizing.create(publishedConfigurations)
            mutableDiskSpace.value = DvrDiskSpaceState.Synchronizing(publishedDiskSpace)
            publishMetadataObservation()
            previousFence
        }
        retiredFence.cancel(CancellationException("Session generation is no longer current"))
    }

    override fun applyDvrAccess(generation: GatewayGeneration, access: Boolean?) {
        synchronized(lock) {
            if (this.generation === generation) {
                when (access) {
                    true -> setDvrAccess(CapabilityAccess.ALLOWED)
                    false -> setDvrAccess(CapabilityAccess.DENIED)
                    null -> Unit
                }
            }
        }
    }

    override fun applyDvrMutationProof(
        generation: GatewayGeneration,
        allowed: Boolean,
    ): SessionCapabilitiesSnapshot? = synchronized(lock) {
        if (this.generation !== generation) {
            null
        } else {
            val proof = if (allowed) CapabilityAccess.ALLOWED else CapabilityAccess.DENIED
            if (dvrAccess == proof) {
                null
            } else {
                setDvrAccess(proof)
                capabilitySnapshotLocked(generation)
            }
        }
    }

    override fun applyDvrConfigurations(
        generation: GatewayGeneration,
        result: GatewayResult<List<DvrConfiguration>>,
    ): SessionCapabilitiesSnapshot? = synchronized(lock) {
            if (this.generation !== generation) {
                return@synchronized null
            }
            val previousRevision = capabilityRevision
            when (result) {
                is GatewayResult.Ok -> {
                    setDvrAccess(CapabilityAccess.ALLOWED)
                    val configurations = DvrConfigurationsState.Current.create(result.value)
                    publishedConfigurations = configurations.configurations
                    mutableConfigurations.value = configurations
                }
                GatewayResult.AccessDenied -> {
                    setDvrAccess(CapabilityAccess.DENIED)
                    publishedConfigurations = null
                    mutableConfigurations.value = DvrConfigurationsState.Denied
                }
                GatewayResult.ServerRejected,
                GatewayResult.ConnectionLimit,
                GatewayResult.Timeout,
                GatewayResult.TransportUnavailable,
                GatewayResult.NotSupported,
                -> mutableConfigurations.value = publishedConfigurationsState()
            }
            publishMetadataObservation()
            if (synchronizedCurrent && capabilityRevision != previousRevision) {
                capabilitySnapshotLocked(generation)
            } else {
                null
            }
        }

    override fun applyDvrDiskSpace(
        generation: GatewayGeneration,
        result: GatewayResult<DvrDiskSpace>,
    ) {
        synchronized(lock) {
            if (this.generation !== generation) {
                return
            }
            when (result) {
                is GatewayResult.Ok -> {
                    publishedDiskSpace = result.value
                    mutableDiskSpace.value = DvrDiskSpaceState.Current(result.value)
                }
                GatewayResult.AccessDenied,
                GatewayResult.ServerRejected,
                GatewayResult.ConnectionLimit,
                GatewayResult.Timeout,
                GatewayResult.TransportUnavailable,
                GatewayResult.NotSupported,
                -> mutableDiskSpace.value = publishedDiskSpace?.let(DvrDiskSpaceState::Stale)
                    ?: DvrDiskSpaceState.Unknown
            }
            publishMetadataObservation()
        }
    }

    override fun publishServerFacts(generation: GatewayGeneration, facts: GatewayServerFacts) {
        synchronized(lock) {
            if (this.generation === generation) {
                serverFacts = facts
                capabilityRevision += 1
            }
        }
    }

    override fun acceptMetadata(event: MetadataEvent) {
        var acceptedDvrEvent: MetadataEvent? = null
        val completedFence = synchronized(lock) {
            if (event.generation !== generation) {
                return@synchronized null
            }
            when (event) {
                is MetadataEvent.InitialSyncCompleted -> {
                    if (synchronizedCurrent) {
                        null
                    } else {
                        reducer.reconcileReferences()
                        val channelSnapshot = reducer.snapshot()
                        epgReducer.reconcileChannels(channelSnapshot.channels.map { channel -> channel.id })
                        val epgSnapshot = epgReducer.snapshot()
                        val dvrSnapshot = dvrReducer.snapshot()
                        synchronizedCurrent = true
                        publishCurrent(channelSnapshot, epgSnapshot, dvrSnapshot)
                        initialSync
                    }
                }
                is MetadataEvent.ChannelAdded,
                is MetadataEvent.ChannelUpdated,
                is MetadataEvent.ChannelDeleted,
                is MetadataEvent.TagAdded,
                is MetadataEvent.TagUpdated,
                is MetadataEvent.TagDeleted,
                is MetadataEvent.EventAdded,
                is MetadataEvent.EventUpdated,
                is MetadataEvent.EventDeleted,
                is MetadataEvent.DvrEntryAdded,
                is MetadataEvent.DvrEntryUpdated,
                is MetadataEvent.DvrEntryDeleted,
                is MetadataEvent.AutorecRuleAdded,
                is MetadataEvent.AutorecRuleUpdated,
                is MetadataEvent.AutorecRuleDeleted,
                is MetadataEvent.TimerecRuleAdded,
                is MetadataEvent.TimerecRuleUpdated,
                is MetadataEvent.TimerecRuleDeleted,
                -> {
                    val updatedDvrEntry = (event as? MetadataEvent.DvrEntryUpdated)?.entry?.id
                    val previousDvrEntry = updatedDvrEntry?.let { id ->
                        (mutableDvr.value as? DvrRepositoryState.Current)
                            ?.snapshot
                            ?.entries
                            ?.singleOrNull { entry -> entry.id == id }
                    }
                    reducer.accept(event)
                    epgReducer.accept(event)
                    val dvrEventAccepted = dvrReducer.accept(event)
                    val dvrSnapshot = if (synchronizedCurrent) dvrReducer.snapshot() else null
                    if (dvrEventAccepted && synchronizedCurrent) {
                        when (event) {
                            is MetadataEvent.DvrEntryAdded -> {
                                val id = event.entry.id
                                val current = dvrSnapshot
                                    ?.entries
                                    ?.singleOrNull { entry -> entry.id == id }
                                dvrEntryIncarnations[id] = DvrEntryIncarnation(current)
                            }
                            is MetadataEvent.DvrEntryDeleted -> {
                                dvrEntryIncarnations[event.entryId] = DvrEntryIncarnation()
                            }
                            is MetadataEvent.DvrEntryUpdated -> {
                                val id = event.entry.id
                                val previous = previousDvrEntry
                                val current = dvrSnapshot
                                    ?.entries
                                    ?.singleOrNull { entry -> entry.id == id }
                                val preserved = previous != null && current != null &&
                                    dvrEntryIncarnations
                                        .getOrPut(id) { DvrEntryIncarnation(previous) }
                                        .preservesUpdate(previous, current)
                                if (!preserved) {
                                    dvrEntryIncarnations[id] = DvrEntryIncarnation(current)
                                }
                            }
                            else -> Unit
                        }
                    }
                    if (synchronizedCurrent) {
                        publishCurrent(reducer.snapshot(), epgReducer.snapshot(), checkNotNull(dvrSnapshot))
                    }
                    if (dvrEventAccepted && event.isDvrMutationConfirmation()) {
                        acceptedDvrEvent = event
                    }
                    null
                }
            }
        }
        completedFence?.complete(Unit)
        acceptedDvrEvent?.let(onDvrMetadataAccepted)
    }

    override suspend fun awaitMetadataCurrent(generation: GatewayGeneration) {
        val fence = synchronized(lock) {
            check(this.generation === generation) { "Session generation is not current" }
            initialSync
        }
        fence.await()
        synchronized(lock) {
            check(
                this.generation === generation &&
                    synchronizedCurrent &&
                    mutableChannelsAndTags.value is ChannelRepositoryState.Current &&
                    mutableEpg.value is EpgRepositoryState.Current &&
                    mutableDvr.value is DvrRepositoryState.Current,
            ) { "Session generation is not current" }
        }
    }

    override fun isKnownChannel(
        generation: GatewayGeneration,
        channelId: ChannelId,
    ): Boolean? = synchronized(lock) {
        if (this.generation !== generation) {
            null
        } else {
            val catalog = when (val state = mutableChannelsAndTags.value) {
                ChannelRepositoryState.Empty -> null
                is ChannelRepositoryState.Synchronizing -> state.staleCatalog
                is ChannelRepositoryState.Current -> state.catalog
                is ChannelRepositoryState.Stale -> state.catalog
            }
            catalog?.channels?.any { channel -> channel.id == channelId }
        }
    }

    override fun currentDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): CurrentDvrEntryLookup = synchronized(lock) {
        if (this.generation !== generation) {
            CurrentDvrEntryLookup.GenerationLost
        } else {
            val current = mutableDvr.value as? DvrRepositoryState.Current
                ?: return@synchronized CurrentDvrEntryLookup.NotCurrent
            var match: DvrEntry? = null
            var matchCount = 0
            current.snapshot.entries.forEach { entry ->
                if (entry.id == id) {
                    match = entry
                    matchCount += 1
                }
            }
            val incarnation = if (matchCount == 1) {
                dvrEntryIncarnations.getOrPut(id) { DvrEntryIncarnation(match) }
            } else {
                null
            }
            CurrentDvrEntryLookup.Current(current, match, matchCount, incarnation)
        }
    }

    private fun playbackRecordingLocked(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
        id: DvrEntryId,
        expectedTarget: PlaybackRecordingTarget?,
    ): PlaybackRecordingLookup {
        if (this.generation !== generation) return PlaybackRecordingLookup.ObservationExpired
        val observation = observationStore.currentObservation(currentSession, generation)
            ?: return PlaybackRecordingLookup.ObservationExpired
        val current = mutableDvr.value as? DvrRepositoryState.Current
            ?: return PlaybackRecordingLookup.TargetUnavailable
        val matches = current.snapshot.entries.filter { entry -> entry.id == id }
        val entry = matches.singleOrNull()
            ?: return PlaybackRecordingLookup.TargetUnavailable
        val incarnation = dvrEntryIncarnations.getOrPut(id) { DvrEntryIncarnation(entry) }
        if (expectedTarget != null && expectedTarget.incarnation !== incarnation) {
            return PlaybackRecordingLookup.TargetUnavailable
        }
        return PlaybackRecordingLookup.Current(
            observation = observation,
            state = current,
            entry = entry,
            target = expectedTarget ?: PlaybackRecordingTarget(
                generation = generation,
                currentSession = currentSession,
                recordingId = id,
                incarnation = incarnation,
            ),
        )
    }

    override fun bindEpgCoverageRequester(
        generation: GatewayGeneration,
        requester: EpgCoverageRequester,
    ): Boolean = synchronized(lock) {
        if (this.generation !== generation || !synchronizedCurrent || epgCoverageRequester != null) {
            false
        } else {
            epgCoverageRequester = requester
            true
        }
    }

    override fun clearEpgCoverageRequester(
        generation: GatewayGeneration,
        requester: EpgCoverageRequester,
    ) {
        synchronized(lock) {
            if (this.generation === generation && epgCoverageRequester === requester) {
                epgCoverageRequester = null
            }
        }
    }

    override fun recordSuccessfulEpgQuery(
        generation: GatewayGeneration,
        channelId: ChannelId,
        queriedTo: Instant,
    ) {
        synchronized(lock) {
            if (this.generation === generation) {
                epgReducer.recordSuccessfulQuery(channelId, queriedTo)
                if (synchronizedCurrent) publishCurrentEpg(epgReducer.snapshot())
            }
        }
    }

    override fun currentEpgSnapshot(generation: GatewayGeneration): EpgSnapshot? = synchronized(lock) {
        if (this.generation !== generation || !synchronizedCurrent) {
            null
        } else {
            (mutableEpg.value as? EpgRepositoryState.Current)?.snapshot
        }
    }

    override fun beginEpgQuery(
        generation: GatewayGeneration,
        channelId: ChannelId,
    ): EpgQueryFence? = synchronized(lock) {
        if (this.generation !== generation || !synchronizedCurrent) {
            null
        } else {
            epgReducer.beginQuery(channelId)
        }
    }

    override fun abandonEpgQuery(generation: GatewayGeneration, query: EpgQueryFence) {
        synchronized(lock) {
            if (this.generation === generation) epgReducer.abandonQuery(query)
        }
    }

    override fun applySuccessfulEpgQuery(
        generation: GatewayGeneration,
        query: EpgQueryFence,
        queriedTo: Instant,
        events: List<GatewayEpgQueryEvent>,
    ) {
        synchronized(lock) {
            if (this.generation === generation && synchronizedCurrent) {
                if (epgReducer.acceptSuccessfulQuery(query, queriedTo, events)) {
                    publishCurrentEpg(epgReducer.snapshot())
                }
            }
        }
    }

    override fun retainEpgEvents(generation: GatewayGeneration, from: Instant, to: Instant) {
        synchronized(lock) {
            if (this.generation === generation) {
                epgReducer.retainOverlapping(from, to)
                if (synchronizedCurrent) publishCurrentEpg(epgReducer.snapshot())
            }
        }
    }

    override fun capabilitySnapshot(
        generation: GatewayGeneration,
    ): SessionCapabilitiesSnapshot = synchronized(lock) {
        capabilitySnapshotLocked(generation)
    }

    private fun capabilitySnapshotLocked(
        generation: GatewayGeneration,
    ): SessionCapabilitiesSnapshot {
        check(this.generation === generation && synchronizedCurrent) {
            "Session generation is not current"
        }
        val facts = serverFacts
        return SessionCapabilitiesSnapshot(
            generation = generation,
            revision = capabilityRevision,
            capabilities = ServerCapabilities.create(
                streaming = facts?.streaming.toCapabilityAccess(),
                dvrWrite = dvrAccess,
                protocolDvr = facts?.dvr.toCapabilityAccess(),
                failedDvr = facts?.failedDvr.toCapabilityAccess(),
                admin = facts?.admin.toCapabilityAccess(),
                anonymous = facts?.anonymous.toCapabilityAccess(),
                apiVersion = facts?.apiVersion,
                allLimit = facts?.limitAll,
                dvrLimit = facts?.limitDvr,
                streamingLimit = facts?.limitStreaming,
                uiLevel = facts?.uiLevel,
                features = facts?.serverCapabilities,
                serverName = facts?.serverName,
                serverVersion = facts?.serverVersion,
                webRoot = facts?.webRoot,
                language = facts?.language,
                uiLanguage = facts?.uiLanguage,
            ),
        )
    }

    private fun setDvrAccess(access: CapabilityAccess) {
        if (dvrAccess != access) {
            dvrAccess = access
            capabilityRevision += 1
        }
    }

    private fun publishCurrent(
        catalog: ChannelCatalog,
        epgSnapshot: EpgSnapshot,
        dvrSnapshot: DvrSnapshot,
    ) {
        mutableChannelsAndTags.value = ChannelRepositoryState.Current(catalog)
        publishedCatalog = (mutableChannelsAndTags.value as ChannelRepositoryState.Current).catalog
        mutableEpg.value = EpgRepositoryState.Current(epgSnapshot)
        publishedEpgSnapshot = (mutableEpg.value as EpgRepositoryState.Current).snapshot
        mutableDvr.value = DvrRepositoryState.Current(dvrSnapshot)
        publishedDvrSnapshot = (mutableDvr.value as DvrRepositoryState.Current).snapshot
        publishMetadataObservation()
    }

    private fun publishCurrentEpg(snapshot: EpgSnapshot) {
        mutableEpg.value = EpgRepositoryState.Current(snapshot)
        publishedEpgSnapshot = (mutableEpg.value as EpgRepositoryState.Current).snapshot
        publishMetadataObservation()
    }

    private fun publishMetadataObservation() {
        observationStore.publishMetadata(
            channelState = mutableChannelsAndTags.value,
            epgState = mutableEpg.value,
            dvrState = mutableDvr.value,
            configurationsState = mutableConfigurations.value,
            diskSpaceState = mutableDiskSpace.value,
        )
    }

    private fun publishedConfigurationsState(): DvrConfigurationsState =
        publishedConfigurations?.let(DvrConfigurationsState.Stale::create)
            ?: DvrConfigurationsState.Unknown

    private suspend fun acquireEpgCoverage(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageAcquisitionResult {
        val requester = synchronized(lock) {
            epgCoverageRequester.takeIf { this.generation === generation }
        } ?: return EpgCoverageAcquisitionResult.ObservationExpired
        return requester.acquireCoverage(currentSession, channelId, through)
    }
}

private fun GatewayResult<List<GatewayEpgQueryEvent>>.toEpgSearchResult(
    originatingSession: CurrentSessionObservation,
): EpgSearchResult {
    return when (this) {
        is GatewayResult.Ok -> {
            val events = ArrayList<EpgEvent>(value.size)
            value.forEach { event ->
                val mapped = ReducedEpgEvent.fromQuery(event)?.toPublicOrNull()
                    ?: return EpgSearchResult.InvalidQuery
                events += mapped
            }
            EpgSearchResult.Available.create(events, originatingSession)
        }
        GatewayResult.ServerRejected -> EpgSearchResult.InvalidQuery
        GatewayResult.AccessDenied -> EpgSearchResult.AccessDenied
        GatewayResult.ConnectionLimit -> EpgSearchResult.ConnectionLimit
        GatewayResult.Timeout -> EpgSearchResult.Timeout
        GatewayResult.TransportUnavailable -> EpgSearchResult.TransportUnavailable
        GatewayResult.NotSupported -> EpgSearchResult.NotSupported
    }
}

private fun Boolean?.toCapabilityAccess(): CapabilityAccess = when (this) {
    null -> CapabilityAccess.UNKNOWN
    true -> CapabilityAccess.ALLOWED
    false -> CapabilityAccess.DENIED
}

private fun MetadataEvent.isDvrMutationConfirmation(): Boolean = when (this) {
    is MetadataEvent.DvrEntryAdded,
    is MetadataEvent.DvrEntryUpdated,
    is MetadataEvent.DvrEntryDeleted,
    is MetadataEvent.AutorecRuleAdded,
    is MetadataEvent.AutorecRuleUpdated,
    is MetadataEvent.AutorecRuleDeleted,
    is MetadataEvent.TimerecRuleAdded,
    is MetadataEvent.TimerecRuleUpdated,
    is MetadataEvent.TimerecRuleDeleted,
    -> true
    is MetadataEvent.ChannelAdded,
    is MetadataEvent.ChannelUpdated,
    is MetadataEvent.ChannelDeleted,
    is MetadataEvent.TagAdded,
    is MetadataEvent.TagUpdated,
    is MetadataEvent.TagDeleted,
    is MetadataEvent.EventAdded,
    is MetadataEvent.EventUpdated,
    is MetadataEvent.EventDeleted,
    is MetadataEvent.InitialSyncCompleted,
    -> false
}

internal interface SessionChildren : ArtworkLoader {
    public suspend fun open(
        generation: GatewayGeneration,
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = SubscriptionOpenResult.NotReady

    /** Reports no active generation until a child binds profile discovery. */
    public suspend fun getStreamProfiles(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
    ): StreamProfilesResult = StreamProfilesResult.NotReady

    /** Reports the changed connection until a child actually binds a generation to artwork. */
    public override suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult = ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED)

    public suspend fun openRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)

    public fun bindGrowingRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<GrowingRecordingFileLease> =
        RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)

    public fun bindGeneration(generation: GatewayGeneration)

    public fun startLiveAdmission(
        generation: GatewayGeneration,
        streamingAccess: CapabilityAccess,
    ): Boolean

    public fun stopAdmission()

    public fun prepareBackgroundEnrichment(
        generation: GatewayGeneration,
        onDvrCapabilitiesChanged: suspend (SessionCapabilitiesSnapshot) -> Unit,
    ): Boolean = true

    public fun startBackgroundEnrichment(generation: GatewayGeneration): Boolean = true

    public suspend fun cancelAndJoinBackgroundEnrichment()

    public suspend fun closeAndJoinSubscriptions()

    public data object None : SessionChildren {
        override fun bindGeneration(generation: GatewayGeneration) = Unit

        override fun startLiveAdmission(
            generation: GatewayGeneration,
            streamingAccess: CapabilityAccess,
        ): Boolean = true

        override fun stopAdmission() = Unit

        override fun prepareBackgroundEnrichment(
            generation: GatewayGeneration,
            onDvrCapabilitiesChanged: suspend (SessionCapabilitiesSnapshot) -> Unit,
        ): Boolean = true

        override fun startBackgroundEnrichment(generation: GatewayGeneration): Boolean = true

        override suspend fun cancelAndJoinBackgroundEnrichment() = Unit

        override suspend fun closeAndJoinSubscriptions() = Unit
    }
}
