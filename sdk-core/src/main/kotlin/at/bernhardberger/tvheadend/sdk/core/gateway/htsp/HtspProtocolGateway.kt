package at.bernhardberger.tvheadend.sdk.core.gateway.htsp

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
import at.bernhardberger.tvheadend.htsp.messages.HtspDescrambleInfoMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspEventUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspInitialSyncCompletedMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspMuxPacketMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspQueueStatusMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspServerMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSignalStatusMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionGraceMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionSkipMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionSpeedMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspSubscriptionStartMessage
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
import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.SubscribeResponse
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.requests.subscribe
import at.bernhardberger.tvheadend.htsp.requests.unsubscribe
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DeferredMetadataKind
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayBinary
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnection
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.MuxFrameType
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.gateway.SkipOutcome
import at.bernhardberger.tvheadend.sdk.core.gateway.StreamIndex
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionId
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

internal class HtspProtocolGateway internal constructor(
    private val connection: HtspConnection,
) : ProtocolGateway {
    internal constructor(ioDispatcher: CoroutineDispatcher) : this(
        createHtspConnection(ioDispatcher),
    )

    private val generationLock = Any()
    private val gatewayGenerations =
        WeakHashMap<HtspConnectionGeneration, WeakReference<GatewayGeneration>>()
    private val htspGenerations = WeakHashMap<GatewayGeneration, HtspConnectionGeneration>()

    override val connectionState: StateFlow<GatewayState> = MappingStateFlow(
        source = connection.connectionState,
        transform = HtspConnectionState::toGatewayState,
    )

    override val metadata: Flow<MetadataEvent> = connection.events.transform { event ->
        if (event is HtspTransportEvent.ServerMessage) {
            event.message.toGatewayMetadata(generationFor(event.generation))?.let { emit(it) }
        }
    }

    override val connectionFailures: Flow<GatewayConnectionFailureEvent> =
        connection.events.transform { event ->
            if (event is HtspTransportEvent.ConnectionFailure) {
                emit(
                    GatewayConnectionFailureEvent(
                        failure = event.failure.kind.toGatewayFailure(),
                        generation = event.generation?.let(::generationFor),
                    ),
                )
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
        return when (val result = connection.connect(endpoint)) {
            is HtspConnectOutcome.Connected -> GatewayConnectResult.Connected(
                connection = result.connection.toGatewayConnection(),
            )
            is HtspConnectOutcome.Failed -> GatewayConnectResult.Failed(
                failure = result.failure.kind.toGatewayFailure(),
            )
        }
    }

    override suspend fun disconnect() {
        connection.disconnect()
    }

    override suspend fun shutdown() {
        connection.close()
    }

    override suspend fun enableInitialMetadata(
        generation: GatewayGeneration,
    ): GatewayResult<Unit> = connection.enableAsyncMetadataAwaitingInitialSync(
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override fun subscription(id: SubscriptionId): Flow<SubscriptionEvent> =
        connection.subscriptionEvents(id.value).map(HtspSubscriptionEvent::toGatewayEvent)

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
    ): GatewayResult<SubscriptionConfirmation> = connection.subscribe(
        subscriptionId = id.value,
        channelId = channelId.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult(SubscribeResponse::toGatewayConfirmation)

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): GatewayResult<Unit> = connection.unsubscribe(
        subscriptionId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

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
    is HtspConnectionState.Connecting -> GatewayState.Connecting
    is HtspConnectionState.Connected -> GatewayState.Connected
    is HtspConnectionState.Error -> GatewayState.Failed
}

private fun HtspTransportFailureKind.toGatewayFailure(): GatewayConnectionFailure = when (this) {
    HtspTransportFailureKind.AUTHENTICATION_REJECTED ->
        GatewayConnectionFailure.AUTHENTICATION_REJECTED
    HtspTransportFailureKind.PERMISSION_DENIED -> GatewayConnectionFailure.PERMISSION_DENIED
    HtspTransportFailureKind.HOST_NOT_FOUND,
    HtspTransportFailureKind.CONNECTION_REFUSED,
    HtspTransportFailureKind.CONNECTION_TIMEOUT,
    -> GatewayConnectionFailure.SERVER_UNREACHABLE
    HtspTransportFailureKind.NETWORK_UNREACHABLE -> GatewayConnectionFailure.NETWORK_UNAVAILABLE
    HtspTransportFailureKind.INCOMPATIBLE_SERVER -> GatewayConnectionFailure.INCOMPATIBLE_SERVER
    HtspTransportFailureKind.ZERO_CHANNELS -> GatewayConnectionFailure.NO_CHANNELS
    HtspTransportFailureKind.TRANSPORT_UNAVAILABLE ->
        GatewayConnectionFailure.TRANSPORT_UNAVAILABLE
}

private inline fun <T, R> HtspResult<T>.toGatewayResult(
    transform: (T) -> R,
): GatewayResult<R> = when (this) {
    is HtspResult.Ok -> GatewayResult.Ok(transform(value))
    HtspResult.ServerError -> GatewayResult.ServerRejected
    HtspResult.AccessDenied -> GatewayResult.AccessDenied
    HtspResult.ConnectionLimit -> GatewayResult.ConnectionLimit
    HtspResult.Timeout -> GatewayResult.Timeout
    HtspResult.TransportUnavailable -> GatewayResult.TransportUnavailable
    HtspResult.NotSupported -> GatewayResult.NotSupported
}

private fun HtspServerMessage.toGatewayMetadata(
    generation: GatewayGeneration,
): MetadataEvent? = when (this) {
    is HtspChannelAddMessage -> MetadataEvent.ChannelAdded(
        generation = generation,
        channel = toGatewayChannel(),
    )
    is HtspChannelUpdateMessage -> MetadataEvent.ChannelUpdated(
        generation = generation,
        channel = toGatewayChannel(),
    )
    is HtspChannelDeleteMessage -> MetadataEvent.ChannelDeleted(
        generation = generation,
        channelId = ChannelId(channelId),
    )
    is HtspTagAddMessage -> MetadataEvent.TagAdded(
        generation = generation,
        tag = toGatewayTag(),
    )
    is HtspTagUpdateMessage -> MetadataEvent.TagUpdated(
        generation = generation,
        tag = toGatewayTag(),
    )
    is HtspTagDeleteMessage -> MetadataEvent.TagDeleted(
        generation = generation,
        tagId = TagId(tagId),
    )
    HtspInitialSyncCompletedMessage -> MetadataEvent.InitialSyncCompleted(generation)
    is HtspEventAddMessage -> MetadataEvent.Deferred(generation, DeferredMetadataKind.EPG_ADDED)
    is HtspEventUpdateMessage -> MetadataEvent.Deferred(generation, DeferredMetadataKind.EPG_UPDATED)
    is HtspEventDeleteMessage -> MetadataEvent.Deferred(generation, DeferredMetadataKind.EPG_DELETED)
    is HtspDvrEntryAddMessage -> MetadataEvent.Deferred(generation, DeferredMetadataKind.DVR_ADDED)
    is HtspDvrEntryUpdateMessage -> MetadataEvent.Deferred(generation, DeferredMetadataKind.DVR_UPDATED)
    is HtspDvrEntryDeleteMessage -> MetadataEvent.Deferred(generation, DeferredMetadataKind.DVR_DELETED)
    is HtspAutorecEntryAddMessage ->
        MetadataEvent.Deferred(generation, DeferredMetadataKind.AUTOREC_ADDED)
    is HtspAutorecEntryUpdateMessage ->
        MetadataEvent.Deferred(generation, DeferredMetadataKind.AUTOREC_UPDATED)
    is HtspAutorecEntryDeleteMessage ->
        MetadataEvent.Deferred(generation, DeferredMetadataKind.AUTOREC_DELETED)
    is HtspTimerecEntryAddMessage ->
        MetadataEvent.Deferred(generation, DeferredMetadataKind.TIMEREC_ADDED)
    is HtspTimerecEntryUpdateMessage ->
        MetadataEvent.Deferred(generation, DeferredMetadataKind.TIMEREC_UPDATED)
    is HtspTimerecEntryDeleteMessage ->
        MetadataEvent.Deferred(generation, DeferredMetadataKind.TIMEREC_DELETED)
    is HtspMuxPacketMessage,
    is HtspQueueStatusMessage,
    is HtspSubscriptionStartMessage,
    is HtspSubscriptionStopMessage,
    is HtspSubscriptionGraceMessage,
    is HtspSubscriptionStatusMessage,
    is HtspSignalStatusMessage,
    is HtspDescrambleInfoMessage,
    is HtspSubscriptionSpeedMessage,
    is HtspTimeshiftStatusMessage,
    is HtspSubscriptionSkipMessage,
    -> null
}

private fun HtspChannelAddMessage.toGatewayChannel(): GatewayChannelMetadata =
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

private fun HtspChannelUpdateMessage.toGatewayChannel(): GatewayChannelMetadata =
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

private fun HtspTagAddMessage.toGatewayTag(): GatewayTagMetadata = GatewayTagMetadata(
    id = TagId(tagId),
    name = tagName,
    uuid = tagUuid,
    index = tagIndex,
    icon = tagIcon,
    titledIcon = tagTitledIcon,
    channelIds = channelIds?.map(::ChannelId),
)

private fun HtspTagUpdateMessage.toGatewayTag(): GatewayTagMetadata = GatewayTagMetadata(
    id = TagId(tagId),
    name = tagName,
    uuid = tagUuid,
    index = tagIndex,
    icon = tagIcon,
    titledIcon = tagTitledIcon,
    channelIds = channelIds?.map(::ChannelId),
)

private fun HtspSubscriptionEvent.toGatewayEvent(): SubscriptionEvent = when (this) {
    is HtspSubscriptionEvent.Started -> message.toGatewayEvent()
    is HtspSubscriptionEvent.Packet -> packet.toGatewayEvent()
    is HtspSubscriptionEvent.Skipped -> message.toGatewayEvent()
    is HtspSubscriptionEvent.Stopped -> SubscriptionEvent.Stopped(
        condition = subscriptionCondition(message.status, message.subscriptionError),
    )
    is HtspSubscriptionEvent.Status -> SubscriptionEvent.Status(
        condition = subscriptionCondition(message.status, message.subscriptionError),
    )
    is HtspSubscriptionEvent.Grace -> SubscriptionEvent.Grace(message.graceTimeoutSeconds)
    is HtspSubscriptionEvent.Speed -> SubscriptionEvent.Speed(message.speed)
    is HtspSubscriptionEvent.Timeshift -> SubscriptionEvent.Timeshift(
        full = message.full,
        shift = message.shift,
        start = message.start,
        end = message.end,
        speed = message.speed,
    )
    is HtspSubscriptionEvent.Queue -> SubscriptionEvent.Queue(
        packetCount = message.packetCount,
        byteCount = message.byteCount,
        delay = message.delay,
        bFrameDropCount = message.bFrameDropCount,
        pFrameDropCount = message.pFrameDropCount,
        iFrameDropCount = message.iFrameDropCount,
    )
    is HtspSubscriptionEvent.Signal -> SubscriptionEvent.Signal(
        relativeSnr = message.relativeSnr,
        absoluteSnr = message.absoluteSnr,
        relativeSignal = message.relativeSignal,
        absoluteSignal = message.absoluteSignal,
        bitErrorRate = message.bitErrorRate,
        uncorrectedBlockCount = message.uncorrectedBlockCount,
        frontendStatusReported = message.frontendStatus != null,
    )
    is HtspSubscriptionEvent.Descramble -> message.toGatewayEvent()
    is HtspSubscriptionEvent.Dropped -> SubscriptionEvent.Dropped(count)
    is HtspSubscriptionEvent.Terminated -> SubscriptionEvent.Terminated(
        reason = reason.toGatewayTermination(),
    )
}

private fun HtspSubscriptionStartMessage.toGatewayEvent(): SubscriptionEvent.Started =
    SubscriptionEvent.Started(
        streams = streams?.map(HtspSubscriptionStream::toGatewayStream),
        codecMetadata = codecMetadata?.let(::HtspGatewayBinary),
        condition = subscriptionCondition(status, subscriptionError),
    )

private fun HtspSubscriptionStream.toGatewayStream(): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(streamIndex),
    type = streamType.toGatewayStreamType(),
    language = language,
    compositionId = compositionId,
    ancillaryId = ancillaryId,
    width = width,
    height = height,
    frameDuration = frameDuration,
    aspectNumerator = aspectNumerator,
    aspectDenominator = aspectDenominator,
    audioType = audioType,
    audioVersion = audioVersion,
    channelCount = channelCount,
    rate = sampleRate,
    rdsUecp = rdsUecp,
    codecMetadata = codecMetadata?.let(::HtspGatewayBinary),
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
            66L -> MuxFrameType.B
            73L -> MuxFrameType.I
            80L -> MuxFrameType.P
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
        absolute = absolute?.let { it != 0L },
        outcome = when (error) {
            null, 0L -> SkipOutcome.ACCEPTED
            1L -> SkipOutcome.REJECTED
            else -> SkipOutcome.UNKNOWN
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
        HtspSubscriptionTermination.TRANSPORT_CLOSED -> SubscriptionTermination.TRANSPORT_CLOSED
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

private fun SubscribeResponse.toGatewayConfirmation(): SubscriptionConfirmation =
    SubscriptionConfirmation(
        ninetyKhz = ninetyKhz,
        normalizedTimestamps = normalizedTimestamps,
        weight = weight,
        timeshiftPeriodSeconds = timeshiftPeriodSeconds,
    )

private class HtspGatewayBinary(
    private val binary: HtspBinary,
) : GatewayBinary {
    override val size: Int
        get() = binary.size

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int =
        binary.copyInto(destination, destinationOffset)

    override fun toString(): String = "GatewayBinary(<redacted>)"
}
