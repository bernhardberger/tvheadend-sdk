@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
)

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
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrRecordingFile
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
import at.bernhardberger.tvheadend.htsp.requests.FileSeekResponse
import at.bernhardberger.tvheadend.htsp.requests.FileSeekWhence
import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.AddDvrEntrySelector
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrCutpoint
import at.bernhardberger.tvheadend.htsp.requests.HtspEvent
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrMutationResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspRecordingRuleChannel
import at.bernhardberger.tvheadend.htsp.requests.SubscribeResponse
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSeekPosition
import at.bernhardberger.tvheadend.htsp.requests.addAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.addDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.addTimerecEntry
import at.bernhardberger.tvheadend.htsp.requests.cancelDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.deleteTimerecEntry
import at.bernhardberger.tvheadend.htsp.requests.enableAsyncMetadataAwaitingInitialSync
import at.bernhardberger.tvheadend.htsp.requests.fileClose
import at.bernhardberger.tvheadend.htsp.requests.fileOpen
import at.bernhardberger.tvheadend.htsp.requests.fileRead
import at.bernhardberger.tvheadend.htsp.requests.fileSeek
import at.bernhardberger.tvheadend.htsp.requests.getDiskSpace
import at.bernhardberger.tvheadend.htsp.requests.getDvrCutpoints
import at.bernhardberger.tvheadend.htsp.requests.getDvrConfigs
import at.bernhardberger.tvheadend.htsp.requests.getEvents
import at.bernhardberger.tvheadend.htsp.requests.getSysTime
import at.bernhardberger.tvheadend.htsp.requests.stopDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.subscribe
import at.bernhardberger.tvheadend.htsp.requests.subscriptionLive
import at.bernhardberger.tvheadend.htsp.requests.subscriptionSkip
import at.bernhardberger.tvheadend.htsp.requests.unsubscribe
import at.bernhardberger.tvheadend.htsp.requests.updateAutorecEntry
import at.bernhardberger.tvheadend.htsp.requests.updateDvrEntry
import at.bernhardberger.tvheadend.htsp.requests.updateTimerecEntry
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointAction
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_INCR_PLAY_COUNT
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_KEEP_PLAY_COUNT
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.RecordingRuleChannel
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.toDvrMutationSeconds
import at.bernhardberger.tvheadend.sdk.core.gateway.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrSubscriptionError
import at.bernhardberger.tvheadend.sdk.core.gateway.EpgEpisodeId
import at.bernhardberger.tvheadend.sdk.core.gateway.EpgSeriesLinkId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnection
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
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
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(SubscriptionInfrastructureApi::class)
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

    override fun <T> commitIfLive(
        generation: GatewayGeneration,
        block: () -> T,
    ): T? = connection.commitIfLive(htspGenerationFor(generation)) { block() }

    override suspend fun enableInitialMetadata(
        generation: GatewayGeneration,
    ): GatewayResult<Unit> {
        val htspGeneration = htspGenerationFor(generation)
        val protocolVersion = connection.commitIfLive(htspGeneration) { live ->
            live.protocolVersion
        }
        val supportsAsyncEpg = protocolVersion != null &&
            protocolVersion >= ASYNC_EPG_MINIMUM_PROTOCOL_VERSION
        val epgMaxTime = if (supportsAsyncEpg) {
            when (val serverTime = connection.getSysTime(expectedGeneration = htspGeneration)) {
                is HtspResult.Ok -> serverTime.value.unixTimeSeconds +
                    ASYNC_EPG_HORIZON.inWholeSeconds
                else -> return serverTime.toGatewayResult {}
            }
        } else {
            null
        }
        return connection.enableAsyncMetadataAwaitingInitialSync(
            epg = ASYNC_EPG_ENABLED.takeIf { supportsAsyncEpg },
            epgMaxTime = epgMaxTime,
            expectedGeneration = htspGeneration,
        ).toGatewayResult {}
    }

    override suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>> = connection.getEvents(
        channelId = channelId.value,
        maxTime = maxTime.epochSeconds,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response ->
        Collections.unmodifiableList(
            response.events.mapTo(ArrayList(), HtspEvent::toGatewayEpgQueryEvent),
        )
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
    ).toCheckedGatewayResult { response ->
        response.entryId?.takeIf { response.success == 1L }?.let(::DvrEntryId)
    }

    override suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): GatewayResult<Unit> = connection.updateDvrEntry(
        entryId = id.value,
        channelId = update.channelId?.value,
        configName = update.configId?.value,
        title = update.title,
        subtitle = update.subtitle,
        summary = update.summary,
        description = update.description,
        language = update.language,
        comment = update.comment,
        enabled = update.enabled?.toWireFlag(),
        start = update.start?.epochSeconds,
        stop = update.stop?.epochSeconds,
        startExtra = update.startExtraMinutes,
        stopExtra = update.stopExtraMinutes,
        retention = update.retentionDays,
        removal = update.removalDays,
        priority = update.priority,
        ageRating = update.ageRating,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult(::acceptedDvrAcknowledgement)

    override suspend fun stopDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.stopDvrEntry(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult(::acceptedDvrAcknowledgement)

    override suspend fun cancelDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.cancelDvrEntry(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult(::acceptedDvrAcknowledgement)

    override suspend fun deleteDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = connection.deleteDvrEntry(
        entryId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult(::acceptedDvrAcknowledgement)

    override suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): GatewayResult<AutorecRuleId> = connection.addAutorecEntry(
        title = request.title,
        channel = request.channel.toHtspChannel(),
        minDurationSeconds = request.minDuration?.toDvrMutationSeconds(),
        maxDurationSeconds = request.maxDuration?.toDvrMutationSeconds(),
        fullText = request.fullText?.toWireFlag(),
        mergeText = request.mergeText?.toWireFlag(),
        duplicateDetection = request.duplicateDetection,
        maximumRecordingCount = request.maximumRecordingCount,
        broadcastType = request.broadcastType,
        startExtraMinutes = request.startExtraMinutes,
        stopExtraMinutes = request.stopExtraMinutes,
        seriesLinkUri = request.seriesLinkUri,
        approximateStartMinutesSinceMidnight = request.approximateStartMinutesSinceMidnight,
        startMinutesSinceMidnight = request.startMinutesSinceMidnight,
        startWindowEndMinutesSinceMidnight = request.startWindowEndMinutesSinceMidnight,
        enabled = request.enabled,
        retentionDays = request.retentionDays,
        removalDays = request.removalDays,
        priority = request.priority,
        name = request.name,
        comment = request.comment,
        directory = request.directory,
        configName = request.configId?.value,
        daysOfWeekMask = request.daysOfWeekMask,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response -> AutorecRuleId(response.id) }

    override suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): GatewayResult<Unit> = connection.updateAutorecEntry(
        id = id.value,
        channel = update.channel.toHtspChannel(),
        minDurationSeconds = update.minDuration?.toDvrMutationSeconds(),
        maxDurationSeconds = update.maxDuration?.toDvrMutationSeconds(),
        fullText = update.fullText?.toWireFlag(),
        mergeText = update.mergeText?.toWireFlag(),
        duplicateDetection = update.duplicateDetection,
        maximumRecordingCount = update.maximumRecordingCount,
        broadcastType = update.broadcastType,
        startExtraMinutes = update.startExtraMinutes,
        stopExtraMinutes = update.stopExtraMinutes,
        seriesLinkUri = update.seriesLinkUri,
        startMinutesSinceMidnight = update.startMinutesSinceMidnight,
        startWindowEndMinutesSinceMidnight = update.startWindowEndMinutesSinceMidnight,
        enabled = update.enabled,
        retentionDays = update.retentionDays,
        removalDays = update.removalDays,
        priority = update.priority,
        name = update.name,
        comment = update.comment,
        directory = update.directory,
        title = update.title,
        configName = update.configId?.value,
        daysOfWeekMask = update.daysOfWeekMask,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): GatewayResult<Unit> = connection.deleteAutorecEntry(
        id = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): GatewayResult<TimerecRuleId> = connection.addTimerecEntry(
        title = request.title,
        channel = request.channel.toHtspChannel(),
        startMinutesSinceMidnight = request.startMinutesSinceMidnight?.toLong(),
        stopMinutesSinceMidnight = request.stopMinutesSinceMidnight?.toLong(),
        enabled = request.enabled,
        retentionDays = request.retentionDays,
        removalDays = request.removalDays,
        priority = request.priority,
        name = request.name,
        comment = request.comment,
        directory = request.directory,
        configName = request.configId?.value,
        daysOfWeekMask = request.daysOfWeekMask,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult { response -> TimerecRuleId(response.id) }

    override suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): GatewayResult<Unit> = connection.updateTimerecEntry(
        id = id.value,
        channel = update.channel.toHtspChannel(),
        startMinutesSinceMidnight = update.startMinutesSinceMidnight?.toLong(),
        stopMinutesSinceMidnight = update.stopMinutesSinceMidnight?.toLong(),
        enabled = update.enabled,
        retentionDays = update.retentionDays,
        removalDays = update.removalDays,
        priority = update.priority,
        name = update.name,
        comment = update.comment,
        directory = update.directory,
        title = update.title,
        configName = update.configId?.value,
        daysOfWeekMask = update.daysOfWeekMask,
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
        playPosition = progress.position.inWholeSeconds,
        expectedGeneration = htspGenerationFor(generation),
    ).toCheckedGatewayResult(::acceptedDvrAcknowledgement)

    /**
     * Opens the stored file of one DVR entry using TVHeadend's `dvr/<id>` selector.
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
        val protocolVersion = connection.commitIfLive(htspGeneration) { live ->
            live.protocolVersion
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
            offset = position,
            whence = FileSeekWhence.SET,
            expectedGeneration = htspGenerationFor(generation),
        ).toGatewayResult(FileSeekResponse::offset)
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
            size = length.toLong(),
            offset = position,
            expectedGeneration = htspGenerationFor(generation),
        ).toCheckedGatewayResult { response ->
            response.data
                .takeIf { data -> data.size <= length }
                ?.copyInto(destination, destinationOffset)
        }
    }

    /**
     * Closes one recording handle without inflating the entry's play count.
     *
     * TVHeadend increments the play count on every close whose `playcount` field is absent, so a
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
    ): GatewayResult<Unit> = connection.deleteTimerecEntry(
        id = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).toGatewayResult {}

    override fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent> = connection.subscriptionEvents(
        subscriptionId = id.value,
        expectedGeneration = htspGenerationFor(generation),
    ).map(HtspSubscriptionEvent::toGatewayEvent)

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> = connection.subscribe(
        subscriptionId = id.value,
        channelId = channelId.value,
        timeshiftPeriodSeconds = timeshiftPeriod.inWholeSeconds.takeIf { seconds -> seconds > 0L },
        expectedGeneration = htspGenerationFor(generation),
    ).toSubscriptionResult(SubscribeResponse::toGatewayConfirmation)

    /**
     * Maps one timeshift positioning request onto its exact HTSP command.
     *
     * SDK subscriptions never request the 90 kHz clock, so media coordinates are sent as
     * microseconds. Returning to live has no coordinate and uses `subscriptionLive`.
     */
    override suspend fun skipSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> = when (target) {
        SubscriptionSeekTarget.Live -> connection.subscriptionLive(
            subscriptionId = id.value,
            expectedGeneration = htspGenerationFor(generation),
        )
        is SubscriptionSeekTarget.Absolute -> connection.subscriptionSkip(
            subscriptionId = id.value,
            position = SubscriptionSeekPosition.Time(target.position.inWholeMicroseconds),
            absolute = ABSOLUTE_SKIP_FLAG,
            expectedGeneration = htspGenerationFor(generation),
        )
        is SubscriptionSeekTarget.Relative -> connection.subscriptionSkip(
            subscriptionId = id.value,
            position = SubscriptionSeekPosition.Time(target.offset.inWholeMicroseconds),
            absolute = RELATIVE_SKIP_FLAG,
            expectedGeneration = htspGenerationFor(generation),
        )
    }.toSubscriptionResult {}

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

/** Maps a reply whose payload must still pass a local acceptance check before it is trusted. */
private inline fun <T, R : Any> HtspResult<T>.toCheckedGatewayResult(
    transform: (T) -> R?,
): GatewayResult<R> = when (this) {
    is HtspResult.Ok -> transform(value)?.let { transformed -> GatewayResult.Ok(transformed) }
        ?: GatewayResult.ServerRejected
    HtspResult.ServerError -> GatewayResult.ServerRejected
    HtspResult.AccessDenied -> GatewayResult.AccessDenied
    HtspResult.ConnectionLimit -> GatewayResult.ConnectionLimit
    HtspResult.Timeout -> GatewayResult.Timeout
    HtspResult.TransportUnavailable -> GatewayResult.TransportUnavailable
    HtspResult.NotSupported -> GatewayResult.NotSupported
}

private fun acceptedDvrAcknowledgement(response: HtspDvrMutationResponse): Unit? =
    Unit.takeIf { response.success == 1L }

private fun HtspDvrCutpoint.toDvrCutpointOrNull(): DvrCutpoint? {
    if (end <= start) return null
    return DvrCutpoint(
        start = start.milliseconds,
        end = end.milliseconds,
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
        start = start.epochSeconds,
        stop = stop.epochSeconds,
    )
}

private fun RecordingRuleChannel?.toHtspChannel(): HtspRecordingRuleChannel? = when (this) {
    null -> null
    is RecordingRuleChannel.SpecificChannel -> HtspRecordingRuleChannel.Id(channelId.value)
    RecordingRuleChannel.AllChannels -> HtspRecordingRuleChannel.Any
}

private fun Boolean.toWireFlag(): Long = if (this) 1L else 0L

@OptIn(SubscriptionInfrastructureApi::class)
private inline fun <T, R> HtspResult<T>.toSubscriptionResult(
    transform: (T) -> R,
): SubscriptionOperationResult<R> = when (this) {
    is HtspResult.Ok -> SubscriptionOperationResult.Ok(transform(value))
    HtspResult.ServerError -> SubscriptionOperationResult.ServerRejected
    HtspResult.AccessDenied -> SubscriptionOperationResult.AccessDenied
    HtspResult.ConnectionLimit -> SubscriptionOperationResult.ConnectionLimit
    HtspResult.Timeout -> SubscriptionOperationResult.Timeout
    HtspResult.TransportUnavailable -> SubscriptionOperationResult.TransportUnavailable
    HtspResult.NotSupported -> SubscriptionOperationResult.NotSupported
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
    is HtspEventAddMessage -> MetadataEvent.EventAdded(generation, toGatewayEpgEvent())
    is HtspEventUpdateMessage -> MetadataEvent.EventUpdated(generation, toGatewayEpgUpdate())
    is HtspEventDeleteMessage -> MetadataEvent.EventDeleted(generation, EventId(eventId))
    is HtspDvrEntryAddMessage -> MetadataEvent.DvrEntryAdded(generation, toGatewayDvrEntry())
    is HtspDvrEntryUpdateMessage -> MetadataEvent.DvrEntryUpdated(generation, toGatewayDvrEntry())
    is HtspDvrEntryDeleteMessage -> MetadataEvent.DvrEntryDeleted(generation, DvrEntryId(entryId))
    is HtspAutorecEntryAddMessage -> MetadataEvent.AutorecRuleAdded(generation, toGatewayAutorecRule())
    is HtspAutorecEntryUpdateMessage ->
        MetadataEvent.AutorecRuleUpdated(generation, toGatewayAutorecRule())
    is HtspAutorecEntryDeleteMessage ->
        MetadataEvent.AutorecRuleDeleted(generation, AutorecRuleId(id))
    is HtspTimerecEntryAddMessage -> MetadataEvent.TimerecRuleAdded(generation, toGatewayTimerecRule())
    is HtspTimerecEntryUpdateMessage ->
        MetadataEvent.TimerecRuleUpdated(generation, toGatewayTimerecRule())
    is HtspTimerecEntryDeleteMessage ->
        MetadataEvent.TimerecRuleDeleted(generation, TimerecRuleId(id))
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

private fun HtspEventAddMessage.toGatewayEpgEvent(): GatewayEpgEvent = event.toGatewayEpgEvent(
    genre = genre,
    episodeId = episodeId?.let(::EpgEpisodeId),
    seriesLinkId = seriesLinkId?.let(::EpgSeriesLinkId),
)

private fun HtspEvent.toGatewayEpgEvent(
    genre: String?,
    episodeId: EpgEpisodeId?,
    seriesLinkId: EpgSeriesLinkId?,
): GatewayEpgEvent = GatewayEpgEvent(
    id = EventId(eventId),
    channelId = channelId?.let(::ChannelId),
    start = Instant.fromEpochSeconds(start),
    stop = Instant.fromEpochSeconds(stop),
    title = title,
    subtitle = subtitle,
    summary = summary,
    description = description,
    genre = genre,
    categories = categories,
    keywords = keywords,
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
    firstAired = firstAired?.let { Instant.fromEpochSeconds(it) },
    isNew = isNew.toFlag(),
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    episodeOnscreen = episodeOnscreen,
    episodeId = episodeId,
    seriesLinkId = seriesLinkId,
    image = image,
    dvrEntryId = dvrId?.let(::DvrEntryId),
    nextEventId = nextEventId?.let(::EventId),
)

private fun HtspEvent.toGatewayEpgQueryEvent(): GatewayEpgQueryEvent = GatewayEpgQueryEvent(
    id = EventId(eventId),
    channelId = channelId?.let(::ChannelId),
    start = Instant.fromEpochSeconds(start),
    stop = Instant.fromEpochSeconds(stop),
    title = title,
    subtitle = subtitle,
    summary = summary,
    description = description,
    categories = categories,
    keywords = keywords,
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
    firstAired = firstAired?.let { Instant.fromEpochSeconds(it) },
    isNew = isNew.toFlag(),
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

private fun HtspEventUpdateMessage.toGatewayEpgUpdate(): GatewayEpgUpdate = GatewayEpgUpdate(
    id = EventId(eventId),
    channelId = channelId?.let(::ChannelId),
    start = start?.let { Instant.fromEpochSeconds(it) },
    stop = stop?.let { Instant.fromEpochSeconds(it) },
    title = title,
    subtitle = subtitle,
    summary = summary,
    description = description,
    genre = genre,
    categories = categories,
    keywords = keywords,
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
    firstAired = firstAired?.let { Instant.fromEpochSeconds(it) },
    isNew = isNew.toFlag(),
    seasonNumber = seasonNumber,
    seasonCount = seasonCount,
    episodeNumber = episodeNumber,
    episodeCount = episodeCount,
    partNumber = partNumber,
    partCount = partCount,
    episodeOnscreen = episodeOnscreen,
    episodeId = episodeId?.let(::EpgEpisodeId),
    seriesLinkId = seriesLinkId?.let(::EpgSeriesLinkId),
    image = image,
    dvrEntryId = dvrId?.let(::DvrEntryId),
    nextEventId = nextEventId?.let(::EventId),
)

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
    titledIcon = tagTitledIcon.toFlag(),
    channelIds = channelIds?.map(::ChannelId),
)

private fun HtspTagUpdateMessage.toGatewayTag(): GatewayTagMetadata = GatewayTagMetadata(
    id = TagId(tagId),
    name = tagName,
    uuid = tagUuid,
    index = tagIndex,
    icon = tagIcon,
    titledIcon = tagTitledIcon.toFlag(),
    channelIds = channelIds?.map(::ChannelId),
)

private fun Long?.toFlag(): Boolean? = this?.let { it != 0L }

private fun HtspDvrEntryAddMessage.toGatewayDvrEntry(): GatewayDvrEntry = GatewayDvrEntry(
    id = DvrEntryId(entryId),
    uuid = entryUuid,
    enabled = enabled.toFlag(),
    channelId = channelId?.let(::ChannelId),
    channelName = channelName,
    eventId = eventId?.let(::EventId),
    autorecRuleId = autorecEntryUuid?.let(::AutorecRuleId),
    timerecRuleId = timerecEntryUuid?.let(::TimerecRuleId),
    start = start?.let(Instant::fromEpochSeconds),
    stop = stop?.let(Instant::fromEpochSeconds),
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
    duplicate = duplicate,
    state = state.toDvrEntryState(),
    failure = error.toDvrFailure(),
    subscriptionError = subscriptionError.toDvrSubscriptionError(),
    streamErrors = streamErrors,
    dataErrors = dataErrors,
    dataSizeBytes = dataSizeBytes,
)

private fun HtspDvrEntryUpdateMessage.toGatewayDvrEntry(): GatewayDvrEntry = GatewayDvrEntry(
    id = DvrEntryId(entryId),
    uuid = entryUuid,
    enabled = enabled.toFlag(),
    channelId = channelId?.let(::ChannelId),
    channelName = channelName,
    eventId = eventId?.let(::EventId),
    autorecRuleId = autorecEntryUuid?.let(::AutorecRuleId),
    timerecRuleId = timerecEntryUuid?.let(::TimerecRuleId),
    start = start?.let(Instant::fromEpochSeconds),
    stop = stop?.let(Instant::fromEpochSeconds),
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
    duplicate = duplicate,
    state = state.toDvrEntryState(),
    failure = error.toDvrFailure(),
    subscriptionError = subscriptionError.toDvrSubscriptionError(),
    streamErrors = streamErrors,
    dataErrors = dataErrors,
    dataSizeBytes = dataSizeBytes,
)

private fun HtspDvrRecordingFile.toGatewayFile(): GatewayDvrRecordingFile = GatewayDvrRecordingFile(
    fileId = fileId,
    path = path,
    start = start?.let(Instant::fromEpochSeconds),
    stop = stop?.let(Instant::fromEpochSeconds),
    sizeBytes = sizeBytes,
)

private fun HtspAutorecEntryAddMessage.toGatewayAutorecRule(): GatewayAutorecRule = GatewayAutorecRule(
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
    configId = configId?.let(::DvrConfigId),
)

private fun HtspAutorecEntryUpdateMessage.toGatewayAutorecRule(): GatewayAutorecRule =
    GatewayAutorecRule(
        id = AutorecRuleId(id),
        enabled = enabled,
        maxDuration = maxDurationSeconds?.seconds,
        minDuration = minDurationSeconds?.seconds,
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
        configId = configId?.let(::DvrConfigId),
    )

private fun HtspTimerecEntryAddMessage.toGatewayTimerecRule(): GatewayTimerecRule = GatewayTimerecRule(
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
    directory = directory,
    owner = owner,
    creator = creator,
    configId = configId?.let(::DvrConfigId),
    comment = comment,
)

private fun HtspTimerecEntryUpdateMessage.toGatewayTimerecRule(): GatewayTimerecRule =
    GatewayTimerecRule(
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
        directory = directory,
        owner = owner,
        creator = creator,
        configId = configId?.let(::DvrConfigId),
        comment = comment,
    )

private fun String?.toDvrEntryState(): DvrEntryState? = when (this) {
    null -> null
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
    else -> GatewayDvrFailure.PRESENT
}

private fun String?.toDvrSubscriptionError(): DvrSubscriptionError? = when (this) {
    null -> null
    "noFreeAdapter" -> DvrSubscriptionError.NO_FREE_ADAPTER
    "scrambled" -> DvrSubscriptionError.SCRAMBLED
    "badSignal" -> DvrSubscriptionError.BAD_SIGNAL
    "tuningFailed" -> DvrSubscriptionError.TUNING_FAILED
    "subscriptionOverridden" -> DvrSubscriptionError.SUBSCRIPTION_OVERRIDDEN
    "muxNotEnabled" -> DvrSubscriptionError.MUX_NOT_ENABLED
    "invalidTarget" -> DvrSubscriptionError.INVALID_TARGET
    "No service assigned to channel" -> DvrSubscriptionError.NO_SERVICE
    "Invalid service" -> DvrSubscriptionError.INVALID_SERVICE
    "userAccess" -> DvrSubscriptionError.USER_ACCESS
    "userLimit" -> DvrSubscriptionError.USER_LIMIT
    "weakStream" -> DvrSubscriptionError.WEAK_STREAM
    "noDiskSpace" -> DvrSubscriptionError.NO_DISK_SPACE
    else -> DvrSubscriptionError.UNKNOWN
}

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
        HtspSubscriptionTermination.REMOTE_EOF -> SubscriptionTermination.REMOTE_EOF
        HtspSubscriptionTermination.IO_FAILURE -> SubscriptionTermination.IO_FAILURE
        HtspSubscriptionTermination.FRAMING_FAILURE -> SubscriptionTermination.FRAMING_FAILURE
        HtspSubscriptionTermination.MALFORMED_MESSAGE -> SubscriptionTermination.MALFORMED_MESSAGE
        HtspSubscriptionTermination.TIMEOUT -> SubscriptionTermination.TIMEOUT
        HtspSubscriptionTermination.LOCAL_RETIREMENT -> SubscriptionTermination.LOCAL_RETIREMENT
        HtspSubscriptionTermination.PUBLICATION_FAILURE -> SubscriptionTermination.PUBLICATION_FAILURE
        HtspSubscriptionTermination.INTERNAL_FAILURE -> SubscriptionTermination.INTERNAL_FAILURE
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

private const val RECORDING_FILE_SELECTOR_PREFIX = "dvr/"
private const val ABSOLUTE_SKIP_FLAG = 1L
private const val RELATIVE_SKIP_FLAG = 0L
private const val ASYNC_EPG_MINIMUM_PROTOCOL_VERSION = 6
private const val ASYNC_EPG_ENABLED = 1L
private val ASYNC_EPG_HORIZON = 24.hours

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
