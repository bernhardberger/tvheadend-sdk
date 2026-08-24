@file:OptIn(SubscriptionInfrastructureApi::class)

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
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspAutorecEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspChannelUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDescrambleInfoMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryAddMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryDeleteMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrEntryUpdateMessage
import at.bernhardberger.tvheadend.htsp.messages.HtspDvrRecordingFile
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
import at.bernhardberger.tvheadend.htsp.requests.FileCloseRequest
import at.bernhardberger.tvheadend.htsp.requests.FileCloseResponse
import at.bernhardberger.tvheadend.htsp.requests.FileOpenRequest
import at.bernhardberger.tvheadend.htsp.requests.FileOpenResponse
import at.bernhardberger.tvheadend.htsp.requests.FileReadRequest
import at.bernhardberger.tvheadend.htsp.requests.FileReadResponse
import at.bernhardberger.tvheadend.htsp.requests.FileSeekRequest
import at.bernhardberger.tvheadend.htsp.requests.FileSeekResponse
import at.bernhardberger.tvheadend.htsp.requests.FileSeekWhence
import at.bernhardberger.tvheadend.htsp.requests.GetDiskSpaceRequest
import at.bernhardberger.tvheadend.htsp.requests.GetDiskSpaceResponse
import at.bernhardberger.tvheadend.htsp.requests.GetDvrConfigsRequest
import at.bernhardberger.tvheadend.htsp.requests.GetDvrConfigsResponse
import at.bernhardberger.tvheadend.htsp.requests.GetDvrCutpointsRequest
import at.bernhardberger.tvheadend.htsp.requests.GetDvrCutpointsResponse
import at.bernhardberger.tvheadend.htsp.requests.GetEventsRequest
import at.bernhardberger.tvheadend.htsp.requests.GetEventsResponse
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeRequest
import at.bernhardberger.tvheadend.htsp.requests.GetSysTimeResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrConfig
import at.bernhardberger.tvheadend.htsp.requests.HtspDvrCutpoint
import at.bernhardberger.tvheadend.htsp.requests.HtspChannelService
import at.bernhardberger.tvheadend.htsp.requests.HtspEmptyResponse
import at.bernhardberger.tvheadend.htsp.requests.HtspEvent
import at.bernhardberger.tvheadend.htsp.requests.HtspRequest
import at.bernhardberger.tvheadend.htsp.requests.HtspRecordingRuleChannel
import at.bernhardberger.tvheadend.htsp.requests.StopDvrEntryRequest
import at.bernhardberger.tvheadend.htsp.requests.StopDvrEntryResponse
import at.bernhardberger.tvheadend.htsp.requests.SubscribeRequest
import at.bernhardberger.tvheadend.htsp.requests.SubscribeResponse
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSeekPosition
import at.bernhardberger.tvheadend.htsp.requests.SubscriptionSkipRequest
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
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.RecordingRuleChannel
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
        assertEquals(null, updated.channel.name)
        assertEquals(emptyList<ChannelId>(), updated.channel.tagIds)
        val tagAdded = events[3] as MetadataEvent.TagAdded
        assertEquals("Private tag", tagAdded.tag.name)
        assertEquals(true, tagAdded.tag.titledIcon)
        assertEquals(1L, tagAdded.tag.channelIds?.single()?.value)
        val tagUpdated = events[4] as MetadataEvent.TagUpdated
        assertEquals(null, tagUpdated.tag.name)
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
    fun `metadata maps complete event adds and nullable updates without losing wall clock semantics`() = runTest {
        val generation = HtspConnectionGeneration()
        val add = HtspEventAddMessage(
            event = HtspEvent(
                eventId = 1,
                channelId = 2,
                start = -3,
                stop = 2_147_483_648,
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
                firstAired = 0,
                isNew = 0,
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
            genre = "Private genre",
            episodeId = 15,
            seriesLinkId = 16,
        )
        val update = HtspEventUpdateMessage(
            eventId = 1,
            channelId = 3,
            stop = 2_147_483_649,
            title = "",
            categories = emptyList(),
            keywords = emptyList(),
            contentType = 17,
            ageRating = 18,
            starRating = 19,
            copyrightYear = 0,
            firstAired = -20,
            isNew = 2,
            seasonNumber = 21,
            seasonCount = 22,
            episodeNumber = 23,
            episodeCount = 24,
            partNumber = 25,
            partCount = 26,
            episodeId = 27,
            seriesLinkId = 28,
            dvrId = 29,
            nextEventId = 30,
        )
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
        assertEquals(15L, added.episodeId?.value)
        assertEquals(16L, added.seriesLinkId?.value)

        val updated = (events[1] as MetadataEvent.EventUpdated).event
        assertEquals(null, updated.start)
        assertEquals(Instant.fromEpochSeconds(2_147_483_649), updated.stop)
        assertEquals("", updated.title)
        assertEquals(emptyList<String>(), updated.categories)
        assertEquals(emptyList<String>(), updated.keywords)
        assertEquals(true, updated.isNew)
        assertEquals(Instant.fromEpochSeconds(-20), updated.firstAired)
        assertEquals(27L, updated.episodeId?.value)
        assertEquals(28L, updated.seriesLinkId?.value)
        assertEquals(29L, updated.dvrEntryId?.value)
        assertEquals(30L, updated.nextEventId?.value)
        assertTrue(events.all { it.generation === events.first().generation })
        assertFalse(events.toString().contains("Private"), "Gateway EPG rendering exposed metadata")
    }

    @Test
    fun `metadata maps DVR entry and rule payloads without leaking paths or errors`() = runTest {
        val generation = HtspConnectionGeneration()
        val files = mutableListOf(
            HtspDvrRecordingFile(
                fileId = 9,
                path = "/private/recording.ts",
                start = 10,
                stop = 20,
                sizeBytes = 30,
            ),
        )
        val add = HtspDvrEntryAddMessage(
            entryId = 1,
            enabled = 1,
            channelId = 2,
            eventId = 3,
            start = -4,
            stop = 2_147_483_648,
            startExtraMinutes = 5,
            stopExtraMinutes = 6,
            contentType = 7,
            playCount = 8,
            playPositionSeconds = 9,
            title = "Private title",
            files = files,
            path = "/private/path.ts",
            dvrConfigUuid = "private-config",
            state = "recording",
            error = "Private error",
            subscriptionError = "scrambled",
        )
        val update = HtspDvrEntryUpdateMessage(
            entryId = 1,
            enabled = 0,
            title = "",
            files = emptyList(),
            state = "completed",
            error = "File missing",
            subscriptionError = "invalidTarget",
        )
        val autorecAdd = HtspAutorecEntryAddMessage(
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
            channelId = 12,
            configId = "private-auto-config",
        )
        val messages = listOf<HtspServerMessage>(
            add,
            update,
            HtspDvrEntryDeleteMessage(1),
            autorecAdd,
            HtspAutorecEntryUpdateMessage(id = "private-auto", title = null, enabled = false),
            HtspAutorecEntryDeleteMessage("private-auto"),
            HtspTimerecEntryAddMessage(
                id = "private-time",
                enabled = true,
                channelId = null,
                startMinutesSinceMidnight = null,
                stopMinutesSinceMidnight = null,
                configId = "private-time-config",
            ),
            HtspTimerecEntryUpdateMessage(
                id = "private-time",
                enabled = false,
                channelId = null,
                startMinutesSinceMidnight = null,
                stopMinutesSinceMidnight = null,
            ),
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
        assertEquals("/private/recording.ts", added.files?.single()?.path)
        assertEquals(DvrEntryState.RECORDING, added.state)
        assertEquals(GatewayDvrFailure.PRESENT, added.failure)
        assertEquals(DvrSubscriptionError.SCRAMBLED, added.subscriptionError)
        val updated = (events[1] as MetadataEvent.DvrEntryUpdated).entry
        assertEquals(false, updated.enabled)
        assertEquals("", updated.title)
        assertEquals(emptyList<Any>(), updated.files)
        assertEquals(DvrEntryState.COMPLETED, updated.state)
        assertEquals(GatewayDvrFailure.FILE_MISSING, updated.failure)
        assertEquals(DvrSubscriptionError.INVALID_TARGET, updated.subscriptionError)
        assertEquals(GatewayDvrUpdateProvenance.FULL, (events[1] as MetadataEvent.DvrEntryUpdated).provenance)
        assertEquals("private-auto", (events[3] as MetadataEvent.AutorecRuleAdded).rule.id.value)
        assertEquals(false, (events[4] as MetadataEvent.AutorecRuleUpdated).rule.enabled)
        assertEquals(null, (events[4] as MetadataEvent.AutorecRuleUpdated).rule.title)
        val timerecAdded = (events[6] as MetadataEvent.TimerecRuleAdded).rule
        assertEquals(null, timerecAdded.channelId)
        assertEquals(null, timerecAdded.startMinutesSinceMidnight)
        assertEquals(null, timerecAdded.stopMinutesSinceMidnight)
        val timerecUpdated = (events[7] as MetadataEvent.TimerecRuleUpdated).rule
        assertEquals(false, timerecUpdated.enabled)
        assertEquals(null, timerecUpdated.channelId)
        assertEquals(null, timerecUpdated.startMinutesSinceMidnight)
        assertEquals(null, timerecUpdated.stopMinutesSinceMidnight)
        assertTrue(events.all { it.generation === events.first().generation })
        assertFalse(events.toString().contains("Private"), "Gateway DVR rendering exposed metadata")
        assertFalse(events.toString().contains("/private"), "Gateway DVR rendering exposed a path")
    }

    @Test
    fun `DVR failure none is not an error and subscription tokens include source fallbacks`() = runTest {
        val generation = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            eventsFlow = flowOf(
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryAddMessage(
                        entryId = 1,
                        state = "completed",
                        error = "none",
                        subscriptionError = "No service assigned to channel",
                    ),
                    generation,
                    1,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryUpdateMessage(
                        entryId = 1,
                        error = "File missing",
                        subscriptionError = "Invalid service",
                    ),
                    generation,
                    2,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryUpdateMessage(entryId = 1, state = "invalid", subscriptionError = "noDiskSpace"),
                    generation,
                    3,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryUpdateMessage(entryId = 1, subscriptionError = "noService"),
                    generation,
                    4,
                ),
                HtspTransportEvent.ServerMessage(
                    HtspDvrEntryUpdateMessage(entryId = 1, subscriptionError = "invalidService"),
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
        val updated = (events[1] as MetadataEvent.DvrEntryUpdated).entry
        assertEquals(null, updated.state)
        assertEquals(GatewayDvrFailure.FILE_MISSING, updated.failure)
        assertEquals(DvrSubscriptionError.INVALID_SERVICE, updated.subscriptionError)
        assertTrue(
            events.drop(1).all {
                (it as MetadataEvent.DvrEntryUpdated).provenance == GatewayDvrUpdateProvenance.STATS_ONLY
            },
        )
        val invalid = (events[2] as MetadataEvent.DvrEntryUpdated).entry
        assertEquals(DvrEntryState.INVALID, invalid.state)
        assertEquals(DvrSubscriptionError.NO_DISK_SPACE, invalid.subscriptionError)
        assertEquals(
            DvrSubscriptionError.UNKNOWN,
            (events[3] as MetadataEvent.DvrEntryUpdated).entry.subscriptionError,
        )
        assertEquals(
            DvrSubscriptionError.UNKNOWN,
            (events[4] as MetadataEvent.DvrEntryUpdated).entry.subscriptionError,
        )
        assertFalse(events.toString().contains("service"), "Gateway DVR rendering exposed a server error")
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
                            start = -3,
                            stop = 2_147_483_648,
                            title = "Private title",
                            subtitle = "Private subtitle",
                            summary = "Private summary",
                            description = "Private description",
                            categories = listOf("category"),
                            keywords = emptyList(),
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
                            firstAired = 0,
                            isNew = 0,
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
        assertEquals(2_147_483_649L, request.maxTime)
        assertEquals(null, request.eventId)
        assertEquals(null, request.language)
        assertEquals(null, request.numFollowing)
        assertEquals(Instant.fromEpochSeconds(-3), event.start)
        assertEquals(Instant.fromEpochSeconds(2_147_483_648), event.stop)
        assertEquals(false, event.isNew)
        assertEquals(emptyList<String>(), event.keywords)
        assertEquals(13L, event.dvrEntryId?.value)
        assertEquals("GatewayEpgQueryEvent(<redacted>)", event.toString())
        assertFalse(result.value.toString().contains("Private"))
        assertThrows(UnsupportedOperationException::class.java) {
            (result.value as MutableList<*>).clear()
        }

        val failures = listOf(
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(expected, gateway.queryEpg(generation, ChannelId(2), maxTime))
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
    fun `initial metadata uses server time for bounded async EPG with legacy fallback`() = runTest {
        val serverNow = 1_000_000L
        val serverTime = GetSysTimeResponse(
            unixTimeSeconds = serverNow,
            legacyTimezoneHoursWestOfGmt = 0,
            gmtOffsetMinutes = 0,
        )

        suspend fun requestFor(
            protocolVersion: Int,
            serverTimeResult: HtspResult<GetSysTimeResponse> = HtspResult.Ok(serverTime),
        ): Pair<GatewayResult<Unit>, List<HtspRequest<*>>> {
            val sourceGeneration = HtspConnectionGeneration()
            val metadataEvents = MutableSharedFlow<HtspTransportEvent>()
            val requests = mutableListOf<HtspRequest<*>>()
            val fake = FakeHtspConnection().apply {
                liveConnectionValue.value = liveConnection(sourceGeneration, protocolVersion)
                connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
                eventsFlow = metadataEvents
                beforeExecute = { request ->
                    requests += request
                    when (request) {
                        is GetSysTimeRequest -> executeResult = serverTimeResult
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
                        else -> error("Unexpected initial metadata request")
                    }
                }
            }
            val gateway = HtspProtocolGateway(fake)
            val generation = (gateway.connect(ServerConfiguration("host", 9_982))
                as GatewayConnectResult.Connected).connection.generation

            val result = gateway.enableInitialMetadata(generation)
            assertSame(sourceGeneration, fake.lastExpectedGeneration)
            return result to requests
        }

        val (supportedResult, supportedRequests) = requestFor(protocolVersion = 6)
        assertTrue(supportedResult is GatewayResult.Ok)
        assertTrue(supportedRequests.singleOrNull { it is GetSysTimeRequest } is GetSysTimeRequest)
        val supported = supportedRequests.last() as EnableAsyncMetadataRequest
        assertEquals(1L, supported.epg)
        assertEquals(serverNow + 86_400L, supported.epgMaxTime)
        assertEquals(6, supported.minimumProtocolVersion)

        val (legacyResult, legacyRequests) = requestFor(protocolVersion = 5)
        assertTrue(legacyResult is GatewayResult.Ok)
        val legacy = legacyRequests.single() as EnableAsyncMetadataRequest
        assertEquals(null, legacy.epg)
        assertEquals(null, legacy.epgMaxTime)
        assertEquals(null, legacy.minimumProtocolVersion)

        val (failure, failedRequests) = requestFor(
            protocolVersion = 6,
            serverTimeResult = HtspResult.Timeout,
        )
        assertSame(GatewayResult.Timeout, failure)
        assertTrue(failedRequests.single() is GetSysTimeRequest)
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
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(expected, gateway.getDvrConfigs(generation))
            assertSame(expected, gateway.getDiskSpace(generation))
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
    fun `DVR entry mutations map complete requests and require semantic acceptance`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(AddDvrEntryResponse(1, 7, "private raw error"))
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
        assertEquals(-3L, selector.start)
        assertEquals(4L, selector.stop)
        assertEquals("private-config", addRequest.configName)
        assertEquals("eng", addRequest.language)
        assertEquals("private-title", addRequest.title)
        assertEquals("private-subtitle", addRequest.subtitle)
        assertEquals("private-summary", addRequest.summary)
        assertEquals("private-description", addRequest.description)
        assertEquals(12L, addRequest.ageRating)
        assertFalse(scheduled.toString().contains("private"))

        listOf(
            AddDvrEntryResponse(null, null, "private rejected"),
            AddDvrEntryResponse(0, 7, "private rejected"),
            AddDvrEntryResponse(1, null, null),
        ).forEach { response ->
            fake.executeResult = HtspResult.Ok(response)
            assertSame(GatewayResult.ServerRejected, gateway.scheduleDvrEntry(generation, schedule))
        }

        fake.executeResult = HtspResult.Ok(UpdateDvrEntryResponse(1, "private ignored"))
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
            startExtraMinutes = -7,
            stopExtraMinutes = 8,
            retentionDays = 9,
            removalDays = 10,
            priority = 11,
            ageRating = 13,
        )
        assertTrue(gateway.updateDvrEntry(generation, DvrEntryId(7), update) is GatewayResult.Ok)
        val updateRequest = fake.lastRequest as UpdateDvrEntryRequest
        assertEquals(7L, updateRequest.entryId)
        assertEquals(3L, updateRequest.channelId)
        assertEquals("private-next-config", updateRequest.configName)
        assertEquals("private-next-title", updateRequest.title)
        assertEquals("private-next-subtitle", updateRequest.subtitle)
        assertEquals("private-next-summary", updateRequest.summary)
        assertEquals("private-next-description", updateRequest.description)
        assertEquals("deu", updateRequest.language)
        assertEquals("private-comment", updateRequest.comment)
        assertEquals(1L, updateRequest.enabled)
        assertEquals(-5L, updateRequest.start)
        assertEquals(6L, updateRequest.stop)
        assertEquals(-7L, updateRequest.startExtra)
        assertEquals(8L, updateRequest.stopExtra)
        assertEquals(9L, updateRequest.retention)
        assertEquals(10L, updateRequest.removal)
        assertEquals(11L, updateRequest.priority)
        assertEquals(13L, updateRequest.ageRating)
        assertEquals(null, updateRequest.playCount)
        assertEquals(null, updateRequest.playPosition)

        fake.executeResult = HtspResult.Ok(StopDvrEntryResponse(1, null))
        assertTrue(gateway.stopDvrEntry(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is StopDvrEntryRequest)
        fake.executeResult = HtspResult.Ok(CancelDvrEntryResponse(1, null))
        assertTrue(gateway.cancelDvrEntry(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is CancelDvrEntryRequest)
        fake.executeResult = HtspResult.Ok(DeleteDvrEntryResponse(1, null))
        assertTrue(gateway.deleteDvrEntry(generation, DvrEntryId(7)) is GatewayResult.Ok)
        assertTrue(fake.lastRequest is DeleteDvrEntryRequest)
        fake.executeResult = HtspResult.Ok(UpdateDvrEntryResponse(null, "private rejected"))
        assertSame(
            GatewayResult.ServerRejected,
            gateway.updateDvrEntry(generation, DvrEntryId(7), DvrEntryUpdate()),
        )

        val failures = listOf(
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(expected, gateway.scheduleDvrEntry(generation, schedule))
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
    fun `DVR progress maps keep and increment playcount without metadata fields`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(UpdateDvrEntryResponse(1, "private ignored"))
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        assertTrue(
            gateway.reportDvrProgress(
                generation,
                DvrEntryId(7),
                DvrPlaybackProgress.checkpoint(90.seconds),
            ) is GatewayResult.Ok,
        )
        val keep = fake.lastRequest as UpdateDvrEntryRequest
        assertEquals(7L, keep.entryId)
        assertEquals(90L, keep.playPosition)
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
        assertEquals(120L, increment.playPosition)
        assertEquals(DVR_PROGRESS_INCR_PLAY_COUNT, increment.playCount)

        fake.executeResult = HtspResult.Ok(UpdateDvrEntryResponse(null, "private rejected"))
        assertSame(
            GatewayResult.ServerRejected,
            gateway.reportDvrProgress(
                generation,
                DvrEntryId(7),
                DvrPlaybackProgress.checkpoint(1.seconds),
            ),
        )
        val failures = listOf(
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        )
        failures.forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(
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
                        HtspDvrCutpoint(start = 5_000, end = 10_000, type = 0),
                        HtspDvrCutpoint(start = 1_000, end = 8_000, type = 1),
                        HtspDvrCutpoint(start = 10_000, end = 10_001, type = 2),
                        HtspDvrCutpoint(start = 12_000, end = 15_000, type = 3),
                        HtspDvrCutpoint(start = 20_000, end = 21_000, type = 99),
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
            GetDvrCutpointsResponse(listOf(HtspDvrCutpoint(start = 10, end = 10, type = 0))),
        )
        assertSame(
            GatewayResult.ServerRejected,
            gateway.getDvrCutpoints(generation, DvrEntryId(7)),
            "One invalid interval must reject the complete response",
        )

        listOf(
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(expected, gateway.getDvrCutpoints(generation, DvrEntryId(7)))
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
            duplicateDetection = 3,
            maximumRecordingCount = 4,
            broadcastType = 5,
            startExtraMinutes = -6,
            stopExtraMinutes = 7,
            seriesLinkUri = "private-series",
            approximateStartMinutesSinceMidnight = 8,
            startMinutesSinceMidnight = 9,
            startWindowEndMinutesSinceMidnight = 10,
            enabled = true,
            retentionDays = 11,
            removalDays = 12,
            priority = 13,
            name = "private-name",
            comment = "private-comment",
            directory = "private-directory",
            configId = DvrConfigId("private-config"),
            daysOfWeekMask = 127,
        )
        fake.executeResult = HtspResult.Ok(AddAutorecEntryResponse("private-autorec-id"))

        val createdAutorec = gateway.createAutorecRule(generation, autorec) as GatewayResult.Ok
        val addAutorec = fake.lastRequest as AddAutorecEntryRequest
        assertEquals(AutorecRuleId("private-autorec-id"), createdAutorec.value)
        assertEquals(2L, (addAutorec.channel as HtspRecordingRuleChannel.Id).channelId)
        assertEquals(60L, addAutorec.minDurationSeconds)
        assertEquals(120L, addAutorec.maxDurationSeconds)
        assertEquals(1L, addAutorec.fullText)
        assertEquals(0L, addAutorec.mergeText)
        assertEquals(3L, addAutorec.duplicateDetection)
        assertEquals(4L, addAutorec.maximumRecordingCount)
        assertEquals(5L, addAutorec.broadcastType)
        assertEquals(-6L, addAutorec.startExtraMinutes)
        assertEquals(7L, addAutorec.stopExtraMinutes)
        assertEquals("private-series", addAutorec.seriesLinkUri)
        assertEquals(8, addAutorec.approximateStartMinutesSinceMidnight)
        assertEquals(9, addAutorec.startMinutesSinceMidnight)
        assertEquals(10, addAutorec.startWindowEndMinutesSinceMidnight)
        assertEquals(true, addAutorec.enabled)
        assertEquals(11L, addAutorec.retentionDays)
        assertEquals(12L, addAutorec.removalDays)
        assertEquals(13L, addAutorec.priority)
        assertEquals("private-name", addAutorec.name)
        assertEquals("private-comment", addAutorec.comment)
        assertEquals("private-directory", addAutorec.directory)
        assertEquals("private-config", addAutorec.configName)
        assertEquals(127L, addAutorec.daysOfWeekMask)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

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
                startMinutesSinceMidnight = 60,
                stopMinutesSinceMidnight = 120,
                enabled = true,
                retentionDays = 1,
                removalDays = 2,
                priority = 3,
                name = "private-time-name",
                comment = "private-time-comment",
                directory = "private-time-directory",
                configId = DvrConfigId("private-time-config"),
                daysOfWeekMask = 31,
            ),
        ) as GatewayResult.Ok
        val addTimerec = fake.lastRequest as AddTimerecEntryRequest
        assertEquals(TimerecRuleId("private-timerec-id"), createdTimerec.value)
        assertSame(HtspRecordingRuleChannel.Any, addTimerec.channel)
        assertEquals(60L, addTimerec.startMinutesSinceMidnight)
        assertEquals(120L, addTimerec.stopMinutesSinceMidnight)
        assertEquals("private-time-config", addTimerec.configName)

        fake.executeResult = HtspResult.Ok(UpdateTimerecEntryResponse)
        assertTrue(
            gateway.updateTimerecRule(
                generation,
                TimerecRuleId("private-timerec-id"),
                TimerecRuleUpdate(title = "private-next-time", stopMinutesSinceMidnight = 180),
            ) is GatewayResult.Ok,
        )
        val updateTimerec = fake.lastRequest as UpdateTimerecEntryRequest
        assertEquals("private-timerec-id", updateTimerec.id)
        assertEquals("private-next-time", updateTimerec.title)
        assertEquals(180L, updateTimerec.stopMinutesSinceMidnight)

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
                    message = HtspTagAddMessage(tagId = 1),
                    generation = generation,
                    messageSequence = 1,
                ),
                HtspTransportEvent.ServerMessage(
                    message = HtspTagAddMessage(tagId = 2, tagTitledIcon = 0),
                    generation = generation,
                    messageSequence = 2,
                ),
                HtspTransportEvent.ServerMessage(
                    message = HtspTagAddMessage(tagId = 3, tagTitledIcon = 2),
                    generation = generation,
                    messageSequence = 3,
                ),
            )
        }
        val events = HtspProtocolGateway(fake).metadata.toList()
        assertEquals(null, (events[0] as MetadataEvent.TagAdded).tag.titledIcon)
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
                            unixTimeSeconds = 1_000_000L,
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
            HtspResult.ServerError to SubscriptionOperationResult.ServerRejected,
            HtspResult.AccessDenied to SubscriptionOperationResult.AccessDenied,
            HtspResult.ConnectionLimit to SubscriptionOperationResult.ConnectionLimit,
            HtspResult.Timeout to SubscriptionOperationResult.Timeout,
            HtspResult.TransportUnavailable to SubscriptionOperationResult.TransportUnavailable,
            HtspResult.NotSupported to SubscriptionOperationResult.NotSupported,
        )
        operationFailures.forEach { (source, expected) ->
            fake.executeResult = source
            val result = gateway.subscribe(generation, SubscriptionId(10), ChannelId(20), Duration.ZERO)
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
        gateway.subscribe(generation, SubscriptionId(5), ChannelId(6), 300.seconds)
        assertEquals(300L, (fake.lastRequest as SubscribeRequest).timeshiftPeriodSeconds)
        gateway.subscribe(generation, SubscriptionId(5), ChannelId(6), Duration.ZERO)
        assertEquals(null, (fake.lastRequest as SubscribeRequest).timeshiftPeriodSeconds)
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
        assertEquals(1L, absolute.absolute)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        gateway.skipSubscription(
            generation,
            SubscriptionId(5),
            SubscriptionSeekTarget.Relative((-30).seconds),
        )
        val relative = fake.lastRequest as SubscriptionSkipRequest
        assertEquals(SubscriptionSeekPosition.Time(-30_000_000L), relative.position)
        assertEquals(0L, relative.absolute)

        val requestBeforeLive = fake.lastRequest
        assertSame(
            SubscriptionOperationResult.NotSupported,
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
        assertEquals(1L, nearLive.absolute)
        assertSame(sourceGeneration, fake.lastExpectedGeneration)

        listOf(
            HtspResult.ServerError to SubscriptionOperationResult.ServerRejected,
            HtspResult.AccessDenied to SubscriptionOperationResult.AccessDenied,
            HtspResult.ConnectionLimit to SubscriptionOperationResult.ConnectionLimit,
            HtspResult.Timeout to SubscriptionOperationResult.Timeout,
            HtspResult.TransportUnavailable to SubscriptionOperationResult.TransportUnavailable,
            HtspResult.NotSupported to SubscriptionOperationResult.NotSupported,
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(
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
            assertSame(
                SubscriptionOperationResult.NotSupported,
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
    fun `recording file access binds the DVR selector bounded reads and generation`() = runTest {
        val sourceGeneration = HtspConnectionGeneration()
        val fake = FakeHtspConnection().apply {
            liveConnectionValue.value = liveConnection(sourceGeneration)
            connectOutcome = HtspConnectOutcome.Connected(requireNotNull(liveConnectionValue.value))
            executeResult = HtspResult.Ok(
                FileOpenResponse(id = 12, sizeBytes = 4_096, modifiedAtUnixSeconds = 1_700_000_000),
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
            FileOpenResponse(id = 13, sizeBytes = -1, modifiedAtUnixSeconds = null),
        )
        assertEquals(
            null,
            ((gateway.openRecordingFile(generation, DvrEntryId(7)) as GatewayResult.Ok).value).sizeBytes,
            "A negative reported size must not be published as a readable length",
        )

        fake.executeResult = HtspResult.Ok(FileSeekResponse(offset = 900))
        assertEquals(900L, (gateway.seekRecordingFile(generation, file, 900) as GatewayResult.Ok).value)
        val seek = fake.lastRequest as FileSeekRequest
        assertEquals(12L, seek.id)
        assertEquals(900L, seek.offset)
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
        assertEquals(4L, read.size)
        assertEquals(900L, read.offset, "Every read must carry its absolute position")
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
            GatewayResult.ServerRejected,
            gateway.readRecordingFile(generation, file, 0, guarded, 0, 2),
            "A payload larger than the request must be rejected instead of copied",
        )
        assertArrayEquals(byteArrayOf(7, 7, 7, 7), guarded)

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
            HtspResult.ServerError to GatewayResult.ServerRejected,
            HtspResult.AccessDenied to GatewayResult.AccessDenied,
            HtspResult.ConnectionLimit to GatewayResult.ConnectionLimit,
            HtspResult.Timeout to GatewayResult.Timeout,
            HtspResult.TransportUnavailable to GatewayResult.TransportUnavailable,
            HtspResult.NotSupported to GatewayResult.NotSupported,
        ).forEach { (source, expected) ->
            fake.executeResult = source
            assertSame(expected, gateway.openRecordingFile(generation, DvrEntryId(7)))
            assertSame(expected, gateway.seekRecordingFile(generation, file, 0))
            assertSame(expected, gateway.readRecordingFile(generation, file, 0, destination, 0, 4))
            assertSame(expected, gateway.closeRecordingFile(generation, file))
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

        gateway.shutdown()
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
                        FileOpenResponse(id = 31, sizeBytes = 5, modifiedAtUnixSeconds = null),
                    )
                    is FileReadRequest -> HtspResult.Ok(
                        FileReadResponse(
                            HtspBinary(
                                if (request.offset == 0L) byteArrayOf(1, 2, 3)
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
        assertEquals(listOf(0L, 3L), requests.filterIsInstance<FileReadRequest>().map { it.offset })
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
                        FileOpenResponse(id = 37, sizeBytes = null, modifiedAtUnixSeconds = null),
                    )
                    is FileReadRequest -> HtspResult.Ok(
                        FileReadResponse(
                            HtspBinary(
                                when (request.offset) {
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
        assertEquals(listOf(0L, 2L, 3L), requests.filterIsInstance<FileReadRequest>().map { it.offset })
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
                        FileOpenResponse(id = 43, sizeBytes = null, modifiedAtUnixSeconds = null),
                    )
                    is FileReadRequest -> HtspResult.Ok(
                        FileReadResponse(HtspBinary(ByteArray(request.size.toInt()) { 1 })),
                    )
                    is FileCloseRequest -> HtspResult.Ok(FileCloseResponse)
                    else -> error("Unexpected request type")
                }
            }
        }
        val gateway = HtspProtocolGateway(fake)
        val generation = (gateway.connect(ServerConfiguration("host", 9_982))
            as GatewayConnectResult.Connected).connection.generation

        assertSame(GatewayResult.ServerRejected, gateway.loadArtwork(generation, ArtworkId(83)))

        val reads = requests.filterIsInstance<FileReadRequest>()
        assertEquals(16L * 1024 * 1024, reads.last().offset)
        assertEquals(1L, reads.last().size)
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
                            modifiedAtUnixSeconds = null,
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

        assertSame(GatewayResult.ServerRejected, gateway.loadArtwork(generation, ArtworkId(7)))
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
                FileOpenResponse(id = 3, sizeBytes = 10, modifiedAtUnixSeconds = null),
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
        val sourceGeneration = HtspConnectionGeneration()
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

        val terminations = gateway.subscription(generation, SubscriptionId(77)).toList()
            .map { event -> (event as SubscriptionEvent.Terminated).reason }

        assertEquals(
            sourceTerminations.map { termination ->
                SubscriptionTermination.valueOf(termination.name)
            },
            terminations,
        )
        assertEquals(sourceTerminations.size, terminations.distinct().size)
    }

    private fun liveConnection(
        generation: HtspConnectionGeneration,
        protocolVersion: Int? = 43,
    ): HtspLiveConnection =
        HtspLiveConnection(
            generation = generation,
            protocolVersion = protocolVersion,
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
    internal val expectedGenerations = ArrayList<HtspConnectionGeneration?>()
    internal var lastRequest: HtspRequest<*>? = null
    internal var lastSubscriptionId: Long? = null
    internal var lastSubscriptionExpectedGeneration: HtspConnectionGeneration? = null
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
