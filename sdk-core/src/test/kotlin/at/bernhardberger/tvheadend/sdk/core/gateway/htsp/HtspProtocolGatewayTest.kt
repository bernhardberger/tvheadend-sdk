@file:OptIn(
    SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core.gateway.htsp

import at.bernhardberger.tvheadend.htsp.model.HtspProgrammeCredit
import at.bernhardberger.tvheadend.htsp.model.HtspProgrammeCredits
import at.bernhardberger.tvheadend.htsp.model.HtspChannel
import at.bernhardberger.tvheadend.htsp.model.HtspTag
import at.bernhardberger.tvheadend.htsp.model.HtspDvrEntry
import at.bernhardberger.tvheadend.htsp.model.HtspAutorecEntry
import at.bernhardberger.tvheadend.htsp.model.HtspTimerecEntry
import at.bernhardberger.tvheadend.htsp.messages.HtspFrameType
import at.bernhardberger.tvheadend.htsp.messages.HtspMetadataMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryStatsMessage
import kotlinx.coroutines.flow.map
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole

import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOptions
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectOutcome
import at.bernhardberger.tvheadend.htsp.connection.HtspConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionGeneration
import at.bernhardberger.tvheadend.htsp.connection.HtspConnectionState
import at.bernhardberger.tvheadend.htsp.connection.HtspEndpoint
import at.bernhardberger.tvheadend.htsp.connection.HtspLiveConnection
import at.bernhardberger.tvheadend.htsp.connection.HtspResult
import at.bernhardberger.tvheadend.htsp.connection.HtspServerFacts
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspSubscriptionTermination
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportEvent
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportFailure
import at.bernhardberger.tvheadend.htsp.connection.HtspTransportFailureKind
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelNowNextMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDescrambleInfoMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.model.HtspDvrRecordingFile
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
import at.bernhardberger.tvheadend.htsp.requests.EnableAsyncMetadataRequest
import at.bernhardberger.tvheadend.htsp.requests.AddAutorecEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.AddAutorecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.AddDvrEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.AddDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.AddDvrEntrySelector
import at.bernhardberger.tvheadend.htsp.requests.AddTimerecEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.AddTimerecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.CancelDvrEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.CancelDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.DeleteAutorecEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.DeleteAutorecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.DeleteDvrEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.DeleteDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.DeleteTimerecEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.DeleteTimerecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.EpgQueryRequest
import at.bernhardberger.tvheadend.htsp.requests.EpgQueryResponse
import at.bernhardberger.tvheadend.htsp.requests.FileCloseRequest
import at.bernhardberger.tvheadend.htsp.requests.FileCloseResponse
import at.bernhardberger.tvheadend.htsp.requests.FileOpenRequest
import at.bernhardberger.tvheadend.htsp.requests.FileOpenResponse
import at.bernhardberger.tvheadend.htsp.requests.FileReadRequest
import at.bernhardberger.tvheadend.htsp.requests.FileReadResponse
import at.bernhardberger.tvheadend.htsp.requests.FileSeekRequest
import at.bernhardberger.tvheadend.htsp.requests.FileSeekResponse
import at.bernhardberger.tvheadend.htsp.requests.FileSeekWhence
import at.bernhardberger.tvheadend.htsp.requests.FileStatRequest
import at.bernhardberger.tvheadend.htsp.requests.FileStatResponse
import at.bernhardberger.tvheadend.htsp.requests.GetDiskSpaceRequest
import at.bernhardberger.tvheadend.htsp.requests.GetDiskSpaceResponse
import at.bernhardberger.tvheadend.htsp.requests.GetDvrConfigsRequest
import at.bernhardberger.tvheadend.htsp.requests.GetDvrConfigsResponse
import at.bernhardberger.tvheadend.htsp.requests.GetDvrCutpointsRequest
import at.bernhardberger.tvheadend.htsp.requests.GetDvrCutpointsResponse
import at.bernhardberger.tvheadend.htsp.requests.GetEventsRequest
import at.bernhardberger.tvheadend.htsp.requests.GetEventsResponse
import at.bernhardberger.tvheadend.htsp.requests.GetProfilesRequest
import at.bernhardberger.tvheadend.htsp.requests.GetProfilesResponse
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeRequest
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrConfig
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrCutpoint
import at.bernhardberger.tvheadend.htsp.model.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.HtspEmptyResponse
import at.bernhardberger.tvheadend.htsp.model.HtspEvent
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.HtspRecordingRuleChannel
import at.bernhardberger.tvheadend.htsp.requests.HtspProfile
import at.bernhardberger.tvheadend.htsp.requests.StopDvrEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.StopDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.SubscribeRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscribeResponse
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionChangeWeightRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSeekPosition
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSkipRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSpeedRequest
import at.bernhardberger.tvheadend.htsp.requests.UnsubscribeRequest
import at.bernhardberger.tvheadend.htsp.requests.UpdateAutorecEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.UpdateAutorecEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.UpdateDvrEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.UpdateDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.UpdateTimerecEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.UpdateTimerecEntryResponse
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.sdk.core.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointAction
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_INCR_PLAY_COUNT
import at.bernhardberger.tvheadend.sdk.core.DVR_PROGRESS_KEEP_PLAY_COUNT
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.DvrSubscriptionError
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.ChannelTagId
import at.bernhardberger.tvheadend.sdk.core.EpgSearchRequest
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecTime
import at.bernhardberger.tvheadend.sdk.core.BroadcastType
import at.bernhardberger.tvheadend.sdk.core.DuplicateDetection
import at.bernhardberger.tvheadend.sdk.core.DvrPriority
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.RecordingRuleChannel
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.core.SessionRecoveryDisposition
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.session.ConnectionOwner
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.session.ReconnectBackoff
import at.bernhardberger.tvheadend.sdk.core.session.SessionChildren
import at.bernhardberger.tvheadend.sdk.core.StreamProfile
import at.bernhardberger.tvheadend.sdk.core.StreamProfileId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionPriority
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal class HtspProtocolGatewayTest {
    @ParameterizedTest
    @CsvSource("AUTHENTICATION_REJECTED,false", "UNSUPPORTED_SERVER_VERSION,false", "AUTHENTICATION_REJECTED,true", "UNSUPPORTED_SERVER_VERSION,true")
    fun `admission denial resolves termination while watcher continuation is queued`(
        kind: HtspTransportFailureKind,
        delayedTermination: Boolean,
    ) = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        val retries = mutableListOf<Int>()
        var source: HtspConnectionGeneration? = null
        val children = object : SessionChildren by SessionChildren.None {
            override fun bindGeneration(generation: at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration) {
                assertEquals(1, fake.terminationWaiters)
                source = requireNotNull(fake.liveConnectionValue.value).generation
                if (!delayedTermination) fake.fail(checkNotNull(source), HtspTransportFailure(kind))
                fake.connectionStateValue.value = HtspConnectionState.Error(HtspTransportFailure(kind))
                // StandardTestDispatcher keeps the termination watcher queued until binding returns.
            }
        }
        val owner = ConnectionOwner(gateway, PhaseOneSessionMetadata(), children,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            backoff = ReconnectBackoff { retries += it; 1.seconds })
        try {
            owner.connect(ServerProfile("host"))
            testScheduler.runCurrent()
            if (delayedTermination) {
                assertEquals(SessionState.Synchronizing, owner.observation.value.sessionState)
                assertTrue(retries.isEmpty())
                fake.fail(checkNotNull(source), HtspTransportFailure(kind))
                testScheduler.runCurrent()
            }
            val expected = if (kind == HtspTransportFailureKind.AUTHENTICATION_REJECTED)
                SessionFailure.AUTHENTICATION_REJECTED else SessionFailure.SERVER_UPGRADE_REQUIRED
            assertEquals(SessionState.Unavailable(expected), owner.observation.value.sessionState)
            assertTrue(retries.isEmpty())
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `foreign termination token ends owner attempt as unexpected failure`() = runTest {
        val fake = connectedFake().apply { rejectTerminationToken = true }
        val owner = ConnectionOwner(HtspProtocolGateway(fake), PhaseOneSessionMetadata(), SessionChildren.None,
            defaultDispatcher = StandardTestDispatcher(testScheduler), backoff = ReconnectBackoff { 1.seconds })
        try {
            owner.connect(ServerProfile("host"))
            testScheduler.runCurrent()
            assertEquals(SessionState.Unavailable(SessionFailure.UNEXPECTED_FAILURE), owner.observation.value.sessionState)
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `intentional termination during binding resolves denial without waiting for failure`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        val retries = mutableListOf<Int>()
        val children = object : SessionChildren by SessionChildren.None {
            override fun bindGeneration(generation: at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration) {
                fake.end(requireNotNull(fake.liveConnectionValue.value).generation,
                    at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination.Disconnected)
                fake.connectionStateValue.value = HtspConnectionState.Disconnected
            }
        }
        val owner = ConnectionOwner(gateway, PhaseOneSessionMetadata(), children,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            backoff = ReconnectBackoff { retries += it; 1.seconds })
        try {
            owner.connect(ServerProfile("host"))
            testScheduler.runCurrent()
            assertEquals(SessionState.Unavailable(SessionFailure.TRANSPORT_UNAVAILABLE), owner.observation.value.sessionState)
            assertEquals(listOf(0), retries)
            assertEquals(0, fake.terminationWaiters)
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `termination awaits are latched generation scoped and cancellation independent`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        val first = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        val oldSource = requireNotNull(fake.liveConnectionValue.value).generation
        val cancelled = backgroundScope.launch { gateway.awaitConnectionFailure(first) }
        testScheduler.runCurrent()
        cancelled.cancel()
        cancelled.join()
        fake.fail(oldSource, HtspTransportFailure(HtspTransportFailureKind.NETWORK_UNREACHABLE))
        assertEquals(GatewayConnectionFailure.NETWORK_UNAVAILABLE, gateway.awaitConnectionFailure(first))
        val newSource = HtspConnectionGeneration()
        fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection(newSource))
        val second = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        val secondResult = backgroundScope.async { gateway.awaitConnectionFailure(second) }
        testScheduler.runCurrent()
        assertFalse(secondResult.isCompleted)
        gateway.disconnect()
        testScheduler.runCurrent()
        assertFalse(secondResult.isCompleted, "SDK disconnect must not become a failure")
        secondResult.cancel()
        secondResult.join()
        assertEquals(0, fake.terminationWaiters)
    }

    @Test
    fun `transport failure before overflow remains the first retirement cause`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        val source = requireNotNull(fake.liveConnectionValue.value).generation
        fake.fail(source, HtspTransportFailure(HtspTransportFailureKind.NETWORK_UNREACHABLE))
        assertEquals(GatewayConnectionFailure.NETWORK_UNAVAILABLE, gateway.awaitConnectionFailure(generation))
        fake.eventsFlow = flowOf(HtspTransportEvent.MetadataOverflow(source, 1))
        gateway.metadata.toList()
        assertEquals(GatewayConnectionFailure.NETWORK_UNAVAILABLE, gateway.awaitConnectionFailure(generation))
        assertEquals(GatewayConnectionFailure.NETWORK_UNAVAILABLE, gateway.retirementFailure(generation))
    }

    @Test
    fun `later batches cannot publish a catalog after metadata overflow`() = runTest {
        val source = HtspConnectionGeneration()
        val transportBatches = MutableSharedFlow<List<HtspTransportEvent>>()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            batchesFlow = transportBatches
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        val metadata = PhaseOneSessionMetadata()
        metadata.bindGeneration(generation)
        val failures = mutableListOf<GatewayConnectionFailureEvent>()
        backgroundScope.launch { failures += GatewayConnectionFailureEvent(gateway.awaitConnectionFailure(generation), generation) }
        backgroundScope.launch { gateway.metadataBatches.collect { metadata.acceptMetadataBatch(it) } }
        testScheduler.runCurrent()
        assertEquals(1, transportBatches.subscriptionCount.value)
        transportBatches.emit(listOf(HtspTransportEvent.MetadataOverflow(source, 1)))
        testScheduler.runCurrent()
        assertEquals(listOf(GatewayConnectionFailure.METADATA_OVERFLOW), failures.map { it.failure })
        fake.fail(source, HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE))
        transportBatches.emit(listOf(
            HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelFixture()), source, 1),
            HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, source, 2),
            HtspTransportEvent.ConnectionFailure(HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE), source),
        ))
        testScheduler.runCurrent()
        assertTrue(metadata.observation.value.channelState is ChannelRepositoryState.Synchronizing)
        assertEquals(
            listOf(GatewayConnectionFailure.METADATA_OVERFLOW),
            failures.map { it.failure },
        )
        // Delivery continues after retirement, including new overflow markers.
        transportBatches.emit(listOf(HtspTransportEvent.MetadataOverflow(source, 1)))
        testScheduler.runCurrent()
        assertEquals(1, failures.count { it.failure == GatewayConnectionFailure.METADATA_OVERFLOW })
        assertTrue(failures.all { it.generation === generation })
        assertEquals(GatewayConnectionFailure.METADATA_OVERFLOW, gateway.retirementFailure(generation))
    }

    @Test
    fun `fake batches honour the gateway requested batch size`() = runTest {
        val source = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            batchesFlow = flowOf(List(1_025) { index ->
                HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelFixture()), source, index.toLong() + 1)
            })
        }
        val gateway = HtspProtocolGateway(fake)
        gateway.connect(ServerConfiguration("host", 9_982))
        assertEquals(listOf(512, 512, 1), gateway.metadataBatches.toList().map { it.size })
    }

    @Test
    fun `fake batches omit empty input and split at size and generation boundaries`() = runTest {
        val first = HtspConnectionGeneration()
        val second = HtspConnectionGeneration()
        val events = listOf(first, first, first, second, second).mapIndexed { index, source ->
            HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelFixture()), source, index.toLong() + 1)
        }
        val fake = FakeHtspConnection().apply { batchesFlow = flowOf(emptyList(), events) }
        val batches = fake.eventBatches(2).toList()
        assertEquals(listOf(2, 1, 2), batches.map { it.size })
        assertEquals(events, batches.flatten())
        assertTrue(batches.all { batch -> batch.all { it.generation === batch.first().generation } })
        assertThrows(IllegalArgumentException::class.java) { fake.eventBatches(0) }
        assertThrows(IllegalArgumentException::class.java) { fake.eventBatches(4_097) }
    }

    @Test
    fun `transport batches stay grouped and split at sync and overflow barriers`() = runTest {
        val source = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            eventsFlow = flow { error("Per-event stream must not be collected") }
            batchesFlow = flowOf(listOf(
                HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelFixture()), source, 1),
                HtspTransportEvent.MalformedMetadata(source, 2),
                HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, source, 2),
                HtspTransportEvent.ServerMessage(HtspChannelUpdateMessage(channelFixture()), source, 3),
                HtspTransportEvent.MetadataOverflow(source, 1),
                HtspTransportEvent.ServerMessage(HtspChannelDeleteMessage(1), source, 4),
            ))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        val batches = gateway.metadataBatches.toList()
        assertEquals(listOf(2, 1), batches.map { it.size })
        assertTrue(batches[0][0] is MetadataEvent.ChannelAdded)
        assertTrue(batches[0][1] is MetadataEvent.InitialSyncCompleted)
        assertTrue(batches[1][0] is MetadataEvent.ChannelUpdated)
        assertTrue(batches.flatten().all { it.generation === generation })
        assertEquals(GatewayConnectionFailure.METADATA_OVERFLOW, gateway.retirementFailure(generation))
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `post sync malformed metadata preserves ready and later updates including retired reports`(retired: Boolean) = runTest {
        val source = HtspConnectionGeneration()
        val messages = MutableSharedFlow<HtspTransportEvent>()
        val fake = metadataConnection(source, messages)
        val gateway = HtspProtocolGateway(fake)
        val oldSource = HtspConnectionGeneration()
        fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection(oldSource))
        gateway.connect(ServerConfiguration("host", 9_982))
        gateway.disconnect()
        fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
        val owner = ConnectionOwner(
            gateway, PhaseOneSessionMetadata(), SessionChildren.None,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            backoff = ReconnectBackoff { error("Malformed metadata must not schedule recovery") },
        )
        try {
            owner.connect(ServerProfile("host"))
            runCurrent()
            val ready = owner.observation.value
            assertTrue(ready.sessionState is SessionState.Ready)
            val disconnects = fake.disconnectCalls
            val requests = fake.expectedGenerations.size
            val report = HtspTransportEvent.MalformedMetadata(if (retired) oldSource else source, 3)
            assertEquals("MalformedMetadata(generation=<redacted>, count=3)", report.toString())
            messages.emit(report)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertSame(ready, owner.observation.value)
            assertEquals(disconnects, fake.disconnectCalls)
            assertEquals(requests, fake.expectedGenerations.size)
            assertSame(source, (fake.connectionState.value as HtspConnectionState.Connected).connection.generation)
            messages.emit(HtspTransportEvent.ServerMessage(
                HtspChannelUpdateMessage(channelFixture().copy(channelName = "later")), source, 3,
            ))
            runCurrent()
            assertEquals(ready.sessionState, owner.observation.value.sessionState)
            val catalog = (owner.observation.value.channelState as ChannelRepositoryState.Current).catalog
            assertEquals("later", catalog.channels.single().name)
        } finally {
            owner.shutdown()
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `pre sync skipped metadata reaches ready but malformed acknowledgement requires explicit retry`(
        foreign: Boolean,
        malformedAcknowledgement: Boolean,
    ) = runTest {
        val source = HtspConnectionGeneration()
        val messages = MutableSharedFlow<HtspTransportEvent>()
        val fake = metadataConnection(
            source, messages,
            reportBeforeSync = HtspTransportEvent.MalformedMetadata(if (foreign) HtspConnectionGeneration() else source, 1),
            malformedAcknowledgement = malformedAcknowledgement,
        )
        val owner = ConnectionOwner(
            HtspProtocolGateway(fake), PhaseOneSessionMetadata(), SessionChildren.None,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            backoff = ReconnectBackoff { error("Malformed response requires explicit retry") },
        )
        try {
            owner.connect(ServerProfile("host"))
            runCurrent()
            if (!malformedAcknowledgement) {
                val ready = owner.observation.value
                assertTrue(ready.sessionState is SessionState.Ready)
                val requests = fake.expectedGenerations.size
                val disconnects = fake.disconnectCalls
                advanceTimeBy(60_000)
                runCurrent()
                assertSame(ready, owner.observation.value)
                assertEquals(requests, fake.expectedGenerations.size)
                assertEquals(disconnects, fake.disconnectCalls)
                messages.emit(HtspTransportEvent.ServerMessage(
                    HtspChannelUpdateMessage(channelFixture().copy(channelName = "later")), source, 3,
                ))
                runCurrent()
                assertEquals(ready.sessionState, owner.observation.value.sessionState)
                val catalog = (owner.observation.value.channelState as ChannelRepositoryState.Current).catalog
                assertEquals("later", catalog.channels.single().name)
            } else {
                val failure = SessionFailure.synchronizationFailed(TvheadendFailure.MALFORMED_RESPONSE)
                assertEquals(SessionState.Unavailable(failure), owner.observation.value.sessionState)
                assertEquals(SessionRecoveryDisposition.EXPLICIT_RETRY, failure.recoveryDisposition)
                assertEquals(
                    "SessionFailure(kind=SYNCHRONIZATION_FAILED, operationFailure=MALFORMED_RESPONSE)",
                    failure.toString(),
                )
                val requests = fake.expectedGenerations.size
                val disconnects = fake.disconnectCalls
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(requests, fake.expectedGenerations.size)
                assertEquals(disconnects, fake.disconnectCalls)
                assertEquals(SessionCommandResult.STARTED, owner.retry())
            }
        } finally {
            owner.shutdown()
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `pre sync metadata overflow still retires the generation and schedules backoff`(collectorOnly: Boolean) = runTest {
        val source = HtspConnectionGeneration()
        val messages = MutableSharedFlow<HtspTransportEvent>()
        val fake = metadataConnection(source, messages, HtspTransportEvent.MetadataOverflow(source, 1))
        if (collectorOnly) {
            // Model S1: the helper sees the sync marker, but only the SDK batch collector
            // sees its own overflow. It cannot rely on a helper TransportUnavailable result.
            fake.batchesFlow = messages.map { listOf(it) }
            fake.eventsFlow = messages.filterNot { it is HtspTransportEvent.MetadataOverflow }
        }
        val gateway = HtspProtocolGateway(fake)
        val retries = mutableListOf<Int>()
        val owner = ConnectionOwner(
            gateway, PhaseOneSessionMetadata(), SessionChildren.None,
            defaultDispatcher = StandardTestDispatcher(testScheduler),
            backoff = ReconnectBackoff { retries += it; 1.seconds },
        )
        try {
            owner.connect(ServerProfile("host"))
            runCurrent()
            assertEquals(SessionState.Unavailable(SessionFailure.METADATA_OVERFLOW), owner.observation.value.sessionState)
            assertEquals(listOf(0), retries)
            assertSame(HtspConnectionState.Disconnected, fake.connectionState.value)
            val requests = fake.expectedGenerations.size
            advanceTimeBy(999)
            runCurrent()
            assertEquals(requests, fake.expectedGenerations.size)
            assertEquals(listOf(0), retries)
        } finally {
            owner.shutdown()
        }
    }

    private fun metadataConnection(
        source: HtspConnectionGeneration,
        messages: MutableSharedFlow<HtspTransportEvent>,
        reportBeforeSync: HtspTransportEvent? = null,
        malformedAcknowledgement: Boolean = false,
    ): FakeHtspConnection = FakeHtspConnection().apply {
        connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
        eventsFlow = messages
        beforeExecute = { request ->
            when (request) {
                is GetSysTimeRequest -> executeResult = HtspResult.Ok(GetSysTimeResponse(1_000_000, 0, 0))
                is EnableAsyncMetadataRequest -> {
                    executeResult = if (malformedAcknowledgement) HtspResult.MalformedReply else HtspResult.Ok(HtspEmptyResponse)
                    messages.emit(HtspTransportEvent.ServerMessage(HtspChannelAddMessage(channelFixture()), source, 1))
                    reportBeforeSync?.let { messages.emit(it) }
                    messages.emit(HtspTransportEvent.ServerMessage(HtspInitialSyncCompletedMessage, source, 2))
                }
                else -> error("Unexpected metadata request")
            }
        }
    }

    @Test
    fun `live admission and retirement share one atomic boundary`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retiring = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val commit = workers.submit<Boolean?> {
                gateway.commitIfLive(generation) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Admission test timed out" }
                    true
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val retirement = workers.submit {
                fake.connectionStateValue.value = HtspConnectionState.Error(
                    HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
                )
                retiring.countDown()
                runBlocking { gateway.disconnect() }
                retired.countDown()
            }
            assertTrue(retiring.await(5, TimeUnit.SECONDS))
            assertSame(GatewayState.Failed, gateway.connectionState.value)
            assertEquals(null, gateway.estimatedServerTime(generation))
            assertFalse(retired.await(200, TimeUnit.MILLISECONDS), "Retirement bypassed admission lock")
            release.countDown()
            assertEquals(true, commit.get(5, TimeUnit.SECONDS))
            retirement.get(5, TimeUnit.SECONDS)
            assertEquals(null, gateway.commitIfLive(generation) { true })
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `sticky Error blocks admission and failed connect is not a runtime failure`() = runTest {
        val transportEvents = MutableSharedFlow<HtspTransportEvent>()
        val fake = connectedFake().apply { eventsFlow = transportEvents }
        val gateway = HtspProtocolGateway(fake)
        val failures = mutableListOf<GatewayConnectionFailureEvent>()
        testScheduler.runCurrent()
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        backgroundScope.launch { failures += GatewayConnectionFailureEvent(gateway.awaitConnectionFailure(generation), generation) }
        val sourceGeneration = requireNotNull(fake.liveConnectionValue.value).generation
        fake.connectionStateValue.value = HtspConnectionState.Error(
            HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
        )
        repeat(2) {
            assertSame(GatewayState.Failed, gateway.connectionState.value)
            assertEquals(null, gateway.commitIfLive(generation) { true })
        }
        fake.fail(sourceGeneration, HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE))
        testScheduler.runCurrent()
        assertEquals(1, failures.size)
        assertSame(generation, failures.single().generation)
        fake.connectOutcome = HtspConnectOutcome.Failed(
            HtspTransportFailure(HtspTransportFailureKind.UNSUPPORTED_SERVER_VERSION),
        )
        val failed = gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Failed
        assertEquals(GatewayConnectionFailure.UNSUPPORTED_SERVER_VERSION, failed.failure)
        testScheduler.runCurrent()
        assertEquals(1, failures.size, "A failed connect must only report via its outcome")
        assertSame(GatewayState.Failed, gateway.connectionState.value)
    }

    @Test
    fun `retirement snapshots do not invent or erase a typed failure cause`() = runTest {
        val transportEvents = MutableSharedFlow<HtspTransportEvent>()
        val fake = connectedFake().apply { eventsFlow = transportEvents }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val source = requireNotNull(fake.liveConnectionValue.value).generation
        val failure = HtspTransportFailure(HtspTransportFailureKind.AUTHENTICATION_REJECTED)
        val failures = mutableListOf<GatewayConnectionFailureEvent>()
        backgroundScope.launch { failures += GatewayConnectionFailureEvent(gateway.awaitConnectionFailure(generation), generation) }
        testScheduler.runCurrent()
        fake.fail(source, failure)
        fake.connectionStateValue.value = HtspConnectionState.Error(failure)
        assertEquals(null, gateway.commitIfLive(generation) { true })
        assertEquals(GatewayConnectionFailure.AUTHENTICATION_REJECTED, gateway.retirementFailure(generation))
        testScheduler.runCurrent()
        assertEquals(GatewayConnectionFailure.AUTHENTICATION_REJECTED, failures.single().failure)
        assertEquals(GatewayConnectionFailure.AUTHENTICATION_REJECTED, gateway.retirementFailure(generation))
        gateway.disconnect()
        assertEquals(GatewayConnectionFailure.AUTHENTICATION_REJECTED, gateway.retirementFailure(generation))
    }

    @Test
    fun `failed reconnect is cleared by disconnect and shutdown closes the disconnected service`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        gateway.connect(ServerConfiguration("host", 9_982))
        val old = requireNotNull(fake.liveConnectionValue.value).generation
        fake.connectOutcome = HtspConnectOutcome.Failed(
            HtspTransportFailure(HtspTransportFailureKind.CONNECTION_REFUSED),
        )
        gateway.connect(ServerConfiguration("host", 9_982))
        assertFalse(fake.isCurrent(old))
        assertSame(GatewayState.Failed, gateway.connectionState.value)
        gateway.disconnect()
        assertSame(GatewayState.Disconnected, gateway.connectionState.value)
        assertEquals(true, gateway.lastDisconnectChanged)
        gateway.shutdown()
        assertTrue(fake.closed)
        assertEquals(true, gateway.lastCloseChanged)
        gateway.shutdown()
        assertEquals(false, gateway.lastCloseChanged)
    }

    @Test
    fun `library attempt fence protects a replacement after disconnect admission`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        gateway.connect(ServerConfiguration("host", 9_982))
        val replacement = liveConnection(HtspConnectionGeneration())
        fake.beforeTeardown = {
            fake.connectOutcome = HtspConnectOutcome.Connected(replacement)
            fake.connect(HtspEndpoint("host", 9_982))
        }
        gateway.disconnect()
        assertEquals(null, fake.lastExpectedGeneration)
        assertSame(replacement, fake.liveConnectionValue.value)
        assertEquals(false, gateway.lastDisconnectChanged)
    }

    @Test
    fun `fake retains current but ended tokens and fences stale attempts like the library`() = runTest {
        val fake = connectedFake()
        fake.connect(HtspEndpoint("host", 9_982))
        val live = requireNotNull(fake.liveConnectionValue.value)
        val token = live.generation
        fake.connectOutcome = HtspConnectOutcome.Failed(HtspTransportFailure(HtspTransportFailureKind.SUPERSEDED))
        fake.connect(HtspEndpoint("host", 9_982))
        assertSame(live, fake.liveConnectionValue.value)
        fake.connectOutcome = HtspConnectOutcome.Connected(live)
        fake.beforeTeardown = { fake.connect(HtspEndpoint("host", 9_982)) }
        assertTrue(fake.disconnect(token))
        fake.beforeTeardown = {}
        assertTrue(fake.isCurrent(token))
        assertEquals(null, fake.liveConnectionValue.value)
        assertFalse(fake.disconnect(token))
        assertFalse(fake.close(HtspConnectionGeneration()))
        assertTrue(fake.close(token))
        assertFalse(fake.close(null))
    }

    @Test
    fun `retirement during system time request prevents estimate and protocol admission`() = runTest {
        val fake = connectedFake()
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        fake.beforeExecute = { request ->
            if (request is GetSysTimeRequest) {
                fake.liveConnectionValue.value = null
                fake.executeResult = HtspResult.Ok(GetSysTimeResponse(1_000, 0, 0))
            } else fake.executeResult = HtspResult.TransportUnavailable
        }
        gateway.enableInitialMetadata(generation)
        assertEquals(null, gateway.estimatedServerTime(generation))
        fake.lastRequest = null
        assertSame(GatewayResult.TransportUnavailable, gateway.enableInitialMetadata(generation))
        assertEquals(null, fake.lastRequest)
        fake.subscriptionFlow = flowOf(
            HtspSubscriptionEvent.Timeshift(HtspTimeshiftStatusMessage(77, false, 0, 0, 100, 100)),
        )
        val status = gateway.subscription(generation, SubscriptionId(77)).toList().single()
            as SubscriptionEvent.Timeshift
        assertEquals(null, status.estimatedServerTime)
    }

    @Test
    fun `metadata overflow retires its generation and requests reconnect for full resync`() = runTest {
        val fake = connectedFake()
        fake.eventsFlow = flowOf(HtspTransportEvent.MetadataOverflow(
            requireNotNull(fake.liveConnectionValue.value).generation, 1,
        ))
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val failures = mutableListOf<GatewayConnectionFailureEvent>()
        assertTrue(gateway.metadata.toList().isEmpty())
        backgroundScope.launch { failures += GatewayConnectionFailureEvent(gateway.awaitConnectionFailure(generation), generation) }
        testScheduler.runCurrent()
        assertSame(generation, failures.single().generation)
        assertEquals(GatewayConnectionFailure.METADATA_OVERFLOW, failures.single().failure)
        assertEquals(null, gateway.commitIfLive(generation) { true })
        assertEquals(GatewayConnectionFailure.METADATA_OVERFLOW, gateway.retirementFailure(generation))
    }

    @Test
    fun `generation identity survives distinct live snapshots and metadata projections`() = runTest {
        val fake = connectedFake()
        val source = requireNotNull(fake.liveConnectionValue.value)
        fake.eventsFlow = flowOf(HtspTransportEvent.ServerMessage(
            HtspInitialSyncCompletedMessage, source.generation, 1,
        ))
        val gateway = HtspProtocolGateway(fake)
        val first = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection
        fake.liveConnectionValue.value = source.copy(protocolVersion = 44)
        fake.connectOutcome = HtspConnectOutcome.Connected(requireNotNull(fake.liveConnectionValue.value))
        val second = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection
        assertSame(first.generation, second.generation)
        assertSame(first.generation, gateway.metadata.toList().single().generation)
        assertEquals(true, gateway.commitIfLive(second.generation) { true })
    }

    @Test
    fun `unsigned server facts retain their full range and request current protocol`() = runTest {
        val fake = connectedFake()
        val source = requireNotNull(fake.liveConnectionValue.value)
        fake.liveConnectionValue.value = source.copy(serverFacts = HtspServerFacts(
            apiVersion = 0xffff_ffffL, limitAll = 0xffff_ffffL, limitDvr = 0xffff_ffffL,
            limitStreaming = 0xffff_ffffL, uiLevel = 0xffff_ffffL,
        ))
        fake.connectOutcome = HtspConnectOutcome.Connected(requireNotNull(fake.liveConnectionValue.value))
        val facts = (HtspProtocolGateway(fake).connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.serverFacts
        assertEquals(List(5) { 0xffff_ffffL }, listOf(
            facts.apiVersion, facts.limitAll, facts.limitDvr, facts.limitStreaming, facts.uiLevel,
        ))
        assertEquals(44, fake.lastConnectOptions?.requestedProtocolVersion)
        assertEquals(10_000L, fake.lastConnectOptions?.connectTimeoutMs)
    }

    @Test
    fun `malformed stream indices retain malformed termination not infrastructure failure`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            HtspMuxPacketMessage(77, HtspFrameType.I, -1, null, null, 0, HtspBinary(byteArrayOf()))
        }
        // Library constructors enforce the same u32 range as SDK StreamIndex: no public fixture
        // can reach this defensive catch. Corrupt only this fake DTO and fail loudly if its shape
        // changes, rather than merely forwarding a library MALFORMED_MESSAGE terminal.
        val packet = HtspMuxPacketMessage(77, HtspFrameType.I, 0, null, null, 0, HtspBinary(byteArrayOf()))
        val field = packet.javaClass.declaredFields.singleOrNull { it.name == "streamIndex" }
        assertNotNull(field, "Malformed stream fixture requires the stream index field")
        requireNotNull(field).apply { isAccessible = true }.setLong(packet, -1)
        val fake = connectedFake().apply {
            subscriptionFlow = flowOf(
                HtspSubscriptionEvent.Packet(packet),
                HtspSubscriptionEvent.Speed(HtspSubscriptionSpeedMessage(77, 100)),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val terminal = gateway.subscription(generation, SubscriptionId(77)).toList().single()
            as SubscriptionEvent.Terminated
        assertEquals(SubscriptionTermination.MALFORMED_MESSAGE, terminal.reason)
    }

    @Test
    fun `locally invalid DVR reply is malformed not transport loss or server rejection`() = runTest {
        val fake = connectedFake().apply {
            executeResult = HtspResult.Ok(GetDvrCutpointsResponse(listOf(HtspDvrCutpoint(10, 10, 0))))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        assertSame(GatewayResult.MalformedReply, gateway.getDvrCutpoints(generation, DvrEntryId(7)))
    }

    private fun connectedFake(): FakeHtspConnection = FakeHtspConnection().apply {
        liveConnectionValue.value = liveConnection(HtspConnectionGeneration())
        connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
    }

    @Test
    fun `library malformed replies preserve uncertainty without implying transport loss`() = runTest {
        val fake = connectedFake().apply { executeResult = HtspResult.MalformedReply }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        assertSame(GatewayResult.MalformedReply, gateway.getDiskSpace(generation))
        assertSame(GatewayResult.MalformedReply, gateway.getDvrCutpoints(generation, DvrEntryId(7)))
        assertEquals(
            SubscriptionOperationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE),
            gateway.subscribe(generation, SubscriptionId(77), ChannelId(7), 60.seconds),
        )
        assertEquals(true, gateway.commitIfLive(generation) { true })
        fake.executeResult = HtspResult.ServerError(null)
        assertEquals(GatewayResult.ServerRejected(), gateway.getDiskSpace(generation))
        assertEquals(GatewayResult.ServerRejected(), gateway.getDvrCutpoints(generation, DvrEntryId(7)))
    }

    @Test
    fun `queue projection preserves absent zero and unsigned data errors`() = runTest {
        val counts = listOf(null, 0L, 0x8000_0000L, 0xffff_ffffL)
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            subscriptionFlow = flowOf(*counts.map { errors ->
                HtspSubscriptionEvent.Queue(HtspQueueStatusMessage(77, 8, 9, 10, 1, 2, 3, errors))
            }.toTypedArray())
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val events = gateway.subscription(generation, SubscriptionId(77)).toList()
        assertEquals(counts.size, events.size)
        events.zip(counts).forEach { (event, errors) ->
            val queue = event as SubscriptionEvent.Queue
            assertEquals(errors, queue.errorCount)
            assertEquals(8L, queue.packetCount)
            assertEquals(9L, queue.byteCount)
            assertEquals(10L, queue.delay)
            assertEquals(1L, queue.bFrameDropCount)
            assertEquals(2L, queue.pFrameDropCount)
            assertEquals(3L, queue.iFrameDropCount)
            assertEquals("SubscriptionEvent.Queue(<redacted>)", queue.toString())
        }
    }

    @Test
    fun `configuration validates normalizes and redacts endpoint secrets`() {
        val authentication = ServerAuthentication.Password(
            username = "  alice  ",
            password = " password with spaces ",
        )
        val configuration = ServerConfiguration(
            host = " tvh.example.test ",
            port = 9_982,
            authentication = authentication,
        )

        assertTrue(configuration.host == "tvh.example.test", "Host normalization failed")
        assertTrue(authentication.username == "alice", "Username normalization failed")
        assertTrue(
            authentication.password == " password with spaces ",
            "Password preservation failed",
        )
        assertEquals("ServerConfiguration(<redacted>)", configuration.toString())
        assertEquals("ServerAuthentication.Password(<redacted>)", authentication.toString())
        assertThrows(IllegalArgumentException::class.java) { ServerConfiguration(" ", 9_982) }
        assertThrows(IllegalArgumentException::class.java) { ServerConfiguration("host", 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ServerAuthentication.Password(" ", "password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerAuthentication.Password("alice", " ")
        }
    }

    @Test
    fun `connection state results facts and failures map without endpoint or throwable details`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val facts = HtspServerFacts(
            serverName = "private server",
            serverVersion = "private version",
            webRoot = "/secret",
            language = "eng",
            serverCapabilities = listOf("capability"),
            apiVersion = 42,
            admin = false,
            streaming = true,
            dvr = true,
            failedDvr = false,
            anonymous = false,
            limitAll = 10,
            limitDvr = 9,
            limitStreaming = 8,
            uiLevel = 7,
            uiLanguage = "deu",
        )
        val liveConnection = HtspLiveConnection(
            generation = sourceGeneration,
            protocolVersion = 43,
            dvrAccess = true,
            serverFacts = facts,
        )
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection)
        }
        val gateway = HtspProtocolGateway(fake)
        val observedStates = mutableListOf<GatewayState>()
        val stateCollection = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.connectionState.take(4).toList(observedStates)
        }
        val result = gateway.connect(
            ServerConfiguration(
                host = " tvh.example.test ",
                port = 9_982,
                authentication = ServerAuthentication.Password(" alice ", "secret"),
            ),
        ) as GatewayConnectResult.Connected

        assertTrue(fake.lastEndpoint?.host == "tvh.example.test", "Endpoint host mapping failed")
        assertTrue(fake.lastEndpoint?.username == "alice", "Endpoint username mapping failed")
        assertTrue(fake.lastEndpoint?.password == "secret", "Endpoint password mapping failed")
        assertEquals(43, result.connection.protocolVersion)
        assertEquals(true, result.connection.dvrAccess)
        assertEquals("private server", result.connection.serverFacts.serverName)
        assertEquals(listOf("capability"), result.connection.serverFacts.serverCapabilities)
        assertEquals("GatewayConnectResult.Connected(<redacted>)", result.toString())
        assertEquals("GatewayConnection(<redacted>)", result.connection.toString())
        assertEquals("GatewayServerFacts(<redacted>)", result.connection.serverFacts.toString())
        fake.liveConnectionValue.value = liveConnection
        assertEquals("committed", gateway.commitIfLive(result.connection.generation) { "committed" })
        fake.liveConnectionValue.value = null
        assertEquals(null, gateway.commitIfLive(result.connection.generation) { "not committed" })

        fake.connectionStateValue.value = HtspConnectionState.Connecting("private host", 9_982)
        yield()
        assertSame(GatewayState.Connecting, gateway.connectionState.value)
        fake.connectionStateValue.value = HtspConnectionState.Connecting("other private host", 9_983)
        yield()
        fake.connectionStateValue.value = HtspConnectionState.Connected(
            host = "private host",
            port = 9_982,
            connection = liveConnection,
        )
        yield()
        assertSame(GatewayState.Connected, gateway.connectionState.value)
        fake.connectionStateValue.value = HtspConnectionState.Error(
            HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
        )
        stateCollection.join()
        assertSame(GatewayState.Failed, gateway.connectionState.value)
        assertEquals(
            listOf(
                GatewayState.Disconnected,
                GatewayState.Connecting,
                GatewayState.Connected,
                GatewayState.Failed,
            ),
            observedStates,
        )

        val expectedFailures = listOf(
            GatewayConnectionFailure.AUTHENTICATION_REJECTED,
            GatewayConnectionFailure.SERVER_UNREACHABLE,
            GatewayConnectionFailure.SERVER_UNREACHABLE,
            GatewayConnectionFailure.SERVER_UNREACHABLE,
            GatewayConnectionFailure.NETWORK_UNAVAILABLE,
            GatewayConnectionFailure.INCOMPATIBLE_SERVER,
            GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
            GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
            GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
            GatewayConnectionFailure.UNSUPPORTED_SERVER_VERSION,
            GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
        )
        val failures = (HtspTransportFailureKind.entries.toList() + HtspTransportFailureKind.TRANSPORT_UNAVAILABLE).map { kind ->
            val source = HtspConnectionGeneration()
            fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection.copy(generation = source))
            val connected = gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected
            fake.fail(source, HtspTransportFailure(kind)) // Complete before the await starts.
            GatewayConnectionFailureEvent(gateway.awaitConnectionFailure(connected.connection.generation), connected.connection.generation)
        }

        assertEquals(expectedFailures, failures.map { it.failure })
        assertEquals(failures.size, failures.map { it.generation }.distinct().size)
        assertTrue(failures.all { it.toString() == "GatewayConnectionFailureEvent(<redacted>)" })
        assertFalse(failures.toString().contains("private"))

        val nextGeneration = HtspConnectionGeneration()
        fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection.copy(generation = nextGeneration))
        val nextResult = gateway.connect(ServerConfiguration("next-host", 9_982))
            as GatewayConnectResult.Connected
        assertNotSame(result.connection.generation, nextResult.connection.generation)
    }

    @Test
    fun `anonymous authentication rejection remains a typed terminal connection failure`() = runTest {
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Failed(
                HtspTransportFailure(HtspTransportFailureKind.AUTHENTICATION_REJECTED),
            )
        }

        val result = HtspProtocolGateway(fake).connect(
            ServerConfiguration(
                host = "private-host",
                port = 9_982,
                authentication = ServerAuthentication.Anonymous,
            ),
        ) as GatewayConnectResult.Failed

        assertEquals(GatewayConnectionFailure.AUTHENTICATION_REJECTED, result.failure)
        assertEquals("", fake.lastEndpoint?.username)
        assertEquals("", fake.lastEndpoint?.password)
        assertEquals("GatewayConnectResult.Failed", result.toString())
    }

    @Test
    fun `sparse now next maps an explicit patch while malformed policy is pending`() = runTest {
        val source = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            eventsFlow = flowOf(
                HtspTransportEvent.MalformedMetadata(source, 1),
                HtspTransportEvent.ServerMessage(HtspChannelNowNextMessage(1, 0, 9), source, 2),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val event = gateway.metadata.toList().single() as MetadataEvent.ChannelNowNextUpdated
        assertSame(generation, event.generation)
        assertEquals(ChannelId(1), event.channelId)
        assertEquals(EventId(0), event.currentEventId)
        assertEquals(EventId(9), event.nextEventId)
        assertEquals(true, gateway.commitIfLive(generation) { true })
    }

    @Test
    fun `metadata preserves ordered channel and tag deltas on one translated generation`() = runTest {
        val generation = HtspConnectionGeneration()
        val sourceMessages = listOf<HtspMetadataMessage>(
            HtspChannelAddMessage(HtspChannel(
                channelId = 1,
                channelName = "Private channel",
                channelUuid = "private-channel-uuid",
                channelNumber = 2,
                channelNumberMinor = 3,
                channelIcon = "private-icon",
                currentEventId = 4,
                nextEventId = 5,
                services = listOf(
                    HtspChannelService(
                        name = "Private service",
                        type = "H264",
                        content = 6,
                        conditionalAccessId = 7,
                        conditionalAccessName = "Private CA",
                        providerName = "Private provider",
                    ),
                ),
                tagIds = listOf(8),
            )),
            HtspChannelUpdateMessage(channelFixture().copy(channelName = "Updated", tagIds = emptyList())),
            HtspChannelDeleteMessage(channelId = 1),
            HtspTagAddMessage(HtspTag(
                tagId = 8,
                tagName = "Private tag",
                tagUuid = "private-tag-uuid",
                tagIndex = 9,
                tagIcon = "private-tag-icon",
                tagTitledIcon = 1,
                channelIds = listOf(1),
            )),
            HtspTagUpdateMessage(tagFixture().copy(tagId = 8, tagName = "Updated", channelIds = emptyList())),
            HtspTagDeleteMessage(tagId = 8),
            HtspEventDeleteMessage(eventId = 4),
            HtspInitialSyncCompletedMessage,
        )
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                *sourceMessages.mapIndexed { index, message ->
                    HtspTransportEvent.ServerMessage(
                        message = message,
                        generation = generation,
                        messageSequence = index + 1L,
                    )
                }.toTypedArray(),
            )
        }
        val events = HtspProtocolGateway(fake).metadata.toList()

        assertEquals(
            listOf(
                MetadataEvent.ChannelAdded::class,
                MetadataEvent.ChannelUpdated::class,
                MetadataEvent.ChannelDeleted::class,
                MetadataEvent.TagAdded::class,
                MetadataEvent.TagUpdated::class,
                MetadataEvent.TagDeleted::class,
                MetadataEvent.EventDeleted::class,
                MetadataEvent.InitialSyncCompleted::class,
            ),
            events.map { it::class },
        )
        assertTrue(events.all { it.generation === events.first().generation })

        val added = events[0] as MetadataEvent.ChannelAdded
        assertEquals(1L, added.channel.id.value)
        assertEquals("Private channel", added.channel.name)
        assertEquals(4L, added.channel.currentEventId?.value)
        assertEquals("Private service", added.channel.services?.single()?.name)
        assertEquals(8L, added.channel.tagIds?.single()?.value)
        val updated = events[1] as MetadataEvent.ChannelUpdated
        assertEquals("Updated", updated.channel.name)
        assertEquals(emptyList<ChannelId>(), updated.channel.tagIds)
        val tagAdded = events[3] as MetadataEvent.TagAdded
        assertEquals("Private tag", tagAdded.tag.name)
        assertEquals(true, tagAdded.tag.titledIcon)
        assertEquals(1L, tagAdded.tag.channelIds?.single()?.value)
        val tagUpdated = events[4] as MetadataEvent.TagUpdated
        assertEquals("Updated", tagUpdated.tag.name)
        assertEquals(emptyList<ChannelId>(), tagUpdated.tag.channelIds)
        val eventDeleted = events[6] as MetadataEvent.EventDeleted
        assertEquals(4L, eventDeleted.eventId.value)
        assertEquals(
            listOf(
                "MetadataEvent.ChannelAdded(<redacted>)",
                "MetadataEvent.ChannelUpdated(<redacted>)",
                "MetadataEvent.ChannelDeleted(<redacted>)",
                "MetadataEvent.TagAdded(<redacted>)",
                "MetadataEvent.TagUpdated(<redacted>)",
                "MetadataEvent.TagDeleted(<redacted>)",
                "MetadataEvent.EventDeleted(<redacted>)",
                "MetadataEvent.InitialSyncCompleted(<redacted>)",
            ),
            events.map(Any::toString),
        )
        assertFalse(events.toString().contains("Private"))
    }

    @Test
    fun `service classification content survives gateway mapping without defaulting missing services`() = runTest {
        val generation = HtspConnectionGeneration()
        val contents = listOf(0L, 1L, 2L, 3L, 0xffff_ffffL)
        val messages = contents.map { content ->
            decodeChannelAdd(
                mapOf(
                    "channelId" to 1L,
                    "services" to listOf(mapOf("name" to "Service", "type" to "", "content" to content)),
                ),
            )
        } + decodeChannelAdd(mapOf("channelId" to 2L))
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                *messages.mapIndexed { index, message ->
                    HtspTransportEvent.ServerMessage(message, generation, index + 1L)
                }.toTypedArray(),
            )
        }

        val events = HtspProtocolGateway(fake).metadata.toList().map { it as MetadataEvent.ChannelAdded }

        assertEquals(contents, events.dropLast(1).map { it.channel.services?.single()?.content })
        assertEquals(emptyList<Any>(), events.last().channel.services)
    }

    @Test
    fun `pinned decoder rejects a service missing required content rather than inventing other`() {
        val failure = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            decodeChannelAdd(
                mapOf("channelId" to 1L, "services" to listOf(mapOf("name" to "Service", "type" to ""))),
            )
        }
        assertTrue(failure.cause is IllegalArgumentException)
    }

    // Exercise the pinned library's internal decoder, not a second SDK decoder. Reflection stays
    // inside gateway tests and makes a dependency change to required-field handling visible.
    private fun decodeChannelAdd(fields: Map<String, Any>): HtspMetadataMessage =
        Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDecodersKt")
            .getMethod("decodeChannelAdd", Map::class.java, Long::class.javaPrimitiveType)
            .invoke(null, mapOf(
                "channelNumber" to 1L, "channelName" to "Channel", "channelIdStr" to "channel-uuid",
                "eventId" to 0L, "nextEventId" to 0L, "services" to emptyList<Any>(), "tags" to emptyList<Long>(),
            ) + fields, 44L) as HtspMetadataMessage

    @Test
    fun `credits retain repeated names with distinct roles in server order`() = runTest {
        val generation = HtspConnectionGeneration()
        val event = HtspEvent(
            eventId = 1, channelId = 2, startEpochSeconds = 10, stopEpochSeconds = 20,
            credits = HtspProgrammeCredits(listOf(
                HtspProgrammeCredit("Same person", "writer"),
                HtspProgrammeCredit("Same person", "director"),
            )),
        )
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(HtspEventAddMessage(event), HtspEventUpdateMessage(event))
                .map { HtspTransportEvent.ServerMessage(it, generation, 1) }
        }
        val events = HtspProtocolGateway(fake).metadata.toList()
        val expected = listOf(
            EpgCredit("Same person", EpgCreditRole.WRITER),
            EpgCredit("Same person", EpgCreditRole.DIRECTOR),
        )
        assertEquals(expected, (events[0] as MetadataEvent.EventAdded).event.credits)
        assertEquals(expected, (events[1] as MetadataEvent.EventUpdated).event.credits)
    }

    @Test
    fun `credits map known and unknown roles in order and preserve absent and empty updates`() = runTest {
        val generation = HtspConnectionGeneration()
        val roles = listOf("actor", "director", "guest", "presenter", "writer", "composer")
        val credits = HtspProgrammeCredits(roles.mapIndexed { index, role -> HtspProgrammeCredit("Person $index", role) })
        val expected = roles.mapIndexed { index, role -> EpgCredit("Person $index", EpgCreditRole.of(role)) }
        fun added(credits: HtspProgrammeCredits? = null): HtspEventAddMessage = HtspEventAddMessage(HtspEvent(
            eventId = 1, channelId = 2, startEpochSeconds = 10, stopEpochSeconds = 20,
            title = null, subtitle = null, summary = null, description = null,
            categories = null, keywords = null, seriesLinkUri = null, episodeUri = null,
            contentType = null, ageRating = null, ratingLabel = null, ratingIcon = null,
            ratingAuthority = null, ratingCountry = null, starRating = null, copyrightYear = null,
            firstAiredEpochSeconds = null, isNew = null, seasonNumber = null, seasonCount = null,
            episodeNumber = null, episodeCount = null, partNumber = null, partCount = null,
            episodeOnscreen = null, image = null, dvrId = null, nextEventId = null, credits = credits,
        ))
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                added(credits),
                added(),
                HtspEventUpdateMessage(added(credits).event),
                HtspEventUpdateMessage(added().event),
                HtspEventUpdateMessage(added(HtspProgrammeCredits(emptyList())).event),
            ).map { HtspTransportEvent.ServerMessage(it, generation, 1) }
        }
        val events = HtspProtocolGateway(fake).metadata.toList()
        assertEquals(expected, (events[0] as MetadataEvent.EventAdded).event.credits)
        assertEquals(null, (events[1] as MetadataEvent.EventAdded).event.credits)
        assertEquals(expected, (events[2] as MetadataEvent.EventUpdated).event.credits)
        assertEquals(null, (events[3] as MetadataEvent.EventUpdated).event.credits)
        assertEquals(emptyList<EpgCredit>(), (events[4] as MetadataEvent.EventUpdated).event.credits)
    }

    @Test
    fun `metadata maps complete event adds and nullable updates without losing wall clock semantics`() = runTest {
        val generation = HtspConnectionGeneration()
        val add = HtspEventAddMessage(
            event = HtspEvent(
                eventId = 1,
                channelId = 2,
                startEpochSeconds = -3,
                stopEpochSeconds = 2_147_483_648,
                title = "Private title",
                subtitle = "Private subtitle",
                summary = "Private summary",
                description = "Private description",
                categories = listOf("category"),
                keywords = listOf("keyword"),
                seriesLinkUri = "private-series-uri",
                episodeUri = "private-episode-uri",
                contentType = 4,
                ageRating = 5,
                ratingLabel = "Private rating",
                ratingIcon = "private-rating-icon",
                ratingAuthority = "Private authority",
                ratingCountry = "GB",
                starRating = 6,
                copyrightYear = 2026,
                firstAiredEpochSeconds = 0,
                isNew = false,
                seasonNumber = 7,
                seasonCount = 8,
                episodeNumber = 9,
                episodeCount = 10,
                partNumber = 11,
                partCount = 12,
                episodeOnscreen = "S07E09",
                image = "private-image",
                dvrId = 13,
                nextEventId = 14,
            ),
        )
        val update = HtspEventUpdateMessage(HtspEvent(
            eventId = 1,
            channelId = 3,
            startEpochSeconds = -3,
            stopEpochSeconds = 2_147_483_649,
            title = "",
            categories = emptyList(),
            keywords = emptyList(),
            contentType = 17,
            ageRating = 18,
            starRating = 19,
            copyrightYear = 0,
            firstAiredEpochSeconds = -20,
            isNew = true,
            seasonNumber = 21,
            seasonCount = 22,
            episodeNumber = 23,
            episodeCount = 24,
            partNumber = 25,
            partCount = 26,
            dvrId = 29,
            nextEventId = 30,
        ))
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(add, generation, 1),
                HtspTransportEvent.ServerMessage(update, generation, 2),
                HtspTransportEvent.ServerMessage(HtspEventDeleteMessage(1), generation, 3),
            )
        }

        val events = HtspProtocolGateway(fake).metadata.toList()

        assertEquals(
            listOf(
                MetadataEvent.EventAdded::class,
                MetadataEvent.EventUpdated::class,
                MetadataEvent.EventDeleted::class,
            ),
            events.map { it::class },
        )
        val added = (events[0] as MetadataEvent.EventAdded).event
        assertEquals(1L, added.id.value)
        assertEquals(2L, added.channelId?.value)
        assertEquals(Instant.fromEpochSeconds(-3), added.start)
        assertEquals(Instant.fromEpochSeconds(2_147_483_648), added.stop)
        assertEquals("Private title", added.title)
        assertEquals(listOf("category"), added.categories)
        assertEquals(listOf("keyword"), added.keywords)
        assertEquals(false, added.isNew)
        assertEquals(Instant.fromEpochSeconds(0), added.firstAired)
        assertEquals(13L, added.dvrEntryId?.value)
        assertEquals(14L, added.nextEventId?.value)
        assertEquals(null, added.episodeId)
        assertEquals(null, added.seriesLinkId)

        val updated = (events[1] as MetadataEvent.EventUpdated).event
        assertEquals(Instant.fromEpochSeconds(-3), updated.start)
        assertEquals(Instant.fromEpochSeconds(2_147_483_649), updated.stop)
        assertEquals("", updated.title)
        assertEquals(emptyList<String>(), updated.categories)
        assertEquals(emptyList<String>(), updated.keywords)
        assertEquals(true, updated.isNew)
        assertEquals(Instant.fromEpochSeconds(-20), updated.firstAired)
        assertEquals(null, updated.episodeId)
        assertEquals(null, updated.seriesLinkId)
        assertEquals(29L, updated.dvrEntryId?.value)
        assertEquals(30L, updated.nextEventId?.value)
        assertTrue(events.all { it.generation === events.first().generation })
        assertFalse(events.toString().contains("Private"), "Gateway EPG rendering exposed metadata")
    }

    @Test
    fun `recording streams map per file preserving placeholders absence and dimension limits`() = runTest {
        val generation = HtspConnectionGeneration()
        val fields = mapOf(
            "id" to 1L,
            "enabled" to 1L, "start" to 10L, "stop" to 20L,
            "startExtra" to 0L, "stopExtra" to 0L, "retention" to 0L, "removal" to 0L,
            "priority" to 0L, "contentType" to 0L, "state" to "completed",
            "files" to listOf(
                emptyMap<String, Any>(),
                mapOf("info" to emptyList<Any>()),
                mapOf("info" to listOf(
                    mapOf("type" to "H264", "width" to 1920L, "height" to 1080L),
                    mapOf("type" to "AAC", "language" to "private-language"),
                    mapOf("type" to "future-codec", "width" to 1L, "height" to Int.MAX_VALUE.toLong()),
                    "malformed",
                    mapOf("type" to 1L, "language" to 2L, "width" to 0L, "height" to -1L),
                    mapOf("type" to "H265", "width" to 2_147_483_648L, "height" to 720L),
                    mapOf("type" to "HEVC", "width" to 1280L, "height" to 0xffff_ffffL),
                )),
            ),
        )
        // Use the pinned decoder to exercise malformed elements without another SDK parser.
        val decoder = Class.forName("at.bernhardberger.tvheadend.htsp.messages.HtspServerMessageDecodersKt")
        val messages = listOf("decodeDvrEntryAdd", "decodeDvrEntryUpdate").map { name ->
            decoder.getMethod(name, Map::class.java, Long::class.javaPrimitiveType)
                .invoke(null, fields, 36L) as HtspMetadataMessage
        }
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(*messages.mapIndexed { index, message ->
                HtspTransportEvent.ServerMessage(message, generation, index + 1L)
            }.toTypedArray())
        }
        val events = HtspProtocolGateway(fake).metadata.toList()
        val entries = listOf(
            (events[0] as MetadataEvent.DvrEntryAdded).entry,
            (events[1] as MetadataEvent.DvrEntryUpdated).entry,
        )
        for (entry in entries) {
            val files = requireNotNull(entry.files)
            assertEquals(3, files.size)
            assertEquals(null, files[0].streams)
            assertEquals(emptyList<Any>(), files[1].streams)
            val streams = requireNotNull(files[2].streams)
            assertEquals(
                listOf(SubscriptionStreamType.H264, SubscriptionStreamType.AAC,
                    SubscriptionStreamType.UNKNOWN, SubscriptionStreamType.UNKNOWN,
                    SubscriptionStreamType.UNKNOWN, SubscriptionStreamType.H265, SubscriptionStreamType.H265),
                streams.map { it.type },
            )
            assertEquals(listOf(1920, null, 1, null, null, null, 1280), streams.map { it.width })
            assertEquals(listOf(1080, null, Int.MAX_VALUE, null, null, 720, null), streams.map { it.height })
            assertEquals(listOf(null, "private-language", null, null, null, null, null), streams.map { it.language })
            assertFalse(listOf(entry, files, streams).toString().contains("private"),
                "Recording metadata rendering exposed server text")
        }
    }

    @Test
    fun `metadata maps DVR entry and rule payloads without leaking paths or errors`() = runTest {
        val generation = HtspConnectionGeneration()
        val files = mutableListOf(
            HtspDvrRecordingFile(
                path = "/private/recording.ts",
                startEpochSeconds = 10,
                stopEpochSeconds = 20,
                sizeBytes = 30,
            ),
        )
        val add = HtspDvrEntryAddMessage(dvrFixture().copy(
            entryId = 1,
            enabled = true,
            channelId = 2,
            eventId = 3,
            startEpochSeconds = -4,
            stopEpochSeconds = 2_147_483_648,
            startExtraMinutes = 5,
            stopExtraMinutes = 6,
            contentType = 7,
            playCount = 8,
            playPositionSeconds = 9,
            title = "Private title",
            seasonCount = 8,
            episodeOnscreen = "S2 E3",
            owner = "test-owner",
            creator = "test-creator",
            files = files,
            path = "/private/path.ts",
            dvrConfigUuid = "private-config",
            state = "recording",
            error = "Private error",
            subscriptionError = "scrambled",
        ))
        val update = HtspDvrEntryUpdateMessage(dvrFixture().copy(
            entryId = 1,
            enabled = false,
            title = "",
            files = emptyList(),
            state = "completed",
            error = "File missing",
            subscriptionError = "invalidTarget",
            seasonCount = 9,
            episodeOnscreen = "S2 E4",
            owner = "updated-owner",
            creator = "updated-creator",
        ))
        val autorecAdd = HtspAutorecEntryAddMessage(autorecFixture().copy(
            id = "private-auto",
            enabled = true,
            maxDurationSeconds = 60,
            minDurationSeconds = 0,
            retentionDays = 1,
            removalDays = 2,
            daysOfWeekMask = 127,
            approximateStartMinutesSinceMidnight = 3,
            startMinutesSinceMidnight = 4,
            startWindowEndMinutesSinceMidnight = 5,
            priority = 6,
            startExtraMinutes = 7,
            stopExtraMinutes = 8,
            duplicateDetection = 9,
            maximumRecordingCount = 10,
            broadcastType = 11,
            title = "Private autorec",
            owner = "test-owner",
            creator = "test-creator",
            channelId = 12,
            dvrConfigUuid = "private-auto-config",
        ))
        val messages = listOf<HtspMetadataMessage>(
            add,
            update,
            HtspDvrEntryDeleteMessage(1),
            autorecAdd,
            HtspAutorecEntryUpdateMessage(autorecFixture().copy(
                id = "private-auto", title = "", enabled = false,
                owner = "updated-owner", creator = "updated-creator",
            )),
            HtspAutorecEntryDeleteMessage("private-auto"),
            HtspTimerecEntryAddMessage(timerecFixture().copy(
                id = "private-time",
                enabled = true,
                channelId = null,
                startMinutesSinceMidnight = null,
                stopMinutesSinceMidnight = null,
                dvrConfigUuid = "private-time-config",
                removalDays = 0xffff_ffffL,
                owner = "test-owner",
                creator = "test-creator",
            )),
            HtspTimerecEntryUpdateMessage(timerecFixture().copy(
                id = "private-time",
                enabled = false,
                removalDays = 0,
                owner = "updated-owner",
                creator = "updated-creator",
                channelId = null,
                startMinutesSinceMidnight = null,
                stopMinutesSinceMidnight = null,
            )),
            HtspTimerecEntryDeleteMessage("private-time"),
        )
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                *messages.mapIndexed { index, message ->
                    HtspTransportEvent.ServerMessage(message, generation, index + 1L)
                }.toTypedArray(),
            )
        }
        files.clear()

        val events = HtspProtocolGateway(fake).metadata.toList()

        assertEquals(
            listOf(
                MetadataEvent.DvrEntryAdded::class,
                MetadataEvent.DvrEntryUpdated::class,
                MetadataEvent.DvrEntryDeleted::class,
                MetadataEvent.AutorecRuleAdded::class,
                MetadataEvent.AutorecRuleUpdated::class,
                MetadataEvent.AutorecRuleDeleted::class,
                MetadataEvent.TimerecRuleAdded::class,
                MetadataEvent.TimerecRuleUpdated::class,
                MetadataEvent.TimerecRuleDeleted::class,
            ),
            events.map { it::class },
        )
        val added = (events[0] as MetadataEvent.DvrEntryAdded).entry
        assertEquals(1L, added.id.value)
        assertEquals(true, added.enabled)
        assertEquals(2L, added.channelId?.value)
        assertEquals(Instant.fromEpochSeconds(-4), added.start)
        assertEquals(Instant.fromEpochSeconds(2_147_483_648), added.stop)
        assertEquals(9.seconds, added.playPosition)
        assertEquals(8L, added.seasonCount)
        assertEquals("S2 E3", added.episodeOnscreen)
        assertEquals("test-owner", added.owner)
        assertEquals("test-creator", added.creator)
        assertEquals("/private/recording.ts", added.files?.single()?.path)
        assertEquals(DvrEntryState.RECORDING, added.state)
        assertEquals(GatewayDvrFailure.PRESENT, added.failure)
        assertEquals(DvrSubscriptionError.SCRAMBLED, added.subscriptionError)
        val updated = (events[1] as MetadataEvent.DvrEntryUpdated).entry
        assertEquals(false, updated.enabled)
        assertEquals(9L, updated.seasonCount)
        assertEquals("S2 E4", updated.episodeOnscreen)
        assertEquals("updated-owner", updated.owner)
        assertEquals("updated-creator", updated.creator)
        assertEquals("", updated.title)
        assertEquals(emptyList<Any>(), updated.files)
        assertEquals(DvrEntryState.COMPLETED, updated.state)
        assertEquals(GatewayDvrFailure.FILE_MISSING, updated.failure)
        assertEquals(DvrSubscriptionError.INVALID_TARGET, updated.subscriptionError)
        assertEquals("private-auto", (events[3] as MetadataEvent.AutorecRuleAdded).rule.id.value)
        assertEquals("test-owner", (events[3] as MetadataEvent.AutorecRuleAdded).rule.owner)
        assertEquals("test-creator", (events[3] as MetadataEvent.AutorecRuleAdded).rule.creator)
        assertEquals("updated-owner", (events[4] as MetadataEvent.AutorecRuleUpdated).rule.owner)
        assertEquals("updated-creator", (events[4] as MetadataEvent.AutorecRuleUpdated).rule.creator)
        assertEquals(false, (events[4] as MetadataEvent.AutorecRuleUpdated).rule.enabled)
        assertEquals("", (events[4] as MetadataEvent.AutorecRuleUpdated).rule.title)
        val timerecAdded = (events[6] as MetadataEvent.TimerecRuleAdded).rule
        assertEquals(0xffff_ffffL, timerecAdded.removalDays)
        assertEquals("test-owner", timerecAdded.owner)
        assertEquals("test-creator", timerecAdded.creator)
        assertEquals(null, timerecAdded.channelId)
        assertEquals(null, timerecAdded.startMinutesSinceMidnight)
        assertEquals(null, timerecAdded.stopMinutesSinceMidnight)
        val timerecUpdated = (events[7] as MetadataEvent.TimerecRuleUpdated).rule
        assertEquals(0L, timerecUpdated.removalDays)
        assertEquals("updated-owner", timerecUpdated.owner)
        assertEquals("updated-creator", timerecUpdated.creator)
        assertEquals(false, timerecUpdated.enabled)
        assertEquals(null, timerecUpdated.channelId)
        assertEquals(null, timerecUpdated.startMinutesSinceMidnight)
        assertEquals(null, timerecUpdated.stopMinutesSinceMidnight)
        assertTrue(events.all { it.generation === events.first().generation })
        assertFalse(events.toString().contains("Private"), "Gateway DVR rendering exposed metadata")
        assertFalse(events.toString().contains("/private"), "Gateway DVR rendering exposed a path")
    }

    @Test
    fun `future DVR state and subscription error map to safe unknown values`() = runTest {
        val generation = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(
                        entryId = 1,
                        entryUuid = null,
                        state = "future-private-state",
                        subscriptionError = "future-private-error",
                    ),
                    generation,
                    1,
                ),
            )
        }
        val entry = (HtspProtocolGateway(fake).metadata.toList().single() as MetadataEvent.DvrEntryStatsUpdated).stats
        assertSame(DvrEntryState.UNKNOWN, entry.state)
        assertSame(DvrSubscriptionError.UNKNOWN, entry.subscriptionError)
        assertEquals("UNKNOWN", entry.state.toString())
        assertEquals("UNKNOWN", entry.subscriptionError.toString())
    }

    @Test
    fun `DVR user abort is distinct from a recording failure`() = runTest {
        val generation = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(entryId = 1, entryUuid = null, state = "completed", error = "Aborted by user"),
                    generation,
                    1,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(entryId = 1, entryUuid = null, state = "completed", error = "other failure"),
                    generation,
                    2,
                ),
            )
        }
        val events = HtspProtocolGateway(fake).metadata.toList()
        assertEquals(GatewayDvrFailure.ABORTED, (events[0] as MetadataEvent.DvrEntryStatsUpdated).stats.failure)
        assertEquals(GatewayDvrFailure.PRESENT, (events[1] as MetadataEvent.DvrEntryStatsUpdated).stats.failure)
    }

    @Test
    fun `DVR failure none is not an error and subscription tokens include source fallbacks`() = runTest {
        val generation = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryAddMessage(dvrFixture().copy(
                        entryId = 1,
                        state = "completed",
                        error = "none",
                        subscriptionError = "No service assigned to channel",
                    )),
                    generation,
                    1,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(
                        entryId = 1,
                        entryUuid = null,
                        state = "completed",
                        error = "File missing",
                        subscriptionError = "Invalid service",
                    ),
                    generation,
                    2,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(entryId = 1, entryUuid = null, state = "invalid", subscriptionError = "noDiskSpace"),
                    generation,
                    3,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(entryId = 1, entryUuid = null, state = "invalid", subscriptionError = "noService"),
                    generation,
                    4,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryStatsMessage(entryId = 1, entryUuid = null, state = "invalid", subscriptionError = "invalidService"),
                    generation,
                    5,
                ),
            )
        }

        val events = HtspProtocolGateway(fake).metadata.toList()
        val added = (events[0] as MetadataEvent.DvrEntryAdded).entry
        assertEquals(DvrEntryState.COMPLETED, added.state)
        assertEquals(GatewayDvrFailure.NONE, added.failure)
        assertEquals(DvrSubscriptionError.NO_SERVICE, added.subscriptionError)
        val updated = (events[1] as MetadataEvent.DvrEntryStatsUpdated).stats
        assertEquals(DvrEntryState.COMPLETED, updated.state)
        assertEquals(GatewayDvrFailure.FILE_MISSING, updated.failure)
        assertEquals(DvrSubscriptionError.INVALID_SERVICE, updated.subscriptionError)
        assertTrue(
            events.drop(1).all {
                it is MetadataEvent.DvrEntryStatsUpdated
            },
        )
        val invalid = (events[2] as MetadataEvent.DvrEntryStatsUpdated).stats
        assertEquals(DvrEntryState.INVALID, invalid.state)
        assertEquals(DvrSubscriptionError.NO_DISK_SPACE, invalid.subscriptionError)
        assertEquals(
            DvrSubscriptionError.UNKNOWN,
            (events[3] as MetadataEvent.DvrEntryStatsUpdated).stats.subscriptionError,
        )
        assertEquals(
            DvrSubscriptionError.UNKNOWN,
            (events[4] as MetadataEvent.DvrEntryStatsUpdated).stats.subscriptionError,
        )
        assertFalse(events.toString().contains("service"), "Gateway DVR rendering exposed a server error")
    }

    @Test
    fun `every public subscription issue has an explicit DVR mapping`() {
        val expected = mapOf(
            SubscriptionIssue.NO_INPUT to DvrSubscriptionError.BAD_SIGNAL,
            SubscriptionIssue.NO_FREE_ADAPTER to DvrSubscriptionError.NO_FREE_ADAPTER,
            SubscriptionIssue.SCRAMBLED to DvrSubscriptionError.SCRAMBLED,
            SubscriptionIssue.BAD_SIGNAL to DvrSubscriptionError.BAD_SIGNAL,
            SubscriptionIssue.TUNING_FAILED to DvrSubscriptionError.TUNING_FAILED,
            SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN to
                DvrSubscriptionError.SUBSCRIPTION_OVERRIDDEN,
            SubscriptionIssue.MUX_NOT_ENABLED to DvrSubscriptionError.MUX_NOT_ENABLED,
            SubscriptionIssue.INVALID_TARGET to DvrSubscriptionError.INVALID_TARGET,
            SubscriptionIssue.USER_ACCESS to DvrSubscriptionError.USER_ACCESS,
            SubscriptionIssue.USER_LIMIT to DvrSubscriptionError.USER_LIMIT,
            SubscriptionIssue.WEAK_STREAM to DvrSubscriptionError.WEAK_STREAM,
            SubscriptionIssue.NO_DISK_SPACE to DvrSubscriptionError.NO_DISK_SPACE,
            SubscriptionIssue.UNKNOWN to DvrSubscriptionError.UNKNOWN,
        )
        val publicValues = SubscriptionIssue::class.java.fields
            .filter { field -> field.type == SubscriptionIssue::class.java }
            .mapTo(mutableSetOf()) { field -> field.get(null) as SubscriptionIssue }

        assertEquals(publicValues, expected.keys)
        expected.forEach { (issue, result) ->
            assertEquals(result, issue.toDvrSubscriptionError())
        }
        assertEquals(
            DvrSubscriptionError.UNKNOWN,
            (null as SubscriptionIssue?).toDvrSubscriptionError(),
        )
    }

    @Test
    fun `EPG query maps exact generation horizon results and redacted events`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                GetEventsResponse(
                    listOf(
                        HtspEvent(
                            eventId = 1,
                            channelId = 2,
                            startEpochSeconds = -3,
                            stopEpochSeconds = 2_147_483_648,
                            title = "Private title",
                            subtitle = "Private subtitle",
                            summary = "Private summary",
                            description = "Private description",
                            categories = listOf("category"),
                            keywords = emptyList(),
                            credits = HtspProgrammeCredits(listOf(HtspProgrammeCredit("Guest", "guest"))),
                            seriesLinkUri = "private-series",
                            episodeUri = "private-episode",
                            contentType = 4,
                            ageRating = 5,
                            ratingLabel = "Private rating",
                            ratingIcon = "private-icon",
                            ratingAuthority = "Private authority",
                            ratingCountry = "GB",
                            starRating = 6,
                            copyrightYear = 2026,
                            firstAiredEpochSeconds = 0,
                            isNew = false,
                            seasonNumber = 7,
                            seasonCount = 8,
                            episodeNumber = 9,
                            episodeCount = 10,
                            partNumber = 11,
                            partCount = 12,
                            episodeOnscreen = "S07E09",
                            image = "private-image",
                            dvrId = 13,
                            nextEventId = 14,
                        ),
                    ),
                ),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val maxTime = Instant.fromEpochSeconds(2_147_483_649, 999_000_000)

        val result = gateway.queryEpg(generation, ChannelId(2), maxTime) as GatewayResult.Ok
        val request = fake.lastRequest as GetEventsRequest
        val event = result.value.single()

        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertEquals(2L, request.channelId)
        assertEquals(2_147_483_649L, request.maxTimeEpochSeconds)
        assertEquals(null, request.eventId)
        assertEquals(null, request.language)
        assertEquals(null, request.numFollowing)
        assertEquals(Instant.fromEpochSeconds(-3), event.start)
        assertEquals(Instant.fromEpochSeconds(2_147_483_648), event.stop)
        assertEquals(false, event.isNew)
        assertEquals(emptyList<String>(), event.keywords)
        assertEquals(listOf(EpgCredit("Guest", EpgCreditRole.GUEST)), event.credits)
        assertEquals(13L, event.dvrEntryId?.value)
        assertEquals("GatewayEpgQueryEvent(<redacted>)", event.toString())
        assertFalse(result.value.toString().contains("Private"))
        assertThrows(UnsupportedOperationException::class.java) {
            (result.value as MutableList<*>).clear()
        }

        val failures = listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.queryEpg(generation, ChannelId(2), maxTime))
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.queryEpg(generation, ChannelId(2), maxTime)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `EPG search maps every filter requests full events and preserves failures`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                EpgQueryResponse.Events(
                    listOf(
                        HtspEvent(
                            eventId = 7,
                            channelId = 8,
                            startEpochSeconds = 9,
                            stopEpochSeconds = 10,
                            title = "Private search result",
                            subtitle = null,
                            summary = null,
                            description = null,
                            categories = null,
                            keywords = null,
                            seriesLinkUri = null,
                            episodeUri = null,
                            contentType = null,
                            ageRating = null,
                            ratingLabel = null,
                            ratingIcon = null,
                            ratingAuthority = null,
                            ratingCountry = null,
                            starRating = null,
                            copyrightYear = null,
                            firstAiredEpochSeconds = null,
                            isNew = null,
                            seasonNumber = null,
                            seasonCount = null,
                            episodeNumber = null,
                            episodeCount = null,
                            partNumber = null,
                            partCount = null,
                            episodeOnscreen = null,
                            image = null,
                            dvrId = null,
                            nextEventId = null,
                        ),
                    ),
                ),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val search = EpgSearchRequest.create(
            query = "private-query",
            fullText = true,
            channelId = at.bernhardberger.tvheadend.sdk.core.ChannelId(11),
            tagId = ChannelTagId(12),
            contentType = 13,
            language = "de",
            minimumDuration = 14.seconds,
            maximumDuration = 15.seconds,
        )

        val result = gateway.searchEpg(generation, search) as GatewayResult.Ok
        val request = fake.lastRequest as EpgQueryRequest

        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertEquals("private-query", request.query)
        assertEquals(11L, request.channelId)
        assertEquals(12L, request.tagId)
        assertEquals(13L, request.contentType)
        assertEquals("de", request.language)
        assertEquals(true, request.fullText)
        assertEquals(null, request.mergeText)
        assertEquals(true, request.full)
        assertEquals(14L, request.minDurationSeconds)
        assertEquals(15L, request.maxDurationSeconds)
        assertEquals(7L, result.value.single().id.value)
        assertFalse(result.value.toString().contains("Private"))
        assertThrows(UnsupportedOperationException::class.java) {
            (result.value as MutableList<*>).clear()
        }

        fake.executeResult = HtspResult.Ok(EpgQueryResponse.EventIds(listOf(7)))
        assertSame(GatewayResult.MalformedReply, gateway.searchEpg(generation, search))

        val failures = listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.searchEpg(generation, search))
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.searchEpg(generation, search)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `initial metadata uses server time for bounded async EPG`() = runTest {
        val serverNow = 1_000_000L
        val serverTime = GetSysTimeResponse(
            timeEpochSeconds = serverNow,
            legacyTimezoneHoursWestOfGmt = 0,
            gmtOffsetMinutes = 0,
        )

        suspend fun requestFor(
            protocolVersion: Int,
            coveragePolicy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
            serverTimeResult: HtspResult<GetSysTimeResponse> = HtspResult.Ok(serverTime),
            malformedBeforeSync: Boolean = false,
        ): Pair<GatewayResult<Unit>, List<HtspRequest<*>>> {
            val sourceGeneration = HtspConnectionGeneration()
            val metadataEvents = MutableSharedFlow<HtspTransportEvent>()
            val requests = mutableListOf<HtspRequest<*>>()
            val fake = FakeHtspConnection().apply {
                liveConnectionValue.value = liveConnection(sourceGeneration, protocolVersion)
                connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
                eventsFlow = metadataEvents
                batchesFlow = metadataEvents.filterNot { it is HtspTransportEvent.MalformedMetadata }.map { event ->
                    if (malformedBeforeSync) listOf(HtspTransportEvent.MalformedMetadata(sourceGeneration, 1), event)
                    else listOf(event)
                }
                subscriptionFlow = flowOf(
                    HtspSubscriptionEvent.Timeshift(HtspTimeshiftStatusMessage(77, false, 0, 0, 100, 100)),
                )
                beforeExecute = { request ->
                    requests += request
                    when (request) {
                        is GetSysTimeRequest -> executeResult = serverTimeResult
                        is EnableAsyncMetadataRequest -> {
                            executeResult = HtspResult.Ok(HtspEmptyResponse)
                            if (malformedBeforeSync) metadataEvents.emit(
                                HtspTransportEvent.MalformedMetadata(sourceGeneration, 1),
                            )
                            metadataEvents.emit(
                                HtspTransportEvent.ServerMessage(
                                    message = HtspInitialSyncCompletedMessage,
                                    generation = sourceGeneration,
                                    messageSequence = 1,
                                ),
                            )
                        }
                        else -> error("Unexpected initial metadata request")
                    }
                }
            }
            val gateway = HtspProtocolGateway(fake, coveragePolicy)
            val generation = (gateway.connect(ServerConfiguration("host", 9_982))
                as GatewayConnectResult.Connected).connection.generation

            val completions = mutableListOf<MetadataEvent.InitialSyncCompleted>()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                gateway.metadata.collect { if (it is MetadataEvent.InitialSyncCompleted) completions += it }
            }
            val result = gateway.enableInitialMetadata(generation)
            runCurrent()
            if (result is GatewayResult.Ok) {
                assertEquals(
                    if (malformedBeforeSync) null else Instant.fromEpochSeconds(serverNow + coveragePolicy.futureHorizon.inWholeSeconds),
                    completions.single().epgThrough,
                )
            } else {
                assertTrue(completions.isEmpty())
            }
            collector.cancel()
            assertSame(sourceGeneration, fake.lastExpectedGeneration)
            val status = gateway.subscription(generation, SubscriptionId(77)).toList().single() as SubscriptionEvent.Timeshift
            assertEquals(serverTimeResult is HtspResult.Ok, status.estimatedServerTime != null)
            return result to requests
        }

        requestFor(protocolVersion = 36, malformedBeforeSync = true)
        val (supportedResult, supportedRequests) = requestFor(protocolVersion = 36)
        assertTrue(supportedResult is GatewayResult.Ok)
        assertTrue(supportedRequests.singleOrNull { it is GetSysTimeRequest } is GetSysTimeRequest)
        val supported = supportedRequests.last() as EnableAsyncMetadataRequest
        assertEquals(true, supported.epg)
        assertEquals(serverNow + 86_400L, supported.epgMaxTimeEpochSeconds)
        assertEquals(6, supported.minimumProtocolVersion)

        val (configuredResult, configuredRequests) = requestFor(
            protocolVersion = 44,
            coveragePolicy = EpgCoveragePolicy.create(7.days),
        )
        assertTrue(configuredResult is GatewayResult.Ok)
        val configured = configuredRequests.last() as EnableAsyncMetadataRequest
        assertEquals(serverNow + 604_800L, configured.epgMaxTimeEpochSeconds)

        val (failure, failedRequests) = requestFor(
            protocolVersion = 36,
            serverTimeResult = HtspResult.Timeout,
        )
        assertSame(GatewayResult.Timeout, failure)
        assertTrue(failedRequests.single() is GetSysTimeRequest)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `configured async EPG horizon survives a fresh connection generation`() = runTest {
        val serverTimes = ArrayDeque(listOf(1_000_000L, 2_000_000L))
        val metadataEvents = MutableSharedFlow<HtspTransportEvent>()
        val requests = mutableListOf<HtspRequest<*>>()
        val sourceGenerations = mutableListOf<HtspConnectionGeneration>()
        val timeRequestGenerations = mutableListOf<HtspConnectionGeneration?>()
        val fake = FakeHtspConnection().apply {
            eventsFlow = metadataEvents
            subscriptionFlow = flowOf(
                HtspSubscriptionEvent.Timeshift(HtspTimeshiftStatusMessage(77, false, 0, 0, 100, 100)),
            )
            beforeExecute = { request ->
                requests += request
                when (request) {
                    is GetSysTimeRequest -> {
                        timeRequestGenerations += lastExpectedGeneration
                        executeResult = HtspResult.Ok(
                            GetSysTimeResponse(
                                timeEpochSeconds = serverTimes.removeFirst(),
                                legacyTimezoneHoursWestOfGmt = 0,
                                gmtOffsetMinutes = 0,
                            ),
                        )
                    }
                    is EnableAsyncMetadataRequest -> {
                        executeResult = HtspResult.Ok(HtspEmptyResponse)
                        metadataEvents.emit(
                            HtspTransportEvent.ServerMessage(
                                message = HtspInitialSyncCompletedMessage,
                                generation = requireNotNull(liveConnectionValue.value).generation,
                                messageSequence = requests.size.toLong(),
                            ),
                        )
                    }
                    else -> error("Unexpected initial metadata request")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake, EpgCoveragePolicy.create(7.days))
        val completions = mutableListOf<MetadataEvent.InitialSyncCompleted>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.metadata.collect { if (it is MetadataEvent.InitialSyncCompleted) completions += it }
        }

        repeat(2) { index ->
            val sourceGeneration = HtspConnectionGeneration()
            sourceGenerations += sourceGeneration
            val liveConnection = liveConnection(sourceGeneration, protocolVersion = 36)
            fake.liveConnectionValue.value = liveConnection
            fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection)
            val generation = (gateway.connect(ServerConfiguration("host-$index", 9_982))
                as GatewayConnectResult.Connected).connection.generation

            val before = gateway.subscription(generation, SubscriptionId(77)).toList().single() as SubscriptionEvent.Timeshift
            assertEquals(null, before.estimatedServerTime)
            assertTrue(gateway.enableInitialMetadata(generation) is GatewayResult.Ok)
            runCurrent()
            assertEquals(
                Instant.fromEpochSeconds((index + 1) * 1_000_000L + 604_800L),
                completions.last().epgThrough,
            )
            assertSame(sourceGeneration, fake.lastExpectedGeneration)
            val after = gateway.subscription(generation, SubscriptionId(77)).toList().single() as SubscriptionEvent.Timeshift
            assertTrue(requireNotNull(after.estimatedServerTime).epochSeconds >= (index + 1) * 1_000_000L)
            fake.liveConnectionValue.value = null
            val retired = gateway.subscription(generation, SubscriptionId(77)).toList().single() as SubscriptionEvent.Timeshift
            assertEquals(null, retired.estimatedServerTime)
        }

        val asyncRequests = requests.filterIsInstance<EnableAsyncMetadataRequest>()
        assertEquals(2, asyncRequests.size)
        assertEquals(listOf(1_604_800L, 2_604_800L), asyncRequests.map { it.epgMaxTimeEpochSeconds })
        assertEquals(2, timeRequestGenerations.size)
        sourceGenerations.zip(timeRequestGenerations).forEach { (expected, actual) ->
            assertSame(expected, actual)
        }
    }

    @Test
    fun `DVR configs and disk space map generation results failures and redaction`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                GetDvrConfigsResponse(
                    listOf(
                        HtspDvrConfig(
                            dvrConfigUuid = "private-config",
                            name = "Default",
                            comment = "private-comment",
                        ),
                    ),
                ),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val configs = gateway.getDvrConfigs(generation) as GatewayResult.Ok
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertTrue(fake.lastRequest is GetDvrConfigsRequest)
        assertEquals(
            listOf(DvrConfiguration(DvrConfigId("private-config"), "Default", "private-comment")),
            configs.value,
        )
        assertEquals("DvrConfiguration(<redacted>)", configs.value.single().toString())
        assertFalse(configs.value.toString().contains("private"))
        assertThrows(UnsupportedOperationException::class.java) {
            (configs.value as MutableList<*>).clear()
        }

        fake.executeResult = HtspResult.Ok(GetDvrConfigsResponse(null))
        val empty = gateway.getDvrConfigs(generation) as GatewayResult.Ok
        assertEquals(emptyList<DvrConfiguration>(), empty.value)

        fake.executeResult = HtspResult.Ok(GetDiskSpaceResponse(8, null, 16))
        val disk = gateway.getDiskSpace(generation) as GatewayResult.Ok
        assertTrue(fake.lastRequest is GetDiskSpaceRequest)
        assertEquals(DvrDiskSpace(8, null, 16), disk.value)
        assertEquals("DvrDiskSpace(freeBytes=8, usedBytes=null, totalBytes=16)", disk.value.toString())

        val failures = listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.getDvrConfigs(generation))
            assertEquals(expected, gateway.getDiskSpace(generation))
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.getDvrConfigs(generation)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
        try {
            gateway.getDiskSpace(generation)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `stream profiles map exact generation immutable UUIDs failures and cancellation`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                GetProfilesResponse(
                    listOf(
                        HtspProfile(
                            profileUuid = "0123456789abcdef0123456789abcdef",
                            name = "Pass",
                            comment = "Original streams",
                        ),
                    ),
                ),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val available = gateway.getStreamProfiles(generation) as GatewayResult.Ok
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertTrue(fake.lastRequest is GetProfilesRequest)
        assertEquals(
            listOf(
                StreamProfile(
                    StreamProfileId("0123456789abcdef0123456789abcdef"),
                    "Pass",
                    "Original streams",
                ),
            ),
            available.value,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (available.value as MutableList<*>).clear()
        }

        fake.executeResult = HtspResult.Ok(GetProfilesResponse(null))
        assertSame(GatewayResult.MalformedReply, gateway.getStreamProfiles(generation))
        fake.executeResult = HtspResult.Ok(
            GetProfilesResponse(listOf(HtspProfile("INVALID", "", ""))),
        )
        assertSame(GatewayResult.MalformedReply, gateway.getStreamProfiles(generation))
        fake.executeResult = HtspResult.Ok(
            GetProfilesResponse(
                List(2) { HtspProfile("0123456789abcdef0123456789abcdef", "", "") },
            ),
        )
        assertSame(GatewayResult.MalformedReply, gateway.getStreamProfiles(generation))

        val failures = listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.getStreamProfiles(generation))
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        val caught = try {
            gateway.getStreamProfiles(generation)
            null
        } catch (failure: CancellationException) {
            failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `DVR entry mutations map complete requests and require semantic acceptance`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(AddDvrEntryResponse(7))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val schedule = DvrScheduleRequest(
            schedule = DvrSchedule.ExplicitTime(
                channelId = at.bernhardberger.tvheadend.sdk.core.ChannelId(2),
                start = Instant.fromEpochSeconds(-3),
                stop = Instant.fromEpochSeconds(4),
            ),
            configId = DvrConfigId("private-config"),
            language = "eng",
            title = "private-title",
            subtitle = "private-subtitle",
            summary = "private-summary",
            description = "private-description",
            ageRating = 12,
        )

        val scheduled = gateway.scheduleDvrEntry(generation, schedule) as GatewayResult.Ok
        val addRequest = fake.lastRequest as AddDvrEntryRequest
        val selector = addRequest.selector as AddDvrEntrySelector.ExplicitChannelTime
        assertEquals(DvrEntryId(7), scheduled.value)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertEquals(2L, selector.channelId)
        assertEquals(-3L, selector.startEpochSeconds)
        assertEquals(4L, selector.stopEpochSeconds)
        assertEquals("private-config", addRequest.configName)
        assertEquals("eng", addRequest.language)
        assertEquals("private-title", addRequest.title)
        assertEquals("private-subtitle", addRequest.subtitle)
        assertEquals("private-summary", addRequest.summary)
        assertEquals("private-description", addRequest.description)
        assertEquals(12L, addRequest.ageRating)
        assertFalse(scheduled.toString().contains("private"))

        fake.executeResult = HtspResult.Ok(UpdateDvrEntryResponse)
        val update = DvrEntryUpdate(
            channelId = at.bernhardberger.tvheadend.sdk.core.ChannelId(3),
            configId = DvrConfigId("private-next-config"),
            title = "private-next-title",
            subtitle = "private-next-subtitle",
            summary = "private-next-summary",
            description = "private-next-description",
            language = "deu",
            comment = "private-comment",
            enabled = true,
            start = Instant.fromEpochSeconds(-5),
            stop = Instant.fromEpochSeconds(6),
            startExtraMinutes = (-7).minutes,
            stopExtraMinutes = 8.minutes,
            retentionDays = 9,
            removalDays = 10,
            priority = DvrPriority.HIGH,
            ageRating = 13,
        )
        assertTrue(gateway.updateDvrEntry(generation, DvrEntryId(7), update, configName = "chosen name") is GatewayResult.Ok)
        val updateRequest = fake.lastRequest as UpdateDvrEntryRequest
        assertEquals(7L, updateRequest.entryId)
        assertEquals(3L, updateRequest.channelId)
        assertTrue(updateRequest.configName == "chosen name", "Resolved name must override the UUID")
        assertEquals("private-next-title", updateRequest.title)
        assertEquals("private-next-subtitle", updateRequest.subtitle)
        assertEquals("private-next-summary", updateRequest.summary)
        assertEquals("private-next-description", updateRequest.description)
        assertEquals("deu", updateRequest.language)
        assertEquals("private-comment", updateRequest.comment)
        assertEquals(true, updateRequest.enabled)
        assertEquals(-5L, updateRequest.startEpochSeconds)
        assertEquals(6L, updateRequest.stopEpochSeconds)
        assertEquals(-7L, updateRequest.startExtraMinutes)
        assertEquals(8L, updateRequest.stopExtraMinutes)
        assertEquals(9L, updateRequest.retentionDays)
        assertEquals(10L, updateRequest.removalDays)
        assertEquals(1L, updateRequest.priority)
        assertEquals(13L, updateRequest.ageRating)
        assertEquals(null, updateRequest.playCount)
        assertEquals(null, updateRequest.playPositionSeconds)

        fake.executeResult = HtspResult.Ok(StopDvrEntryResponse)
        assertTrue(gateway.stopDvrEntry(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is StopDvrEntryRequest)
        fake.executeResult = HtspResult.Ok(CancelDvrEntryResponse)
        assertTrue(gateway.cancelDvrEntry(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is CancelDvrEntryRequest)
        fake.executeResult = HtspResult.Ok(DeleteDvrEntryResponse)
        assertTrue(gateway.deleteDvrEntry(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is DeleteDvrEntryRequest)
        fake.executeResult = HtspResult.ServerError("private rejected")
        assertEquals(
            GatewayResult.ServerRejected("private rejected"),
            gateway.updateDvrEntry(generation, DvrEntryId(7), DvrEntryUpdate(), configName = null),
        )

        val failures = listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.scheduleDvrEntry(generation, schedule))
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.scheduleDvrEntry(generation, schedule)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `DVR update sends resolved config name verbatim including empty default name`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(sourceGeneration))
            executeResult = HtspResult.Ok(UpdateDvrEntryResponse)
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        for (name in listOf("custom config", "")) {
            val before = fake.expectedGenerations.size
            assertTrue(gateway.updateDvrEntry(generation, DvrEntryId(7),
                DvrEntryUpdate(configId = DvrConfigId("uuid"), title = "changed"), configName = name) is GatewayResult.Ok)
            val request = fake.lastRequest as UpdateDvrEntryRequest
            assertTrue(request.configName == name, "Resolved config must be sent unchanged")
            assertEquals("changed", request.title)
            assertEquals(before + 1, fake.expectedGenerations.size)
            assertSame(sourceGeneration, fake.lastExpectedGeneration)
        }
    }

    @Test
    fun `watch actions send exactly SET or RESET and zero resume in one fenced request`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(UpdateDvrEntryResponse)
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        for (watched in listOf(true, false)) {
            val before = fake.expectedGenerations.size
            assertTrue(gateway.updateDvrWatchState(generation, DvrEntryId(7), watched) is GatewayResult.Ok)
            assertEquals(UpdateDvrEntryRequest(entryId = 7, playCount = if (watched) 1L else 0L,
                playPositionSeconds = 0), fake.lastRequest)
            assertEquals(before + 1, fake.expectedGenerations.size)
            assertSame(sourceGeneration, fake.lastExpectedGeneration)
            assertEquals(27, fake.lastRequest?.minimumProtocolVersion)
            assertEquals(at.bernhardberger.tvheadend.htsp.requests.HtspAccess.ACCESS_HTSP_RECORDER,
                fake.lastRequest?.access)
        }
        for ((reply, expected) in listOf(
            HtspResult.ServerError("private refusal") to GatewayResult.ServerRejected("private refusal"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.NotSupported to GatewayResult.NotSupported,
            HtspResult.Timeout to GatewayResult.Timeout,
        )) {
            fake.executeResult = reply
            assertEquals(expected, gateway.updateDvrWatchState(generation, DvrEntryId(7), true))
        }
    }

    @Test
    fun `reset sends zero position and omits playcount in one fenced request`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(UpdateDvrEntryResponse)
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val before = fake.expectedGenerations.size
        assertTrue(gateway.resetDvrResume(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertEquals(UpdateDvrEntryRequest(entryId = 7, playPositionSeconds = 0), fake.lastRequest)
        assertEquals(null, (fake.lastRequest as UpdateDvrEntryRequest).playCount)
        assertEquals(before + 1, fake.expectedGenerations.size)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertEquals(27, fake.lastRequest?.minimumProtocolVersion)
        assertEquals(at.bernhardberger.tvheadend.htsp.requests.HtspAccess.ACCESS_HTSP_RECORDER,
            fake.lastRequest?.access)
        for ((reply, expected) in listOf(
            HtspResult.ServerError("private refusal") to GatewayResult.ServerRejected("private refusal"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.NotSupported to GatewayResult.NotSupported,
            HtspResult.Timeout to GatewayResult.Timeout,
        )) {
            fake.executeResult = reply
            assertEquals(expected, gateway.resetDvrResume(generation, DvrEntryId(7)))
        }
    }

    @Test
    fun `DVR progress maps keep and increment playcount without metadata fields`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(UpdateDvrEntryResponse)
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val before = fake.expectedGenerations.size

        assertTrue(
            gateway.reportDvrProgress(
                generation,
                DvrEntryId(7),
                DvrPlaybackProgress.checkpoint(90.seconds),
            ) is GatewayResult.Ok,
        )
        val keep = fake.lastRequest as UpdateDvrEntryRequest
        assertEquals(before + 1, fake.expectedGenerations.size)
        assertEquals(null, keep.configName)
        assertEquals(7L, keep.entryId)
        assertEquals(90L, keep.playPositionSeconds)
        assertEquals(DVR_PROGRESS_KEEP_PLAY_COUNT, keep.playCount)
        assertEquals(null, keep.title)
        assertEquals(null, keep.channelId)

        assertTrue(
            gateway.reportDvrProgress(
                generation,
                DvrEntryId(7),
                DvrPlaybackProgress(120.seconds, markWatched = true),
            ) is GatewayResult.Ok,
        )
        val increment = fake.lastRequest as UpdateDvrEntryRequest
        assertEquals(before + 2, fake.expectedGenerations.size)
        assertEquals(null, increment.configName)
        assertEquals(120L, increment.playPositionSeconds)
        assertEquals(DVR_PROGRESS_INCR_PLAY_COUNT, increment.playCount)

        fake.executeResult = HtspResult.ServerError("private rejected")
        assertEquals(
            GatewayResult.ServerRejected("private rejected"),
            gateway.reportDvrProgress(
                generation,
                DvrEntryId(7),
                DvrPlaybackProgress.checkpoint(1.seconds),
            ),
        )
        val failures = listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(
                expected,
                gateway.reportDvrProgress(
                    generation,
                    DvrEntryId(7),
                    DvrPlaybackProgress.checkpoint(1.seconds),
                ),
            )
        }
        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.reportDvrProgress(
                generation,
                DvrEntryId(7),
                DvrPlaybackProgress.checkpoint(1.seconds),
            )
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `DVR cutpoints preserve wire order and map safe actions atomically`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                GetDvrCutpointsResponse(
                    listOf(
                        HtspDvrCutpoint(startMs = 5_000, endMs = 10_000, type = 0),
                        HtspDvrCutpoint(startMs = 1_000, endMs = 8_000, type = 1),
                        HtspDvrCutpoint(startMs = 10_000, endMs = 10_001, type = 2),
                        HtspDvrCutpoint(startMs = 12_000, endMs = 15_000, type = 3),
                        HtspDvrCutpoint(startMs = 20_000, endMs = 21_000, type = 99),
                    ),
                ),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val result = gateway.getDvrCutpoints(generation, DvrEntryId(7)) as GatewayResult.Ok
        assertEquals(
            listOf(5_000L, 1_000L, 10_000L, 12_000L, 20_000L),
            result.value.map { cutpoint -> cutpoint.start.inWholeMilliseconds },
            "Overlapping, unsorted cutpoints must retain server order",
        )
        assertEquals(
            listOf(
                DvrCutpointAction.CUT,
                DvrCutpointAction.MUTE,
                DvrCutpointAction.SCENE_MARKER,
                DvrCutpointAction.COMMERCIAL_BREAK,
                DvrCutpointAction.UNKNOWN,
            ),
            result.value.map { cutpoint -> cutpoint.action },
        )
        assertEquals(7L, (fake.lastRequest as GetDvrCutpointsRequest).entryId)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        fake.executeResult = HtspResult.Ok(GetDvrCutpointsResponse(null))
        assertEquals(
            emptyList<Any>(),
            (gateway.getDvrCutpoints(generation, DvrEntryId(7)) as GatewayResult.Ok).value,
        )

        fake.executeResult = HtspResult.Ok(
            GetDvrCutpointsResponse(listOf(HtspDvrCutpoint(startMs = 10, endMs = 10, type = 0))),
        )
        assertSame(
            GatewayResult.MalformedReply,
            gateway.getDvrCutpoints(generation, DvrEntryId(7)),
            "One invalid interval must reject the complete response",
        )

        listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.getDvrCutpoints(generation, DvrEntryId(7)))
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.getDvrCutpoints(generation, DvrEntryId(7))
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `title-only updates omit every untouched field including fallback policies`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(HtspAutorecEntryAddMessage(autorecFixture().copy(id = "rule")), sourceGeneration, 1),
                HtspTransportEvent.ServerMessage(HtspTimerecEntryAddMessage(timerecFixture()), sourceGeneration, 2),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        fake.executeResult = HtspResult.Ok(UpdateDvrEntryResponse)
        gateway.updateDvrEntry(generation, DvrEntryId(7), DvrEntryUpdate(title = "new"), configName = null)
        assertEquals(UpdateDvrEntryRequest(entryId = 7, title = "new"), fake.lastRequest)
        fake.executeResult = HtspResult.Ok(UpdateAutorecEntryResponse)
        gateway.metadata.toList()
        gateway.updateAutorecRule(generation, AutorecRuleId("rule"), AutorecRuleUpdate(title = "new"))
        assertEquals(UpdateAutorecEntryRequest(id = "rule", channel = HtspRecordingRuleChannel.Any, title = "new"), fake.lastRequest)
        fake.executeResult = HtspResult.Ok(UpdateTimerecEntryResponse)
        gateway.updateTimerecRule(generation, TimerecRuleId("time"), TimerecRuleUpdate(title = "new"))
        assertEquals(UpdateTimerecEntryRequest(id = "time", channel = HtspRecordingRuleChannel.Any, title = "new"), fake.lastRequest)

        // An explicit caller choice is required to send even a server-default policy.
        fake.executeResult = HtspResult.Ok(UpdateAutorecEntryResponse)
        gateway.updateAutorecRule(generation, AutorecRuleId("rule"), AutorecRuleUpdate(
            priority = DvrPriority.DEFAULT, duplicateDetection = DuplicateDetection.DVR_PROFILE,
            broadcastType = BroadcastType.ALL, daysOfWeekMask = emptySet(),
            startMinutesSinceMidnight = AutorecTime.AnyTime,
            startWindowEndMinutesSinceMidnight = AutorecTime.At(LocalTime.MIDNIGHT),
        ))
        assertEquals(UpdateAutorecEntryRequest(
            id = "rule", channel = HtspRecordingRuleChannel.Any, priority = 6, duplicateDetection = 15, broadcastType = 0, daysOfWeekMask = 0,
            startMinutesSinceMidnight = -1, startWindowEndMinutesSinceMidnight = 0,
        ), fake.lastRequest)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `rule updates preserve observed restrictions and never invent an unknown channel`() = runTest {
        val source = HtspConnectionGeneration()
        val messages = MutableSharedFlow<HtspTransportEvent>()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            eventsFlow = messages
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { gateway.metadata.collect {} }
        val autoId = AutorecRuleId("auto")
        val timeId = TimerecRuleId("time")
        assertSame(GatewayResult.ObservationExpired, gateway.updateAutorecRule(generation, autoId, AutorecRuleUpdate(title = "next")))
        assertSame(GatewayResult.ObservationExpired, gateway.updateTimerecRule(generation, timeId, TimerecRuleUpdate(title = "next")))
        assertEquals(null, fake.lastRequest)

        messages.emit(HtspTransportEvent.ServerMessage(
            HtspAutorecEntryAddMessage(autorecFixture().copy(channelId = 7)), source, 1,
        ))
        messages.emit(HtspTransportEvent.ServerMessage(
            HtspTimerecEntryUpdateMessage(timerecFixture().copy(channelId = 8)), source, 2,
        ))
        runCurrent()
        fake.executeResult = HtspResult.Ok(UpdateAutorecEntryResponse)
        assertTrue(gateway.updateAutorecRule(generation, autoId, AutorecRuleUpdate(title = "next")) is GatewayResult.Ok)
        assertEquals(7L, ((fake.lastRequest as UpdateAutorecEntryRequest).channel as HtspRecordingRuleChannel.Id).channelId)
        gateway.updateAutorecRule(generation, autoId, AutorecRuleUpdate(channel = RecordingRuleChannel.AllChannels))
        gateway.updateAutorecRule(generation, autoId, AutorecRuleUpdate(title = "after clear"))
        assertSame(HtspRecordingRuleChannel.Any, (fake.lastRequest as UpdateAutorecEntryRequest).channel)

        fake.executeResult = HtspResult.Ok(UpdateTimerecEntryResponse)
        assertTrue(gateway.updateTimerecRule(generation, timeId, TimerecRuleUpdate(title = "next")) is GatewayResult.Ok)
        assertEquals(8L, ((fake.lastRequest as UpdateTimerecEntryRequest).channel as HtspRecordingRuleChannel.Id).channelId)
        gateway.updateTimerecRule(generation, timeId, TimerecRuleUpdate(channel = RecordingRuleChannel.AllChannels))
        gateway.updateTimerecRule(generation, timeId, TimerecRuleUpdate(title = "after clear"))
        assertSame(HtspRecordingRuleChannel.Any, (fake.lastRequest as UpdateTimerecEntryRequest).channel)

        messages.emit(HtspTransportEvent.ServerMessage(HtspAutorecEntryDeleteMessage("auto"), source, 3))
        messages.emit(HtspTransportEvent.ServerMessage(HtspTimerecEntryDeleteMessage("time"), source, 4))
        runCurrent()
        fake.lastRequest = null
        assertSame(GatewayResult.ObservationExpired, gateway.updateAutorecRule(generation, autoId, AutorecRuleUpdate(title = "next")))
        assertSame(GatewayResult.ObservationExpired, gateway.updateTimerecRule(generation, timeId, TimerecRuleUpdate(title = "next")))
        assertEquals(null, fake.lastRequest)

        messages.emit(HtspTransportEvent.ServerMessage(
            HtspTimerecEntryAddMessage(timerecFixture().copy(channelId = 8)), source, 5,
        ))
        runCurrent()
        val replacement = HtspConnectionGeneration()
        fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection(replacement))
        val next = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        messages.emit(HtspTransportEvent.ServerMessage(
            HtspTimerecEntryUpdateMessage(timerecFixture().copy(channelId = 9)), source, 6,
        ))
        runCurrent()
        assertSame(GatewayResult.ObservationExpired, gateway.updateTimerecRule(next, timeId, TimerecRuleUpdate(title = "next")))
        assertSame(GatewayResult.TransportUnavailable, gateway.updateTimerecRule(generation, timeId, TimerecRuleUpdate(title = "next")))
        gateway.shutdown()
        assertSame(HtspConnectionState.Closed, fake.connectionState.value)
        assertSame(GatewayState.Disconnected, gateway.connectionState.value)
        assertEquals(null, gateway.commitIfLive(next) { true })
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `rule reply cannot overwrite newer metadata or resurrect a deletion`(timerec: Boolean, deleted: Boolean) = runTest {
        val source = HtspConnectionGeneration()
        val messages = MutableSharedFlow<HtspTransportEvent>()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            eventsFlow = messages
            executeResult = if (timerec) HtspResult.Ok(UpdateTimerecEntryResponse) else HtspResult.Ok(UpdateAutorecEntryResponse)
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { gateway.metadata.collect {} }
        suspend fun update(): GatewayResult<Unit> = if (timerec) {
            gateway.updateTimerecRule(generation, TimerecRuleId("time"), TimerecRuleUpdate(title = "next"))
        } else {
            gateway.updateAutorecRule(generation, AutorecRuleId("auto"), AutorecRuleUpdate(title = "next"))
        }
        messages.emit(HtspTransportEvent.ServerMessage(
            if (timerec) HtspTimerecEntryAddMessage(timerecFixture().copy(channelId = 7))
            else HtspAutorecEntryAddMessage(autorecFixture().copy(channelId = 7)), source, 1,
        ))
        runCurrent()
        val reply = CompletableDeferred<Unit>()
        fake.beforeExecute = { reply.await() }
        val pending = async { update() }
        runCurrent()
        assertNotNull(fake.lastRequest)
        messages.emit(HtspTransportEvent.ServerMessage(
            when {
                timerec && deleted -> HtspTimerecEntryDeleteMessage("time")
                timerec -> HtspTimerecEntryUpdateMessage(timerecFixture().copy(channelId = 8))
                deleted -> HtspAutorecEntryDeleteMessage("auto")
                else -> HtspAutorecEntryUpdateMessage(autorecFixture().copy(channelId = 8))
            }, source, 2,
        ))
        runCurrent()
        reply.complete(Unit)
        assertTrue(pending.await() is GatewayResult.Ok)
        fake.beforeExecute = {}
        fake.lastRequest = null
        val following = update()
        if (deleted) {
            assertSame(GatewayResult.ObservationExpired, following)
            assertEquals(null, fake.lastRequest)
        } else {
            assertTrue(following is GatewayResult.Ok)
            val channel = if (timerec) (fake.lastRequest as UpdateTimerecEntryRequest).channel
                else (fake.lastRequest as UpdateAutorecEntryRequest).channel
            assertEquals(HtspRecordingRuleChannel.Id(8), channel)
        }
    }

    @ParameterizedTest
    @CsvSource("false,none", "true,none", "false,update", "true,update", "false,delete", "true,delete")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `create seeds restriction only without newer metadata`(timerec: Boolean, metadata: String) = runTest {
        val source = HtspConnectionGeneration()
        val messages = MutableSharedFlow<HtspTransportEvent>()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(source))
            eventsFlow = messages
            executeResult = if (timerec) HtspResult.Ok(AddTimerecEntryResponse("time")) else HtspResult.Ok(AddAutorecEntryResponse("auto"))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { gateway.metadata.collect {} }
        val reply = CompletableDeferred<Unit>()
        fake.beforeExecute = { reply.await() }
        val pending = async {
            val channel = RecordingRuleChannel.SpecificChannel(ChannelId(7))
            if (timerec) gateway.createTimerecRule(generation, TimerecRuleCreate(title = "new", channel = channel))
            else gateway.createAutorecRule(generation, AutorecRuleCreate(title = "new", channel = channel))
        }
        runCurrent()
        if (metadata != "none") {
            messages.emit(HtspTransportEvent.ServerMessage(
                when {
                    timerec && metadata == "delete" -> HtspTimerecEntryDeleteMessage("time")
                    timerec -> HtspTimerecEntryAddMessage(timerecFixture().copy(channelId = 8))
                    metadata == "delete" -> HtspAutorecEntryDeleteMessage("auto")
                    else -> HtspAutorecEntryAddMessage(autorecFixture().copy(channelId = 8))
                }, source, 1,
            ))
            runCurrent()
        }
        reply.complete(Unit)
        assertTrue(pending.await() is GatewayResult.Ok)
        fake.beforeExecute = {}
        fake.lastRequest = null
        fake.executeResult = if (timerec) HtspResult.Ok(UpdateTimerecEntryResponse) else HtspResult.Ok(UpdateAutorecEntryResponse)
        val following = if (timerec) gateway.updateTimerecRule(generation, TimerecRuleId("time"), TimerecRuleUpdate(title = "next"))
            else gateway.updateAutorecRule(generation, AutorecRuleId("auto"), AutorecRuleUpdate(title = "next"))
        if (metadata == "delete") {
            assertSame(GatewayResult.ObservationExpired, following)
            assertEquals(null, fake.lastRequest)
        } else {
            assertTrue(following is GatewayResult.Ok)
            val channel = if (timerec) (fake.lastRequest as UpdateTimerecEntryRequest).channel
                else (fake.lastRequest as UpdateAutorecEntryRequest).channel
            assertEquals(HtspRecordingRuleChannel.Id(if (metadata == "none") 7 else 8), channel)
        }
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `successful explicit rule update seeds following unchanged channel without metadata`(timerec: Boolean) = runTest {
        val fake = connectedFake().apply {
            executeResult = if (timerec) HtspResult.Ok(UpdateTimerecEntryResponse)
                else HtspResult.Ok(UpdateAutorecEntryResponse)
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982)) as GatewayConnectResult.Connected).connection.generation
        suspend fun update(channel: RecordingRuleChannel?): GatewayResult<Unit> = if (timerec) {
            gateway.updateTimerecRule(generation, TimerecRuleId("time"), TimerecRuleUpdate(title = "next", channel = channel))
        } else {
            gateway.updateAutorecRule(generation, AutorecRuleId("auto"), AutorecRuleUpdate(title = "next", channel = channel))
        }
        assertTrue(update(RecordingRuleChannel.SpecificChannel(ChannelId(7))) is GatewayResult.Ok)
        fake.lastRequest = null
        assertTrue(update(null) is GatewayResult.Ok)
        val channel = if (timerec) (fake.lastRequest as UpdateTimerecEntryRequest).channel
            else (fake.lastRequest as UpdateAutorecEntryRequest).channel
        assertEquals(HtspRecordingRuleChannel.Id(7), channel)
    }

    @Test
    fun `recording rule mutations map SDK fields identifiers and any-channel selection`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val autorec = AutorecRuleCreate(
            title = "private-title",
            channel = RecordingRuleChannel.SpecificChannel(
                at.bernhardberger.tvheadend.sdk.core.ChannelId(2),
            ),
            minDuration = 60.seconds,
            maxDuration = 120.seconds,
            fullText = true,
            mergeText = false,
            duplicateDetection = DuplicateDetection.DIFFERENT_DESCRIPTION,
            maximumRecordingCount = 4,
            broadcastType = BroadcastType.NEW,
            startExtraMinutes = (-6).minutes,
            stopExtraMinutes = 7.minutes,
            seriesLinkUri = "private-series",
            approximateStartMinutesSinceMidnight = AutorecTime.At(LocalTime.of(0, 8)),
            startMinutesSinceMidnight = AutorecTime.At(LocalTime.of(0, 9)),
            startWindowEndMinutesSinceMidnight = AutorecTime.At(LocalTime.of(0, 10)),
            enabled = true,
            retentionDays = 11,
            removalDays = 12,
            priority = DvrPriority.LOW,
            name = "private-name",
            comment = "private-comment",
            directory = "private-directory",
            configId = DvrConfigId("private-config"),
            daysOfWeekMask = DayOfWeek.values().toSet(),
        )
        fake.executeResult = HtspResult.Ok(AddAutorecEntryResponse("private-autorec-id"))

        val createdAutorec = gateway.createAutorecRule(generation, autorec) as GatewayResult.Ok
        val addAutorec = fake.lastRequest as AddAutorecEntryRequest
        assertEquals(AutorecRuleId("private-autorec-id"), createdAutorec.value)
        assertEquals(2L, (addAutorec.channel as HtspRecordingRuleChannel.Id).channelId)
        assertEquals(60L, addAutorec.minDurationSeconds)
        assertEquals(120L, addAutorec.maxDurationSeconds)
        assertEquals(true, addAutorec.fullText)
        assertEquals(false, addAutorec.mergeText)
        assertEquals(3L, addAutorec.duplicateDetection)
        assertEquals(4L, addAutorec.maximumRecordingCount)
        assertEquals(3L, addAutorec.broadcastType)
        assertEquals(-6L, addAutorec.startExtraMinutes)
        assertEquals(7L, addAutorec.stopExtraMinutes)
        assertEquals("private-series", addAutorec.seriesLinkUri)
        assertEquals(8, addAutorec.approximateStartMinutesSinceMidnight)
        assertEquals(9, addAutorec.startMinutesSinceMidnight)
        assertEquals(10, addAutorec.startWindowEndMinutesSinceMidnight)
        assertEquals(true, addAutorec.enabled)
        assertEquals(11L, addAutorec.retentionDays)
        assertEquals(12L, addAutorec.removalDays)
        assertEquals(3L, addAutorec.priority)
        assertEquals("private-name", addAutorec.name)
        assertEquals("private-comment", addAutorec.comment)
        assertEquals("private-directory", addAutorec.directory)
        assertEquals("private-config", addAutorec.configName)
        assertEquals(127L, addAutorec.daysOfWeekMask)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        gateway.createAutorecRule(generation, autorec.copy(
            approximateStartMinutesSinceMidnight = AutorecTime.AnyTime,
            startMinutesSinceMidnight = AutorecTime.AnyTime,
            startWindowEndMinutesSinceMidnight = AutorecTime.AnyTime,
        ))
        val anyTimeRequest = fake.lastRequest as AddAutorecEntryRequest
        assertEquals(-1, anyTimeRequest.approximateStartMinutesSinceMidnight)
        assertEquals(-1, anyTimeRequest.startMinutesSinceMidnight)
        assertEquals(-1, anyTimeRequest.startWindowEndMinutesSinceMidnight)

        fake.executeResult = HtspResult.Ok(UpdateAutorecEntryResponse)
        assertTrue(
            gateway.updateAutorecRule(
                generation,
                AutorecRuleId("private-autorec-id"),
                AutorecRuleUpdate(
                    channel = RecordingRuleChannel.AllChannels,
                    title = "private-next-title",
                    enabled = false,
                ),
            ) is GatewayResult.Ok,
        )
        val updateAutorec = fake.lastRequest as UpdateAutorecEntryRequest
        assertEquals("private-autorec-id", updateAutorec.id)
        assertSame(HtspRecordingRuleChannel.Any, updateAutorec.channel)
        assertEquals("private-next-title", updateAutorec.title)
        assertEquals(false, updateAutorec.enabled)

        fake.executeResult = HtspResult.Ok(DeleteAutorecEntryResponse)
        assertTrue(
            gateway.deleteAutorecRule(generation, AutorecRuleId("private-autorec-id"))
                is GatewayResult.Ok,
        )
        assertEquals("private-autorec-id", (fake.lastRequest as DeleteAutorecEntryRequest).id)

        fake.executeResult = HtspResult.Ok(AddTimerecEntryResponse("private-timerec-id"))
        val createdTimerec = gateway.createTimerecRule(
            generation,
            TimerecRuleCreate(
                title = "private-time-title",
                channel = RecordingRuleChannel.AllChannels,
                startMinutesSinceMidnight = LocalTime.of(1, 0),
                stopMinutesSinceMidnight = LocalTime.of(2, 0),
                enabled = true,
                retentionDays = 1,
                removalDays = 2,
                priority = DvrPriority.LOW,
                name = "private-time-name",
                comment = "private-time-comment",
                directory = "private-time-directory",
                configId = DvrConfigId("private-time-config"),
                daysOfWeekMask = setOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                ),
            ),
        ) as GatewayResult.Ok
        val addTimerec = fake.lastRequest as AddTimerecEntryRequest
        assertEquals(TimerecRuleId("private-timerec-id"), createdTimerec.value)
        assertSame(HtspRecordingRuleChannel.Any, addTimerec.channel)
        assertEquals(60, addTimerec.startMinutesSinceMidnight)
        assertEquals(120, addTimerec.stopMinutesSinceMidnight)
        assertEquals("private-time-config", addTimerec.configName)

        fake.executeResult = HtspResult.Ok(UpdateTimerecEntryResponse)
        assertTrue(
            gateway.updateTimerecRule(
                generation,
                TimerecRuleId("private-timerec-id"),
                TimerecRuleUpdate(title = "private-next-time", stopMinutesSinceMidnight = LocalTime.of(3, 0)),
            ) is GatewayResult.Ok,
        )
        val updateTimerec = fake.lastRequest as UpdateTimerecEntryRequest
        assertEquals("private-timerec-id", updateTimerec.id)
        assertEquals("private-next-time", updateTimerec.title)
        assertEquals(180, updateTimerec.stopMinutesSinceMidnight)

        fake.executeResult = HtspResult.Ok(DeleteTimerecEntryResponse)
        assertTrue(
            gateway.deleteTimerecRule(generation, TimerecRuleId("private-timerec-id"))
                is GatewayResult.Ok,
        )
        assertEquals("private-timerec-id", (fake.lastRequest as DeleteTimerecEntryRequest).id)
    }

    @Test
    fun `tag titled icon maps absent zero and nonzero flags`() = runTest {
        val generation = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(
                    message = HtspTagAddMessage(tagFixture()),
                    generation = generation,
                    messageSequence = 1,
                ),
                HtspTransportEvent.ServerMessage(
                    message = HtspTagAddMessage(tagFixture().copy(tagId = 2, tagTitledIcon = 0)),
                    generation = generation,
                    messageSequence = 2,
                ),
                HtspTransportEvent.ServerMessage(
                    message = HtspTagAddMessage(tagFixture().copy(tagId = 3, tagTitledIcon = 2)),
                    generation = generation,
                    messageSequence = 3,
                ),
            )
        }
        val events = HtspProtocolGateway(fake).metadata.toList()
        assertEquals(false, (events[0] as MetadataEvent.TagAdded).tag.titledIcon)
        assertEquals(false, (events[1] as MetadataEvent.TagAdded).tag.titledIcon)
        assertEquals(true, (events[2] as MetadataEvent.TagAdded).tag.titledIcon)
    }

    @Test
    fun `workflow commands map outcomes pass exact generations and preserve cancellation`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val metadataEvents = MutableSharedFlow<HtspTransportEvent>()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            eventsFlow = metadataEvents
            beforeExecute = { request ->
                when (request) {
                    is GetSysTimeRequest -> executeResult = HtspResult.Ok(
                        GetSysTimeResponse(
                            timeEpochSeconds = 1_000_000L,
                            legacyTimezoneHoursWestOfGmt = 0,
                            gmtOffsetMinutes = 0,
                        ),
                    )
                    is EnableAsyncMetadataRequest -> {
                        executeResult = HtspResult.Ok(HtspEmptyResponse)
                        metadataEvents.emit(
                            HtspTransportEvent.ServerMessage(
                                message = HtspInitialSyncCompletedMessage,
                                generation = sourceGeneration,
                                messageSequence = 1,
                            ),
                        )
                    }
                    else -> Unit
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val operationFailures = listOf(
            HtspResult.ServerError("private error") to
                SubscriptionOperationResult.Failed(TvheadendFailure.SERVER_REJECTED),
            HtspResult.AccessDenied to SubscriptionOperationResult.Failed(TvheadendFailure.ACCESS_DENIED),
            HtspResult.ConnectionLimit to SubscriptionOperationResult.Failed(TvheadendFailure.CONNECTION_LIMIT),
            HtspResult.Timeout to SubscriptionOperationResult.Failed(TvheadendFailure.TIMEOUT),
            HtspResult.TransportUnavailable to
                SubscriptionOperationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE),
            HtspResult.NotSupported to SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED),
        )
        operationFailures.forEach { (source, expected) ->
            fake.executeResult = source
            val result = gateway.subscribe(generation, SubscriptionId(10), ChannelId(20), Duration.ZERO)
            assertEquals(expected, result)
            assertSame(sourceGeneration, fake.lastExpectedGeneration)
            val request = fake.lastRequest as SubscribeRequest
            assertTrue(request.subscriptionId == 10L, "Subscribe request id mapping failed")
            assertTrue(
                request.channel.let { channel ->
                    (channel as at.bernhardberger.tvheadend.htsp.requests.SubscribeChannel.Id).channelId
                } == 20L,
                "Subscribe channel id mapping failed",
            )
        }

        fake.executeResult = HtspResult.Ok(
            SubscribeResponse(
                ninetyKhz = true,
                normalizedTimestamps = false,
                weight = 30,
                timeshiftPeriodSeconds = 40,
            ),
        )
        val subscribed = gateway.subscribe(generation, SubscriptionId(10), ChannelId(20), Duration.ZERO)
            as SubscriptionOperationResult.Ok
        assertEquals(true, subscribed.value.ninetyKhz)
        assertEquals(false, subscribed.value.normalizedTimestamps)
        assertEquals(30L, subscribed.value.weight)
        assertEquals(40L, subscribed.value.timeshiftPeriodSeconds)

        fake.executeResult = HtspResult.Ok(HtspEmptyResponse)
        assertTrue(
            gateway.unsubscribe(generation, SubscriptionId(10)) is SubscriptionOperationResult.Ok,
        )
        assertTrue(fake.lastRequest is UnsubscribeRequest)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        assertTrue(gateway.enableInitialMetadata(generation) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is EnableAsyncMetadataRequest)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.subscribe(generation, SubscriptionId(11), ChannelId(21), Duration.ZERO)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)

        gateway.disconnect()
        gateway.shutdown()
        assertEquals(1, fake.disconnectCalls)
        assertEquals(1, fake.closeCalls)

        val unknownGateway = HtspProtocolGateway(FakeHtspConnection())
        var unknownGenerationFailure: IllegalArgumentException? = null
        try {
            unknownGateway.unsubscribe(generation, SubscriptionId(10))
        } catch (failure: IllegalArgumentException) {
            unknownGenerationFailure = failure
        }
        assertEquals("Unknown gateway generation", unknownGenerationFailure?.message)
    }

    @Test
    fun `timeshift requests map exact generation bound HTSP commands`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(
                liveConnection(sourceGeneration),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        fake.executeResult = HtspResult.Ok(
            SubscribeResponse(
                ninetyKhz = null,
                normalizedTimestamps = null,
                weight = null,
                timeshiftPeriodSeconds = 300,
            ),
        )
        gateway.subscribe(
            generation,
            SubscriptionId(5),
            ChannelId(6),
            "0123456789abcdef0123456789abcdef",
            300.seconds,
        )
        assertEquals(300L, (fake.lastRequest as SubscribeRequest).timeshiftPeriodSeconds)
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            (fake.lastRequest as SubscribeRequest).profile,
        )
        gateway.subscribe(generation, SubscriptionId(5), ChannelId(6), Duration.ZERO)
        assertEquals(null, (fake.lastRequest as SubscribeRequest).timeshiftPeriodSeconds)
        assertEquals(null, (fake.lastRequest as SubscribeRequest).profile)
        gateway.subscribe(generation, SubscriptionId(5), ChannelId(6), 900.milliseconds)
        assertEquals(
            null,
            (fake.lastRequest as SubscribeRequest).timeshiftPeriodSeconds,
            "A sub-second timeshift request must not become a zero-second request",
        )

        fake.executeResult = HtspResult.Ok(HtspEmptyResponse)
        assertTrue(
            gateway.skipSubscription(
                generation,
                SubscriptionId(5),
                SubscriptionSeekTarget.Absolute(90.seconds),
            ) is SubscriptionOperationResult.Ok,
        )
        val absolute = fake.lastRequest as SubscriptionSkipRequest
        assertEquals(5L, absolute.subscriptionId)
        assertEquals(SubscriptionSeekPosition.Time(90_000_000L), absolute.position)
        assertEquals(true, absolute.absolute)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        gateway.skipSubscription(
            generation,
            SubscriptionId(5),
            SubscriptionSeekTarget.Relative((-30).seconds),
        )
        val relative = fake.lastRequest as SubscriptionSkipRequest
        assertEquals(SubscriptionSeekPosition.Time(-30_000_000L), relative.position)
        assertEquals(false, relative.absolute)

        val requestBeforeLive = fake.lastRequest
        assertEquals(
            SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED),
            gateway.skipSubscription(generation, SubscriptionId(5), SubscriptionSeekTarget.Live),
        )
        assertSame(requestBeforeLive, fake.lastRequest, "Direct live must never be dispatched")

        val nearLiveStatus = SubscriptionEvent.Timeshift(
            full = 10,
            shift = -20_000_000,
            start = 10_000_000,
            end = 90_000_000,
            speed = 100,
        )
        assertTrue(
            gateway.skipSubscriptionNearLive(
                generation = generation,
                id = SubscriptionId(5),
                status = nearLiveStatus,
                marginSeconds = 3,
            ) is SubscriptionOperationResult.Ok,
        )
        val nearLive = fake.lastRequest as SubscriptionSkipRequest
        assertEquals(5L, nearLive.subscriptionId)
        assertEquals(SubscriptionSeekPosition.Time(87_000_000L), nearLive.position)
        assertEquals(true, nearLive.absolute)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        assertTrue(
            gateway.speedSubscription(
                generation = generation,
                id = SubscriptionId(5),
                speed = 0,
            ) is SubscriptionOperationResult.Ok,
        )
        val pause = fake.lastRequest as SubscriptionSpeedRequest
        assertEquals(5L, pause.subscriptionId)
        assertEquals(0, pause.speed)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        gateway.speedSubscription(generation, SubscriptionId(5), 100)
        assertEquals(100, (fake.lastRequest as SubscriptionSpeedRequest).speed)

        listOf(
            HtspResult.ServerError("private error") to
                SubscriptionOperationResult.Failed(TvheadendFailure.SERVER_REJECTED),
            HtspResult.AccessDenied to SubscriptionOperationResult.Failed(TvheadendFailure.ACCESS_DENIED),
            HtspResult.ConnectionLimit to SubscriptionOperationResult.Failed(TvheadendFailure.CONNECTION_LIMIT),
            HtspResult.Timeout to SubscriptionOperationResult.Failed(TvheadendFailure.TIMEOUT),
            HtspResult.TransportUnavailable to
                SubscriptionOperationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE),
            HtspResult.NotSupported to SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED),
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(
                expected,
                gateway.skipSubscriptionNearLive(
                    generation = generation,
                    id = SubscriptionId(5),
                    status = nearLiveStatus,
                    marginSeconds = 3,
                ),
            )
        }

        fake.executeResult = HtspResult.Ok(HtspEmptyResponse)
        val requestBeforeInvalidStatus = fake.lastRequest
        listOf(
            SubscriptionEvent.Timeshift(1, 0, null, 90_000_000, 100),
            SubscriptionEvent.Timeshift(1, 0, 88_000_000, 90_000_000, 100),
        ).forEach { invalidStatus ->
            assertEquals(
                SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED),
                gateway.skipSubscriptionNearLive(
                    generation = generation,
                    id = SubscriptionId(5),
                    status = invalidStatus,
                    marginSeconds = 3,
                ),
            )
        }
        assertSame(
            requestBeforeInvalidStatus,
            fake.lastRequest,
            "Invalid near-live status must fail before dispatch",
        )

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.skipSubscriptionNearLive(
                generation = generation,
                id = SubscriptionId(5),
                status = nearLiveStatus,
                marginSeconds = 3,
            )
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)

        gateway.shutdown()
    }

    @Test
    fun `live priority maps to generation bound HTSP subscription weights`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            connectOutcome = HtspConnectOutcome.Connected(liveConnection(sourceGeneration))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        fake.executeResult = HtspResult.Ok(
            SubscribeResponse(
                ninetyKhz = null,
                normalizedTimestamps = null,
                weight = null,
                timeshiftPeriodSeconds = null,
            ),
        )
        gateway.subscribe(
            generation,
            SubscriptionId(5),
            ChannelId(6),
            null,
            Duration.ZERO,
            LiveSubscriptionPriority.YIELD,
        )
        assertEquals(10L, (fake.lastRequest as SubscribeRequest).weight)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        gateway.subscribe(
            generation,
            SubscriptionId(5),
            ChannelId(6),
            null,
            Duration.ZERO,
            LiveSubscriptionPriority.NORMAL,
        )
        assertEquals(null, (fake.lastRequest as SubscribeRequest).weight, "Normal keeps the profile default")
        gateway.subscribe(generation, SubscriptionId(5), ChannelId(6), Duration.ZERO)
        assertEquals(null, (fake.lastRequest as SubscribeRequest).weight)

        fake.executeResult = HtspResult.Ok(HtspEmptyResponse)
        assertTrue(
            gateway.changeSubscriptionPriority(
                generation,
                SubscriptionId(5),
                LiveSubscriptionPriority.YIELD,
            ) is SubscriptionOperationResult.Ok,
        )
        val yieldRequest = fake.lastRequest as SubscriptionChangeWeightRequest
        assertEquals(5L, yieldRequest.subscriptionId)
        assertEquals(10L, yieldRequest.weight)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        gateway.changeSubscriptionPriority(generation, SubscriptionId(5), LiveSubscriptionPriority.NORMAL)
        assertEquals(0L, (fake.lastRequest as SubscriptionChangeWeightRequest).weight)

        listOf(
            HtspResult.ServerError("private error") to
                SubscriptionOperationResult.Failed(TvheadendFailure.SERVER_REJECTED),
            HtspResult.Timeout to SubscriptionOperationResult.Failed(TvheadendFailure.TIMEOUT),
            HtspResult.TransportUnavailable to
                SubscriptionOperationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE),
            HtspResult.NotSupported to SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED),
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(
                expected,
                gateway.changeSubscriptionPriority(generation, SubscriptionId(5), LiveSubscriptionPriority.YIELD),
            )
        }

        var unknownGenerationFailure: IllegalArgumentException? = null
        try {
            HtspProtocolGateway(FakeHtspConnection())
                .changeSubscriptionPriority(generation, SubscriptionId(5), LiveSubscriptionPriority.YIELD)
        } catch (failure: IllegalArgumentException) {
            unknownGenerationFailure = failure
        }
        assertEquals("Unknown gateway generation", unknownGenerationFailure?.message)

        gateway.shutdown()
    }

    @Test
    fun `recording file access binds the DVR selector bounded reads and generation`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                FileOpenResponse(id = 12, sizeBytes = 4_096, modifiedAtEpochSeconds = 1_700_000_000),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val opened = gateway.openRecordingFile(generation, DvrEntryId(7))
        val file = (opened as GatewayResult.Ok).value
        assertEquals("dvr/7", (fake.lastRequest as FileOpenRequest).file)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertEquals(12L, file.handleId)
        assertEquals(4_096L, file.sizeBytes)
        assertEquals(43, file.protocolVersion)
        assertEquals("GatewayRecordingFile(<redacted>)", file.toString())

        fake.executeResult = HtspResult.Ok(
            FileOpenResponse(id = 13, sizeBytes = -1, modifiedAtEpochSeconds = null),
        )
        assertEquals(
            null,
            ((gateway.openRecordingFile(generation, DvrEntryId(7)) as GatewayResult.Ok).value).sizeBytes,
            "A negative reported size must not be published as a readable length",
        )

        fake.executeResult = HtspResult.Ok(FileSeekResponse(offsetBytes = 900))
        assertEquals(900L, (gateway.seekRecordingFile(generation, file, 900) as GatewayResult.Ok).value)
        val seek = fake.lastRequest as FileSeekRequest
        assertEquals(12L, seek.id)
        assertEquals(900L, seek.offsetBytes)
        assertSame(FileSeekWhence.SET, seek.whence, "A recording seek must be absolute")
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        val destination = ByteArray(8) { 9 }
        fake.executeResult = HtspResult.Ok(FileReadResponse(HtspBinary(byteArrayOf(1, 2, 3))))
        assertEquals(
            3,
            (gateway.readRecordingFile(generation, file, 900, destination, 2, 4) as GatewayResult.Ok).value,
        )
        val read = fake.lastRequest as FileReadRequest
        assertEquals(12L, read.id)
        assertEquals(4L, read.sizeBytes)
        assertEquals(900L, read.offsetBytes, "Every read must carry its absolute position")
        assertArrayEquals(byteArrayOf(9, 9, 1, 2, 3, 9, 9, 9), destination)

        fake.lastRequest = null
        assertEquals(
            0,
            (gateway.readRecordingFile(generation, file, 900, destination, 2, 0) as GatewayResult.Ok).value,
        )
        assertEquals(null, fake.lastRequest, "An empty read must not reach the server")

        val guarded = ByteArray(4) { 7 }
        fake.executeResult = HtspResult.Ok(FileReadResponse(HtspBinary(byteArrayOf(1, 2, 3, 4))))
        assertSame(
            GatewayResult.MalformedReply,
            gateway.readRecordingFile(generation, file, 0, guarded, 0, 2),
            "A payload larger than the request must be rejected instead of copied",
        )
        assertArrayEquals(byteArrayOf(7, 7, 7, 7), guarded)

        fake.executeResult = HtspResult.Ok(
            FileStatResponse(sizeBytes = 4_100, modifiedAtEpochSeconds = -7),
        )
        val stat = gateway.statRecordingFile(generation, file) as GatewayResult.Ok
        assertEquals(4_100L, stat.value.sizeBytes)
        assertEquals(-7L, stat.value.modifiedAtUnixSeconds)
        assertEquals("GatewayRecordingFileStat(<redacted>)", stat.value.toString())
        assertEquals(12L, (fake.lastRequest as FileStatRequest).id)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        fake.executeResult = HtspResult.Ok(
            FileStatResponse(sizeBytes = null, modifiedAtEpochSeconds = null),
        )
        val omittedStat = gateway.statRecordingFile(generation, file) as GatewayResult.Ok
        assertEquals(null, omittedStat.value.sizeBytes)
        assertEquals(null, omittedStat.value.modifiedAtUnixSeconds)

        fake.executeResult = HtspResult.Ok(
            FileStatResponse(sizeBytes = 4_100, modifiedAtEpochSeconds = null),
        )
        assertSame(
            GatewayResult.MalformedReply,
            gateway.statRecordingFile(generation, file),
            "A partial stat pair must fail closed even if a test transport bypasses decoding",
        )

        fake.executeResult = HtspResult.Ok(FileCloseResponse)
        assertTrue(gateway.closeRecordingFile(generation, file) is GatewayResult.Ok)
        val close = fake.lastRequest as FileCloseRequest
        assertEquals(12L, close.id)
        assertEquals(
            DVR_PROGRESS_KEEP_PLAY_COUNT,
            close.playCount,
            "A negotiated version 27 server must not increment the play count on every reopen",
        )
        assertEquals(null, close.playPositionSeconds)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        listOf(
            HtspResult.ServerError("private error") to GatewayResult.ServerRejected("private error"),
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertEquals(expected, gateway.openRecordingFile(generation, DvrEntryId(7)))
            assertEquals(expected, gateway.seekRecordingFile(generation, file, 0))
            assertEquals(expected, gateway.readRecordingFile(generation, file, 0, destination, 0, 4))
            assertEquals(expected, gateway.statRecordingFile(generation, file))
            assertEquals(expected, gateway.closeRecordingFile(generation, file))
        }

        assertRejects("negative read position") {
            gateway.readRecordingFile(generation, file, -1, destination, 0, 4)
        }
        assertRejects("destination offset outside the array") {
            gateway.readRecordingFile(generation, file, 0, destination, 9, 0)
        }
        assertRejects("length past the destination window") {
            gateway.readRecordingFile(generation, file, 0, destination, 6, 4)
        }
        assertRejects("negative seek position") {
            gateway.seekRecordingFile(generation, file, -1)
        }

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var cancelled: CancellationException? = null
        try {
            gateway.readRecordingFile(generation, file, 0, destination, 0, 4)
        } catch (failure: CancellationException) {
            cancelled = failure
        }
        assertSame(cancellation, cancelled)
        cancelled = null
        try {
            gateway.statRecordingFile(generation, file)
        } catch (failure: CancellationException) {
            cancelled = failure
        }
        assertSame(cancellation, cancelled)

        gateway.shutdown()
    }

    @Test
    fun `artwork access keeps at most three file handles open per connection`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val release = CompletableDeferred<Unit>()
        var openHandles = 0
        var peakOpenHandles = 0
        var nextHandle = 100L
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            beforeExecute = { request ->
                executeResult = when (request) {
                    is FileOpenRequest -> {
                        openHandles++
                        peakOpenHandles = maxOf(peakOpenHandles, openHandles)
                        HtspResult.Ok(
                            FileOpenResponse(id = nextHandle++, sizeBytes = 1, modifiedAtEpochSeconds = null),
                        )
                    }
                    is FileReadRequest -> {
                        release.await()
                        HtspResult.Ok(FileReadResponse(HtspBinary(byteArrayOf(9))))
                    }
                    is FileCloseRequest -> {
                        openHandles--
                        HtspResult.Ok(FileCloseResponse)
                    }
                    else -> error("Unexpected request type")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val loads = (1..8).map { index ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                gateway.loadArtwork(generation, ArtworkId(index))
            }
        }
        yield()
        assertEquals(3, openHandles)
        release.complete(Unit)
        loads.forEach { it.join() }

        assertEquals(3, peakOpenHandles)
        assertEquals(0, openHandles)
        gateway.shutdown()
    }

    @Test
    fun `malformed artwork chunks are distinct from size policy rejection`() = runTest {
        for ((size, chunkSize) in listOf(5L to 0, 5L to 6, null to 65_537)) {
            var closed = 0
            val fake = connectedFake().apply {
                beforeExecute = { request ->
                    executeResult = when (request) {
                        is FileOpenRequest -> HtspResult.Ok(FileOpenResponse(31, size, null))
                        is FileReadRequest -> HtspResult.Ok(FileReadResponse(HtspBinary(ByteArray(chunkSize))))
                        is FileCloseRequest -> {
                            closed++
                            HtspResult.Ok(FileCloseResponse)
                        }
                        else -> error("Unexpected request type")
                    }
                }
            }
            val gateway = HtspProtocolGateway(fake)
            val generation = (gateway.connect(ServerConfiguration("host", 9_982))
                as GatewayConnectResult.Connected).connection.generation
            assertSame(GatewayResult.MalformedReply, gateway.loadArtwork(generation, ArtworkId(7)))
            assertEquals(1, closed)
            gateway.shutdown()
        }
    }

    @Test
    fun `metadata buffer limits bound wire retention independently from event count`() {
        assertEquals(32L * 1024 * 1024, METADATA_BUFFER_OPTIONS.metadataQueueBytes)
        assertEquals(65_536, METADATA_BUFFER_OPTIONS.metadataQueueEvents)
    }

    @Test
    fun `artwork access preserves bytes when handle close reports a failure`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val requests = ArrayList<HtspRequest<*>>()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            beforeExecute = { request ->
                requests += request
                executeResult = when (request) {
                    is FileOpenRequest -> HtspResult.Ok(
                        FileOpenResponse(id = 31, sizeBytes = 5, modifiedAtEpochSeconds = null),
                    )
                    is FileReadRequest -> HtspResult.Ok(
                        FileReadResponse(
                            HtspBinary(
                                if (request.offsetBytes == 0L) byteArrayOf(1, 2, 3)
                                else byteArrayOf(4, 5),
                            ),
                        ),
                    )
                    is FileCloseRequest -> HtspResult.Timeout
                    else -> error("Unexpected request type")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val result = gateway.loadArtwork(generation, ArtworkId(73)) as GatewayResult.Ok

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result.value)
        assertEquals("imagecache/73", (requests[0] as FileOpenRequest).file)
        assertEquals(listOf(0L, 3L), requests.filterIsInstance<FileReadRequest>().map { it.offsetBytes })
        val close = requests.last() as FileCloseRequest
        assertEquals(31L, close.id)
        assertEquals(null, close.playCount)
        assertEquals(null, close.playPositionSeconds)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertTrue(fake.expectedGenerations.all { it === sourceGeneration })

        gateway.shutdown()
    }

    @Test
    fun `artwork access reads an unknown size until the first empty response`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val requests = ArrayList<HtspRequest<*>>()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            beforeExecute = { request ->
                requests += request
                executeResult = when (request) {
                    is FileOpenRequest -> HtspResult.Ok(
                        FileOpenResponse(id = 37, sizeBytes = null, modifiedAtEpochSeconds = null),
                    )
                    is FileReadRequest -> HtspResult.Ok(
                        FileReadResponse(
                            HtspBinary(
                                when (request.offsetBytes) {
                                    0L -> byteArrayOf(1, 2)
                                    2L -> byteArrayOf(3)
                                    else -> byteArrayOf()
                                },
                            ),
                        ),
                    )
                    is FileCloseRequest -> HtspResult.Ok(FileCloseResponse)
                    else -> error("Unexpected request type")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val result = gateway.loadArtwork(generation, ArtworkId(81)) as GatewayResult.Ok

        assertArrayEquals(byteArrayOf(1, 2, 3), result.value)
        assertEquals(listOf(0L, 2L, 3L), requests.filterIsInstance<FileReadRequest>().map { it.offsetBytes })
        assertTrue(requests.last() is FileCloseRequest)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)
        assertTrue(fake.expectedGenerations.all { it === sourceGeneration })

        gateway.shutdown()
    }

    @Test
    fun `unknown size artwork rejects the first byte beyond the total limit`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val requests = ArrayList<HtspRequest<*>>()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            beforeExecute = { request ->
                requests += request
                executeResult = when (request) {
                    is FileOpenRequest -> HtspResult.Ok(
                        FileOpenResponse(id = 43, sizeBytes = null, modifiedAtEpochSeconds = null),
                    )
                    is FileReadRequest -> HtspResult.Ok(
                        FileReadResponse(HtspBinary(ByteArray(request.sizeBytes.toInt()) { 1 })),
                    )
                    is FileCloseRequest -> HtspResult.Ok(FileCloseResponse)
                    else -> error("Unexpected request type")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        assertEquals(GatewayResult.ServerRejected(), gateway.loadArtwork(generation, ArtworkId(83)))

        val reads = requests.filterIsInstance<FileReadRequest>()
        assertEquals(16L * 1024 * 1024, reads.last().offsetBytes)
        assertEquals(1L, reads.last().sizeBytes)
        assertTrue(requests.last() is FileCloseRequest)
        assertTrue(fake.expectedGenerations.all { it === sourceGeneration })

        gateway.shutdown()
    }

    @Test
    fun `artwork access rejects oversized content and closes after cancellation`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val requests = ArrayList<HtspRequest<*>>()
        val cancellation = CancellationException("private cancellation")
        var cancelRead = false
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            beforeExecute = { request ->
                requests += request
                executeResult = when (request) {
                    is FileOpenRequest -> HtspResult.Ok(
                        FileOpenResponse(
                            id = 41,
                            sizeBytes = if (cancelRead) null else 16L * 1024 * 1024 + 1,
                            modifiedAtEpochSeconds = null,
                        ),
                    )
                    is FileReadRequest -> throw cancellation
                    is FileCloseRequest -> HtspResult.Ok(FileCloseResponse)
                    else -> error("Unexpected request type")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        assertEquals(GatewayResult.ServerRejected(), gateway.loadArtwork(generation, ArtworkId(7)))
        assertTrue(requests.none { it is FileReadRequest })
        assertTrue(requests.last() is FileCloseRequest)

        requests.clear()
        cancelRead = true
        var caught: CancellationException? = null
        try {
            gateway.loadArtwork(generation, ArtworkId(8))
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
        assertTrue(requests.any { it is FileReadRequest })
        assertTrue(requests.last() is FileCloseRequest)

        gateway.shutdown()
    }

    @Test
    fun `recording file close omits the play count below the progress protocol version`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val legacy = HtspLiveConnection(
            generation = sourceGeneration,
            protocolVersion = 26,
            dvrAccess = true,
            serverFacts = HtspServerFacts(),
        )
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = legacy
            connectOutcome = HtspConnectOutcome.Connected(legacy)
            executeResult = HtspResult.Ok(
                FileOpenResponse(id = 3, sizeBytes = 10, modifiedAtEpochSeconds = null),
            )
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val file = (gateway.openRecordingFile(generation, DvrEntryId(4)) as GatewayResult.Ok).value
        assertEquals(26, file.protocolVersion)

        fake.executeResult = HtspResult.Ok(FileCloseResponse)
        assertTrue(gateway.closeRecordingFile(generation, file) is GatewayResult.Ok)
        val close = fake.lastRequest as FileCloseRequest
        assertEquals(3L, close.id)
        assertEquals(
            null,
            close.playCount,
            "A pre-27 server rejects a play-count field, so the close must stay bare",
        )

        gateway.shutdown()
    }

    private suspend fun assertRejects(reason: String, block: suspend () -> Unit) {
        var caught: IllegalArgumentException? = null
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            caught = failure
        }
        assertTrue(caught != null, "Expected rejection of $reason")
    }

    @Test
    fun `subscription issues exhaustively preserve canonical values and redact fallbacks`() = runTest {
        val cases = listOf(
            Triple(null, null, null),
            Triple(null, "noFreeAdapter", SubscriptionIssue.NO_FREE_ADAPTER),
            Triple(null, "scrambled", SubscriptionIssue.SCRAMBLED),
            Triple(null, "badSignal", SubscriptionIssue.BAD_SIGNAL),
            Triple(null, "tuningFailed", SubscriptionIssue.TUNING_FAILED),
            Triple(null, "subscriptionOverridden", SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN),
            Triple(null, "muxNotEnabled", SubscriptionIssue.MUX_NOT_ENABLED),
            Triple(null, "invalidTarget", SubscriptionIssue.INVALID_TARGET),
            Triple(null, "userAccess", SubscriptionIssue.USER_ACCESS),
            Triple(null, "userLimit", SubscriptionIssue.USER_LIMIT),
            Triple(null, "weakStream", SubscriptionIssue.WEAK_STREAM),
            Triple(null, "noDiskSpace", SubscriptionIssue.NO_DISK_SPACE),
            Triple(null, "futureCanonicalValue", SubscriptionIssue.UNKNOWN),
            Triple(null, "Localized subscription failure", SubscriptionIssue.UNKNOWN),
            Triple("No input detected", "badSignal", SubscriptionIssue.NO_INPUT),
            Triple("No input detected", null, SubscriptionIssue.NO_INPUT),
            Triple("No input detected", "userAccess", SubscriptionIssue.USER_ACCESS),
            Triple("No input detected", "futureCanonicalValue", SubscriptionIssue.NO_INPUT),
            Triple("Localized no-input status", "badSignal", SubscriptionIssue.BAD_SIGNAL),
        )
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            subscriptionFlow = flowOf(
                *cases.map { (status, error, _) ->
                    HtspSubscriptionEvent.Status(
                        HtspSubscriptionStatusMessage(77, status, error),
                    )
                }.toTypedArray(),
            )
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val events = gateway.subscription(generation, SubscriptionId(77)).toList()

        assertEquals(cases.map { it.third }, events.map { (it as SubscriptionEvent.Status).issue })
        assertTrue(events.all { it.toString() == "SubscriptionEvent.Status(<redacted>)" })
        assertFalse(events.toString().contains("Localized subscription failure"))
        assertFalse(events.toString().contains("No input detected"))
        gateway.shutdown()
    }

    @Test
    fun `frontend status maps only proven values into non exhaustive state`() = runTest {
        val rawStatuses = listOf(
            "GOOD",
            "BAD",
            "FAINT",
            "NONE",
            "UNKNOWN",
            "Localized frontend state",
            "",
        )
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            subscriptionFlow = flowOf(
                *rawStatuses.map { status ->
                    HtspSubscriptionEvent.Signal(
                        HtspSignalStatusMessage(77, status, null, null, null, null, null, null),
                    )
                }.toTypedArray(),
            )
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val events = gateway.subscription(generation, SubscriptionId(77)).toList()
            .map { it as SubscriptionEvent.Signal }

        assertEquals(
            listOf(
                Triple(true, true, true),
                Triple(true, true, false),
                Triple(true, false, false),
                Triple(false, false, false),
                null,
                null,
                null,
            ),
            events.map { signal ->
                signal.frontendState?.let { state ->
                    Triple(state.signalDetected, state.partiallySynchronized, state.locked)
                }
            },
        )
        assertTrue(events.all(SubscriptionEvent.Signal::frontendStatusReported))
        assertFalse(events.toString().contains("Localized frontend state"))
        gateway.shutdown()
    }

    @Test
    fun `subscription mapping retains complete event order and delegates bounded payload copies`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val payloadBytes = byteArrayOf(1, 2, 3, 4)
        val codecBytes = byteArrayOf(5, 6)
        val sourceEvents = listOf(
            HtspSubscriptionEvent.Started(
                HtspSubscriptionStartMessage(
                    subscriptionId = 77,
                    streams = listOf(
                        HtspSubscriptionStream(
                            streamIndex = 1,
                            streamType = "H264",
                            language = "eng",
                            compositionId = 2,
                            ancillaryId = 3,
                            width = 1_920,
                            height = 1_080,
                            frameDurationUs = 4,
                            aspectNumerator = 16,
                            aspectDenominator = 9,
                            audioType = null,
                            audioVersion = null,
                            channelCount = null,
                            sampleRate = null,
                            rdsUecp = true,
                        ),
                        HtspSubscriptionStream(
                            streamIndex = 2,
                            streamType = "PRIVATE_UNKNOWN_CODEC",
                            language = null,
                            compositionId = null,
                            ancillaryId = null,
                            width = null,
                            height = null,
                            frameDurationUs = null,
                            aspectNumerator = null,
                            aspectDenominator = null,
                            audioType = null,
                            audioVersion = null,
                            channelCount = null,
                            sampleRate = null,
                            rdsUecp = null,
                        ),
                        HtspSubscriptionStream(
                            streamIndex = 3,
                            streamType = "DVBSUB",
                            language = "ger",
                            compositionId = 0x1234,
                            ancillaryId = 0xabcd,
                            width = null,
                            height = null,
                            frameDurationUs = null,
                            aspectNumerator = null,
                            aspectDenominator = null,
                            audioType = null,
                            audioVersion = null,
                            channelCount = null,
                            sampleRate = null,
                            rdsUecp = null,
                        ),
                    ),
                    sourceInfo = HtspSubscriptionSourceInfo(
                        adapterUuid = "secret adapter uuid",
                        muxUuid = "secret mux uuid",
                        networkUuid = "secret network uuid",
                        adapter = "\u4f8b\u5b50\u3002cn",
                        mux = "127\uff610\uff610\uff611",
                        network = "Network display",
                        networkType = "secret network type",
                        provider = "Provider display",
                        service = "Service display",
                        satellitePosition = "secret orbital position",
                    ),
                    codecMetadata = HtspBinary(codecBytes),
                ),
            ),
            HtspSubscriptionEvent.Packet(
                HtspMuxPacketMessage(
                    subscriptionId = 77,
                    frameType = HtspFrameType.I,
                    streamIndex = 1,
                    decodingTimeUs = 100,
                    presentationTimeUs = 110,
                    durationUs = 40,
                    payload = HtspBinary(payloadBytes),
                ),
            ),
            HtspSubscriptionEvent.Skipped(
                HtspSubscriptionSkipMessage(77, true, true, 120, 130),
            ),
            HtspSubscriptionEvent.Stopped(
                HtspSubscriptionStopMessage(77, "private stop", "private error"),
            ),
            HtspSubscriptionEvent.Status(
                HtspSubscriptionStatusMessage(77, null, "private error"),
            ),
            HtspSubscriptionEvent.Grace(HtspSubscriptionGraceMessage(77, 5)),
            HtspSubscriptionEvent.Speed(HtspSubscriptionSpeedMessage(77, -2)),
            HtspSubscriptionEvent.Timeshift(HtspTimeshiftStatusMessage(77, true, -10, 20, 30, 4)),
            HtspSubscriptionEvent.Queue(HtspQueueStatusMessage(77, 8, 9, -1, 1, 2, 3)),
            HtspSubscriptionEvent.Signal(
                HtspSignalStatusMessage(77, "GOOD", 1, -2, 3, -4, 5, 6),
            ),
            HtspSubscriptionEvent.Descramble(
                HtspDescrambleInfoMessage(
                    subscriptionId = 77,
                    pid = 1,
                    conditionalAccessId = 2,
                    providerId = 3,
                    ecmTime = 4,
                    hopCount = 5,
                    cardSystem = "private card",
                    reader = "private reader",
                    source = "private source",
                    protocol = "private protocol",
                ),
            ),
            HtspSubscriptionEvent.Dropped(9),
            HtspSubscriptionEvent.Terminated(HtspSubscriptionTermination.GENERATION_LOST),
        )
        val fake = FakeHtspConnection().apply {
            subscriptionFlow = flowOf(*sourceEvents.toTypedArray())
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation
        val events = gateway.subscription(generation, SubscriptionId(77)).toList()

        assertTrue(fake.lastSubscriptionId == 77L, "Subscription id routing failed")
        assertSame(sourceGeneration, fake.lastSubscriptionExpectedGeneration)
        assertEquals(
            listOf(
                SubscriptionEvent.Started::class,
                SubscriptionEvent.Packet::class,
                SubscriptionEvent.Skipped::class,
                SubscriptionEvent.Stopped::class,
                SubscriptionEvent.Status::class,
                SubscriptionEvent.Grace::class,
                SubscriptionEvent.Speed::class,
                SubscriptionEvent.Timeshift::class,
                SubscriptionEvent.Queue::class,
                SubscriptionEvent.Signal::class,
                SubscriptionEvent.Descramble::class,
                SubscriptionEvent.Dropped::class,
                SubscriptionEvent.Terminated::class,
            ),
            events.map { it::class },
        )

        val started = events[0] as SubscriptionEvent.Started
        assertEquals(SubscriptionStreamType.H264, started.streams?.get(0)?.type)
        assertEquals(4L, started.streams?.get(0)?.frameDuration)
        assertEquals(1L, started.streams?.get(0)?.rdsUecp)
        assertEquals(SubscriptionStreamType.UNKNOWN, started.streams?.get(1)?.type)
        val dvbSubtitle = started.streams?.get(2)
        assertEquals(SubscriptionStreamType.DVB_SUBTITLE, dvbSubtitle?.type)
        assertEquals(0x1234L, dvbSubtitle?.compositionId)
        assertEquals(0xabcdL, dvbSubtitle?.ancillaryId)
        assertEquals(2, started.codecMetadata?.size)
        with(requireNotNull(started.source)) {
            assertTrue(adapterName == null, "Unsafe adapter label was exposed")
            assertTrue(muxName == null, "Unsafe mux label was exposed")
            assertEquals("Network display", networkName)
            assertEquals("Provider display", providerName)
            assertEquals("Service display", serviceName)
            assertEquals("LiveSubscriptionSource(<redacted>)", toString())
        }
        val codecDestination = ByteArray(4) { -1 }
        assertEquals(2, started.codecMetadata?.copyInto(codecDestination, 1))
        assertArrayEquals(byteArrayOf(-1, 5, 6, -1), codecDestination)

        val packet = events[1] as SubscriptionEvent.Packet
        assertEquals(MuxFrameType.I, packet.frameType)
        assertEquals(1L, packet.streamIndex.value)
        assertEquals(100L, packet.decodingTimeUs)
        assertEquals(110L, packet.presentationTimeUs)
        assertEquals(40L, packet.durationUs)
        assertEquals(4, packet.payload.size)
        val destination = ByteArray(6) { -1 }
        assertEquals(4, packet.payload.copyInto(destination, 1))
        assertArrayEquals(byteArrayOf(-1, 1, 2, 3, 4, -1), destination)
        assertNotSame(payloadBytes, destination)

        assertEquals(SkipOutcome.REJECTED, (events[2] as SubscriptionEvent.Skipped).outcome)
        assertEquals(
            SubscriptionCondition.STATUS_AND_ERROR_REPORTED,
            (events[3] as SubscriptionEvent.Stopped).condition,
        )
        assertSame(SubscriptionIssue.UNKNOWN, (events[3] as SubscriptionEvent.Stopped).issue)
        assertEquals(
            SubscriptionCondition.ERROR_REPORTED,
            (events[4] as SubscriptionEvent.Status).condition,
        )
        assertSame(SubscriptionIssue.UNKNOWN, (events[4] as SubscriptionEvent.Status).issue)
        assertEquals(1L, (events[7] as SubscriptionEvent.Timeshift).full)
        val queue = events[8] as SubscriptionEvent.Queue
        assertEquals(8L, queue.packetCount)
        assertEquals(9L, queue.byteCount)
        assertEquals(-1L, queue.delay)
        assertEquals(1L, queue.bFrameDropCount)
        assertEquals(2L, queue.pFrameDropCount)
        assertEquals(3L, queue.iFrameDropCount)
        assertEquals(null, queue.errorCount)
        val signal = events[9] as SubscriptionEvent.Signal
        assertTrue(signal.frontendStatusReported)
        assertTrue(requireNotNull(signal.frontendState).locked)
        assertEquals(1L, signal.relativeSnr)
        assertEquals(-2L, signal.absoluteSnr)
        assertEquals(3L, signal.relativeSignal)
        assertEquals(-4L, signal.absoluteSignal)
        assertEquals(5L, signal.bitErrorRate)
        assertEquals(6L, signal.uncorrectedBlockCount)
        assertEquals(9L, (events[11] as SubscriptionEvent.Dropped).count)
        assertEquals(
            SubscriptionTermination.GENERATION_LOST,
            (events[12] as SubscriptionEvent.Terminated).reason,
        )
        assertTrue(events.all { it.toString().contains("<redacted>") })
        assertFalse(events.toString().contains("private", ignoreCase = true))
        assertFalse(events.toString().contains("secret", ignoreCase = true))
    }

    @Test
    fun `packet before start remains first at the SDK boundary`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val packet = HtspSubscriptionEvent.Packet(
            HtspMuxPacketMessage(
                subscriptionId = 77,
                frameType = HtspFrameType.UNKNOWN,
                streamIndex = 1,
                decodingTimeUs = null,
                presentationTimeUs = null,
                durationUs = 0,
                payload = HtspBinary(byteArrayOf(1)),
            ),
        )
        val started = HtspSubscriptionEvent.Started(
            HtspSubscriptionStartMessage(subscriptionId = 77),
        )
        val fake = FakeHtspConnection().apply {
            subscriptionFlow = flowOf(packet, started)
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val events = gateway.subscription(generation, SubscriptionId(77)).toList()

        assertTrue(events[0] is SubscriptionEvent.Packet, "Packet order changed at gateway")
        assertTrue(events[1] is SubscriptionEvent.Started, "Start order changed at gateway")
    }

    @Test
    fun `every available HTSP termination keeps its attributed SDK reason`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val sourceTerminations = HtspSubscriptionTermination.entries.toList()
        val fake = FakeHtspConnection().apply {
            subscriptionFlow = flowOf(
                *sourceTerminations.map { termination ->
                    HtspSubscriptionEvent.Terminated(termination)
                }.toTypedArray(),
            )
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val terminations = sourceTerminations.map { termination ->
            fake.subscriptionFlow = flowOf(HtspSubscriptionEvent.Terminated(termination))
            (gateway.subscription(generation, SubscriptionId(77)).toList().single()
                as SubscriptionEvent.Terminated).reason
        }

        assertEquals(
            sourceTerminations.map { termination ->
                when (termination) {
                    HtspSubscriptionTermination.CONSUMER_OVERFLOW -> SubscriptionTermination.PUBLICATION_FAILURE
                    HtspSubscriptionTermination.SUBSCRIBE_REJECTED -> SubscriptionTermination.SUBSCRIBE_REJECTED
                    else -> SubscriptionTermination.valueOf(termination.name)
                }
            },
            terminations,
        )
        assertEquals(sourceTerminations.size, terminations.size)
    }

    private fun liveConnection(
        generation: HtspConnectionGeneration,
        protocolVersion: Int = 43,
    ): HtspLiveConnection =
        HtspLiveConnection(
            generation = generation,
            protocolVersion = protocolVersion.toLong(),
            dvrAccess = true,
            serverFacts = HtspServerFacts(),
        )
}

