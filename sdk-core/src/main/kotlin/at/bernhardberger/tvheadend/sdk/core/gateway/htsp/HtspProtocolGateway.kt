@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core.gateway.htsp

import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionTermination
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportFailureKind
import at.bernhardberger.tvheadend.htsp.connection.createHtspConnection
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.model.HtspDvrRecordingFile
import at.bernhardberger.tvheadend.htsp.model.HtspChannel
import at.bernhardberger.tvheadend.htsp.model.HtspTag
import at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry
import at.bernhardberger.tvheadend.htsp.model.HtspAutorecEntry
import at.bernhardberger.tvheadend.htsp.model.HtspTimerecEntry
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelNowNextMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryStatsMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspFrameType
import at.bernhardberger.tvheadend.htsp.messages.HtspDescrambleInfoMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspMuxPacketMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspQueueStatusMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspMetadataMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSignalStatusMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionGraceMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionSkipMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionSpeedMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStartMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionSourceInfo
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStatusMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStopMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStream
import at.bernhardberger.tvheadend.htsp.messages.HtspTagAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTagDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTagUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimerecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
import at.bernhardberger.tvheadend.htsp.requests.FileSeekResponse
import at.bernhardberger.tvheadend.htsp.requests.FileSeekWhence
import at.bernhardberger.tvheadend.htsp.requests.EpgQueryResponse
import at.bernhardberger.tvheadend.htsp.model.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.AddDvrEntrySelector
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrCutpoint
import at.bernhardberger.tvheadend.htsp.model.HtspEvent
import at.bernhardberger.tvheadend.htsp.requests.HtspRecordingRuleChannel
import at.bernhardberger.tvheadend.htsp.requests.SubscribeResponse
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSeekPosition
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionTimestampClock
import at.bernhardberger.tvheadend.htsp.requests.addAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.addDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.addTimerecEntry
import at.bernhardberger.tvheadend.htsp.requests.cancelDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteTimerecEntry
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.requests.epgQuery
import at.bernhardberger.tvheadend.htsp.requests.fileClose
import at.bernhardberger.tvheadend.htsp.requests.fileOpen
import at.bernhardberger.tvheadend.htsp.requests.fileRead
import at.bernhardberger.tvheadend.htsp.requests.fileSeek
import at.bernhardberger.tvheadend.htsp.requests.fileStat
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import at.bernhardberger.tvheadend.htsp.requests.getDvrCutpoints
import at.bernhardberger.tvheadend.htsp.requests.getDvrConfigs
import at.bernhardberger.tvheadend.htsp.requests.getEvents
import at.bernhardberger.tvheadend.htsp.requests.getProfiles
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import at.bernhardberger.tvheadend.htsp.requests.stopDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.subscribe
import at.bernhardberger.tvheadend.htsp.requests.subscriptionChangeWeight
import at.bernhardberger.tvheadend.htsp.requests.subscriptionSkip
import at.bernhardberger.tvheadend.htsp.requests.subscriptionSkipNearLive
import at.bernhardberger.tvheadend.htsp.requests.subscriptionSpeed
import at.bernhardberger.tvheadend.htsp.requests.unsubscribe
import at.bernhardberger.tvheadend.htsp.requests.updateAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.updateDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.updateTimerecEntry
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointAction
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_INCR_PLAY_COUNT
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_KEEP_PLAY_COUNT
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.toDvrMinute
import at.bernhardberger.tvheadend.sdk.core.toDvrDayMask
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.RecordingRuleChannel
import at.bernhardberger.tvheadend.sdk.core.StreamProfile
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.toDvrMutationSeconds
import at.bernhardberger.tvheadend.sdk.core.gateway.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrSubscriptionError
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrStats
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnection
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFileStat
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import at.bernhardberger.tvheadend.sdk.core.gateway.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.LiveFrontendState
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionPriority
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionSource
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import at.bernhardberger.tvheadend.htsp.connection.HtspEventBufferOptions
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(SubscriptionInfrastructureApi::class)
internal class HtspProtocolGateway internal constructor(
    private val connection: HtspConnection,
    private val epgCoveragePolicy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
) : ProtocolGateway {
    internal constructor(
        ioDispatcher: CoroutineDispatcher,
        epgCoveragePolicy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
    ) : this(createHtspConnection(ioDispatcher, eventBufferOptions = METADATA_BUFFER_OPTIONS), epgCoveragePolicy)

    // Lock order: generationLock -> SDK owner/state-machine locks inside commitIfLive.
    // Read-only state/time access must not acquire generationLock from those consumer locks.
    // Never suspend or invoke library lifecycle operations while holding this monitor.
    private val generationLock = Any()
    private var currentLive: HtspLiveConnection? = null
    private var initialEpgThrough: Instant? = null
    private var initialEpgIncomplete = false
    // HTSP rule updates always replace the channel restriction. Preserve SDK null-as-unchanged
    // from metadata (or a successful local mutation), never by guessing Any for an unknown rule.
    // SDK mutations are serialized by DvrMutationCoordinator.operationMutex. Metadata can still
    // overtake replies, so every cache write is also fenced by the observed per-rule revision.
    private val autorecChannels = RuleChannelCache()
    private val timerecChannels = RuleChannelCache()
    @Volatile private var observedLive: LiveObservation? = null
    @Volatile internal var lastDisconnectChanged: Boolean? = null
        private set
    @Volatile internal var lastCloseChanged: Boolean? = null
        private set
    private val retiredGenerations = WeakHashMap<HtspConnectionGeneration, GatewayConnectionFailure?>()
    private val intentionallyEnded = WeakHashMap<HtspConnectionGeneration, Boolean>()
    private var ownedGeneration: HtspConnectionGeneration? = null
    // Generation-local latch: overflow may be observed before the owner starts its await.
    private val failures = WeakHashMap<HtspConnectionGeneration, CompletableDeferred<GatewayConnectionFailure>>()
    private val serverTimeEstimate = at.bernhardberger.tvheadend.sdk.core.gateway.ServerTimeEstimate()

    override fun estimatedServerTime(generation: GatewayGeneration): Instant? {
        val observed = observedLive ?: return null
        if (observed.gatewayGeneration !== generation ||
            (connection.connectionState.value as? HtspConnectionState.Connected)
                ?.connection?.generation !== observed.htspGeneration
        ) return null
        return serverTimeEstimate.atStatus(generation)
    }

    private class LiveObservation(
        val htspGeneration: HtspConnectionGeneration,
        val gatewayGeneration: GatewayGeneration,
    )

    /**
     * Bounds concurrent artwork file handles on this connection. Each artwork load holds an HTSP
     * file open across several round trips; an unbounded burst (a channel list scrolling picons)
     * exhausts the server's per-connection file budget and turns every further load into a
     * rejection.
     */
    private val artworkFiles = Semaphore(MAX_CONCURRENT_ARTWORK_FILES)
    private val gatewayGenerations =
        WeakHashMap<HtspConnectionGeneration, WeakReference<GatewayGeneration>>()
    private val htspGenerations = WeakHashMap<GatewayGeneration, HtspConnectionGeneration>()

    override val connectionState: StateFlow<GatewayState> = MappingStateFlow(
        source = connection.connectionState,
        transform = HtspConnectionState::toGatewayState,
    )

    override val metadata: Flow<MetadataEvent>
        get() = metadataBatches.transform { batch -> batch.forEach { emit(it) } }

    override val metadataBatches: Flow<List<MetadataEvent>> = connection.eventBatches().transform { batch ->
        val source = batch.firstOrNull()?.generation ?: return@transform
        val metadataRetired = synchronized(generationLock) {
            retiredGenerations[source] == GatewayConnectionFailure.METADATA_OVERFLOW
        }
        val generation = generationFor(source)
        var mapped = ArrayList<MetadataEvent>(batch.size)
        for (event in batch) when (event) {
            is HtspTransportEvent.ServerMessage -> {
                if (metadataRetired) continue
                rememberRuleChannel(event.generation, event.message)
                val epgThrough = if (event.message === HtspInitialSyncCompletedMessage) {
                    synchronized(generationLock) {
                        refreshLive()?.takeIf { it.generation === event.generation }
                            ?.let { initialEpgThrough.takeUnless { initialEpgIncomplete } }
                    }
                } else null
                event.message.toGatewayMetadata(generation, epgThrough)?.let { mapped.add(it) }
                if (event.message === HtspInitialSyncCompletedMessage && mapped.isNotEmpty()) {
                    emit(mapped)
                    mapped = ArrayList(batch.size)
                }
            }
            // Before and after the sync marker, keep usable metadata and accept later updates:
            // the initial-sync waiter accepts skipped metadata, and retrying a
            // deterministic decode failure would repeat it. The next reconnect replaces all
            // reduced state. Ignore retired reports too; never retain or render their tokens.
            is HtspTransportEvent.MalformedMetadata -> synchronized(generationLock) {
                if (refreshLive()?.generation === event.generation) initialEpgIncomplete = true
            }
            is HtspTransportEvent.MetadataOverflow -> {
                if (mapped.isNotEmpty()) emit(mapped)
                // A repeated async-enable only resends EPG, not a complete snapshot. The
                // connection owner's existing backoff/reconnect path performs a full resync.
                retire(event.generation, GatewayConnectionFailure.METADATA_OVERFLOW)
                return@transform
            }
            is HtspTransportEvent.ConnectionFailure -> {
                if (mapped.isNotEmpty()) emit(mapped)
                return@transform
            }
            else -> Unit
        }
        if (mapped.isNotEmpty()) emit(mapped)
    }

    override suspend fun awaitConnectionFailure(generation: GatewayGeneration): GatewayConnectionFailure =
        coroutineScope {
            val source = htspGenerationFor(generation)
            val result = synchronized(generationLock) {
                failures.getOrPut(source) { CompletableDeferred() }
            }
            val termination = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val failure = when (val ended = connection.awaitTermination(source)) {
                    is HtspGenerationTermination.Failed ->
                        ended.failure.kind.toGatewayFailure()
                    HtspGenerationTermination.Disconnected -> disconnectedFailure(source) ?: return@launch
                    // Guard against future HTSP termination subtypes.
                    else -> GatewayConnectionFailure.TRANSPORT_UNAVAILABLE
                }
                retire(source, failure)
            }
            try {
                result.await()
            } finally {
                termination.cancel()
            }
        }

    override suspend fun connect(server: ServerConfiguration): GatewayConnectResult {
        val endpoint = when (val authentication = server.authentication) {
            ServerAuthentication.Anonymous -> HtspEndpoint(
                host = server.host,
                port = server.port,
            )
            is ServerAuthentication.Password -> HtspEndpoint(
                host = server.host,
                port = server.port,
                username = authentication.username,
                password = authentication.password,
            )
        }
        retireObservedGeneration()
        return when (val result = connection.connect(endpoint)) {
            is HtspConnectOutcome.Connected -> {
                synchronized(generationLock) {
                    ownedGeneration = result.connection.generation
                    // An idempotent connect can return the same still-live generation.
                    intentionallyEnded.remove(result.connection.generation)
                    if (retiredGenerations[result.connection.generation] == null) {
                        retiredGenerations.remove(result.connection.generation)
                    }
                    refreshLive()
                }
                GatewayConnectResult.Connected(connection = result.connection.toGatewayConnection())
            }
            is HtspConnectOutcome.Failed -> {
                synchronized(generationLock) { refreshLive() }
                GatewayConnectResult.Failed(failure = result.failure.kind.toGatewayFailure())
            }
        }
    }

    override suspend fun disconnect() {
        retireObservedGeneration()
        // ConnectionOwner joins its attempt worker before teardown; no new SDK attempt can race.
        // Null also clears a failed attempt's sticky Error (its token is not returned by connect).
        lastDisconnectChanged = connection.disconnect(expectedGeneration = null)
    }

    override suspend fun shutdown() {
        retireObservedGeneration()
        // Terminal owner-global close must also close an already-disconnected service.
        // Retain bounded no-op diagnostics rather than throwing on an idempotent false result.
        lastCloseChanged = connection.close(expectedGeneration = null)
    }

    /** Commits while the generation is live as last observed by the SDK, not under a library lock. */
    override fun <T> commitIfLive(
        generation: GatewayGeneration,
        block: () -> T,
    ): T? = synchronized(generationLock) {
        val live = refreshLive()
        if (live?.generation === htspGenerationFor(generation)) block() else null
    }

    override suspend fun retirementFailure(generation: GatewayGeneration): GatewayConnectionFailure? {
        val source = synchronized(generationLock) {
            val source = htspGenerationFor(generation)
            retiredGenerations[source]?.let { return it }
            if (refreshLive()?.generation === source) return null
            source
        }
        // Closed transport admission guarantees termination, but request-detected failures
        // can publish Error before the reader latches it. Await the generation's result,
        // never infer its cause from the conflated, unscoped connection state.
        val failure = when (val ended = connection.awaitTermination(source)) {
            is HtspGenerationTermination.Failed -> ended.failure.kind.toGatewayFailure()
            HtspGenerationTermination.Disconnected -> disconnectedFailure(source)
            // Guard against future HTSP termination subtypes.
            else -> GatewayConnectionFailure.TRANSPORT_UNAVAILABLE
        }
        retire(source, failure)
        return synchronized(generationLock) { retiredGenerations[source] }
    }

    /** Called only under generationLock; reads snapshots without making a suspending library call. */
    private fun refreshLive(): HtspLiveConnection? {
        val snapshot = (connection.connectionState.value as? HtspConnectionState.Connected)?.connection
        val previous = currentLive
        if (previous?.generation !== snapshot?.generation) {
            autorecChannels.clear()
            timerecChannels.clear()
            initialEpgThrough = null
            initialEpgIncomplete = false
        }
        if (previous != null && previous.generation !== snapshot?.generation) {
            if (!retiredGenerations.containsKey(previous.generation)) {
                retiredGenerations[previous.generation] = null
            }
        }
        currentLive = snapshot?.takeUnless { retiredGenerations.containsKey(it.generation) }
        observedLive = currentLive?.let { LiveObservation(it.generation, generationFor(it.generation)) }
        return currentLive
    }

    private fun retire(
        generation: HtspConnectionGeneration,
        failure: GatewayConnectionFailure? = null,
    ) = synchronized(generationLock) {
        if (retiredGenerations[generation] == null) {
            // Snapshot-only retirement has no cause. The first typed failure supplies it;
            // cleanup and later reports cannot replace that generation's first failure.
            if (failure != null || !retiredGenerations.containsKey(generation)) {
                retiredGenerations[generation] = failure
            }
        }
        if (failure != null) {
            failures.getOrPut(generation) { CompletableDeferred() }
                .complete(checkNotNull(retiredGenerations[generation]))
        }
        if (currentLive?.generation === generation) {
            currentLive = null
            observedLive = null
            initialEpgThrough = null
            initialEpgIncomplete = false
            autorecChannels.clear()
            timerecChannels.clear()
        }
    }

    private fun retireObservedGeneration() = synchronized(generationLock) {
        ownedGeneration?.let {
            intentionallyEnded[it] = true
            retire(it)
        }
    }

    private fun disconnectedFailure(source: HtspConnectionGeneration): GatewayConnectionFailure? =
        synchronized(generationLock) {
            if (intentionallyEnded[source] == true) null else GatewayConnectionFailure.TRANSPORT_UNAVAILABLE
        }

    private fun rememberRuleChannel(generation: HtspConnectionGeneration, message: HtspMetadataMessage) {
        fun remember(cache: RuleChannelCache, id: String, channel: HtspRecordingRuleChannel?) {
            synchronized(generationLock) {
                if (refreshLive()?.generation === generation) cache.remember(id, channel)
            }
        }
        // EPG/channel/DVR traffic never takes the rule-cache monitor or refreshes its snapshot.
        when (message) {
            is HtspAutorecEntryAddMessage -> remember(autorecChannels, message.entry.id, message.entry.channelId.toRuleChannel())
            is HtspAutorecEntryUpdateMessage -> remember(autorecChannels, message.entry.id, message.entry.channelId.toRuleChannel())
            is HtspAutorecEntryDeleteMessage -> remember(autorecChannels, message.id, null)
            is HtspTimerecEntryAddMessage -> remember(timerecChannels, message.entry.id, message.entry.channelId.toRuleChannel())
            is HtspTimerecEntryUpdateMessage -> remember(timerecChannels, message.entry.id, message.entry.channelId.toRuleChannel())
            is HtspTimerecEntryDeleteMessage -> remember(timerecChannels, message.id, null)
            else -> Unit
        }
    }

    /** Accessed only under generationLock; null channel is a retained deletion tombstone. */
    private class RuleChannelCache {
        private class Observation(val revision: Long, val channel: HtspRecordingRuleChannel?)
        private val entries = HashMap<String, Observation>()
        var revision: Long = 0
            private set

        fun clear() { entries.clear() }
        fun revision(id: String): Long = entries[id]?.revision ?: 0
        fun channel(id: String): HtspRecordingRuleChannel? = entries[id]?.channel
        fun remember(id: String, channel: HtspRecordingRuleChannel?) {
            entries[id] = Observation(++revision, channel)
        }

        fun writeBack(id: String, expectedRevision: Long, channel: HtspRecordingRuleChannel?) {
            val observed = entries[id]
            if (revision(id) == expectedRevision && (observed == null || observed.channel != null)) {
                remember(id, channel)
            }
        }

        fun seed(id: String, requestRevision: Long, channel: HtspRecordingRuleChannel) {
            // Create does not know its id yet. The watermark detects any intervening change to
            // the returned id, without rejecting a seed because an unrelated rule changed.
            val observed = entries[id]
            if (observed == null || (observed.revision <= requestRevision && observed.channel != null)) {
                remember(id, channel)
            }
        }
    }

    private sealed interface RuleChannelResolution {
        data object NotLive : RuleChannelResolution
        data object Unknown : RuleChannelResolution
        class Observed(val channel: HtspRecordingRuleChannel, val revision: Long) : RuleChannelResolution
    }

    private fun ruleUpdateChannel(
        generation: GatewayGeneration,
        id: String,
        requested: RecordingRuleChannel?,
        channels: RuleChannelCache,
    ): RuleChannelResolution = synchronized(generationLock) {
        if (refreshLive()?.generation !== htspGenerationFor(generation)) return@synchronized RuleChannelResolution.NotLive
        val channel = requested.toHtspChannel() ?: channels.channel(id)
            ?: return@synchronized RuleChannelResolution.Unknown
        RuleChannelResolution.Observed(channel, channels.revision(id))
    }

    override suspend fun enableInitialMetadata(
        generation: GatewayGeneration,
    ): GatewayResult<Unit> {
        val htspGeneration = htspGenerationFor(generation)
        if (commitIfLive(generation) { true } != true) return GatewayResult.TransportUnavailable
        val epgMaxTime =
            when (val serverTime = connection.getSysTime(expectedGeneration = htspGeneration)) {
                is HtspResult.Ok -> {
                    commitIfLive(generation) {
                        serverTimeEstimate.observe(generation, Instant.fromEpochSeconds(serverTime.value.timeEpochSeconds))
                    }
                    serverTime.value.timeEpochSeconds + epgCoveragePolicy.futureHorizon.inWholeSeconds
                }
                else -> return serverTime.toGatewayResult {}
            }
        // Capture before enabling: the completion marker may precede the reply.
        commitIfLive(generation) { initialEpgThrough = Instant.fromEpochSeconds(epgMaxTime) }
            ?: return GatewayResult.TransportUnavailable
        return connection.enableAsyncMetadataAwaitingInitialSync(
            epg = true,
            epgMaxTimeEpochSeconds = epgMaxTime,
            expectedGeneration = htspGeneration,
        ).toGatewayResult {}
    }

    override suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>> = connection.getEvents(
        channelId = channelId.value,
        maxTimeEpochSeconds = maxTime.epochSeconds,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response ->
        Collections.unmodifiableList(
            response.events.mapTo(ArrayList(), HtspEvent::toGatewayEpgQueryEvent),
        )
    }

    override suspend fun searchEpg(
        generation: GatewayGeneration,
        request: EpgSearchRequest,
    ): GatewayResult<List<GatewayEpgQueryEvent>> = connection.epgQuery(
        query = request.query,
        channelId = request.channelId?.value,
        tagId = request.tagId?.value,
        contentType = request.contentType,
        language = request.language,
        fullText = request.fullText,
        full = true,
        minDurationSeconds = request.minimumDuration?.inWholeSeconds,
        maxDurationSeconds = request.maximumDuration?.inWholeSeconds,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult { response ->
        val events = (response as? EpgQueryResponse.Events)?.events
            ?: return@toCheckedGatewayResult null
        Collections.unmodifiableList(
            events.mapTo(ArrayList(), HtspEvent::toGatewayEpgQueryEvent),
        )
    }

    override suspend fun getStreamProfiles(
        generation: GatewayGeneration,
    ): GatewayResult<List<StreamProfile>> = connection.getProfiles(
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult { response ->
        val profiles = response.profiles ?: return@toCheckedGatewayResult null
        val ids = HashSet<StreamProfileId>(profiles.size)
        val mapped = ArrayList<StreamProfile>(profiles.size)
        profiles.forEach { profile ->
            val id = try {
                StreamProfileId(profile.profileUuid)
            } catch (_: IllegalArgumentException) {
                return@toCheckedGatewayResult null
            }
            if (!ids.add(id)) return@toCheckedGatewayResult null
            mapped += StreamProfile(id, profile.name, profile.comment)
        }
        Collections.unmodifiableList(mapped)
    }

    override suspend fun getDvrConfigs(
        generation: GatewayGeneration,
    ): GatewayResult<List<DvrConfiguration>> = connection.getDvrConfigs(
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response ->
        Collections.unmodifiableList(
            (response.configurations ?: emptyList()).mapTo(ArrayList()) { configuration ->
                DvrConfiguration(
                    id = DvrConfigId(configuration.dvrConfigUuid),
                    name = configuration.name,
                    comment = configuration.comment,
                )
            },
        )
    }

    override suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace> = connection.getDiskSpace(
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response ->
        DvrDiskSpace(
            freeBytes = response.freeBytes,
            usedBytes = response.usedBytes,
            totalBytes = response.totalBytes,
        )
    }

    override suspend fun getDvrCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<List<DvrCutpoint>> = connection.getDvrCutpoints(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult { response ->
        val mapped = ArrayList<DvrCutpoint>(response.cutpoints?.size ?: 0)
        response.cutpoints.orEmpty().forEach { cutpoint ->
            mapped += cutpoint.toDvrCutpointOrNull() ?: return@toCheckedGatewayResult null
        }
        Collections.unmodifiableList(mapped)
    }

    override suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId> = connection.addDvrEntry(
        selector = request.schedule.toHtspSelector(),
        configName = request.configId?.value,
        language = request.language,
        title = request.title,
        subtitle = request.subtitle,
        summary = request.summary,
        description = request.description,
        ageRating = request.ageRating,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response ->
        DvrEntryId(response.entryId)
    }

    override suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
        configName: String?,
    ): GatewayResult<Unit> = connection.updateDvrEntry(
        entryId = id.value,
        channelId = update.channelId?.value,
        configName = configName,
        title = update.title,
        subtitle = update.subtitle,
        summary = update.summary,
        description = update.description,
        language = update.language,
        comment = update.comment,
        enabled = update.enabled,
        startEpochSeconds = update.start?.epochSeconds,
        stopEpochSeconds = update.stop?.epochSeconds,
        startExtraMinutes = update.startExtraMinutes?.inWholeMinutes,
        stopExtraMinutes = update.stopExtraMinutes?.inWholeMinutes,
        retentionDays = update.retentionDays,
        removalDays = update.removalDays,
        priority = update.priority?.wireValue,
        ageRating = update.ageRating,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun stopDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.stopDvrEntry(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun cancelDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.cancelDvrEntry(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun deleteDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.deleteDvrEntry(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): GatewayResult<AutorecRuleId> {
        val revision = commitIfLive(generation) { autorecChannels.revision }
            ?: return GatewayResult.TransportUnavailable
        return connection.addAutorecEntry(
            title = request.title,
            channel = request.channel.toHtspChannel(),
            minDurationSeconds = request.minDuration?.toDvrMutationSeconds(),
            maxDurationSeconds = request.maxDuration?.toDvrMutationSeconds(),
            fullText = request.fullText,
            mergeText = request.mergeText,
            duplicateDetection = request.duplicateDetection?.wireValue,
            maximumRecordingCount = request.maximumRecordingCount,
            broadcastType = request.broadcastType?.wireValue,
            startExtraMinutes = request.startExtraMinutes?.inWholeMinutes,
            stopExtraMinutes = request.stopExtraMinutes?.inWholeMinutes,
            seriesLinkUri = request.seriesLinkUri,
            approximateStartMinutesSinceMidnight = request.approximateStartMinutesSinceMidnight?.toDvrMinute(),
            startMinutesSinceMidnight = request.startMinutesSinceMidnight?.toDvrMinute(),
            startWindowEndMinutesSinceMidnight = request.startWindowEndMinutesSinceMidnight?.toDvrMinute(),
            enabled = request.enabled,
            retentionDays = request.retentionDays,
            removalDays = request.removalDays,
            priority = request.priority?.wireValue,
            name = request.name,
            comment = request.comment,
            directory = request.directory,
            configName = request.configId?.value,
            daysOfWeekMask = request.daysOfWeekMask?.toDvrDayMask(),
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult { response ->
            commitIfLive(generation) {
                autorecChannels.seed(response.id, revision, request.channel.toHtspChannel() ?: HtspRecordingRuleChannel.Any)
            }
            AutorecRuleId(response.id)
        }
    }

    override suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): GatewayResult<Unit> {
        val observed = when (val result = ruleUpdateChannel(generation, id.value, update.channel, autorecChannels)) {
            RuleChannelResolution.NotLive -> return GatewayResult.TransportUnavailable
            RuleChannelResolution.Unknown -> return GatewayResult.ObservationExpired
            is RuleChannelResolution.Observed -> result
        }
        return connection.updateAutorecEntry(
            id = id.value,
            channel = observed.channel,
            minDurationSeconds = update.minDuration?.toDvrMutationSeconds(),
            maxDurationSeconds = update.maxDuration?.toDvrMutationSeconds(),
            fullText = update.fullText,
            mergeText = update.mergeText,
            duplicateDetection = update.duplicateDetection?.wireValue,
            maximumRecordingCount = update.maximumRecordingCount,
            broadcastType = update.broadcastType?.wireValue,
            startExtraMinutes = update.startExtraMinutes?.inWholeMinutes,
            stopExtraMinutes = update.stopExtraMinutes?.inWholeMinutes,
            seriesLinkUri = update.seriesLinkUri,
            startMinutesSinceMidnight = update.startMinutesSinceMidnight?.toDvrMinute(),
            startWindowEndMinutesSinceMidnight = update.startWindowEndMinutesSinceMidnight?.toDvrMinute(),
            enabled = update.enabled,
            retentionDays = update.retentionDays,
            removalDays = update.removalDays,
            priority = update.priority?.wireValue,
            name = update.name,
            comment = update.comment,
            directory = update.directory,
            title = update.title,
            configName = update.configId?.value,
            daysOfWeekMask = update.daysOfWeekMask?.toDvrDayMask(),
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult {
            commitIfLive(generation) { autorecChannels.writeBack(id.value, observed.revision, observed.channel) }
            Unit
        }
    }

    override suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): GatewayResult<Unit> {
        val revision = commitIfLive(generation) { autorecChannels.revision(id.value) }
            ?: return GatewayResult.TransportUnavailable
        return connection.deleteAutorecEntry(
            id = id.value,
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult {
            commitIfLive(generation) { autorecChannels.writeBack(id.value, revision, null) }
            Unit
        }
    }

    override suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): GatewayResult<TimerecRuleId> {
        val revision = commitIfLive(generation) { timerecChannels.revision }
            ?: return GatewayResult.TransportUnavailable
        return connection.addTimerecEntry(
            title = request.title,
            channel = request.channel.toHtspChannel(),
            startMinutesSinceMidnight = request.startMinutesSinceMidnight?.toDvrMinute(),
            stopMinutesSinceMidnight = request.stopMinutesSinceMidnight?.toDvrMinute(),
            enabled = request.enabled,
            retentionDays = request.retentionDays,
            removalDays = request.removalDays,
            priority = request.priority?.wireValue,
            name = request.name,
            comment = request.comment,
            directory = request.directory,
            configName = request.configId?.value,
            daysOfWeekMask = request.daysOfWeekMask?.toDvrDayMask(),
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult { response ->
            commitIfLive(generation) {
                timerecChannels.seed(response.id, revision, request.channel.toHtspChannel() ?: HtspRecordingRuleChannel.Any)
            }
            TimerecRuleId(response.id)
        }
    }

    override suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): GatewayResult<Unit> {
        val observed = when (val result = ruleUpdateChannel(generation, id.value, update.channel, timerecChannels)) {
            RuleChannelResolution.NotLive -> return GatewayResult.TransportUnavailable
            RuleChannelResolution.Unknown -> return GatewayResult.ObservationExpired
            is RuleChannelResolution.Observed -> result
        }
        return connection.updateTimerecEntry(
            id = id.value,
            channel = observed.channel,
            startMinutesSinceMidnight = update.startMinutesSinceMidnight?.toDvrMinute(),
            stopMinutesSinceMidnight = update.stopMinutesSinceMidnight?.toDvrMinute(),
            enabled = update.enabled,
            retentionDays = update.retentionDays,
            removalDays = update.removalDays,
            priority = update.priority?.wireValue,
            name = update.name,
            comment = update.comment,
            directory = update.directory,
            title = update.title,
            configName = update.configId?.value,
            daysOfWeekMask = update.daysOfWeekMask?.toDvrDayMask(),
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult {
            commitIfLive(generation) { timerecChannels.writeBack(id.value, observed.revision, observed.channel) }
            Unit
        }
    }

    override suspend fun resetDvrResume(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.updateDvrEntry(
        entryId = id.value,
        // Omission has the same upstream meaning as DVR_PROGRESS_KEEP_PLAY_COUNT.
        playPositionSeconds = 0L,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun updateDvrWatchState(
        generation: GatewayGeneration,
        id: DvrEntryId,
        watched: Boolean,
    ): GatewayResult<Unit> = connection.updateDvrEntry(
        entryId = id.value,
        playCount = if (watched) 1L else 0L,
        playPositionSeconds = 0L,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun reportDvrProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): GatewayResult<Unit> = connection.updateDvrEntry(
        entryId = id.value,
        playCount = if (progress.markWatched) {
            DVR_PROGRESS_INCR_PLAY_COUNT
        } else {
            DVR_PROGRESS_KEEP_PLAY_COUNT
        },
        playPositionSeconds = progress.position.inWholeSeconds,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun loadArtwork(
        generation: GatewayGeneration,
        id: ArtworkId,
    ): GatewayResult<ByteArray> = artworkFiles.withPermit {
        loadArtworkFile(generation, id)
    }

    private suspend fun loadArtworkFile(
        generation: GatewayGeneration,
        id: ArtworkId,
    ): GatewayResult<ByteArray> {
        val htspGeneration = htspGenerationFor(generation)
        val opened = when (
            val opening = connection.fileOpen(
                file = "$ARTWORK_FILE_SELECTOR_PREFIX${id.value}",
                expectedGeneration = htspGeneration,
            ).toGatewayResult { response -> response }
        ) {
            is GatewayResult.Ok -> opening.value
            is GatewayResult.ServerRejected -> return opening
            GatewayResult.AccessDenied -> return GatewayResult.AccessDenied
            GatewayResult.ConnectionLimit -> return GatewayResult.ConnectionLimit
            GatewayResult.Timeout -> return GatewayResult.Timeout
            GatewayResult.TransportUnavailable -> return GatewayResult.TransportUnavailable
            GatewayResult.NotSupported -> return GatewayResult.NotSupported
            GatewayResult.ObservationExpired -> return GatewayResult.ObservationExpired
            GatewayResult.MalformedReply -> return GatewayResult.MalformedReply
        }

        val outcome = try {
            if (opened.sizeBytes != null && opened.sizeBytes !in 0L..MAX_ARTWORK_BYTES.toLong()) {
                GatewayResult.ServerRejected()
            } else {
                readArtwork(opened.id, opened.sizeBytes?.toInt(), htspGeneration)
            }
        } finally {
            withContext(NonCancellable) {
                connection.fileClose(
                    id = opened.id,
                    expectedGeneration = htspGeneration,
                ).toGatewayResult {}
            }
        }
        currentCoroutineContext().ensureActive()
        return outcome
    }

    private suspend fun readArtwork(
        handleId: Long,
        expectedSize: Int?,
        generation: HtspConnectionGeneration,
    ): GatewayResult<ByteArray> {
        if (expectedSize != null) {
            val destination = ByteArray(expectedSize)
            var offset = 0
            while (offset < destination.size) {
                val requested = minOf(ARTWORK_READ_CHUNK_BYTES, destination.size - offset)
                when (
                    val reading = connection.fileRead(
                        id = handleId,
                        sizeBytes = requested.toLong(),
                        offsetBytes = offset.toLong(),
                        expectedGeneration = generation,
                    ).toGatewayResult { response -> response.data }
                ) {
                    is GatewayResult.Ok -> {
                        val data = reading.value
                        if (data.size !in 1..requested) return GatewayResult.MalformedReply
                        data.copyInto(destination, offset)
                        offset += data.size
                    }
                    is GatewayResult.ServerRejected -> return reading
                    GatewayResult.AccessDenied -> return GatewayResult.AccessDenied
                    GatewayResult.ConnectionLimit -> return GatewayResult.ConnectionLimit
                    GatewayResult.Timeout -> return GatewayResult.Timeout
                    GatewayResult.TransportUnavailable -> return GatewayResult.TransportUnavailable
                    GatewayResult.NotSupported -> return GatewayResult.NotSupported
                    GatewayResult.ObservationExpired -> return GatewayResult.ObservationExpired
                    GatewayResult.MalformedReply -> return GatewayResult.MalformedReply
                }
            }
            return GatewayResult.Ok(destination)
        }

        val output = ByteArrayOutputStream()
        val chunk = ByteArray(ARTWORK_READ_CHUNK_BYTES)
        var offset = 0L
        while (true) {
            val requested = minOf(
                ARTWORK_READ_CHUNK_BYTES,
                MAX_ARTWORK_BYTES + 1 - output.size(),
            )
            val data = when (
                val reading = connection.fileRead(
                    id = handleId,
                    sizeBytes = requested.toLong(),
                    offsetBytes = offset,
                    expectedGeneration = generation,
                ).toGatewayResult { response -> response.data }
            ) {
                is GatewayResult.Ok -> reading.value
                is GatewayResult.ServerRejected -> return reading
                GatewayResult.AccessDenied -> return GatewayResult.AccessDenied
                GatewayResult.ConnectionLimit -> return GatewayResult.ConnectionLimit
                GatewayResult.Timeout -> return GatewayResult.Timeout
                GatewayResult.TransportUnavailable -> return GatewayResult.TransportUnavailable
                GatewayResult.NotSupported -> return GatewayResult.NotSupported
                GatewayResult.ObservationExpired -> return GatewayResult.ObservationExpired
                GatewayResult.MalformedReply -> return GatewayResult.MalformedReply
            }
            if (data.size > requested) return GatewayResult.MalformedReply
            if (data.size == 0) return GatewayResult.Ok(output.toByteArray())
            if (output.size() + data.size > MAX_ARTWORK_BYTES) return GatewayResult.ServerRejected()
            data.copyInto(chunk)
            output.write(chunk, 0, data.size)
            offset += data.size
        }
    }

    /**
     * Opens the stored file of one DVR entry using Tvheadend's `dvr/<id>` selector.
     *
     * The negotiated protocol version is snapshotted from the same live generation that opens the
     * handle, because only that version decides whether the matching close may keep the entry's
     * play count. A negative reported size is dropped rather than trusted as a length.
     */
    override suspend fun openRecordingFile(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<GatewayRecordingFile> {
        val htspGeneration = htspGenerationFor(generation)
        val protocolVersion = commitIfLive(generation) {
            currentLive?.protocolVersion
        }
        return connection.fileOpen(
            file = "$RECORDING_FILE_SELECTOR_PREFIX${id.value}",
            expectedGeneration = htspGeneration,
        ).toGatewayResult { response ->
            GatewayRecordingFile(
                handleId = response.id,
                sizeBytes = response.sizeBytes?.takeIf { size -> size >= 0L },
                protocolVersion = protocolVersion,
            )
        }
    }

    override suspend fun seekRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
    ): GatewayResult<Long> {
        require(position >= 0L) { "Recording seek position must not be negative" }
        return connection.fileSeek(
            id = file.handleId,
            offsetBytes = position,
            whence = FileSeekWhence.SET,
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult(FileSeekResponse::offsetBytes)
    }

    /**
     * Reads one bounded window at an absolute offset so a retried read cannot move a server cursor.
     *
     * A reply larger than the requested window is rejected instead of copied, because the binary
     * payload's own copy is bounded only by the destination array and would otherwise overwrite
     * bytes outside the caller's window.
     */
    override suspend fun readRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): GatewayResult<Int> {
        require(position >= 0L) { "Recording read position must not be negative" }
        require(destinationOffset in 0..destination.size) {
            "Recording read offset must lie inside the destination array"
        }
        require(length in 0..(destination.size - destinationOffset)) {
            "Recording read window must lie inside the destination array"
        }
        require(length <= MAX_RECORDING_READ_BYTES) {
            "Recording read must not exceed $MAX_RECORDING_READ_BYTES bytes"
        }
        if (length == 0) return GatewayResult.Ok(0)
        return connection.fileRead(
            id = file.handleId,
            sizeBytes = length.toLong(),
            offsetBytes = position,
            expectedGeneration = htspGenerationFor(generation),
        ).toCheckedGatewayResult { response ->
            response.data
                .takeIf { data -> data.size <= length }
                ?.copyInto(destination, destinationOffset)
        }
    }

    override suspend fun statRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
    ): GatewayResult<GatewayRecordingFileStat> = connection.fileStat(
        id = file.handleId,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult { response ->
        val size = response.sizeBytes
        val modified = response.modifiedAtEpochSeconds
        GatewayRecordingFileStat(size, modified).takeIf {
            (size == null) == (modified == null) && (size == null || size >= 0L)
        }
    }

    /**
     * Closes one recording handle without inflating the entry's play count.
     *
     * Tvheadend increments the play count on every close whose `playcount` field is absent, so a
     * plain close would count each extractor reopen as another play. The keep sentinel suppresses
     * that, but the field only exists from protocol 27, so an older server falls back to a plain
     * close rather than a request the transport would refuse. A DataSource close may be only an
     * extractor reopen, so it never carries a play position; playback checkpoints and final
     * position use the separate DVR progress command.
     */
    override suspend fun closeRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
    ): GatewayResult<Unit> = connection.fileClose(
        id = file.handleId,
        playCount = DVR_PROGRESS_KEEP_PLAY_COUNT.takeIf {
            (file.protocolVersion ?: 0) >= DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION
        },
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): GatewayResult<Unit> {
        val revision = commitIfLive(generation) { timerecChannels.revision(id.value) }
            ?: return GatewayResult.TransportUnavailable
        return connection.deleteTimerecEntry(
            id = id.value,
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult {
            commitIfLive(generation) { timerecChannels.writeBack(id.value, revision, null) }
            Unit
        }
    }

    override fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent> {
        val htspGeneration = htspGenerationFor(generation)
        return connection.subscriptionEvents(
            subscriptionId = id.value,
            expectedGeneration = htspGeneration,
        ).transformWhile { event ->
            val mapped = try {
                if (event is HtspSubscriptionEvent.Timeshift) {
                    SubscriptionEvent.Timeshift(
                        full = event.message.full.toWireFlag(),
                        shift = event.message.shift,
                        start = event.message.start,
                        end = event.message.end,
                        speed = event.message.speed,
                        estimatedServerTime = estimatedServerTime(generation),
                    )
                } else event.toGatewayEvent()
            } catch (_: IllegalArgumentException) {
                SubscriptionEvent.Terminated(SubscriptionTermination.MALFORMED_MESSAGE)
            }
            mapped?.let { emit(it) }
            mapped !is SubscriptionEvent.Terminated
        }
    }

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = subscribe(
        generation = generation,
        id = id,
        channelId = channelId,
        streamProfileUuid = null,
        timeshiftPeriod = timeshiftPeriod,
    )

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        streamProfileUuid: String?,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = subscribe(
        generation = generation,
        id = id,
        channelId = channelId,
        streamProfileUuid = streamProfileUuid,
        timeshiftPeriod = timeshiftPeriod,
        priority = LiveSubscriptionPriority.NORMAL,
    )

    /** Normal priority omits the weight so the server applies the stream profile default. */
    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        streamProfileUuid: String?,
        timeshiftPeriod: Duration,
        priority: LiveSubscriptionPriority,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = connection.subscribe(
        subscriptionId = id.value,
        channelId = channelId.value,
        profile = streamProfileUuid,
        weight = priority.subscribeWeight(),
        timeshiftPeriodSeconds = timeshiftPeriod.inWholeSeconds.takeIf { seconds -> seconds > 0L },
        expectedGeneration = htspGenerationFor(generation),
    ).toSubscriptionResult(SubscribeResponse::toGatewayConfirmation)

    /**
     * Maps one timeshift positioning request onto its exact HTSP command.
     *
     * SDK subscriptions never request the 90 kHz clock, so media coordinates are sent as
     * microseconds. Near-live positioning requires an ordered timeshift status and uses the
     * separate bounded command below.
     */
    override suspend fun skipSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> = when (target) {
        SubscriptionSeekTarget.Live -> SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)
        is SubscriptionSeekTarget.Absolute -> connection.subscriptionSkip(
            subscriptionId = id.value,
            position = SubscriptionSeekPosition.Time(target.position.inWholeMicroseconds),
            absolute = ABSOLUTE_SKIP_FLAG,
            expectedGeneration = htspGenerationFor(generation),
        ).toSubscriptionResult {}
        is SubscriptionSeekTarget.Relative -> connection.subscriptionSkip(
            subscriptionId = id.value,
            position = SubscriptionSeekPosition.Time(target.offset.inWholeMicroseconds),
            absolute = RELATIVE_SKIP_FLAG,
            expectedGeneration = htspGenerationFor(generation),
        ).toSubscriptionResult {}
    }

    override suspend fun skipSubscriptionNearLive(
        generation: GatewayGeneration,
        id: SubscriptionId,
        status: SubscriptionEvent.Timeshift,
        marginSeconds: Long,
    ): SubscriptionOperationResult<Unit> {
        val expectedGeneration = htspGenerationFor(generation)
        val sourceStatus = try {
            HtspTimeshiftStatusMessage(
                subscriptionId = id.value,
                full = status.full != 0L,
                shift = status.shift,
                start = status.start,
                end = status.end,
                speed = status.speed,
            )
        } catch (_: IllegalArgumentException) {
            return SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)
        }
        return try {
            connection.subscriptionSkipNearLive(
                status = sourceStatus,
                clock = SubscriptionTimestampClock.MICROSECONDS,
                marginSeconds = marginSeconds,
                expectedGeneration = expectedGeneration,
            ).toSubscriptionResult {}
        } catch (_: IllegalArgumentException) {
            SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)
        }
    }

    override suspend fun speedSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        speed: Int,
    ): SubscriptionOperationResult<Unit> = connection.subscriptionSpeed(
        subscriptionId = id.value,
        speed = speed,
        expectedGeneration = htspGenerationFor(generation),
    ).toSubscriptionResult {}

    /** A zero weight restores the stream profile default, matching a subscribe without weight. */
    override suspend fun changeSubscriptionPriority(
        generation: GatewayGeneration,
        id: SubscriptionId,
        priority: LiveSubscriptionPriority,
    ): SubscriptionOperationResult<Unit> = connection.subscriptionChangeWeight(
        subscriptionId = id.value,
        weight = when (priority) {
            LiveSubscriptionPriority.NORMAL -> PROFILE_DEFAULT_SUBSCRIPTION_WEIGHT
            LiveSubscriptionPriority.YIELD -> YIELD_SUBSCRIPTION_WEIGHT
        },
        expectedGeneration = htspGenerationFor(generation),
    ).toSubscriptionResult {}

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit> = connection.unsubscribe(
        subscriptionId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toSubscriptionResult {}

    private fun HtspLiveConnection.toGatewayConnection(): GatewayConnection = GatewayConnection(
        generation = generationFor(generation),
        protocolVersion = protocolVersion,
        dvrAccess = dvrAccess,
        serverFacts = GatewayServerFacts(
            serverName = serverFacts.serverName,
            serverVersion = serverFacts.serverVersion,
            webRoot = serverFacts.webRoot,
            language = serverFacts.language,
            serverCapabilities = serverFacts.serverCapabilities,
            apiVersion = serverFacts.apiVersion,
            admin = serverFacts.admin,
            streaming = serverFacts.streaming,
            dvr = serverFacts.dvr,
            failedDvr = serverFacts.failedDvr,
            anonymous = serverFacts.anonymous,
            limitAll = serverFacts.limitAll,
            limitDvr = serverFacts.limitDvr,
            limitStreaming = serverFacts.limitStreaming,
            uiLevel = serverFacts.uiLevel,
            uiLanguage = serverFacts.uiLanguage,
        ),
    )

    private fun generationFor(generation: HtspConnectionGeneration): GatewayGeneration =
        synchronized(generationLock) {
            gatewayGenerations[generation]?.get() ?: GatewayGeneration().also { gatewayGeneration ->
                gatewayGenerations[generation] = WeakReference(gatewayGeneration)
                htspGenerations[gatewayGeneration] = generation
            }
        }

    private fun htspGenerationFor(generation: GatewayGeneration): HtspConnectionGeneration =
        synchronized(generationLock) {
            requireNotNull(htspGenerations[generation]) { "Unknown gateway generation" }
        }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class, InternalCoroutinesApi::class)
private class MappingStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        var previous: Any? = UnsetState
        source.collect { value ->
            val mapped = transform(value)
            if (previous === UnsetState || previous != mapped) {
                previous = mapped
                collector.emit(mapped)
            }
        }
    }

    private data object UnsetState
}

private fun HtspConnectionState.toGatewayState(): GatewayState = when (this) {
    HtspConnectionState.Disconnected -> GatewayState.Disconnected
    // Shutdown has always presented as disconnected; terminal ownership stays in ConnectionOwner.
    HtspConnectionState.Closed -> GatewayState.Disconnected
    is HtspConnectionState.Connecting -> GatewayState.Connecting
    is HtspConnectionState.Connected -> GatewayState.Connected
    is HtspConnectionState.Error -> GatewayState.Failed
}

private fun HtspTransportFailureKind.toGatewayFailure(): GatewayConnectionFailure = when (this) {
    HtspTransportFailureKind.AUTHENTICATION_REJECTED ->
        GatewayConnectionFailure.AUTHENTICATION_REJECTED
    HtspTransportFailureKind.HOST_NOT_FOUND,
    HtspTransportFailureKind.CONNECTION_REFUSED,
    HtspTransportFailureKind.CONNECTION_TIMEOUT,
    -> GatewayConnectionFailure.SERVER_UNREACHABLE
    HtspTransportFailureKind.NETWORK_UNREACHABLE -> GatewayConnectionFailure.NETWORK_UNAVAILABLE
    HtspTransportFailureKind.INCOMPATIBLE_SERVER -> GatewayConnectionFailure.INCOMPATIBLE_SERVER
    HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION -> GatewayConnectionFailure.UNSUPPORTED_SERVER_VERSION
    HtspTransportFailureKind.TRANSPORT_UNAVAILABLE,
    HtspTransportFailureKind.SUPERSEDED,
    HtspTransportFailureKind.CLOSED,
    ->
        GatewayConnectionFailure.TRANSPORT_UNAVAILABLE
}

private inline fun <T, R> HtspResult<T>.toGatewayResult(
    transform: (T) -> R,
): GatewayResult<R> = when (this) {
    is HtspResult.Ok -> GatewayResult.Ok(transform(value))
    is HtspResult.ServerError -> GatewayResult.ServerRejected(serverMessage)
    HtspResult.MalformedReply -> GatewayResult.MalformedReply
    HtspResult.AccessDenied -> GatewayResult.AccessDenied
    HtspResult.ConnectionLimit -> GatewayResult.ConnectionLimit
    HtspResult.Timeout -> GatewayResult.Timeout
    HtspResult.TransportUnavailable -> GatewayResult.TransportUnavailable
    HtspResult.NotSupported -> GatewayResult.NotSupported
}

/** Maps a reply whose payload must still pass a local acceptance check before it is trusted. */
private inline fun <T, R : Any> HtspResult<T>.toCheckedGatewayResult(
    transform: (T) -> R?,
): GatewayResult<R> = when (this) {
    is HtspResult.Ok -> transform(value)?.let { transformed -> GatewayResult.Ok(transformed) }
        ?: GatewayResult.MalformedReply
    is HtspResult.ServerError -> GatewayResult.ServerRejected(serverMessage)
    HtspResult.MalformedReply -> GatewayResult.MalformedReply
    HtspResult.AccessDenied -> GatewayResult.AccessDenied
    HtspResult.ConnectionLimit -> GatewayResult.ConnectionLimit
    HtspResult.Timeout -> GatewayResult.Timeout
    HtspResult.TransportUnavailable -> GatewayResult.TransportUnavailable
    HtspResult.NotSupported -> GatewayResult.NotSupported
}

private fun HtspDvrCutpoint.toDvrCutpointOrNull(): DvrCutpoint? {
    if (endMs <= startMs) return null
    return DvrCutpoint(
        start = startMs.milliseconds,
        end = endMs.milliseconds,
        action = when (type) {
            0L -> DvrCutpointAction.CUT
            1L -> DvrCutpointAction.MUTE
            2L -> DvrCutpointAction.SCENE_MARKER
            3L -> DvrCutpointAction.COMMERCIAL_BREAK
            else -> DvrCutpointAction.UNKNOWN
        },
    )
}

private fun DvrSchedule.toHtspSelector(): AddDvrEntrySelector = when (this) {
    is DvrSchedule.Programme -> AddDvrEntrySelector.Event(eventId.value)
    is DvrSchedule.ExplicitTime -> AddDvrEntrySelector.ExplicitChannelTime(
        channelId = channelId.value,
        startEpochSeconds = start.epochSeconds,
        stopEpochSeconds = stop.epochSeconds,
    )
}

private fun RecordingRuleChannel?.toHtspChannel(): HtspRecordingRuleChannel? = when (this) {
    null -> null
    is RecordingRuleChannel.SpecificChannel -> HtspRecordingRuleChannel.Id(channelId.value)
    RecordingRuleChannel.AllChannels -> HtspRecordingRuleChannel.Any
}

private fun Long?.toRuleChannel(): HtspRecordingRuleChannel =
    this?.let(HtspRecordingRuleChannel::Id) ?: HtspRecordingRuleChannel.Any

private fun Boolean.toWireFlag(): Long = if (this) 1L else 0L

@OptIn(SubscriptionInfrastructureApi::class)
private inline fun <T, R> HtspResult<T>.toSubscriptionResult(
    transform: (T) -> R,
): SubscriptionOperationResult<R> = when (this) {
    is HtspResult.Ok -> SubscriptionOperationResult.Ok(transform(value))
    is HtspResult.ServerError -> SubscriptionOperationResult.Failed(TvheadendFailure.SERVER_REJECTED)
    HtspResult.MalformedReply -> SubscriptionOperationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE)
    HtspResult.AccessDenied -> SubscriptionOperationResult.Failed(TvheadendFailure.ACCESS_DENIED)
    HtspResult.ConnectionLimit -> SubscriptionOperationResult.Failed(TvheadendFailure.CONNECTION_LIMIT)
    HtspResult.Timeout -> SubscriptionOperationResult.Failed(TvheadendFailure.TIMEOUT)
    HtspResult.TransportUnavailable -> SubscriptionOperationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE)
    HtspResult.NotSupported -> SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)
}

