package at.bernhardberger.tvheadend.sdk.core.gateway.htsp

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
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDescrambleInfoMessage
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
import at.bernhardberger.tvheadend.htsp.messages.HtspTimeshiftStatusMessage
import at.bernhardberger.tvheadend.htsp.requests.EnableAsyncMetadataRequest
import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.HtspEmptyResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscribeRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscribeResponse
import at.bernhardberger.tvheadend.htsp.requests.UnsubscribeRequest
import at.bernhardberger.tvheadend.htsp.wire.HtspBinary
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.MuxFrameType
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.core.gateway.SkipOutcome
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionId
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.core.gateway.SubscriptionTermination
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HtspProtocolGatewayTest {
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
            eventsFlow = flowOf(
                *(
                    HtspTransportFailureKind.entries.map { kind ->
                        HtspTransportEvent.ConnectionFailure(
                            failure = HtspTransportFailure(kind),
                            generation = sourceGeneration,
                        )
                    } + HtspTransportEvent.ConnectionFailure(
                        failure = HtspTransportFailure(
                            HtspTransportFailureKind.TRANSPORT_UNAVAILABLE,
                        ),
                        generation = null,
                    )
                ).toTypedArray(),
            )
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

        fake.connectionStateValue.value = HtspConnectionState.Connecting("private host", 9_982)
        yield()
        assertSame(GatewayState.Connecting, gateway.connectionState.value)
        fake.connectionStateValue.value = HtspConnectionState.Connecting("other private host", 9_983)
        yield()
        fake.connectionStateValue.value = HtspConnectionState.Connected(
            host = "private host",
            port = 9_982,
            htspVersion = 43,
            dvrAccess = true,
        )
        yield()
        assertSame(GatewayState.Connected, gateway.connectionState.value)
        fake.connectionStateValue.value = HtspConnectionState.Error(
            IllegalStateException("private throwable"),
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
            GatewayConnectionFailure.PERMISSION_DENIED,
            GatewayConnectionFailure.SERVER_UNREACHABLE,
            GatewayConnectionFailure.SERVER_UNREACHABLE,
            GatewayConnectionFailure.SERVER_UNREACHABLE,
            GatewayConnectionFailure.NETWORK_UNAVAILABLE,
            GatewayConnectionFailure.INCOMPATIBLE_SERVER,
            GatewayConnectionFailure.NO_CHANNELS,
            GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
            GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
        )
        val failures = gateway.connectionFailures.toList()

        assertEquals(expectedFailures, failures.map { it.failure })
        assertTrue(failures.dropLast(1).all { it.generation === result.connection.generation })
        assertEquals(null, failures.last().generation)
        assertTrue(failures.all { it.toString() == "GatewayConnectionFailureEvent(<redacted>)" })
        assertFalse(failures.toString().contains("private"))

        val nextGeneration = HtspConnectionGeneration()
        fake.connectOutcome = HtspConnectOutcome.Connected(liveConnection.copy(generation = nextGeneration))
        val nextResult = gateway.connect(ServerConfiguration("next-host", 9_982))
            as GatewayConnectResult.Connected
        assertNotSame(result.connection.generation, nextResult.connection.generation)
    }

    @Test
    fun `metadata preserves ordered channel and tag deltas on one translated generation`() = runTest {
        val generation = HtspConnectionGeneration()
        val sourceMessages = listOf<HtspServerMessage>(
            HtspChannelAddMessage(
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
            ),
            HtspChannelUpdateMessage(channelId = 1, channelName = null, tagIds = emptyList()),
            HtspChannelDeleteMessage(channelId = 1),
            HtspTagAddMessage(
                tagId = 8,
                tagName = "Private tag",
                tagUuid = "private-tag-uuid",
                tagIndex = 9,
                tagIcon = "private-tag-icon",
                tagTitledIcon = 1,
                channelIds = listOf(1),
            ),
            HtspTagUpdateMessage(tagId = 8, tagName = null, channelIds = emptyList()),
            HtspTagDeleteMessage(tagId = 8),
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
        assertEquals(null, updated.channel.name)
        assertEquals(emptyList<ChannelId>(), updated.channel.tagIds)
        val tagAdded = events[3] as MetadataEvent.TagAdded
        assertEquals("Private tag", tagAdded.tag.name)
        assertEquals(1L, tagAdded.tag.channelIds?.single()?.value)
        val tagUpdated = events[4] as MetadataEvent.TagUpdated
        assertEquals(null, tagUpdated.tag.name)
        assertEquals(emptyList<ChannelId>(), tagUpdated.tag.channelIds)
        assertEquals(
            listOf(
                "MetadataEvent.ChannelAdded(<redacted>)",
                "MetadataEvent.ChannelUpdated(<redacted>)",
                "MetadataEvent.ChannelDeleted(<redacted>)",
                "MetadataEvent.TagAdded(<redacted>)",
                "MetadataEvent.TagUpdated(<redacted>)",
                "MetadataEvent.TagDeleted(<redacted>)",
                "MetadataEvent.InitialSyncCompleted(<redacted>)",
            ),
            events.map(Any::toString),
        )
        assertFalse(events.toString().contains("Private"))
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
                if (request is EnableAsyncMetadataRequest) {
                    metadataEvents.emit(
                        HtspTransportEvent.ServerMessage(
                            message = HtspInitialSyncCompletedMessage,
                            generation = sourceGeneration,
                            messageSequence = 1,
                        ),
                    )
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        val operationFailures = listOf(
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        operationFailures.forEach { (source, expected) ->
            fake.executeResult = source
            val result = gateway.subscribe(generation, SubscriptionId(10), ChannelId(20))
            assertSame(expected, result)
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
        val subscribed = gateway.subscribe(generation, SubscriptionId(10), ChannelId(20))
            as GatewayResult.Ok
        assertEquals(true, subscribed.value.ninetyKhz)
        assertEquals(false, subscribed.value.normalizedTimestamps)
        assertEquals(30L, subscribed.value.weight)
        assertEquals(40L, subscribed.value.timeshiftPeriodSeconds)

        fake.executeResult = HtspResult.Ok(HtspEmptyResponse)
        assertTrue(gateway.unsubscribe(generation, SubscriptionId(10)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is UnsubscribeRequest)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        assertTrue(gateway.enableInitialMetadata(generation) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is EnableAsyncMetadataRequest)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        val cancellation = CancellationException("private cancellation")
        fake.executeException = cancellation
        var caught: CancellationException? = null
        try {
            gateway.subscribe(generation, SubscriptionId(11), ChannelId(21))
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
    fun `subscription mapping retains complete event order and delegates bounded payload copies`() = runTest {
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
                            frameDuration = 4,
                            aspectNumerator = 16,
                            aspectDenominator = 9,
                            audioType = null,
                            audioVersion = null,
                            channelCount = null,
                            sampleRate = null,
                            rdsUecp = null,
                            codecMetadata = HtspBinary(codecBytes),
                        ),
                        HtspSubscriptionStream(
                            streamIndex = 2,
                            streamType = "PRIVATE_UNKNOWN_CODEC",
                            language = null,
                            compositionId = null,
                            ancillaryId = null,
                            width = null,
                            height = null,
                            frameDuration = null,
                            aspectNumerator = null,
                            aspectDenominator = null,
                            audioType = null,
                            audioVersion = null,
                            channelCount = null,
                            sampleRate = null,
                            rdsUecp = null,
                        ),
                    ),
                    codecMetadata = HtspBinary(codecBytes),
                    status = "private status",
                    subscriptionError = null,
                ),
            ),
            HtspSubscriptionEvent.Packet(
                HtspMuxPacketMessage(
                    subscriptionId = 77,
                    frameType = 73,
                    streamIndex = 1,
                    decodingTimeUs = 100,
                    presentationTimeUs = 110,
                    durationUs = 40,
                    payload = HtspBinary(payloadBytes),
                ),
            ),
            HtspSubscriptionEvent.Skipped(
                HtspSubscriptionSkipMessage(77, 1, 1, 120, 130),
            ),
            HtspSubscriptionEvent.Stopped(
                HtspSubscriptionStopMessage(77, "private stop", "private error"),
            ),
            HtspSubscriptionEvent.Status(
                HtspSubscriptionStatusMessage(77, null, "private error"),
            ),
            HtspSubscriptionEvent.Grace(HtspSubscriptionGraceMessage(77, 5)),
            HtspSubscriptionEvent.Speed(HtspSubscriptionSpeedMessage(77, -2)),
            HtspSubscriptionEvent.Timeshift(HtspTimeshiftStatusMessage(77, 1, -10, 20, 30, 4)),
            HtspSubscriptionEvent.Queue(HtspQueueStatusMessage(77, 8, 9, -1, 1, 2, 3)),
            HtspSubscriptionEvent.Signal(
                HtspSignalStatusMessage(77, "private frontend", 1, -2, 3, -4, 5, 6),
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
        }
        val events = HtspProtocolGateway(fake).subscription(SubscriptionId(77)).toList()

        assertTrue(fake.lastSubscriptionId == 77L, "Subscription id routing failed")
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
        assertEquals(SubscriptionCondition.STATUS_REPORTED, started.condition)
        assertEquals(SubscriptionStreamType.H264, started.streams?.get(0)?.type)
        assertEquals(SubscriptionStreamType.UNKNOWN, started.streams?.get(1)?.type)
        assertEquals(2, started.codecMetadata?.size)
        val codecDestination = ByteArray(4) { -1 }
        assertEquals(2, started.streams?.first()?.codecMetadata?.copyInto(codecDestination, 1))
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
        assertEquals(
            SubscriptionCondition.ERROR_REPORTED,
            (events[4] as SubscriptionEvent.Status).condition,
        )
        assertEquals(true, (events[9] as SubscriptionEvent.Signal).frontendStatusReported)
        assertEquals(9L, (events[11] as SubscriptionEvent.Dropped).count)
        assertEquals(
            SubscriptionTermination.GENERATION_LOST,
            (events[12] as SubscriptionEvent.Terminated).reason,
        )
        assertTrue(events.all { it.toString().contains("<redacted>") })
        assertFalse(events.toString().contains("private", ignoreCase = true))
    }

    @Test
    fun `packet before start remains first at the SDK boundary`() = runTest {
        val packet = HtspSubscriptionEvent.Packet(
            HtspMuxPacketMessage(
                subscriptionId = 77,
                frameType = -1,
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
        }

        val events = HtspProtocolGateway(fake).subscription(SubscriptionId(77)).toList()

        assertTrue(events[0] is SubscriptionEvent.Packet, "Packet order changed at gateway")
        assertTrue(events[1] is SubscriptionEvent.Started, "Start order changed at gateway")
    }

    private fun liveConnection(generation: HtspConnectionGeneration): HtspLiveConnection =
        HtspLiveConnection(
            generation = generation,
            protocolVersion = 43,
            dvrAccess = true,
            serverFacts = HtspServerFacts(),
        )
}

private class FakeHtspConnection : HtspConnection {
    internal val connectionStateValue = MutableStateFlow<HtspConnectionState>(
        HtspConnectionState.Disconnected,
    )
    internal val liveConnectionValue = MutableStateFlow<HtspLiveConnection?>(null)
    internal var eventsFlow: Flow<HtspTransportEvent> = emptyFlow()
    internal var subscriptionFlow: Flow<HtspSubscriptionEvent> = emptyFlow()
    internal var connectOutcome: HtspConnectOutcome = HtspConnectOutcome.Failed(
        HtspTransportFailure(HtspTransportFailureKind.TRANSPORT_UNAVAILABLE),
    )
    internal var executeResult: HtspResult<*> = HtspResult.TransportUnavailable
    internal var executeException: CancellationException? = null
    internal var beforeExecute: suspend (HtspRequest<*>) -> Unit = {}
    internal var lastEndpoint: HtspEndpoint? = null
    internal var lastExpectedGeneration: HtspConnectionGeneration? = null
    internal var lastRequest: HtspRequest<*>? = null
    internal var lastSubscriptionId: Long? = null
    internal var disconnectCalls: Int = 0
    internal var closeCalls: Int = 0

    override val connectionState: MutableStateFlow<HtspConnectionState> = connectionStateValue
    override val liveConnection: MutableStateFlow<HtspLiveConnection?> = liveConnectionValue
    override val events: Flow<HtspTransportEvent>
        get() = eventsFlow

    override fun subscriptionEvents(subscriptionId: Long): Flow<HtspSubscriptionEvent> {
        lastSubscriptionId = subscriptionId
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
        beforeExecute(request)
        return executeResult as HtspResult<R>
    }

    override suspend fun connect(
        endpoint: HtspEndpoint,
        options: HtspConnectOptions,
    ): HtspConnectOutcome {
        lastEndpoint = endpoint
        return connectOutcome
    }

    override fun isCurrent(generation: HtspConnectionGeneration): Boolean =
        liveConnectionValue.value?.generation === generation

    override fun <T> commitIfCurrent(
        generation: HtspConnectionGeneration,
        block: () -> T,
    ): T? = if (isCurrent(generation)) block() else null

    override fun <T> commitIfLive(
        generation: HtspConnectionGeneration,
        block: (HtspLiveConnection) -> T,
    ): T? = liveConnectionValue.value
        ?.takeIf { it.generation === generation }
        ?.let(block)

    override suspend fun disconnect(expectedGeneration: HtspConnectionGeneration?) {
        disconnectCalls += 1
    }

    override suspend fun close(expectedGeneration: HtspConnectionGeneration?) {
        closeCalls += 1
    }
}