private fun channelFixture(): HtspChannel = HtspChannel(
    channelId = 1, channelUuid = null, channelNumber = 1, channelNumberMinor = null,
    channelName = "Channel", channelIcon = null, currentEventId = 0, nextEventId = 0,
    services = emptyList(), tagIds = emptyList(),
)

private fun tagFixture(): HtspTag = HtspTag(
    tagId = 1, tagName = "Tag", tagUuid = null, tagIndex = 0, tagIcon = null,
    tagTitledIcon = 0, channelIds = null,
)

private fun dvrFixture(): HtspDvrEntry = HtspDvrEntry(
    entryId = 1, enabled = true, startEpochSeconds = 10, stopEpochSeconds = 20,
    startExtraMinutes = 0, stopExtraMinutes = 0, retentionDays = 0, removalDays = 0,
    priority = 0, contentType = 0, state = "scheduled",
)

private fun autorecFixture(): HtspAutorecEntry = HtspAutorecEntry(
    id = "auto", enabled = true, maxDurationSeconds = 0, minDurationSeconds = 0,
    retentionDays = 0, removalDays = 0, daysOfWeekMask = 127,
    approximateStartMinutesSinceMidnight = null, startMinutesSinceMidnight = null,
    startWindowEndMinutesSinceMidnight = null, priority = 0, startExtraMinutes = 0,
    stopExtraMinutes = 0, duplicateDetection = 0, maximumRecordingCount = 0,
)