private fun HtspMetadataMessage.toGatewayMetadata(
    generation: GatewayGeneration,
    epgThrough: Instant? = null,
): MetadataEvent? = when (this) {
    is HtspChannelAddMessage -> MetadataEvent.ChannelAdded(
        generation = generation,
        channel = channel.toGatewayChannel(),
    )
    is HtspChannelUpdateMessage -> MetadataEvent.ChannelUpdated(
        generation = generation,
        channel = channel.toGatewayChannel(),
    )
    is HtspChannelNowNextMessage -> MetadataEvent.ChannelNowNextUpdated(
        generation,
        ChannelId(channelId), EventId(currentEventId), EventId(nextEventId),
    )
    is HtspChannelDeleteMessage -> MetadataEvent.ChannelDeleted(
        generation = generation,
        channelId = ChannelId(channelId),
    )
    is HtspTagAddMessage -> MetadataEvent.TagAdded(
        generation = generation,
        tag = tag.toGatewayTag(),
    )
    is HtspTagUpdateMessage -> MetadataEvent.TagUpdated(
        generation = generation,
        tag = tag.toGatewayTag(),
    )
    is HtspTagDeleteMessage -> MetadataEvent.TagDeleted(
        generation = generation,
        tagId = TagId(tagId),
    )
    HtspInitialSyncCompletedMessage -> MetadataEvent.InitialSyncCompleted(generation, epgThrough)
    is HtspEventAddMessage -> MetadataEvent.EventAdded(generation, event.toGatewayEpgEvent())
    is HtspEventUpdateMessage -> MetadataEvent.EventUpdated(generation, event.toGatewayEpgEvent())
    is HtspEventDeleteMessage -> MetadataEvent.EventDeleted(generation, EventId(eventId))
    is HtspDvrEntryAddMessage -> MetadataEvent.DvrEntryAdded(generation, entry.toGatewayDvrEntry())
    is HtspDvrEntryUpdateMessage -> MetadataEvent.DvrEntryUpdated(
        generation = generation,
        entry = entry.toGatewayDvrEntry(),
    )
    is HtspDvrEntryStatsMessage -> MetadataEvent.DvrEntryStatsUpdated(
        generation,
        GatewayDvrStats(
            id = DvrEntryId(entryId), uuid = entryUuid, state = state.toDvrEntryState(),
            duplicate = duplicate?.toWireFlag(), failure = error.toDvrFailure(),
            subscriptionError = subscriptionError.toDvrSubscriptionError(),
            streamErrors = streamErrors, dataErrors = dataErrors, dataSizeBytes = dataSizeBytes,
        ),
    )
    is HtspDvrEntryDeleteMessage -> MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(entryId))
    is HtspAutorecEntryAddMessage -> MetadataEvent.AutorecRuleAdded(generation, entry.toGatewayAutorecRule())
    is HtspAutorecEntryUpdateMessage ->
        MetadataEvent.AutorecRuleUpdated(generation, entry.toGatewayAutorecRule())
    is HtspAutorecEntryDeleteMessage ->
        MetadataEvent.AutorecRuleDeleted(generation, AutorecRuleId(id))
    is HtspTimerecEntryAddMessage -> MetadataEvent.TimerecRuleAdded(generation, entry.toGatewayTimerecRule())
    is HtspTimerecEntryUpdateMessage ->
        MetadataEvent.TimerecRuleUpdated(generation, entry.toGatewayTimerecRule())
    is HtspTimerecEntryDeleteMessage ->
        MetadataEvent.TimerecRuleDeleted(generation, TimerecRuleId(id))
    else -> null
}

