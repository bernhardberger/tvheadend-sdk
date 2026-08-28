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
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointCommands
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrMutationCommands
import at.bernhardberger.tvheadend.sdk.core.DvrProgressCommands
import at.bernhardberger.tvheadend.sdk.core.DvrRepository
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageRequestResult
import at.bernhardberger.tvheadend.sdk.core.EpgRepository
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
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
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Instant

internal interface SessionMetadata {
    public val observation: StateFlow<SessionObservation>

    public val channelsAndTags: StateFlow<ChannelRepositoryState>

    public val epgRepository: EpgRepository

    public val dvrRepository: DvrRepository

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

internal class DvrEntryIncarnation {
    override fun toString(): String = "DvrEntryIncarnation(<redacted>)"
}

internal fun interface EpgCoverageRequester {
    public fun requestCoverage(
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageRequestResult
}

internal class PhaseOneSessionMetadata(
    mutationCommands: DvrMutationCommands = DvrMutationCommands.None,
    progressCommands: DvrProgressCommands = DvrProgressCommands.None,
    cutpointCommands: DvrCutpointCommands = DvrCutpointCommands.None,
    private val onDvrMetadataAccepted: (MetadataEvent) -> Unit = {},
    private val observationStore: SessionObservationStore = SessionObservationStore(),
) : SessionMetadata {
    private val lock = Any()
    private val reducer = ChannelTagReducer()
    private val epgReducer = EpgReducer()
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
        override fun requestCoverage(
            channelId: ChannelId,
            through: Instant,
        ): EpgCoverageRequestResult = requestEpgCoverage(channelId, through)
    }
    private val stateBackedDvrRepository = object : CommandBackedDvrRepository(
        mutations = mutationCommands,
        progressCommands = progressCommands,
        cutpointCommands = cutpointCommands,
    ) {}
    private var generation: GatewayGeneration? = null
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
                    if (dvrEventAccepted) {
                        val id = when (event) {
                            is MetadataEvent.DvrEntryAdded -> event.entry.id
                            is MetadataEvent.DvrEntryDeleted -> event.entryId
                            is MetadataEvent.DvrEntryUpdated -> previousDvrEntry?.let { previous ->
                                val current = dvrSnapshot
                                    ?.entries
                                    ?.singleOrNull { entry -> entry.id == previous.id }
                                previous.id.takeIf {
                                    current == null || !previous.preservesGrowingContinuity(current)
                                }
                            }
                            else -> null
                        }
                        if (id != null) dvrEntryIncarnations[id] = DvrEntryIncarnation()
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
                dvrEntryIncarnations.getOrPut(id, ::DvrEntryIncarnation)
            } else {
                null
            }
            CurrentDvrEntryLookup.Current(current, match, matchCount, incarnation)
        }
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

    private fun requestEpgCoverage(
        channelId: ChannelId,
        through: Instant,
    ): EpgCoverageRequestResult {
        val requester = synchronized(lock) { epgCoverageRequester }
            ?: return EpgCoverageRequestResult.GENERATION_LOST
        return requester.requestCoverage(channelId, through)
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

internal interface SessionChildren : ArtworkLoader, SubscriptionOpener, RecordingFileOpener {
    /** Reports no active generation until a child binds profile discovery. */
    public suspend fun getStreamProfiles(): StreamProfilesResult = StreamProfilesResult.NotReady

    /** Reports the changed connection until a child actually binds a generation to artwork. */
    public override suspend fun loadArtwork(artworkId: ArtworkId): ArtworkLoadResult =
        ArtworkLoadResult.Unavailable(ArtworkFailure.CONNECTION_CHANGED)

    /** Reports the changed connection until a child actually binds a generation to recordings. */
    public override suspend fun openRecording(
        recordingId: RecordingId,
    ): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)

    /** Reports the changed connection until a child can bind growing-file continuity. */
    public override fun bindGrowingRecording(
        recordingId: RecordingId,
    ): RecordingFileResult<GrowingRecordingFileLease> =
        RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)

    /** Reports the changed connection until a child can correlate fresh growing-file metadata. */
    public override suspend fun openGrowingRecording(
        recordingId: RecordingId,
        position: Long,
    ): RecordingFileResult<GrowingRecordingFileReader> {
        require(position >= 0L) { "Growing recording position must not be negative" }
        return RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
    }

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
        override suspend fun open(
            channelId: SubscriptionChannelId,
            consumer: SubscriptionEventConsumer,
            timeshiftPeriod: Duration,
        ): SubscriptionOpenResult = SubscriptionOpenResult.NotReady

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