private fun timerecFixture(): HtspTimerecEntry = HtspTimerecEntry(
    id = "time", enabled = true, startMinutesSinceMidnight = null, stopMinutesSinceMidnight = null,
    daysOfWeekMask = 127, priority = 0, retentionDays = 0, removalDays = 0,
)

private class FakeHtspConnection : HtspConnection {
    var rejectTerminationToken: Boolean = false
    fun end(generation: HtspConnectionGeneration, result: at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination) {
        checkNotNull(terminations[generation]).complete(result)
    }
    private val terminations = java.util.IdentityHashMap<HtspConnectionGeneration,
        kotlinx.coroutines.CompletableDeferred<at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination>>()

    fun fail(generation: HtspConnectionGeneration, failure: HtspTransportFailure) {
        checkNotNull(terminations[generation]).complete(
            at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination.Failed(failure),
        )
    }

    var terminationWaiters: Int = 0
    override suspend fun awaitTermination(generation: HtspConnectionGeneration):
        at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination {
        require(!rejectTerminationToken)
        val result = requireNotNull(terminations[generation])
        terminationWaiters++
        try {
            return result.await()
        } finally {
            terminationWaiters--
        }
    }

    internal val connectionStateValue = MutableStateFlow<HtspConnectionState>(
        HtspConnectionState.Disconnected,
    )
    internal val liveConnectionValue = LiveValue()
    internal inner class LiveValue {
        var value: HtspLiveConnection? = null
            set(value) {
                field = value
                val state = connectionStateValue.value as? HtspConnectionState.Connected
                if (state != null) {
                    connectionStateValue.value = value?.let {
                        HtspConnectionState.Connected(state.host, state.port, it)
                    } ?: HtspConnectionState.Disconnected
                }
            }
    }
    internal var eventsFlow: Flow<HtspTransportEvent> = emptyFlow()
    internal var batchesFlow: Flow<List<HtspTransportEvent>>? = null
    internal var subscriptionFlow: Flow<HtspSubscriptionEvent> = emptyFlow()
    internal var connectOutcome: HtspConnectOutcome = HtspConnectOutcome.Failed(
        HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
    )
    internal var executeResult: HtspResult<*> = HtspResult.TransportUnavailable
    internal var executeException: CancellationException? = null
    internal var beforeExecute: suspend (HtspRequest<*>) -> Unit = {}
    internal var lastEndpoint: HtspEndpoint? = null
    internal var lastConnectOptions: HtspConnectOptions? = null
    internal var beforeTeardown: suspend () -> Unit = {}
    internal var lastExpectedGeneration: HtspConnectionGeneration? = null
    internal val expectedGenerations = ArrayList<HtspConnectionGeneration?>()
    internal var lastRequest: HtspRequest<*>? = null
    internal var lastSubscriptionId: Long? = null
    internal var lastSubscriptionExpectedGeneration: HtspConnectionGeneration? = null
    internal var disconnectCalls: Int = 0
    internal var closeCalls: Int = 0