private fun HtspEvent.toGatewayEpgEvent(): GatewayEpgEvent = GatewayEpgEvent(
    id = EventId(eventId),
    channelId = channelId?.let(::ChannelId),
    start = Instant.fromEpochSeconds(startEpochSeconds),
    stop = Instant.fromEpochSeconds(stopEpochSeconds),
    title = title,
    subtitle = subtitle,
    summary = summary,
    description = description,
    categories = categories,
    keywords = keywords,
    credits = credits?.entries?.map { EpgCredit(it.name, EpgCreditRole.of(it.role)) },
    seriesLinkUri = seriesLinkUri,
    episodeUri = episodeUri,
    contentType = contentType,
    ageRating = ageRating,
    ratingLabel = ratingLabel,
    ratingIcon = ratingIcon,
    ratingAuthority = ratingAuthority,
    ratingCountry = ratingCountry,
    starRating = starRating,
    copyrightYear = copyrightYear,
    firstAired = firstAiredEpochSeconds?.let { Instant.fromEpochSeconds(it) },
    isNew = isNew,
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    episodeOnscreen = episodeOnscreen,
    image = image,
    dvrEntryId = dvrId?.let(::DvrEntryId),
    nextEventId = nextEventId?.let(::EventId),
)

private fun HtspEvent.toGatewayEpgQueryEvent(): GatewayEpgQueryEvent =
    toGatewayEpgEvent().toGatewayEpgQueryEvent()

