@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelId as SdkChannelId
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId as SdkDvrEntryId
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.StreamProfilesResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId as GatewayDvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
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

internal interface GenerationBoundGrowingRecordingFileLease : GrowingRecordingFileLease {
    public val boundGeneration: GatewayGeneration
    public val boundRecordingId: GatewayDvrEntryId

    public fun isProgressBindingCurrent(): Boolean
}

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
        generation: GatewayGeneration,
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult = openBound(
        expectedGeneration = generation,
        channelId = channelId,
        consumer = consumer,
        options = options,
    )

    private suspend fun openBound(
        expectedGeneration: GatewayGeneration,
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        currentCoroutineContext().ensureActive()
        val admission = synchronized(lock) {
            val boundGeneration = generation ?: return SubscriptionOpenResult.NotReady
            if (boundGeneration !== expectedGeneration) {
                return SubscriptionOpenResult.NotReady
            }
            val manager = subscriptions ?: return SubscriptionOpenResult.NotReady
            val access = streamingAccess ?: return SubscriptionOpenResult.NotReady
            LiveAdmission(boundGeneration, manager, access)
        }
        options.streamProfileUuid?.let { uuid ->
            try {
                StreamProfileId(uuid)
            } catch (_: IllegalArgumentException) {
                return SubscriptionOpenResult.ProfileUnavailable
            }
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
            true -> admission.manager.open(channelId, consumer, options)
        }
    }

    override suspend fun getStreamProfiles(
        generation: GatewayGeneration,
        currentSession: CurrentSessionObservation,
    ): StreamProfilesResult {
        currentCoroutineContext().ensureActive()
        val discovery = synchronized(lock) {
            if (this.generation !== generation) return StreamProfilesResult.ObservationExpired
            ProfileDiscovery(
                generation,
                subscriptions ?: return StreamProfilesResult.NotReady,
            )
        }
        val result = gateway.getStreamProfiles(discovery.generation)
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            if (this.generation !== discovery.generation || subscriptions !== discovery.manager) {
                StreamProfilesResult.TransportUnavailable
            } else {
                result.toStreamProfilesResult(currentSession)
            }
        }
    }

    override suspend fun openRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<RecordingFile> = openRecordingBound(
        generation = target.generation,
        recordingId = target.recordingId,
        target = target,
    )

    internal suspend fun openRecording(
        generation: GatewayGeneration,
        recordingId: SdkDvrEntryId,
    ): RecordingFileResult<RecordingFile> = openRecordingBound(generation, recordingId, target = null)

    private suspend fun openRecordingBound(
        generation: GatewayGeneration,
        recordingId: SdkDvrEntryId,
        target: PlaybackRecordingTarget?,
    ): RecordingFileResult<RecordingFile> {
        currentCoroutineContext().ensureActive()
        val bound = synchronized(lock) {
            this.generation?.takeIf { it === generation }
        }
            ?: return RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
        when (target?.let(metadata::currentPlaybackRecording)) {
            null,
            is PlaybackRecordingLookup.Current,
            -> Unit
            PlaybackRecordingLookup.ObservationExpired ->
                return RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
            PlaybackRecordingLookup.TargetUnavailable ->
                return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        return gateway.openRecordingFile(bound, GatewayDvrEntryId(recordingId.value))
            .toRecordingFileResult { file -> GatewayRecordingFileHandle(gateway, bound, file) }
    }

    override fun bindGrowingRecording(
        target: PlaybackRecordingTarget,
    ): RecordingFileResult<GrowingRecordingFileLease> = bindGrowingRecordingBound(
        generation = target.generation,
        recordingId = target.recordingId,
        target = target,
    )

    internal fun bindGrowingRecording(
        generation: GatewayGeneration,
        recordingId: SdkDvrEntryId,
    ): RecordingFileResult<GrowingRecordingFileLease> =
        bindGrowingRecordingBound(generation, recordingId, target = null)

    private fun bindGrowingRecordingBound(
        generation: GatewayGeneration,
        recordingId: SdkDvrEntryId,
        target: PlaybackRecordingTarget?,
    ): RecordingFileResult<GrowingRecordingFileLease> {
        val bound = synchronized(lock) {
            this.generation?.takeIf { it === generation }
        }
            ?: return RecordingFileResult.Failed(RecordingFileFailure.CONNECTION_CHANGED)
        val dvrEntryId = GatewayDvrEntryId(recordingId.value)
        val tracker = GrowingRecordingMetadataTracker(
            metadata = metadata,
            generation = bound,
            recordingId = dvrEntryId,
            playbackTarget = target,
        )
        return when (val validation = tracker.validate()) {
            is GrowingMetadataValidation.Failed -> RecordingFileResult.Failed(validation.failure)
            is GrowingMetadataValidation.Valid -> RecordingFileResult.Ok(
                BoundGrowingRecordingFileLease(
                    boundGeneration = bound,
                    boundRecordingId = dvrEntryId,
                    tracker = tracker,
                ),
            )
        }
    }

    private inner class BoundGrowingRecordingFileLease(
        override val boundGeneration: GatewayGeneration,
        override val boundRecordingId: GatewayDvrEntryId,
        private val tracker: GrowingRecordingMetadataTracker,
    ) : GenerationBoundGrowingRecordingFileLease {
        override val isCurrent: Boolean
            get() = isProgressBindingCurrent()

        override fun isProgressBindingCurrent(): Boolean =
            tracker.validate() is GrowingMetadataValidation.Valid

        override suspend fun open(
            position: Long,
        ): RecordingFileResult<GrowingRecordingFileReader> {
            require(position >= 0L) { "Growing recording position must not be negative" }
            currentCoroutineContext().ensureActive()
            when (val validation = tracker.validate()) {
                is GrowingMetadataValidation.Failed ->
                    return RecordingFileResult.Failed(validation.failure)
                is GrowingMetadataValidation.Valid -> Unit
            }

            val opened = when (
                val opening = gateway.openRecordingFile(boundGeneration, boundRecordingId)
                    .toRecordingFileResult { file -> file }
            ) {
                is RecordingFileResult.Failed -> return failed(opening.failure)
                is RecordingFileResult.Ok -> opening.value
            }
            val transport = GatewayGrowingRecordingTransport(gateway, boundGeneration, opened)
            var retained = false
            try {
                val openSize = opened.sizeBytes
                if (openSize != null && (openSize < 0L || position > openSize)) {
                    return failed(RecordingFileFailure.FILE_UNAVAILABLE)
                }
                if (openSize == null && position > 0L) {
                    return failed(RecordingFileFailure.FILE_UNAVAILABLE)
                }
                if (openSize != null) {
                    tracker.validateTransportSize(openSize)?.let { failure ->
                        return RecordingFileResult.Failed(failure)
                    }
                }
                when (
                    val seek = gateway.seekRecordingFile(boundGeneration, opened, position)
                        .toRecordingFileResult { offset -> offset }
                ) {
                    is RecordingFileResult.Ok -> if (seek.value != position) {
                        return failed(RecordingFileFailure.FILE_UNAVAILABLE)
                    }
                    is RecordingFileResult.Failed -> return failed(seek.failure)
                }
                when (val validation = tracker.validate()) {
                    is GrowingMetadataValidation.Failed ->
                        return RecordingFileResult.Failed(validation.failure)
                    is GrowingMetadataValidation.Valid -> Unit
                }
                retained = true
                return RecordingFileResult.Ok(
                    CoreGrowingRecordingFileReader(
                        transport = transport,
                        metadata = tracker,
                        position = position,
                        openSizeBytes = openSize,
                    ),
                )
            } finally {
                if (!retained) withContext(NonCancellable) { transport.close() }
            }
        }

        private fun failed(failure: RecordingFileFailure): RecordingFileResult.Failed =
            RecordingFileResult.Failed(tracker.fenceFailure(failure))

        override fun toString(): String = "GrowingRecordingFileLease(<redacted>)"
    }

    override suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult {
        currentCoroutineContext().ensureActive()
        val expectedGeneration = metadata.resolveGeneration(currentSession)
            ?: return ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED)
        val bound = synchronized(lock) {
            generation.takeIf { it === expectedGeneration }
        } ?: return ArtworkLoadResult.Unavailable(ArtworkFailure.OBSERVATION_EXPIRED)
        val result = gateway.loadArtwork(bound, artworkId)
        currentCoroutineContext().ensureActive()
        val proofRemainsCurrent = metadata.resolveGeneration(currentSession) === bound
        return synchronized(lock) {
            if (!proofRemainsCurrent || generation !== bound) {
                ArtworkLoadResult.Unavailable(ArtworkFailure.CONNECTION_CHANGED)
            } else {
                result.toArtworkLoadResult()
            }
        }
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

private class ProfileDiscovery(
    internal val generation: GatewayGeneration,
    internal val manager: SubscriptionManager,
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

    override suspend fun subscribe(
        id: SubscriptionId,
        channelId: SubscriptionChannelId,
        options: SubscriptionOptions,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = gateway.subscribe(
        generation = generation,
        id = id,
        channelId = ChannelId(channelId.value),
        streamProfileUuid = options.streamProfileUuid,
        timeshiftPeriod = options.timeshiftPeriod,
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

    override suspend fun speed(
        id: SubscriptionId,
        speed: Int,
    ): SubscriptionOperationResult<Unit> = gateway.speedSubscription(generation, id, speed)

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

private fun GatewayResult<List<at.bernhardberger.tvheadend.sdk.core.StreamProfile>>.toStreamProfilesResult(
    originatingSession: CurrentSessionObservation,
): StreamProfilesResult = when (this) {
    is GatewayResult.Ok -> StreamProfilesResult.Available.create(value, originatingSession)
    GatewayResult.ServerRejected -> StreamProfilesResult.ServerRejected
    GatewayResult.AccessDenied -> StreamProfilesResult.AccessDenied
    GatewayResult.ConnectionLimit -> StreamProfilesResult.ConnectionLimit
    GatewayResult.Timeout -> StreamProfilesResult.Timeout
    GatewayResult.TransportUnavailable -> StreamProfilesResult.TransportUnavailable
    GatewayResult.NotSupported -> StreamProfilesResult.NotSupported
}