    override val connectionState: MutableStateFlow<HtspConnectionState> = connectionStateValue
    override val events: Flow<HtspTransportEvent>
        get() = eventsFlow

    override fun eventBatches(maxBatchSize: Int): Flow<List<HtspTransportEvent>> {
        require(maxBatchSize in 1..4096)
        return flow {
            (batchesFlow ?: eventsFlow.map { listOf(it) }).collect { batch ->
                var start = 0
                while (start < batch.size) {
                    var end = start + 1
                    while (end < batch.size && end - start < maxBatchSize &&
                        batch[end].generation === batch[start].generation
                    ) end += 1
                    emit(batch.subList(start, end).toList())
                    start = end
                }
            }
        }
    }

    override fun subscriptionEvents(subscriptionId: Long): Flow<HtspSubscriptionEvent> {
        lastSubscriptionId = subscriptionId
        return subscriptionFlow
    }

    override fun subscriptionEvents(
        subscriptionId: Long,
        expectedGeneration: HtspConnectionGeneration,
    ): Flow<HtspSubscriptionEvent> {
        lastSubscriptionId = subscriptionId
        lastSubscriptionExpectedGeneration = expectedGeneration
        return subscriptionFlow
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> execute(
        request: HtspRequest<R>,
        timeoutMs: Long,
        expectedGeneration: HtspConnectionGeneration?,
    ): HtspResult<R> {
        executeException?.let { throw it }
        lastRequest = request
        lastExpectedGeneration = expectedGeneration
        expectedGenerations += expectedGeneration
        beforeExecute(request)
        return executeResult as HtspResult<R>
    }

    override suspend fun connect(
        endpoint: HtspEndpoint,
        options: HtspConnectOptions,
    ): HtspConnectOutcome {
        lastEndpoint = endpoint
        lastConnectOptions = options
        check(!closed) { "Fake service is closed" }
        val connected = (connectOutcome as? HtspConnectOutcome.Connected)?.connection
        if ((connectOutcome as? HtspConnectOutcome.Failed)?.failure?.kind == HtspTransportFailureKind.SUPERSEDED) {
            return connectOutcome
        }
        if (connected != null && connected.generation === currentGeneration &&
            liveConnectionValue.value?.generation === currentGeneration &&
            connectionStateValue.value is HtspConnectionState.Connected
        ) return connectOutcome
        attempt += 1
        currentGeneration?.let { terminations[it]?.complete(at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination.Disconnected) }
        currentGeneration = (connectOutcome as? HtspConnectOutcome.Connected)?.connection?.generation
            ?: HtspConnectionGeneration()
        terminations.getOrPut(checkNotNull(currentGeneration)) { kotlinx.coroutines.CompletableDeferred() }
        liveConnectionValue.value = (connectOutcome as? HtspConnectOutcome.Connected)?.connection
        (connectOutcome as? HtspConnectOutcome.Failed)?.let {
            connectionStateValue.value = HtspConnectionState.Error(it.failure)
        }
        (connectOutcome as? HtspConnectOutcome.Connected)?.connection?.let { live ->
            connectionStateValue.value = HtspConnectionState.Connected(
                host = endpoint.host,
                port = endpoint.port,
                connection = live,
            )
        }
        return connectOutcome
    }

    override fun isCurrent(generation: HtspConnectionGeneration): Boolean =
        currentGeneration === generation

    private var currentGeneration: HtspConnectionGeneration? = null
    private var attempt = 0
    var closed = false
        private set

    override suspend fun disconnect(expectedGeneration: HtspConnectionGeneration?): Boolean {
        disconnectCalls += 1
        lastExpectedGeneration = expectedGeneration
        if (closed || (expectedGeneration != null && !isCurrent(expectedGeneration))) return false
        val admittedAttempt = attempt
        beforeTeardown()
        val accepted = admittedAttempt == attempt && connectionStateValue.value != HtspConnectionState.Disconnected
        if (accepted) {
            currentGeneration?.let { terminations[it]?.complete(at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination.Disconnected) }
            attempt += 1
            liveConnectionValue.value = null
            connectionStateValue.value = HtspConnectionState.Disconnected
        }
        return accepted
    }

    override suspend fun close(expectedGeneration: HtspConnectionGeneration?): Boolean {
        closeCalls += 1
        lastExpectedGeneration = expectedGeneration
        val accepted = !closed && (expectedGeneration == null || isCurrent(expectedGeneration))
        if (accepted) {
            closed = true
            currentGeneration?.let { terminations[it]?.complete(at.bernhardberger.tvheadend.htsp.connection.HtspGenerationTermination.Disconnected) }
            attempt += 1
            currentGeneration = HtspConnectionGeneration()
            liveConnectionValue.value = null
            connectionStateValue.value = HtspConnectionState.Closed
        }
        return accepted
    }
}