private fun GatewayEpgEvent.toGatewayEpgQueryEvent(): GatewayEpgQueryEvent = GatewayEpgQueryEvent(
    id = id,
    channelId = channelId,
    start = start,
    stop = stop,
    title = title,
    subtitle = subtitle,
    summary = summary,
    description = description,
    categories = categories,
    keywords = keywords,
    credits = credits,
    seriesLinkUri = seriesLinkUri,
    episodeUri = episodeUri,
    contentType = contentType,
    ageRating = ageRating,
    ratingLabel = ratingLabel,
    ratingIcon = ratingIcon,
    ratingAuthority = ratingAuthority,
    ratingCountry = ratingCountry,
    starRating = starRating,
    copyrightYear = copyrightYear,
    firstAired = firstAired,
    isNew = isNew,
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    episodeOnscreen = episodeOnscreen,
    image = image,
    dvrEntryId = dvrEntryId,
    nextEventId = nextEventId,
)

private fun HtspChannel.toGatewayChannel(): GatewayChannelMetadata =
    GatewayChannelMetadata(
        id = ChannelId(channelId),
        name = channelName,
        uuid = channelUuid,
        number = channelNumber,
        numberMinor = channelNumberMinor,
        icon = channelIcon,
        currentEventId = currentEventId?.let(::EventId),
        nextEventId = nextEventId?.let(::EventId),
        services = services?.map(HtspChannelService::toGatewayService),
        tagIds = tagIds?.map(::TagId),
    )

