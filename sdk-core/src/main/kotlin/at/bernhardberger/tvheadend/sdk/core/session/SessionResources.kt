@file:OptIn(
    SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.CommandBackedDvrRepository
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrChange
import at.bernhardberger.tvheadend.sdk.core.DvrChangeKind
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointCommands
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrMutationCommands
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressCommands
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
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
import at.bernhardberger.tvheadend.sdk.core.channelCatalogForDisplay
import at.bernhardberger.tvheadend.sdk.core.cache.CachedCatalog
import at.bernhardberger.tvheadend.sdk.core.cache.CachedEpg
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.ChannelTagReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.DvrReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryFence
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.ReducedEpgEvent
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant
import kotlin.time.Clock
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes

/** Catalog and EPG snapshot published together with current authority. */
internal class PublishedSnapshots(
    val catalog: ChannelCatalog,
    val epgSnapshot: EpgSnapshot,
    val channelRefreshTimes: MetadataRefreshTimes,
    val epgRefreshTimes: MetadataRefreshTimes,
    val epgLastQueriedAt: Map<ChannelId, Instant>,
) {
    constructor(catalog: ChannelCatalog, epgSnapshot: EpgSnapshot) :
        this(catalog, epgSnapshot, MetadataRefreshTimes(), MetadataRefreshTimes(), emptyMap())
}

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
    ): DvrProgressResult = DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

    public suspend fun reportProgress(
        target: PlaybackRecordingTarget,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

    public suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false)

    public suspend fun getCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrCutpointsResult = DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)

    public suspend fun getCutpoints(
        target: PlaybackRecordingTarget,
    ): DvrCutpointsResult = DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY)

    public fun publishSessionState(
        state: SessionState,
        progressCapability: RecordingProgressCapability,
        generation: GatewayGeneration?,
    )

    public fun selectAccount(username: String?)

    /**
     * Resets working state but keeps [publishedSnapshots].
     *
     * Returns the retained publication, captured atomically with the reset, so the outgoing
     * cache writer can flush it to its own namespace.
     */
    public fun resetWorkingStateRetainingPublishedSnapshot(): PublishedSnapshots?

    /**
     * Clears all state, including [publishedSnapshots].
     *
     * Returns the publication this clear retired, captured atomically with the clear, so the
     * outgoing profile's cache writer can flush it to its own namespace.
     */
    public fun clearAllState(): PublishedSnapshots?

    /**
     * Latest catalog and EPG snapshot published with current authority, for persistence.
     *
     * `null` until the first initial synchronisation of the process. Retained-snapshot resets do
     * not clear it; a same-profile reconnect republishes the same instances. [clearAllState]
     * resets it to `null` so a later profile never persists the previous profile's data.
     */
    public val publishedSnapshots: StateFlow<PublishedSnapshots?>

    /**
     * Seeds the retained catalog and EPG snapshot from persistence before any connection.
     *
     * Publishes them as stale states. Returns `false` without changes when a generation is bound
     * or a snapshot is already retained, so a cached copy never replaces live data.
     */
    public fun seedRetainedSnapshots(catalog: CachedCatalog?, epgSnapshot: CachedEpg?): Boolean

    /** Whether a cache seed is currently eligible, without reading or publishing cache data. */
    public fun canSeedRetainedSnapshots(): Boolean

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

    /**
     * Accepts [event] while a burst of EPG traffic is being drained. EPG additions, updates and
     * deletions are reduced immediately but their publication waits for [flushDeferredMetadata],
     * so a burst of guide updates costs one snapshot instead of one per message. Every other event
     * publishes as [acceptMetadata] does and carries any deferred EPG change with it.
     */
    public fun acceptMetadataDeferringEpg(event: MetadataEvent) {
        acceptMetadata(event)
    }

    /** Publishes EPG changes accepted through [acceptMetadataDeferringEpg] since the last publication. */
    public fun flushDeferredMetadata() {}

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
    ): EpgQueryAcceptance

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

    public suspend fun acquireCoverageBatch(
        currentSession: CurrentSessionObservation,
        channelIds: List<ChannelId>,
        through: Instant,
    ): EpgCoverageBatchResult = EpgCoverageBatchResult.create(
        channelIds.distinct().map { channelId ->
            acquireCoverage(currentSession, channelId, through).toBatchSettlement(channelId)
        },
    )
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
    private val estimatedServerTime: (GatewayGeneration) -> Instant? = { null },
    private val dvrIntents: DvrIntentLedger? = null,
    private val clock: Clock = Clock.System,
) : SessionMetadata {
    private val lock = Any()
    private var channelRefreshTimes = MetadataRefreshTimes()
    private var epgRefreshTimes = MetadataRefreshTimes()
    private var dvrRefreshTimes = MetadataRefreshTimes()
    private var pendingEpgUpdatedAt: Instant? = null
    private val epgLastQueriedAt = linkedMapOf<ChannelId, Instant>()
    private val reducer = ChannelTagReducer()
    private val epgReducer = EpgReducer(epgCoveragePolicy.maximumRetainedEvents)
    private val dvrReducer = DvrReducer()
    private val dvrFailuresReported = mutableSetOf<DvrEntryId>()
    private val mutableDvrChanges = MutableSharedFlow<DvrChange>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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
                ?: return EpgSearchResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
            val result = searchCommands.search(generationFence.generation, request)
            currentCoroutineContext().ensureActive()
            val mappedResult = result.toEpgSearchResult(currentSession)
            val connectionChanged = hasReplacementGeneration(generationFence)
            currentCoroutineContext().ensureActive()
            return if (connectionChanged) EpgSearchResult.Failed(TvheadendFailure.CONNECTION_CHANGED) else mappedResult
        }

        override suspend fun acquireCoverage(
            currentSession: CurrentSessionObservation,
            channelId: ChannelId,
            through: Instant,
        ): EpgCoverageAcquisitionResult {
            currentCoroutineContext().ensureActive()
            val expectedGeneration = resolveGeneration(currentSession)
                ?: return EpgCoverageAcquisitionResult.ObservationExpired
            return acquireEpgCoverage(expectedGeneration, currentSession, channelId, through)
        }

        override suspend fun acquireCoverageBatch(
            currentSession: CurrentSessionObservation,
            channelIds: List<ChannelId>,
            through: Instant,
        ): EpgCoverageBatchResult {
            currentCoroutineContext().ensureActive()
            val expectedGeneration = resolveGeneration(currentSession)
                ?: return expiredEpgCoverageBatch(channelIds)
            return acquireEpgCoverageBatch(expectedGeneration, currentSession, channelIds, through)
        }
    }
    private val stateBackedDvrRepository = object : CommandBackedDvrRepository(
        mutations = mutationCommands,
        resolveGeneration = ::resolveGeneration,
        progressCommands = progressCommands,
    ) {
        override val changes = mutableDvrChanges.asSharedFlow()
    }
    // Observation readers use volatile generation plus the store's atomic snapshot/proof.
    // Retirement writes generation before requester; acquisition reads requester before generation.
    @Volatile private var generation: GatewayGeneration? = null
    private var generationBindRevision = 0L
    private var initialSync = CompletableDeferred<Unit>()
    private var publishedCatalog: ChannelCatalog? = null
    private var publishedEpgSnapshot: EpgSnapshot? = null
    private val mutablePublishedSnapshots = MutableStateFlow<PublishedSnapshots?>(null)
    override val publishedSnapshots: StateFlow<PublishedSnapshots?> =
        mutablePublishedSnapshots.asStateFlow()
    private var publishedDvrSnapshot: DvrSnapshot? = null
    private var publishedConfigurations: List<DvrConfiguration>? = null
    private var publishedDiskSpace: DvrDiskSpace? = null
    private var synchronizedCurrent = false
    private var epgPublicationDeferred = false
    private var serverFacts: GatewayServerFacts? = null
    private var dvrAccess: CapabilityAccess = CapabilityAccess.UNKNOWN
    private var capabilityRevision = 0L
    @Volatile private var epgCoverageRequester: EpgCoverageRequester? = null
    private val dvrEntryIncarnations = HashMap<DvrEntryId, DvrEntryIncarnation>()

    override val observation: StateFlow<SessionObservation> = observationStore.observation
    override val channelsAndTags: StateFlow<ChannelRepositoryState> =
        mutableChannelsAndTags.asStateFlow()
    override val epgRepository: EpgRepository = stateBackedEpgRepository
    override val dvrRepository: DvrRepository = stateBackedDvrRepository

    override fun resolveGeneration(
        currentSession: CurrentSessionObservation,
    ): GatewayGeneration? {
        val expectedGeneration = generation ?: return null
        return observationStore.resolve(currentSession, expectedGeneration) as? GatewayGeneration
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
    ): SessionObservation? {
        if (this.generation !== generation) return null
        return observationStore.currentObservation(currentSession, generation)
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

    override fun selectAccount(username: String?) {
        observationStore.selectAccount(username)
    }

    override fun resetWorkingStateRetainingPublishedSnapshot(): PublishedSnapshots? =
        resetState(retainPublishedCatalog = true)

    override fun canSeedRetainedSnapshots(): Boolean = synchronized(lock) {
        generation == null && publishedCatalog == null && publishedEpgSnapshot == null
    }

    override fun seedRetainedSnapshots(catalog: CachedCatalog?, epgSnapshot: CachedEpg?): Boolean {
        if (catalog == null && epgSnapshot == null) return false
        synchronized(lock) {
            if (!canSeedRetainedSnapshots()) {
                return false
            }
            epgSnapshot?.snapshot?.prepareForObservation()
            publishedCatalog = catalog?.catalog
            publishedEpgSnapshot = epgSnapshot?.snapshot
            channelRefreshTimes = catalog?.refreshTimes ?: MetadataRefreshTimes()
            epgRefreshTimes = epgSnapshot?.refreshTimes ?: MetadataRefreshTimes()
            epgLastQueriedAt.clear()
            epgLastQueriedAt.putAll(epgSnapshot?.lastQueriedAt.orEmpty())
            mutableChannelsAndTags.value = catalog?.let { ChannelRepositoryState.Stale(it.catalog) }
                ?: ChannelRepositoryState.Empty
            mutableEpg.value = epgSnapshot?.let { EpgRepositoryState.Stale(it.snapshot) } ?: EpgRepositoryState.Empty
            publishMetadataObservation()
        }
        return true
    }

    override fun clearAllState(): PublishedSnapshots? = resetState(retainPublishedCatalog = false)

    private fun resetState(retainPublishedCatalog: Boolean): PublishedSnapshots? {
        var retiredPublication: PublishedSnapshots? = null
        val retiredFence = synchronized(lock) {
            retiredPublication = mutablePublishedSnapshots.value
            generation = null
            val previousFence = initialSync
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            epgPublicationDeferred = false
            serverFacts = null
            pendingEpgUpdatedAt = null
            dvrAccess = CapabilityAccess.UNKNOWN
            capabilityRevision += 1
            epgCoverageRequester = null
            reducer.clear()
            epgReducer.clear()
            dvrReducer.clear()
            dvrFailuresReported.clear()
            dvrEntryIncarnations.clear()
            if (!retainPublishedCatalog) {
                observationStore.selectAccount(null)
                channelRefreshTimes = MetadataRefreshTimes()
                epgRefreshTimes = MetadataRefreshTimes()
                dvrRefreshTimes = MetadataRefreshTimes()
                epgLastQueriedAt.clear()
                publishedCatalog = null
                publishedEpgSnapshot = null
                publishedDvrSnapshot = null
                publishedConfigurations = null
                publishedDiskSpace = null
                mutablePublishedSnapshots.value = null
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
        return retiredPublication
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        val retiredFence = synchronized(lock) {
            val previousFence = initialSync
            generationBindRevision += 1
            this.generation = generation
            initialSync = CompletableDeferred()
            synchronizedCurrent = false
            epgPublicationDeferred = false
            pendingEpgUpdatedAt = null
            serverFacts = null
            dvrAccess = CapabilityAccess.UNKNOWN
            capabilityRevision += 1
            epgCoverageRequester = null
            reducer.clear()
            epgReducer.clear()
            dvrReducer.clear()
            dvrFailuresReported.clear()
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
                is GatewayResult.ServerRejected,
                GatewayResult.ConnectionLimit,
                GatewayResult.Timeout,
                GatewayResult.TransportUnavailable,
                GatewayResult.NotSupported,
                GatewayResult.ObservationExpired,
                GatewayResult.MalformedReply,
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
                is GatewayResult.ServerRejected,
                GatewayResult.ConnectionLimit,
                GatewayResult.Timeout,
                GatewayResult.TransportUnavailable,
                GatewayResult.NotSupported,
                GatewayResult.ObservationExpired,
                GatewayResult.MalformedReply,
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
        acceptMetadata(event, deferEpgPublication = false)
    }

    override fun acceptMetadataDeferringEpg(event: MetadataEvent) {
        acceptMetadata(event, deferEpgPublication = true)
    }

    override fun flushDeferredMetadata() {
        synchronized(lock) {
            if (!epgPublicationDeferred) return
            epgPublicationDeferred = false
            if (synchronizedCurrent) {
                publishCurrent(reducer.snapshot(), epgReducer.snapshot(), dvrReducer.snapshot())
            }
        }
    }

    private fun acceptMetadata(event: MetadataEvent, deferEpgPublication: Boolean) {
        var acceptedDvrEvent: MetadataEvent? = null
        var dvrChange: DvrChange? = null
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
                        val now = acceptanceTime()
                        channelRefreshTimes = MetadataRefreshTimes(now, now)
                        epgRefreshTimes = MetadataRefreshTimes(now, now)
                        dvrRefreshTimes = MetadataRefreshTimes(now, now)
                        epgLastQueriedAt.clear()
                        publishCurrent(channelSnapshot, epgSnapshot, dvrSnapshot)
                        initialSync
                    }
                }
                is MetadataEvent.ChannelAdded,
                is MetadataEvent.ChannelUpdated,
                is MetadataEvent.ChannelNowNextUpdated,
                is MetadataEvent.ChannelDeleted,
                is MetadataEvent.TagAdded,
                is MetadataEvent.TagUpdated,
                is MetadataEvent.TagDeleted,
                is MetadataEvent.EventAdded,
                is MetadataEvent.EventUpdated,
                is MetadataEvent.EventDeleted,
                is MetadataEvent.DvrEntryAdded,
                is MetadataEvent.DvrEntryUpdated,
                is MetadataEvent.DvrEntryStatsUpdated,
                is MetadataEvent.DvrEntryDeleted,
                is MetadataEvent.AutorecRuleAdded,
                is MetadataEvent.AutorecRuleUpdated,
                is MetadataEvent.AutorecRuleDeleted,
                is MetadataEvent.TimerecRuleAdded,
                is MetadataEvent.TimerecRuleUpdated,
                is MetadataEvent.TimerecRuleDeleted,
                -> {
                    // Unknown patches cannot establish a complete entity or refresh its publication.
                    if (event is MetadataEvent.ChannelNowNextUpdated && !reducer.containsChannel(event.channelId)) {
                        return@synchronized null
                    }
                    if (event is MetadataEvent.DvrEntryStatsUpdated && dvrReducer.entry(event.stats.id) == null) {
                        return@synchronized null
                    }
                    val changedEntryId = when (event) {
                        is MetadataEvent.DvrEntryAdded -> event.entry.id
                        is MetadataEvent.DvrEntryUpdated -> event.entry.id
                        is MetadataEvent.DvrEntryStatsUpdated -> event.stats.id
                        is MetadataEvent.DvrEntryDeleted -> event.entryId
                        else -> null
                    }
                    val previousReducedEntry = changedEntryId?.let(dvrReducer::entry)
                    val updatedDvrEntry = when (event) {
                        is MetadataEvent.DvrEntryUpdated -> event.entry.id
                        is MetadataEvent.DvrEntryStatsUpdated -> event.stats.id
                        else -> null
                    }
                    val previousDvrEntry = updatedDvrEntry?.let { id ->
                        (mutableDvr.value as? DvrRepositoryState.Current)
                            ?.snapshot
                            ?.entries
                            ?.singleOrNull { entry -> entry.id == id }
                    }
                    reducer.accept(event)
                    val epgEventAccepted = epgReducer.accept(event, estimatedServerTime(event.generation))
                    val dvrEventAccepted = dvrReducer.accept(event)
                    if (dvrEventAccepted && event is MetadataEvent.DvrEntryDeleted) {
                        dvrFailuresReported.remove(event.entryId)
                    }
                    val dvrSnapshot = if (synchronizedCurrent) dvrReducer.snapshot() else null
                    if (dvrEventAccepted && synchronizedCurrent) {
                        val proof = observation.value.currentSession
                        if (proof != null && resolveGeneration(proof) === event.generation) {
                            dvrChange = classifyDvrChange(
                                event, previousReducedEntry, changedEntryId?.let(dvrReducer::entry),
                                proof.generationIdentity, estimatedServerTime(event.generation), dvrIntents,
                                changedEntryId in dvrFailuresReported,
                            )
                            if (dvrChange?.kind == DvrChangeKind.RECORDING_FAILED) {
                                dvrFailuresReported += checkNotNull(changedEntryId)
                            }
                        }
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
                            is MetadataEvent.DvrEntryUpdated,
                            is MetadataEvent.DvrEntryStatsUpdated,
                            -> {
                                val id = checkNotNull(updatedDvrEntry)
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
                        val now = acceptanceTime()
                        when (event) {
                            is MetadataEvent.ChannelAdded, is MetadataEvent.ChannelUpdated,
                            is MetadataEvent.ChannelNowNextUpdated,
                            is MetadataEvent.ChannelDeleted, is MetadataEvent.TagAdded,
                            is MetadataEvent.TagUpdated, is MetadataEvent.TagDeleted,
                            -> channelRefreshTimes = channelRefreshTimes.copy(lastUpdatedAt = now)
                            is MetadataEvent.EventAdded, is MetadataEvent.EventUpdated,
                            is MetadataEvent.EventDeleted,
                            -> if (epgEventAccepted) pendingEpgUpdatedAt = now
                            else -> if (dvrEventAccepted) {
                                dvrRefreshTimes = dvrRefreshTimes.copy(lastUpdatedAt = now)
                            }
                        }
                        if (event is MetadataEvent.ChannelDeleted) epgLastQueriedAt.remove(event.channelId)
                        if (deferEpgPublication && event.isEpgEvent()) {
                            epgPublicationDeferred = true
                        } else {
                            publishCurrent(reducer.snapshot(), epgReducer.snapshot(), checkNotNull(dvrSnapshot))
                        }
                    }
                    if (dvrEventAccepted && event.isDvrMutationConfirmation()) {
                        acceptedDvrEvent = event
                    }
                    null
                }
            }
        }
        completedFence?.complete(Unit)
        dvrChange?.let(mutableDvrChanges::tryEmit)
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
            val catalog = mutableChannelsAndTags.value.channelCatalogForDisplay
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
    ): EpgQueryAcceptance = synchronized(lock) {
        if (this.generation === generation && synchronizedCurrent) {
            epgReducer.acceptSuccessfulQuery(query, queriedTo, events).also { acceptance ->
                if (acceptance == EpgQueryAcceptance.APPLIED) {
                    val now = acceptanceTime()
                    pendingEpgUpdatedAt = now
                    epgLastQueriedAt[query.channelId] = now
                    publishCurrentEpg(epgReducer.snapshot())
                }
            }
        } else {
            EpgQueryAcceptance.STALE
        }
    }

    override fun retainEpgEvents(generation: GatewayGeneration, from: Instant, to: Instant) {
        synchronized(lock) {
            if (this.generation === generation) {
                epgReducer.retainOverlapping(from, to)
                estimatedServerTime(generation)?.let(epgReducer::pruneHistory)
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
        val preparedEpg = prepareEpgForPublication(epgSnapshot)
        epgPublicationDeferred = false
        mutableChannelsAndTags.value = ChannelRepositoryState.Current(catalog)
        publishedCatalog = (mutableChannelsAndTags.value as ChannelRepositoryState.Current).catalog
        mutableEpg.value = EpgRepositoryState.Current(preparedEpg)
        publishedEpgSnapshot = (mutableEpg.value as EpgRepositoryState.Current).snapshot
        mutableDvr.value = DvrRepositoryState.Current(dvrSnapshot)
        publishedDvrSnapshot = (mutableDvr.value as DvrRepositoryState.Current).snapshot
        publishCachedSnapshots(catalog, preparedEpg)
        publishMetadataObservation()
    }

    private fun publishCurrentEpg(snapshot: EpgSnapshot) {
        val preparedEpg = prepareEpgForPublication(snapshot)
        mutableEpg.value = EpgRepositoryState.Current(preparedEpg)
        publishedEpgSnapshot = (mutableEpg.value as EpgRepositoryState.Current).snapshot
        publishedCatalog?.let { catalog ->
            publishCachedSnapshots(catalog, preparedEpg)
        }
        publishMetadataObservation()
    }

    private fun prepareEpgForPublication(candidate: EpgSnapshot): EpgSnapshot {
        pendingEpgUpdatedAt?.let { epgRefreshTimes = epgRefreshTimes.copy(lastUpdatedAt = it) }
        pendingEpgUpdatedAt = null
        // Match StateFlow conflation before allocating indexes, including no-op EPG updates.
        val snapshot = publishedEpgSnapshot?.takeIf { it == candidate } ?: candidate
        snapshot.prepareForObservation()
        return snapshot
    }

    private fun publishMetadataObservation() {
        observationStore.publishMetadata(
            channelState = mutableChannelsAndTags.value,
            epgState = mutableEpg.value,
            dvrState = mutableDvr.value,
            configurationsState = mutableConfigurations.value,
            diskSpaceState = mutableDiskSpace.value,
            channelRefreshTimes = channelRefreshTimes,
            epgRefreshTimes = epgRefreshTimes,
            dvrRefreshTimes = dvrRefreshTimes,
            epgLastQueriedAt = epgLastQueriedAt,
        )
    }

    private fun acceptanceTime(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    private fun publishCachedSnapshots(catalog: ChannelCatalog, snapshot: EpgSnapshot) {
        mutablePublishedSnapshots.value = PublishedSnapshots(
            catalog, snapshot, channelRefreshTimes, epgRefreshTimes, epgLastQueriedAt.toMap(),
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
        val requester = epgCoverageRequester.takeIf { this.generation === generation }
            ?: return EpgCoverageAcquisitionResult.ObservationExpired
        return requester.acquireCoverage(currentSession, channelId, through)
    }

    private suspend fun acquireEpgCoverageBatch(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
        channelIds: List<ChannelId>,
        through: Instant,
    ): EpgCoverageBatchResult {
        val requester = epgCoverageRequester.takeIf { this.generation === generation }
            ?: return expiredEpgCoverageBatch(channelIds)
        return requester.acquireCoverageBatch(currentSession, channelIds, through)
    }
}

private fun EpgCoverageAcquisitionResult.toBatchSettlement(
    channelId: ChannelId,
): EpgCoverageBatchSettlement = when (this) {
    is EpgCoverageAcquisitionResult.CoveredWithData ->
        EpgCoverageBatchSettlement.CoveredWithData(channelId, observation)
    is EpgCoverageAcquisitionResult.CoveredEmpty ->
        EpgCoverageBatchSettlement.CoveredEmpty(channelId, observation)
    EpgCoverageAcquisitionResult.Ineligible -> EpgCoverageBatchSettlement.Rejected(channelId)
    EpgCoverageAcquisitionResult.ObservationExpired ->
        EpgCoverageBatchSettlement.ObservationExpired(channelId)
}

private fun expiredEpgCoverageBatch(channelIds: List<ChannelId>): EpgCoverageBatchResult =
    EpgCoverageBatchResult.create(
        channelIds.distinct().map(EpgCoverageBatchSettlement::ObservationExpired),
    )

private fun GatewayResult<List<GatewayEpgQueryEvent>>.toEpgSearchResult(
    originatingSession: CurrentSessionObservation,
): EpgSearchResult {
    return when (this) {
        is GatewayResult.Ok -> {
            val events = ArrayList<EpgEvent>(value.size)
            value.forEach { event ->
                val mapped = ReducedEpgEvent.fromQuery(event)?.toPublicOrNull()
                    ?: return EpgSearchResult.Failed(TvheadendFailure.SERVER_REJECTED)
                events += mapped
            }
            EpgSearchResult.Available.create(events, originatingSession)
        }
        is GatewayResult.ServerRejected -> EpgSearchResult.Failed(TvheadendFailure.SERVER_REJECTED)
        GatewayResult.AccessDenied -> EpgSearchResult.Failed(TvheadendFailure.ACCESS_DENIED)
        GatewayResult.ConnectionLimit -> EpgSearchResult.Failed(TvheadendFailure.CONNECTION_LIMIT)
        GatewayResult.Timeout -> EpgSearchResult.Failed(TvheadendFailure.TIMEOUT)
        GatewayResult.TransportUnavailable -> EpgSearchResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE)
        GatewayResult.NotSupported -> EpgSearchResult.Failed(TvheadendFailure.NOT_SUPPORTED)
        GatewayResult.ObservationExpired -> EpgSearchResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED)
        GatewayResult.MalformedReply -> EpgSearchResult.Failed(TvheadendFailure.MALFORMED_RESPONSE)
    }
}

private fun Boolean?.toCapabilityAccess(): CapabilityAccess = when (this) {
    null -> CapabilityAccess.UNKNOWN
    true -> CapabilityAccess.ALLOWED
    false -> CapabilityAccess.DENIED
}

private fun MetadataEvent.isEpgEvent(): Boolean =
    this is MetadataEvent.EventAdded ||
        this is MetadataEvent.EventUpdated ||
        this is MetadataEvent.EventDeleted

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
    is MetadataEvent.ChannelNowNextUpdated,
    is MetadataEvent.DvrEntryStatsUpdated,
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
    ): StreamProfilesResult = StreamProfilesResult.Failed(TvheadendFailure.NOT_READY)

    /** Reports the changed connection until a child actually binds a generation to artwork. */
    public override suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult = ArtworkLoadResult.Unavailable(TvheadendFailure.OBSERVATION_EXPIRED)

    public suspend fun openRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(TvheadendFailure.CONNECTION_CHANGED)

    public fun bindGrowingRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<GrowingRecordingFileLease> =
        RecordingFileResult.Failed(TvheadendFailure.CONNECTION_CHANGED)

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
