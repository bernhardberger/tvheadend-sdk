@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelId as SdkChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConnection
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionManager
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationFailure
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration

internal class PlaybackSessionChildren(
    private val gateway: ProtocolGateway,
    private val metadata: SessionMetadata,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock = Clock.System,
    private val epgSettings: EpgWorkerSettings = EpgWorkerSettings(),
) : SessionChildren {
    private val lock = Any()
    private var generation: GatewayGeneration? = null
    private var backgroundEnrichment: ActiveBackgroundEnrichment? = null
    private var subscriptions: SubscriptionManager? = null
    private var streamingAccess: CapabilityAccess? = null

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult {
        currentCoroutineContext().ensureActive()
        val admission = synchronized(lock) {
            val boundGeneration = generation ?: return SubscriptionOpenResult.NotReady
            val manager = subscriptions ?: return SubscriptionOpenResult.NotReady
            val access = streamingAccess ?: return SubscriptionOpenResult.NotReady
            LiveAdmission(boundGeneration, manager, access)
        }
        if (admission.streamingAccess == CapabilityAccess.DENIED) {
            if (!isCurrent(admission)) return SubscriptionOpenResult.NotReady
            return SubscriptionOpenResult.Failed(
                SubscriptionTerminalReason.OperationFailed(
                    SubscriptionOperationFailure.ACCESS_DENIED,
                ),
            )
        }
        return when (metadata.isKnownChannel(admission.generation, SdkChannelId(channelId.value))) {
            null -> SubscriptionOpenResult.NotReady
            false -> if (isCurrent(admission)) {
                SubscriptionOpenResult.Failed(
                    SubscriptionTerminalReason.OperationFailed(
                        SubscriptionOperationFailure.SERVER_REJECTED,
                    ),
                )
            } else {
                SubscriptionOpenResult.NotReady
            }
            true -> admission.manager.open(channelId, consumer, timeshiftPeriod)
        }
    }

    /**
     * Opens one recording file on the currently bound generation.
     *
     * The returned handle keeps that generation, so it stays usable for its own close even after a
     * newer generation is bound; every other operation on it then reports the changed connection.
     */
    override suspend fun openRecording(
        recordingId: RecordingId,
    ): RecordingFileResult<RecordingFile> {
        currentCoroutineContext().ensureActive()
        val bound = synchronized(lock) { generation }
            ?: return RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
        return gateway.openRecordingFile(bound, DvrEntryId(recordingId.value))
            .toRecordingFileResult { file -> GatewayRecordingFileHandle(gateway, bound, file) }
    }

    override suspend fun loadArtwork(artworkId: ArtworkId): ArtworkLoadResult {
        currentCoroutineContext().ensureActive()
        val bound = synchronized(lock) { generation }
            ?: return ArtworkLoadResult.Unavailable(ArtworkFailure.CONNECTION_CHANGED)
        return gateway.loadArtwork(bound, artworkId).toArtworkLoadResult()
    }

    override fun bindGeneration(generation: GatewayGeneration) {
        synchronized(lock) {
            check(backgroundEnrichment == null) { "Previous background enrichment is still active" }
            check(subscriptions == null) { "Previous subscription generation is still active" }
            this.generation = generation
            streamingAccess = null
            subscriptions = createSubscriptionManager(
                GatewaySubscriptionConnection(gateway, generation),
                dispatcher,
            )
        }
    }

    override fun startLiveAdmission(
        generation: GatewayGeneration,
        streamingAccess: CapabilityAccess,
    ): Boolean = synchronized(lock) {
        if (this.generation !== generation) {
            false
        } else {
            val manager = subscriptions ?: return@synchronized false
            this.streamingAccess = streamingAccess
            if (streamingAccess != CapabilityAccess.DENIED) {
                manager.startAdmission()
            }
            true
        }
    }

    override fun stopAdmission() {
        synchronized(lock) {
            streamingAccess = null
            subscriptions?.stopAdmission()
        }
    }

    override fun prepareBackgroundEnrichment(
        generation: GatewayGeneration,
        onDvrCapabilitiesChanged: suspend (SessionCapabilitiesSnapshot) -> Unit,
    ): Boolean {
        val active = synchronized(lock) {
            if (this.generation !== generation || backgroundEnrichment != null) return false
            val worker = EpgWorker(
                generation = generation,
                metadata = metadata,
                clock = clock,
                settings = epgSettings,
                queryEpg = gateway::queryEpg,
            )
            val rootJob = SupervisorJob()
            val backgroundScope = CoroutineScope(dispatcher + rootJob)
            val jobs = listOf(
                backgroundScope.launch(start = CoroutineStart.LAZY) {
                    runBackgroundEnrichment { worker.run() }
                },
                backgroundScope.launch(start = CoroutineStart.LAZY) {
                    runBackgroundEnrichment {
                        val snapshot = metadata.applyDvrConfigurations(
                            generation,
                            gateway.getDvrConfigs(generation),
                        )
                        snapshot?.let { onDvrCapabilitiesChanged(it) }
                    }
                },
                backgroundScope.launch(start = CoroutineStart.LAZY) {
                    runBackgroundEnrichment {
                        metadata.applyDvrDiskSpace(generation, gateway.getDiskSpace(generation))
                    }
                },
            )
            ActiveBackgroundEnrichment(generation, worker, rootJob, jobs).also {
                backgroundEnrichment = it
            }
        }
        if (!metadata.bindEpgCoverageRequester(generation, active.worker)) {
            synchronized(lock) {
                if (backgroundEnrichment === active) backgroundEnrichment = null
            }
            active.rootJob.cancel()
            return false
        }
        val stillOwned = synchronized(lock) { backgroundEnrichment === active }
        if (!stillOwned) {
            active.worker.stopAcceptingPriorities()
            metadata.clearEpgCoverageRequester(generation, active.worker)
            active.rootJob.cancel()
        }
        return stillOwned
    }

    override fun startBackgroundEnrichment(generation: GatewayGeneration): Boolean {
        val active = synchronized(lock) {
            backgroundEnrichment?.takeIf { enrichment -> enrichment.generation === generation }
        } ?: return false
        active.jobs.forEach(Job::start)
        return synchronized(lock) {
            backgroundEnrichment === active && active.rootJob.isActive
        }
    }

    override suspend fun cancelAndJoinBackgroundEnrichment() {
        val active = synchronized(lock) { backgroundEnrichment } ?: return
        withContext(NonCancellable) {
            active.worker.stopAcceptingPriorities()
            metadata.clearEpgCoverageRequester(active.generation, active.worker)
            active.rootJob.cancelAndJoin()
            synchronized(lock) {
                if (backgroundEnrichment === active) backgroundEnrichment = null
            }
        }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun closeAndJoinSubscriptions() {
        val manager = synchronized(lock) { subscriptions } ?: return
        var childCancellation: CancellationException? = null
        withContext(NonCancellable) {
            try {
                manager.closeAndJoin()
            } catch (cancellation: CancellationException) {
                childCancellation = cancellation
            } finally {
                synchronized(lock) {
                    if (subscriptions === manager) {
                        generation = null
                        subscriptions = null
                        streamingAccess = null
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        childCancellation?.let { throw it }
    }

    private fun isCurrent(admission: LiveAdmission): Boolean = synchronized(lock) {
        generation === admission.generation &&
            subscriptions === admission.manager &&
            streamingAccess == admission.streamingAccess
    }
}

private class LiveAdmission(
    internal val generation: GatewayGeneration,
    internal val manager: SubscriptionManager,
    internal val streamingAccess: CapabilityAccess,
)

private class ActiveBackgroundEnrichment(
    internal val generation: GatewayGeneration,
    internal val worker: EpgWorker,
    internal val rootJob: Job,
    internal val jobs: List<Job>,
)

private suspend fun runBackgroundEnrichment(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Auxiliary enrichment failure cannot make an otherwise ready session unavailable.
    }
}

internal class GatewaySubscriptionConnection(
    private val gateway: ProtocolGateway,
    private val generation: GatewayGeneration,
) : SubscriptionConnection {
    private val lock = Any()
    private val timeshiftStatuses = HashMap<Long, SubscriptionEvent.Timeshift>()

    override fun events(id: SubscriptionId): Flow<SubscriptionEvent> =
        gateway.subscription(generation, id)
            .onEach { event ->
                if (event is SubscriptionEvent.Timeshift) {
                    synchronized(lock) { timeshiftStatuses[id.value] = event }
                }
            }
            .onCompletion {
                synchronized(lock) { timeshiftStatuses.remove(id.value) }
            }

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = gateway.subscribe(
        generation = generation,
        id = id,
        channelId = ChannelId(channelId.value),
        timeshiftPeriod = timeshiftPeriod,
    )

    override suspend fun skip(
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> = when (target) {
        SubscriptionSeekTarget.Live -> synchronized(lock) { timeshiftStatuses[id.value] }
            ?.let { status ->
                gateway.skipSubscriptionNearLive(
                    generation = generation,
                    id = id,
                    status = status,
                    marginSeconds = RETURN_TO_LIVE_MARGIN_SECONDS,
                )
            }
            ?: SubscriptionOperationResult.NotSupported
        is SubscriptionSeekTarget.Absolute,
        is SubscriptionSeekTarget.Relative,
        -> gateway.skipSubscription(generation, id, target)
    }

    override suspend fun unsubscribe(id: SubscriptionId): SubscriptionOperationResult<Unit> =
        gateway.unsubscribe(generation, id)

    override fun <T> commitIfLive(block: () -> T): T? = gateway.commitIfLive(generation, block)
}

private const val RETURN_TO_LIVE_MARGIN_SECONDS = 3L

private fun GatewayResult<ByteArray>.toArtworkLoadResult(): ArtworkLoadResult = when (this) {
    is GatewayResult.Ok -> ArtworkLoadResult.Available(ArtworkContent(value))
    GatewayResult.ServerRejected -> ArtworkLoadResult.Unavailable(ArtworkFailure.FILE_UNAVAILABLE)
    GatewayResult.AccessDenied -> ArtworkLoadResult.Unavailable(ArtworkFailure.ACCESS_DENIED)
    GatewayResult.ConnectionLimit -> ArtworkLoadResult.Unavailable(ArtworkFailure.CONNECTION_LIMIT)
    GatewayResult.Timeout -> ArtworkLoadResult.Unavailable(ArtworkFailure.TIMEOUT)
    GatewayResult.TransportUnavailable ->
        ArtworkLoadResult.Unavailable(ArtworkFailure.CONNECTION_CHANGED)
    GatewayResult.NotSupported -> ArtworkLoadResult.Unavailable(ArtworkFailure.NOT_SUPPORTED)
}