private fun HtspChannelService.toGatewayService(): GatewayChannelService = GatewayChannelService(
    name = name,
    type = type,
    content = content,
    conditionalAccessId = conditionalAccessId,
    conditionalAccessName = conditionalAccessName,
    providerName = providerName,
)

private fun HtspTag.toGatewayTag(): GatewayTagMetadata = GatewayTagMetadata(
    id = TagId(tagId),
    name = tagName,
    uuid = tagUuid,
    index = tagIndex,
    icon = tagIcon,
    titledIcon = tagTitledIcon.toFlag(),
    channelIds = channelIds?.map(::ChannelId),
)

private fun Long?.toFlag(): Boolean? = this?.let { it != 0L }

private fun HtspDvrEntry.toGatewayDvrEntry(): GatewayDvrEntry = GatewayDvrEntry(
    id = DvrEntryId(entryId),
    uuid = entryUuid,
    enabled = enabled,
    channelId = channelId?.let(::ChannelId),
    channelName = channelName,
    eventId = eventId?.let(::EventId),
    autorecRuleId = autorecEntryUuid?.let(::AutorecRuleId),
    timerecRuleId = timerecEntryUuid?.let(::TimerecRuleId),
    start = startEpochSeconds?.let(Instant::fromEpochSeconds),
    stop = stopEpochSeconds?.let(Instant::fromEpochSeconds),
    startExtraMinutes = startExtraMinutes,
    stopExtraMinutes = stopExtraMinutes,
    retentionDays = retentionDays,
    removalDays = removalDays,
    priority = priority,
    contentType = contentType,
    ageRating = ageRating,
    ratingLabel = ratingLabel,
    ratingIcon = ratingIcon,
    ratingAuthority = ratingAuthority,
    ratingCountry = ratingCountry,
    playCount = playCount,
    playPosition = playPositionSeconds?.seconds,
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeOnscreen = episodeOnscreen,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    title = title,
    description = description,
    summary = summary,
    subtitle = subtitle,
    owner = owner,
    creator = creator,
    comment = comment,
    image = image,
    fanartImage = fanartImage,
    copyrightYear = copyrightYear,
    files = files?.map(HtspDvrRecordingFile::toGatewayFile),
    path = path,
    configId = dvrConfigUuid?.let(::DvrConfigId),
    duplicate = duplicate?.toWireFlag(),
    state = state.toDvrEntryState(),
    failure = error.toDvrFailure(),
    subscriptionError = subscriptionError.toDvrSubscriptionError(),
    streamErrors = streamErrors,
    dataErrors = dataErrors,
    dataSizeBytes = dataSizeBytes,
)

private fun HtspDvrRecordingFile.toGatewayFile(): GatewayDvrRecordingFile = GatewayDvrRecordingFile(
    path = path,
    start = startEpochSeconds?.let(Instant::fromEpochSeconds),
    stop = stopEpochSeconds?.let(Instant::fromEpochSeconds),
    sizeBytes = sizeBytes,
    streams = info?.streams?.map { stream ->
        at.bernhardberger.tvheadend.sdk.core.DvrRecordingStream(
            type = stream.type?.toGatewayStreamType() ?: SubscriptionStreamType.UNKNOWN,
            language = stream.language,
            width = stream.width?.takeIf { it in 1L..Int.MAX_VALUE }?.toInt(),
            height = stream.height?.takeIf { it in 1L..Int.MAX_VALUE }?.toInt(),
        )
    },
)

private fun HtspAutorecEntry.toGatewayAutorecRule(): GatewayAutorecRule = GatewayAutorecRule(
    id = AutorecRuleId(id),
    enabled = enabled,
    maxDuration = maxDurationSeconds.seconds,
    minDuration = minDurationSeconds.seconds,
    retentionDays = retentionDays,
    removalDays = removalDays,
    daysOfWeekMask = daysOfWeekMask,
    approximateStartMinutesSinceMidnight = approximateStartMinutesSinceMidnight,
    startMinutesSinceMidnight = startMinutesSinceMidnight,
    startWindowEndMinutesSinceMidnight = startWindowEndMinutesSinceMidnight,
    priority = priority,
    startExtraMinutes = startExtraMinutes,
    stopExtraMinutes = stopExtraMinutes,
    duplicateDetection = duplicateDetection,
    maximumRecordingCount = maximumRecordingCount,
    broadcastType = broadcastType,
    comment = comment,
    title = title,
    fullText = fullText,
    mergeText = mergeText,
    name = name,
    directory = directory,
    owner = owner,
    creator = creator,
    channelId = channelId?.let(::ChannelId),
    seriesLinkUri = seriesLinkUri,
    configId = dvrConfigUuid?.let(::DvrConfigId),
)

private fun HtspTimerecEntry.toGatewayTimerecRule(): GatewayTimerecRule = GatewayTimerecRule(
    id = TimerecRuleId(id),
    enabled = enabled,
    name = name,
    title = title,
    channelId = channelId?.let(::ChannelId),
    startMinutesSinceMidnight = startMinutesSinceMidnight,
    stopMinutesSinceMidnight = stopMinutesSinceMidnight,
    daysOfWeekMask = daysOfWeekMask,
    priority = priority,
    retentionDays = retentionDays,
    removalDays = removalDays,
    directory = directory,
    owner = owner,
    creator = creator,
    configId = dvrConfigUuid?.let(::DvrConfigId),
    comment = comment,
)

private fun String.toDvrEntryState(): DvrEntryState = when (this) {
    "scheduled" -> DvrEntryState.SCHEDULED
    "recording" -> DvrEntryState.RECORDING
    "completed" -> DvrEntryState.COMPLETED
    "missed" -> DvrEntryState.MISSED
    "invalid" -> DvrEntryState.INVALID
    else -> DvrEntryState.UNKNOWN
}

private fun String?.toDvrFailure(): GatewayDvrFailure? = when (this) {
    null -> null
    "none" -> GatewayDvrFailure.NONE
    "File missing" -> GatewayDvrFailure.FILE_MISSING
    "Aborted by user" -> GatewayDvrFailure.ABORTED
    else -> GatewayDvrFailure.PRESENT
}

private fun String?.toDvrSubscriptionError(): DvrSubscriptionError? = when (this) {
    null -> null
    "No service assigned to channel" -> DvrSubscriptionError.NO_SERVICE
    "Invalid service" -> DvrSubscriptionError.INVALID_SERVICE
    else -> toSubscriptionIssue().toDvrSubscriptionError()
}

internal fun SubscriptionIssue?.toDvrSubscriptionError(): DvrSubscriptionError = when (this) {
    SubscriptionIssue.NO_INPUT -> DvrSubscriptionError.BAD_SIGNAL
    SubscriptionIssue.NO_FREE_ADAPTER -> DvrSubscriptionError.NO_FREE_ADAPTER
    SubscriptionIssue.SCRAMBLED -> DvrSubscriptionError.SCRAMBLED
    SubscriptionIssue.BAD_SIGNAL -> DvrSubscriptionError.BAD_SIGNAL
    SubscriptionIssue.TUNING_FAILED -> DvrSubscriptionError.TUNING_FAILED
    SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN -> DvrSubscriptionError.SUBSCRIPTION_OVERRIDDEN
    SubscriptionIssue.MUX_NOT_ENABLED -> DvrSubscriptionError.MUX_NOT_ENABLED
    SubscriptionIssue.INVALID_TARGET -> DvrSubscriptionError.INVALID_TARGET
    SubscriptionIssue.USER_ACCESS -> DvrSubscriptionError.USER_ACCESS
    SubscriptionIssue.USER_LIMIT -> DvrSubscriptionError.USER_LIMIT
    SubscriptionIssue.WEAK_STREAM -> DvrSubscriptionError.WEAK_STREAM
    SubscriptionIssue.NO_DISK_SPACE -> DvrSubscriptionError.NO_DISK_SPACE
    SubscriptionIssue.UNKNOWN, null -> DvrSubscriptionError.UNKNOWN
    else -> DvrSubscriptionError.UNKNOWN
}

private fun HtspSubscriptionEvent.toGatewayEvent(): SubscriptionEvent? = when (this) {
    is HtspSubscriptionEvent.Started -> message.toGatewayEvent()
    is HtspSubscriptionEvent.Packet -> packet.toGatewayEvent()
    is HtspSubscriptionEvent.Skipped -> message.toGatewayEvent()
    is HtspSubscriptionEvent.Stopped -> SubscriptionEvent.Stopped(
        condition = subscriptionCondition(message.status, message.subscriptionError),
        issue = subscriptionIssue(message.status, message.subscriptionError),
    )
    is HtspSubscriptionEvent.Status -> SubscriptionEvent.Status(
        condition = subscriptionCondition(message.status, message.subscriptionError),
        issue = subscriptionIssue(message.status, message.subscriptionError),
    )
    is HtspSubscriptionEvent.Grace -> SubscriptionEvent.Grace(message.graceTimeoutSeconds)
    is HtspSubscriptionEvent.Speed -> SubscriptionEvent.Speed(message.speed)
    is HtspSubscriptionEvent.Timeshift -> SubscriptionEvent.Timeshift(
        full = message.full.toWireFlag(),
        shift = message.shift,
        start = message.start,
        end = message.end,
        speed = message.speed,
    )
    is HtspSubscriptionEvent.Queue -> SubscriptionEvent.Queue(
        packetCount = message.packetCount,
        byteCount = message.byteCount,
        delay = message.delayUs,
        bFrameDropCount = message.bFrameDropCount,
        pFrameDropCount = message.pFrameDropCount,
        iFrameDropCount = message.iFrameDropCount,
        errorCount = message.errorCount,
    )
    is HtspSubscriptionEvent.Signal -> SubscriptionEvent.Signal(
        relativeSnr = message.relativeSnr,
        absoluteSnr = message.absoluteSnr,
        relativeSignal = message.relativeSignal,
        absoluteSignal = message.absoluteSignal,
        bitErrorRate = message.bitErrorRate,
        uncorrectedBlockCount = message.uncorrectedBlockCount,
        frontendStatusReported = message.frontendStatus != null,
        frontendState = message.frontendStatus.toLiveFrontendState(),
    )
    is HtspSubscriptionEvent.Descramble -> message.toGatewayEvent()
    is HtspSubscriptionEvent.Dropped -> SubscriptionEvent.Dropped(count)
    is HtspSubscriptionEvent.Terminated -> SubscriptionEvent.Terminated(
        reason = reason.toGatewayTermination(),
    )
    else -> null
}

private fun HtspSubscriptionStartMessage.toGatewayEvent(): SubscriptionEvent.Started =
    SubscriptionEvent.Started(
        streams = streams?.map(HtspSubscriptionStream::toGatewayStream),
        codecMetadata = codecMetadata?.let(::HtspGatewayBinary),
        source = sourceInfo?.toGatewaySource(),
    )

private fun HtspSubscriptionSourceInfo.toGatewaySource(): LiveSubscriptionSource? =
    LiveSubscriptionSource.create(
        adapterName = adapter,
        muxName = mux,
        networkName = network,
        providerName = provider,
        serviceName = service,
    )

private fun String?.toLiveFrontendState(): LiveFrontendState? = when (this) {
    "GOOD" -> LiveFrontendState.create(
        signalDetected = true,
        partiallySynchronized = true,
        locked = true,
    )
    "BAD" -> LiveFrontendState.create(
        signalDetected = true,
        partiallySynchronized = true,
        locked = false,
    )
    "FAINT" -> LiveFrontendState.create(
        signalDetected = true,
        partiallySynchronized = false,
        locked = false,
    )
    "NONE" -> LiveFrontendState.create(
        signalDetected = false,
        partiallySynchronized = false,
        locked = false,
    )
    else -> null
}

private fun HtspSubscriptionStream.toGatewayStream(): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(streamIndex),
    type = streamType.toGatewayStreamType(),
    language = language,
    compositionId = compositionId,
    ancillaryId = ancillaryId,
    width = width,
    height = height,
    frameDuration = frameDurationUs,
    aspectNumerator = aspectNumerator,
    aspectDenominator = aspectDenominator,
    audioType = audioType,
    audioVersion = audioVersion,
    channelCount = channelCount,
    rate = sampleRate,
    rdsUecp = rdsUecp?.toWireFlag(),
)

private fun String.toGatewayStreamType(): SubscriptionStreamType = when (this) {
    "MPEG2VIDEO" -> SubscriptionStreamType.MPEG2_VIDEO
    "H264" -> SubscriptionStreamType.H264
    "HEVC", "H265" -> SubscriptionStreamType.H265
    "AAC" -> SubscriptionStreamType.AAC
    "AC3" -> SubscriptionStreamType.AC3
    "EAC3" -> SubscriptionStreamType.EAC3
    "MPEG2AUDIO" -> SubscriptionStreamType.MPEG2_AUDIO
    "DVBSUB" -> SubscriptionStreamType.DVB_SUBTITLE
    "TEXTSUB" -> SubscriptionStreamType.TEXT_SUBTITLE
    "TELETEXT" -> SubscriptionStreamType.TELETEXT
    else -> SubscriptionStreamType.UNKNOWN
}

private fun HtspMuxPacketMessage.toGatewayEvent(): SubscriptionEvent.Packet =
    SubscriptionEvent.Packet(
        frameType = when (frameType) {
            HtspFrameType.B -> MuxFrameType.B
            HtspFrameType.I -> MuxFrameType.I
            HtspFrameType.P -> MuxFrameType.P
            else -> MuxFrameType.UNKNOWN
        },
        streamIndex = StreamIndex(streamIndex),
        decodingTimeUs = decodingTimeUs,
        presentationTimeUs = presentationTimeUs,
        durationUs = durationUs,
        payload = HtspGatewayBinary(payload),
    )

private fun HtspSubscriptionSkipMessage.toGatewayEvent(): SubscriptionEvent.Skipped =
    SubscriptionEvent.Skipped(
        absolute = absolute,
        outcome = when (error) {
            null, false -> SkipOutcome.ACCEPTED
            true -> SkipOutcome.REJECTED
        },
        time = time,
        sizeBytes = sizeBytes,
    )

private fun HtspDescrambleInfoMessage.toGatewayEvent(): SubscriptionEvent.Descramble =
    SubscriptionEvent.Descramble(
        pid = pid,
        conditionalAccessId = conditionalAccessId,
        providerId = providerId,
        ecmTime = ecmTime,
        hopCount = hopCount,
    )

private fun HtspSubscriptionTermination.toGatewayTermination(): SubscriptionTermination =
    when (this) {
        HtspSubscriptionTermination.GENERATION_LOST -> SubscriptionTermination.GENERATION_LOST
        HtspSubscriptionTermination.REMOTE_EOF -> SubscriptionTermination.REMOTE_EOF
        HtspSubscriptionTermination.IO_FAILURE -> SubscriptionTermination.IO_FAILURE
        HtspSubscriptionTermination.FRAMING_FAILURE -> SubscriptionTermination.FRAMING_FAILURE
        HtspSubscriptionTermination.MALFORMED_MESSAGE -> SubscriptionTermination.MALFORMED_MESSAGE
        HtspSubscriptionTermination.TIMEOUT -> SubscriptionTermination.TIMEOUT
        HtspSubscriptionTermination.LOCAL_RETIREMENT -> SubscriptionTermination.LOCAL_RETIREMENT
        HtspSubscriptionTermination.PUBLICATION_FAILURE -> SubscriptionTermination.PUBLICATION_FAILURE
        HtspSubscriptionTermination.CONSUMER_OVERFLOW -> SubscriptionTermination.PUBLICATION_FAILURE
        HtspSubscriptionTermination.SUBSCRIBE_REJECTED -> SubscriptionTermination.SUBSCRIBE_REJECTED
        HtspSubscriptionTermination.INTERNAL_FAILURE -> SubscriptionTermination.INTERNAL_FAILURE
        else -> SubscriptionTermination.INTERNAL_FAILURE
    }

private fun subscriptionCondition(
    status: String?,
    error: String?,
): SubscriptionCondition = when {
    status != null && error != null -> SubscriptionCondition.STATUS_AND_ERROR_REPORTED
    status != null -> SubscriptionCondition.STATUS_REPORTED
    error != null -> SubscriptionCondition.ERROR_REPORTED
    else -> SubscriptionCondition.NO_DETAIL
}

private fun subscriptionIssue(status: String?, error: String?): SubscriptionIssue? {
    val issue = error.toSubscriptionIssue()
    return if (
        status == NO_INPUT_STATUS &&
        (issue == null || issue == SubscriptionIssue.BAD_SIGNAL || issue == SubscriptionIssue.UNKNOWN)
    ) {
        SubscriptionIssue.NO_INPUT
    } else {
        issue
    }
}

private fun String?.toSubscriptionIssue(): SubscriptionIssue? = when (this) {
    null -> null
    "noFreeAdapter" -> SubscriptionIssue.NO_FREE_ADAPTER
    "scrambled" -> SubscriptionIssue.SCRAMBLED
    "badSignal" -> SubscriptionIssue.BAD_SIGNAL
    "tuningFailed" -> SubscriptionIssue.TUNING_FAILED
    "subscriptionOverridden" -> SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN
    "muxNotEnabled" -> SubscriptionIssue.MUX_NOT_ENABLED
    "invalidTarget" -> SubscriptionIssue.INVALID_TARGET
    "userAccess" -> SubscriptionIssue.USER_ACCESS
    "userLimit" -> SubscriptionIssue.USER_LIMIT
    "weakStream" -> SubscriptionIssue.WEAK_STREAM
    "noDiskSpace" -> SubscriptionIssue.NO_DISK_SPACE
    else -> SubscriptionIssue.UNKNOWN
}

private const val RECORDING_FILE_SELECTOR_PREFIX = "dvr/"
private const val NO_INPUT_STATUS = "No input detected"
private const val ARTWORK_FILE_SELECTOR_PREFIX = "imagecache/"
private const val ARTWORK_READ_CHUNK_BYTES = 64 * 1024
private const val MAX_ARTWORK_BYTES = 16 * 1024 * 1024
private const val MAX_CONCURRENT_ARTWORK_FILES = 3
private const val ABSOLUTE_SKIP_FLAG = true
private const val RELATIVE_SKIP_FLAG = false
private const val PROFILE_DEFAULT_SUBSCRIPTION_WEIGHT = 0L

// Server SUBSCRIPTION_PRIO_MIN: the lowest weight still treated as a normal subscription.
private const val YIELD_SUBSCRIPTION_WEIGHT = 10L

private fun LiveSubscriptionPriority.subscribeWeight(): Long? = when (this) {
    LiveSubscriptionPriority.NORMAL -> null
    LiveSubscriptionPriority.YIELD -> YIELD_SUBSCRIPTION_WEIGHT
}
// Keep room for 65,536 small metadata/EPG events, but cap wire bodies at 32 MiB per collector.
// Decoded objects are additional heap: a 64 MiB wire allowance risks dominating a 192–256 MiB
// Android TV heap. This is a conservative burst allowance, not a full-sync size guarantee;
// oversized syncs still report overflow and back off. Subscription budgets remain unchanged.
internal val METADATA_BUFFER_OPTIONS: HtspEventBufferOptions = HtspEventBufferOptions(
    metadataQueueBytes = 32L * 1024 * 1024,
    metadataQueueEvents = 65_536,
    subscriptionQueueBytes = 8L * 1024 * 1024,
    subscriptionQueueEvents = 8192,
)

private fun SubscribeResponse.toGatewayConfirmation(): SubscriptionConfirmation =
    SubscriptionConfirmation(
        ninetyKhz = ninetyKhz,
        normalizedTimestamps = normalizedTimestamps,
        weight = weight,
        timeshiftPeriodSeconds = timeshiftPeriodSeconds,
    )

private class HtspGatewayBinary(
    private val binary: HtspBinary,
) : SubscriptionBinary {
    override val size: Int
        get() = binary.size

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int =
        binary.copyInto(destination, destinationOffset)

    override fun toString(): String = "SubscriptionBinary(<redacted>)"
}
