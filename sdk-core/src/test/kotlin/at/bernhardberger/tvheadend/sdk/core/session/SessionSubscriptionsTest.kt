@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationFailure
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSubscriptionsTest {
    @Test
    fun `children bind admission and teardown one exact generation at a time`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        val generationA = GatewayGeneration()
        val generationB = GatewayGeneration()

        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )
        metadata.bindKnownChannels(generationA, 1L)
        children.bindGeneration(generationA)
        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )
        assertFalse(children.startLiveAdmission(generationB, CapabilityAccess.ALLOWED))
        assertTrue(children.startLiveAdmission(generationA, CapabilityAccess.ALLOWED))

        val openA = async {
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {})
        }
        runCurrent()
        gateway.emitStarted(generationA)
        runCurrent()
        assertTrue(openA.await() is SubscriptionOpenResult.Opened)
        assertSame(generationA, gateway.collectedGenerations.single())
        assertTrue(
            gateway.collectedIds.single() == SubscriptionId(0L),
            "The first generation must allocate its first subscription identifier",
        )

        children.stopAdmission()
        children.closeAndJoinSubscriptions()
        assertSame(generationA, gateway.unsubscribedGenerations.single())
        assertTrue(
            gateway.unsubscribedIds.single() == SubscriptionId(0L),
            "Teardown must unsubscribe the admitted subscription",
        )
        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        metadata.bindKnownChannels(generationB, 1L)
        children.bindGeneration(generationB)
        assertTrue(children.startLiveAdmission(generationB, CapabilityAccess.ALLOWED))
        val openB = async {
            children.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {})
        }
        runCurrent()
        gateway.emitStarted(generationB)
        runCurrent()
        assertTrue(openB.await() is SubscriptionOpenResult.Opened)
        assertSame(generationB, gateway.collectedGenerations.last())
        assertTrue(
            gateway.collectedIds.last() == SubscriptionId(0L),
            "A fresh generation must receive a fresh identifier namespace",
        )
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `early admission rejects cold unknown and denied channels without subscribe rpc`() = runTest {
        val coldGateway = SubscriptionGateway()
        val coldMetadata = PhaseOneSessionMetadata()
        val coldGeneration = GatewayGeneration()
        coldMetadata.bindGeneration(coldGeneration)
        val coldChildren = PlaybackSessionChildren(
            coldGateway,
            coldMetadata,
            StandardTestDispatcher(testScheduler),
        )
        coldChildren.bindGeneration(coldGeneration)
        assertTrue(coldChildren.startLiveAdmission(coldGeneration, CapabilityAccess.ALLOWED))

        assertSame(
            SubscriptionOpenResult.NotReady,
            coldChildren.open(SubscriptionChannelId(1L), SubscriptionEventConsumer {}),
        )
        assertTrue(coldGateway.requestedTimeshiftPeriods.isEmpty())
        assertTrue(coldGateway.collectedGenerations.isEmpty())
        coldChildren.closeAndJoinSubscriptions()

        val knownGateway = SubscriptionGateway()
        val knownMetadata = PhaseOneSessionMetadata()
        val knownGeneration = GatewayGeneration()
        knownMetadata.bindKnownChannels(knownGeneration, 1L)
        val knownChildren = PlaybackSessionChildren(
            knownGateway,
            knownMetadata,
            StandardTestDispatcher(testScheduler),
        )
        knownChildren.bindGeneration(knownGeneration)
        assertTrue(knownChildren.startLiveAdmission(knownGeneration, CapabilityAccess.ALLOWED))

        val unknown = knownChildren.open(
            SubscriptionChannelId(2L),
            SubscriptionEventConsumer {},
        ) as SubscriptionOpenResult.Failed
        assertSame(
            SubscriptionOperationFailure.SERVER_REJECTED,
            (unknown.reason as SubscriptionTerminalReason.OperationFailed).failure,
        )
        assertTrue(knownGateway.requestedTimeshiftPeriods.isEmpty())
        assertTrue(knownGateway.collectedGenerations.isEmpty())
        knownChildren.closeAndJoinSubscriptions()

        val deniedGateway = SubscriptionGateway()
        val deniedMetadata = PhaseOneSessionMetadata()
        val deniedGeneration = GatewayGeneration()
        deniedMetadata.bindKnownChannels(deniedGeneration, 1L)
        val deniedChildren = PlaybackSessionChildren(
            deniedGateway,
            deniedMetadata,
            StandardTestDispatcher(testScheduler),
        )
        deniedChildren.bindGeneration(deniedGeneration)
        assertTrue(deniedChildren.startLiveAdmission(deniedGeneration, CapabilityAccess.DENIED))

        val denied = deniedChildren.open(
            SubscriptionChannelId(1L),
            SubscriptionEventConsumer {},
        ) as SubscriptionOpenResult.Failed
        assertSame(
            SubscriptionOperationFailure.ACCESS_DENIED,
            (denied.reason as SubscriptionTerminalReason.OperationFailed).failure,
        )
        assertTrue(deniedGateway.requestedTimeshiftPeriods.isEmpty())
        assertTrue(deniedGateway.collectedGenerations.isEmpty())
        deniedChildren.closeAndJoinSubscriptions()
    }

    @Test
    fun `retained same process catalog admits live channel before replacement sync completes`() =
        runTest {
            val gateway = SubscriptionGateway()
            val metadata = PhaseOneSessionMetadata()
            val previousGeneration = GatewayGeneration()
            val currentGeneration = GatewayGeneration()
            metadata.bindKnownChannels(previousGeneration, 7L)
            metadata.resetWorkingStateRetainingPublishedSnapshot()
            metadata.bindGeneration(currentGeneration)
            val children = PlaybackSessionChildren(
                gateway,
                metadata,
                StandardTestDispatcher(testScheduler),
            )
            children.bindGeneration(currentGeneration)
            assertTrue(
                children.startLiveAdmission(currentGeneration, CapabilityAccess.ALLOWED),
            )

            val opening = async {
                children.open(SubscriptionChannelId(7L), SubscriptionEventConsumer {})
            }
            runCurrent()
            gateway.emitStarted(currentGeneration)
            runCurrent()

            assertTrue(opening.await() is SubscriptionOpenResult.Opened)
            assertSame(currentGeneration, gateway.collectedGenerations.single())
            children.closeAndJoinSubscriptions()
        }

    @Test
    fun `the session forwards the timeshift request and generation bound seek commands`() =
        runTest {
            val gateway = SubscriptionGateway()
            gateway.grantedTimeshiftSeconds = 600L
            val metadata = PhaseOneSessionMetadata()
            val children = PlaybackSessionChildren(
                gateway,
                metadata,
                StandardTestDispatcher(testScheduler),
            )
            val generation = GatewayGeneration()
            metadata.bindKnownChannels(generation, 4L)
            children.bindGeneration(generation)
            assertTrue(children.startLiveAdmission(generation, CapabilityAccess.ALLOWED))
            val opening = async {
                children.open(
                    SubscriptionChannelId(4L),
                    SubscriptionEventConsumer {},
                    600.seconds,
                )
            }
            runCurrent()
            gateway.emitStarted(generation)
            runCurrent()
            val subscription =
                (opening.await() as SubscriptionOpenResult.Opened).subscription

            assertEquals(listOf(600.seconds), gateway.requestedTimeshiftPeriods)
            assertEquals(600.seconds, subscription.grantedTimeshiftPeriod)

            val seeking = async {
                subscription.seek(SubscriptionSeekTarget.Absolute(120.seconds))
            }
            runCurrent()
            gateway.emitSkipped(generation, SkipOutcome.ACCEPTED)
            runCurrent()

            assertSame(SubscriptionSeekResult.Accepted, seeking.await())
            val target = gateway.skippedTargets.single() as SubscriptionSeekTarget.Absolute
            assertEquals(
                120.seconds,
                target.position,
                "The absolute media position must reach the gateway unchanged",
            )
            children.closeAndJoinSubscriptions()
        }

    @Test
    fun `return live uses ordered status before rpc and preserves attributed termination`() =
        runTest {
            val gateway = SubscriptionGateway().apply { grantedTimeshiftSeconds = 600L }
            val nearLiveRequestEntered = CompletableDeferred<Unit>()
            val releaseNearLiveRequest = CompletableDeferred<Unit>()
            gateway.nearLiveAction = {
                nearLiveRequestEntered.complete(Unit)
                releaseNearLiveRequest.await()
                SubscriptionOperationResult.Ok(Unit)
            }
            val metadata = PhaseOneSessionMetadata()
            val children = PlaybackSessionChildren(
                gateway,
                metadata,
                StandardTestDispatcher(testScheduler),
            )
            val generation = GatewayGeneration()
            metadata.bindKnownChannels(generation, 4L)
            children.bindGeneration(generation)
            assertTrue(children.startLiveAdmission(generation, CapabilityAccess.ALLOWED))
            val opening = async {
                children.open(
                    SubscriptionChannelId(4L),
                    SubscriptionEventConsumer {},
                    600.seconds,
                )
            }
            runCurrent()
            gateway.emitStarted(generation)
            runCurrent()
            val subscription =
                (opening.await() as SubscriptionOpenResult.Opened).subscription

            val unavailable = async { subscription.seek(SubscriptionSeekTarget.Live) }
            runCurrent()
            val unavailableResult = unavailable.await() as SubscriptionSeekResult.Refused
            assertSame(SubscriptionOperationFailure.NOT_SUPPORTED, unavailableResult.failure)
            assertTrue(gateway.nearLiveStatuses.isEmpty())
            assertTrue(gateway.skippedTargets.isEmpty())

            gateway.emitTimeshift(generation, start = 10_000_000, end = 90_000_000)
            runCurrent()
            val seeking = async { subscription.seek(SubscriptionSeekTarget.Live) }
            nearLiveRequestEntered.await()

            assertSame(generation, gateway.nearLiveGenerations.single())
            assertEquals(SubscriptionId(0L), gateway.nearLiveIds.single())
            assertEquals(10_000_000L, gateway.nearLiveStatuses.single().start)
            assertEquals(90_000_000L, gateway.nearLiveStatuses.single().end)
            assertEquals(3L, gateway.nearLiveMargins.single())
            assertFalse(seeking.isCompleted)
            assertFalse(releaseNearLiveRequest.isCompleted)

            gateway.emitSkipped(generation, SkipOutcome.ACCEPTED)
            runCurrent()
            assertSame(
                SubscriptionSeekResult.Accepted,
                seeking.await(),
                "The ordered event is authoritative before the RPC reply",
            )

            gateway.emitTerminated(generation, SubscriptionTermination.REMOTE_EOF)
            runCurrent()
            val terminal = subscription.state.value as SubscriptionState.Terminal
            assertSame(SubscriptionTerminalReason.RemoteEof, terminal.reason)
            assertFalse(releaseNearLiveRequest.isCompleted)
            assertTrue(gateway.skippedTargets.isEmpty(), "Direct live must have no fallback")

            children.closeAndJoinSubscriptions()
        }

    @Test
    fun `gateway adapter uses latest ordered status and clears it after collection`() = runTest {
        val gateway = SubscriptionGateway()
        val generation = GatewayGeneration()
        val id = SubscriptionId(7L)
        val connection = GatewaySubscriptionConnection(gateway, generation)
        val collected = async { connection.events(id).toList() }
        runCurrent()

        gateway.emitTimeshift(generation, start = 10_000_000, end = 80_000_000)
        gateway.emitTimeshift(generation, start = 20_000_000, end = 90_000_000)
        runCurrent()
        assertTrue(connection.skip(id, SubscriptionSeekTarget.Live) is SubscriptionOperationResult.Ok)
        assertSame(generation, gateway.nearLiveGenerations.single())
        assertEquals(id, gateway.nearLiveIds.single())
        assertEquals(20_000_000L, gateway.nearLiveStatuses.single().start)
        assertEquals(90_000_000L, gateway.nearLiveStatuses.single().end)
        assertEquals(3L, gateway.nearLiveMargins.single())

        gateway.complete(generation)
        assertEquals(2, collected.await().size)
        assertSame(
            SubscriptionOperationResult.NotSupported,
            connection.skip(id, SubscriptionSeekTarget.Live),
        )
        assertEquals(1, gateway.nearLiveStatuses.size, "Completed collection must clear its status")
    }

    @Test
    fun `cancelled teardown retains the manager until every subscription joins`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        val generation = GatewayGeneration()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        metadata.bindKnownChannels(generation, 2L)
        children.bindGeneration(generation)
        assertTrue(children.startLiveAdmission(generation, CapabilityAccess.ALLOWED))
        val opening = async {
            children.open(
                SubscriptionChannelId(2L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        consumerEntered.complete(Unit)
                        releaseConsumer.await()
                    }
                },
            )
        }
        runCurrent()
        gateway.emitStarted(generation)
        runCurrent()
        opening.await()
        gateway.emitPacket(generation)
        consumerEntered.await()

        children.stopAdmission()
        val cancellationSeen = CompletableDeferred<Boolean>()
        val firstClose = launch {
            try {
                children.closeAndJoinSubscriptions()
                cancellationSeen.complete(false)
            } catch (_: CancellationException) {
                cancellationSeen.complete(true)
            }
        }
        runCurrent()
        firstClose.cancel(CancellationException("fixed teardown cancellation"))
        val secondClose = async { children.closeAndJoinSubscriptions() }
        runCurrent()
        assertFalse(firstClose.isCompleted)
        assertFalse(secondClose.isCompleted)

        releaseConsumer.complete(Unit)
        runCurrent()
        firstClose.join()
        secondClose.await()

        assertTrue(cancellationSeen.await())
        assertEquals(1, gateway.unsubscribeCount)
        assertSame(
            SubscriptionOpenResult.NotReady,
            children.open(SubscriptionChannelId(2L), SubscriptionEventConsumer {}),
        )
    }

    @Test
    fun `child cancellation clears the old manager before a fresh generation binds`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        val generationA = GatewayGeneration()
        val generationB = GatewayGeneration()
        val consumerEntered = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val childCancellation = CancellationException("fixed child cancellation")
        metadata.bindKnownChannels(generationA, 3L)
        children.bindGeneration(generationA)
        assertTrue(children.startLiveAdmission(generationA, CapabilityAccess.ALLOWED))
        val opening = async {
            children.open(
                SubscriptionChannelId(3L),
                SubscriptionEventConsumer { event ->
                    if (event is SubscriptionEvent.Packet) {
                        consumerEntered.complete(Unit)
                        releaseConsumer.await()
                        throw childCancellation
                    }
                },
            )
        }
        runCurrent()
        gateway.emitStarted(generationA)
        runCurrent()
        opening.await()
        gateway.emitPacket(generationA)
        consumerEntered.await()
        children.stopAdmission()
        var observedCancellation: CancellationException? = null
        val closing = launch {
            try {
                children.closeAndJoinSubscriptions()
            } catch (cancellation: CancellationException) {
                observedCancellation = cancellation
            }
        }
        runCurrent()
        releaseConsumer.complete(Unit)
        runCurrent()
        closing.join()

        assertSame(childCancellation, observedCancellation)
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        metadata.bindKnownChannels(generationB, 3L)
        children.bindGeneration(generationB)
        assertTrue(children.startLiveAdmission(generationB, CapabilityAccess.ALLOWED))
        val reopened = async {
            children.open(SubscriptionChannelId(3L), SubscriptionEventConsumer {})
        }
        runCurrent()
        gateway.emitStarted(generationB)
        runCurrent()
        assertTrue(reopened.await() is SubscriptionOpenResult.Opened)
        assertTrue(
            gateway.collectedIds.last() == SubscriptionId(0L),
            "A replacement generation must restart its identifier namespace",
        )
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `recording handles bind one generation and refuse a changed connection`() = runTest {
        val gateway = SubscriptionGateway()
        val children = PlaybackSessionChildren(
            gateway,
            PhaseOneSessionMetadata(),
            StandardTestDispatcher(testScheduler),
        )
        val generation = GatewayGeneration()

        assertSame(
            RecordingFileFailure.CONNECTION_CHANGED,
            (children.openRecording(RecordingId(5L)) as RecordingFileResult.Failed).failure,
            "An unbound session must not present a recording as unreadable",
        )
        assertTrue(gateway.openedRecordingGenerations.isEmpty())

        children.bindGeneration(generation)
        val file = (children.openRecording(RecordingId(5L)) as RecordingFileResult.Ok).value
        assertSame(generation, gateway.openedRecordingGenerations.single())
        assertEquals(DvrEntryId(5L), gateway.openedRecordingIds.single())
        assertEquals(64L, file.sizeBytes)
        assertEquals("GatewayRecordingFileHandle(<redacted>)", file.toString())

        assertEquals(32L, (file.seek(32L) as RecordingFileResult.Ok).value)
        assertSame(generation, gateway.seekedRecordingGenerations.single())
        val destination = ByteArray(4)
        assertEquals(2, (file.read(32L, destination, 1, 2) as RecordingFileResult.Ok).value)
        assertSame(generation, gateway.readRecordingGenerations.single())

        assertTrue(file.close() is RecordingFileResult.Ok)
        assertTrue(file.close() is RecordingFileResult.Ok)
        assertSame(
            generation,
            gateway.closedRecordingGenerations.single(),
            "Closing a recording handle twice must release the server handle once",
        )
        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (file.seek(0L) as RecordingFileResult.Failed).failure,
        )
        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (file.read(0L, destination, 0, 1) as RecordingFileResult.Failed).failure,
        )
        assertEquals(1, gateway.seekedRecordingGenerations.size)
        assertEquals(1, gateway.readRecordingGenerations.size)

        children.closeAndJoinSubscriptions()
        assertSame(
            RecordingFileFailure.CONNECTION_CHANGED,
            (children.openRecording(RecordingId(5L)) as RecordingFileResult.Failed).failure,
            "A torn-down generation must report a changed connection, not a bad file",
        )
    }

    @Test
    fun `recording open failures separate a changed connection from an unreadable file`() = runTest {
        val gateway = SubscriptionGateway()
        val children = PlaybackSessionChildren(
            gateway,
            PhaseOneSessionMetadata(),
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(GatewayGeneration())

        listOf(
            GatewayResult.TransportUnavailable to RecordingFileFailure.CONNECTION_CHANGED,
            GatewayResult.ServerRejected to RecordingFileFailure.FILE_UNAVAILABLE,
            GatewayResult.AccessDenied to RecordingFileFailure.ACCESS_DENIED,
            GatewayResult.ConnectionLimit to RecordingFileFailure.CONNECTION_LIMIT,
            GatewayResult.Timeout to RecordingFileFailure.TIMEOUT,
            GatewayResult.NotSupported to RecordingFileFailure.NOT_SUPPORTED,
        ).forEach { (source, expected) ->
            gateway.recordingOpenResult = source
            assertSame(
                expected,
                (children.openRecording(RecordingId(9L)) as RecordingFileResult.Failed).failure,
            )
        }

        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `growing recording reader binds file metadata and operations to one generation`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindCurrentRecording(generation, id = 5L, sizeBytes = 64L)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(generation)

        val reader = (
            children.openGrowingRecording(RecordingId(5L), position = 4L)
                as RecordingFileResult.Ok
        ).value
        assertEquals(2, (reader.read(ByteArray(2), 0, 2) as RecordingFileResult.Ok).value)
        assertTrue(reader.close() is RecordingFileResult.Ok)
        assertTrue(reader.close() is RecordingFileResult.Ok)

        assertEquals(listOf(generation), gateway.openedRecordingGenerations)
        assertEquals(listOf(generation), gateway.seekedRecordingGenerations)
        assertEquals(listOf(4L), gateway.seekedRecordingPositions)
        assertEquals(listOf(generation), gateway.readRecordingGenerations)
        assertEquals(listOf(generation), gateway.closedRecordingGenerations)
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `growing lease refuses every reopen after its generation is replaced`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val firstGeneration = GatewayGeneration()
        metadata.bindCurrentRecording(firstGeneration, id = 5L, sizeBytes = 64L)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(firstGeneration)
        val lease = (
            children.bindGrowingRecording(RecordingId(5L)) as RecordingFileResult.Ok
        ).value
        assertEquals("GrowingRecordingFileLease(<redacted>)", lease.toString())
        val firstReader = (lease.open(0L) as RecordingFileResult.Ok).value
        assertTrue(firstReader.close() is RecordingFileResult.Ok)

        children.closeAndJoinSubscriptions()
        val secondGeneration = GatewayGeneration()
        metadata.bindCurrentRecording(secondGeneration, id = 5L, sizeBytes = 64L)
        children.bindGeneration(secondGeneration)

        assertFalse(lease.isCurrent)
        assertSame(
            RecordingFileFailure.CONNECTION_CHANGED,
            (lease.open(0L) as RecordingFileResult.Failed).failure,
        )
        assertEquals(listOf(firstGeneration), gateway.openedRecordingGenerations)
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `growing lease refuses a restored physical identity after incarnation drift`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindCurrentRecording(generation, id = 5L, sizeBytes = 64L)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(generation)
        val lease = (
            children.bindGrowingRecording(RecordingId(5L)) as RecordingFileResult.Ok
        ).value
        val firstReader = (lease.open(0L) as RecordingFileResult.Ok).value
        assertTrue(firstReader.close() is RecordingFileResult.Ok)

        metadata.updateCurrentRecording(generation, id = 5L, path = "/replacement.ts")
        metadata.updateCurrentRecording(generation, id = 5L, path = "/recording.ts")

        assertFalse(lease.isCurrent)
        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (lease.open(0L) as RecordingFileResult.Failed).failure,
        )
        assertEquals(1, gateway.openedRecordingGenerations.size)
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `growing lease refuses a smaller handle across reader reopens`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindCurrentRecording(generation, id = 5L, sizeBytes = 64L)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(generation)
        val lease = (
            children.bindGrowingRecording(RecordingId(5L)) as RecordingFileResult.Ok
        ).value
        val firstReader = (lease.open(0L) as RecordingFileResult.Ok).value
        assertTrue(firstReader.close() is RecordingFileResult.Ok)
        gateway.recordingOpenResult = GatewayResult.Ok(
            GatewayRecordingFile(handleId = 8L, sizeBytes = 32L, protocolVersion = 27),
        )

        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (lease.open(0L) as RecordingFileResult.Failed).failure,
        )
        assertFalse(lease.isCurrent)
        assertEquals(2, gateway.openedRecordingGenerations.size)
        assertEquals(2, gateway.closedRecordingGenerations.size)
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `growing recording open requires fresh current DVR metadata`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(generation)

        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (
                children.openGrowingRecording(RecordingId(5L), position = 0L)
                    as RecordingFileResult.Failed
            ).failure,
        )
        assertTrue(gateway.openedRecordingGenerations.isEmpty())
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `failed growing setup closes its generation-bound handle`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindCurrentRecording(generation, id = 5L, sizeBytes = 64L)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(generation)

        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (
                children.openGrowingRecording(RecordingId(5L), position = 65L)
                    as RecordingFileResult.Failed
            ).failure,
        )
        assertEquals(listOf(generation), gateway.openedRecordingGenerations)
        assertTrue(gateway.seekedRecordingGenerations.isEmpty())
        assertEquals(listOf(generation), gateway.closedRecordingGenerations)
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `transient identity drift during growing open closes the handle`() = runTest {
        val gateway = SubscriptionGateway()
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindCurrentRecording(generation, id = 5L, sizeBytes = 64L)
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        children.bindGeneration(generation)
        gateway.beforeRecordingOpenResult = {
            metadata.updateCurrentRecording(generation, id = 5L, path = "/replacement.ts")
            metadata.updateCurrentRecording(generation, id = 5L, path = "/recording.ts")
        }

        assertSame(
            RecordingFileFailure.FILE_UNAVAILABLE,
            (
                children.openGrowingRecording(RecordingId(5L), position = 0L)
                    as RecordingFileResult.Failed
            ).failure,
        )
        assertEquals(listOf(generation), gateway.openedRecordingGenerations)
        assertEquals(listOf(generation), gateway.closedRecordingGenerations)
        children.closeAndJoinSubscriptions()
    }

    @Test
    fun `artwork loads bind one generation and publish only safe typed results`() = runTest {
        val gateway = SubscriptionGateway()
        val children = PlaybackSessionChildren(
            gateway,
            PhaseOneSessionMetadata(),
            StandardTestDispatcher(testScheduler),
        )
        val artworkId = ArtworkId(17)

        assertSame(
            ArtworkFailure.CONNECTION_CHANGED,
            (children.loadArtwork(artworkId) as ArtworkLoadResult.Unavailable).failure,
        )

        val generation = GatewayGeneration()
        children.bindGeneration(generation)
        val source = byteArrayOf(1, 2, 3)
        gateway.artworkLoadResult = GatewayResult.Ok(source)
        val available = children.loadArtwork(artworkId) as ArtworkLoadResult.Available

        assertSame(generation, gateway.loadedArtworkGenerations.single())
        assertEquals(artworkId, gateway.loadedArtworkIds.single())
        assertEquals("ArtworkId(<redacted>)", artworkId.toString())
        assertEquals(3, available.content.sizeBytes)
        assertEquals(listOf<Byte>(1, 2, 3), available.content.openStream().readBytes().toList())
        assertEquals("ArtworkContent(<redacted>)", available.content.toString())
        assertEquals("ArtworkLoadResult.Available(<redacted>)", available.toString())

        listOf(
            GatewayResult.ServerRejected to ArtworkFailure.FILE_UNAVAILABLE,
            GatewayResult.AccessDenied to ArtworkFailure.ACCESS_DENIED,
            GatewayResult.ConnectionLimit to ArtworkFailure.CONNECTION_LIMIT,
            GatewayResult.Timeout to ArtworkFailure.TIMEOUT,
            GatewayResult.TransportUnavailable to ArtworkFailure.CONNECTION_CHANGED,
            GatewayResult.NotSupported to ArtworkFailure.NOT_SUPPORTED,
        ).forEach { (sourceResult, expected) ->
            gateway.artworkLoadResult = sourceResult
            val unavailable = children.loadArtwork(artworkId) as ArtworkLoadResult.Unavailable
            assertSame(expected, unavailable.failure)
            assertEquals(
                "ArtworkLoadResult.Unavailable(failure=$expected)",
                unavailable.toString(),
            )
        }

        children.closeAndJoinSubscriptions()
        assertSame(
            ArtworkFailure.CONNECTION_CHANGED,
            (children.loadArtwork(artworkId) as ArtworkLoadResult.Unavailable).failure,
        )
    }
}

private class SubscriptionGateway : ProtocolGateway {
    private val lock = Any()
    private val live = java.util.Collections.newSetFromMap(
        IdentityHashMap<GatewayGeneration, Boolean>(),
    )
    private val streams = IdentityHashMap<GatewayGeneration, Channel<SubscriptionEvent>>()

    internal val collectedGenerations = ArrayList<GatewayGeneration>()
    internal val collectedIds = ArrayList<SubscriptionId>()
    internal val unsubscribedGenerations = ArrayList<GatewayGeneration>()
    internal val unsubscribedIds = ArrayList<SubscriptionId>()
    internal val requestedTimeshiftPeriods = ArrayList<Duration>()
    internal val skippedTargets = ArrayList<SubscriptionSeekTarget>()
    internal val nearLiveGenerations = ArrayList<GatewayGeneration>()
    internal val nearLiveIds = ArrayList<SubscriptionId>()
    internal val nearLiveStatuses = ArrayList<SubscriptionEvent.Timeshift>()
    internal val nearLiveMargins = ArrayList<Long>()
    internal val openedRecordingGenerations = ArrayList<GatewayGeneration>()
    internal val openedRecordingIds = ArrayList<DvrEntryId>()
    internal val seekedRecordingGenerations = ArrayList<GatewayGeneration>()
    internal val seekedRecordingPositions = ArrayList<Long>()
    internal val readRecordingGenerations = ArrayList<GatewayGeneration>()
    internal val closedRecordingGenerations = ArrayList<GatewayGeneration>()
    internal val loadedArtworkGenerations = ArrayList<GatewayGeneration>()
    internal val loadedArtworkIds = ArrayList<ArtworkId>()
    internal var recordingOpenResult: GatewayResult<GatewayRecordingFile> =
        GatewayResult.Ok(GatewayRecordingFile(handleId = 7L, sizeBytes = 64L, protocolVersion = 27))
    internal var beforeRecordingOpenResult: (() -> Unit)? = null
    internal var artworkLoadResult: GatewayResult<ByteArray> = GatewayResult.NotSupported
    internal var grantedTimeshiftSeconds: Long? = null
    internal var nearLiveAction: suspend () -> SubscriptionOperationResult<Unit> = {
        SubscriptionOperationResult.Ok(Unit)
    }
    internal var unsubscribeCount: Int = 0
        private set

    override val connectionState = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    override val metadata: Flow<MetadataEvent> = emptyFlow()
    override val connectionFailures: Flow<GatewayConnectionFailureEvent> = emptyFlow()

    override suspend fun connect(server: ServerConfiguration): GatewayConnectResult =
        error("Connection is not used by this test")

    override suspend fun disconnect() = Unit

    override suspend fun shutdown() = Unit

    override fun <T> commitIfLive(
        generation: GatewayGeneration,
        block: () -> T,
    ): T? = synchronized(lock) {
        if (live.add(generation) || generation in live) block() else null
    }

    override suspend fun enableInitialMetadata(generation: GatewayGeneration): GatewayResult<Unit> =
        GatewayResult.Ok(Unit)

    override suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>> = GatewayResult.Ok(emptyList())

    override suspend fun getDvrConfigs(
        generation: GatewayGeneration,
    ): GatewayResult<List<DvrConfiguration>> = GatewayResult.Ok(emptyList())

    override suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace> = GatewayResult.Ok(DvrDiskSpace(0, 0, 0))

    override suspend fun getDvrCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<List<DvrCutpoint>> = GatewayResult.NotSupported

    override suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId> = GatewayResult.NotSupported

    override suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun stopDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun cancelDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun deleteDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): GatewayResult<AutorecRuleId> = GatewayResult.NotSupported

    override suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): GatewayResult<TimerecRuleId> = GatewayResult.NotSupported

    override suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun reportDvrProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override suspend fun loadArtwork(
        generation: GatewayGeneration,
        id: ArtworkId,
    ): GatewayResult<ByteArray> {
        loadedArtworkGenerations += generation
        loadedArtworkIds += id
        return artworkLoadResult
    }

    override suspend fun openRecordingFile(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<GatewayRecordingFile> {
        openedRecordingGenerations += generation
        openedRecordingIds += id
        beforeRecordingOpenResult?.invoke()
        return recordingOpenResult
    }

    override suspend fun seekRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
    ): GatewayResult<Long> {
        seekedRecordingGenerations += generation
        seekedRecordingPositions += position
        return GatewayResult.Ok(position)
    }

    override suspend fun readRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): GatewayResult<Int> {
        readRecordingGenerations += generation
        destination.fill(1, destinationOffset, destinationOffset + length)
        return GatewayResult.Ok(length)
    }

    override suspend fun closeRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
    ): GatewayResult<Unit> {
        closedRecordingGenerations += generation
        return GatewayResult.Ok(Unit)
    }

    override fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent> = flow {
        val stream = synchronized(lock) {
            live += generation
            collectedGenerations += generation
            collectedIds += id
            Channel<SubscriptionEvent>(Channel.UNLIMITED).also { streams[generation] = it }
        }
        for (event in stream) emit(event)
    }

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> {
        synchronized(lock) { requestedTimeshiftPeriods += timeshiftPeriod }
        return SubscriptionOperationResult.Ok(
            SubscriptionConfirmation(null, null, null, grantedTimeshiftSeconds),
        )
    }

    override suspend fun skipSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> {
        synchronized(lock) { skippedTargets += target }
        return SubscriptionOperationResult.Ok(Unit)
    }

    override suspend fun skipSubscriptionNearLive(
        generation: GatewayGeneration,
        id: SubscriptionId,
        status: SubscriptionEvent.Timeshift,
        marginSeconds: Long,
    ): SubscriptionOperationResult<Unit> {
        synchronized(lock) {
            nearLiveGenerations += generation
            nearLiveIds += id
            nearLiveStatuses += status
            nearLiveMargins += marginSeconds
        }
        return nearLiveAction()
    }

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit> {
        synchronized(lock) {
            unsubscribeCount += 1
            unsubscribedGenerations += generation
            unsubscribedIds += id
            streams[generation]
        }?.close()
        return SubscriptionOperationResult.Ok(Unit)
    }

    internal suspend fun emitStarted(generation: GatewayGeneration) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(
            SubscriptionEvent.Started(
                streams = listOf(stream()),
                codecMetadata = null,
                condition = SubscriptionCondition.NO_DETAIL,
            ),
        )
    }


    internal suspend fun emitSkipped(generation: GatewayGeneration, outcome: SkipOutcome) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(
            SubscriptionEvent.Skipped(
                absolute = null,
                outcome = outcome,
                time = null,
                sizeBytes = null,
            ),
        )
    }

    internal suspend fun emitTimeshift(
        generation: GatewayGeneration,
        start: Long,
        end: Long,
    ) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(
            SubscriptionEvent.Timeshift(
                full = 1,
                shift = start - end,
                start = start,
                end = end,
                speed = 100,
            ),
        )
    }

    internal suspend fun emitTerminated(
        generation: GatewayGeneration,
        reason: SubscriptionTermination,
    ) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(SubscriptionEvent.Terminated(reason))
    }

    internal fun complete(generation: GatewayGeneration) {
        synchronized(lock) { streams[generation] }?.close()
    }

    internal suspend fun emitPacket(generation: GatewayGeneration) {
        val stream = synchronized(lock) { streams.getValue(generation) }
        stream.send(
            SubscriptionEvent.Packet(
                frameType = at.bernhardberger.tvheadend.sdk.playback.MuxFrameType.I,
                streamIndex = StreamIndex(0L),
                decodingTimeUs = 1L,
                presentationTimeUs = 2L,
                durationUs = 3L,
                payload = object : at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary {
                    override val size: Int = 0

                    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int = 0
                },
            ),
        )
    }
}

private fun PhaseOneSessionMetadata.bindKnownChannels(
    generation: GatewayGeneration,
    vararg channelIds: Long,
) {
    bindGeneration(generation)
    channelIds.forEach { channelId ->
        acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                GatewayChannelMetadata(
                    id = ChannelId(channelId),
                    name = null,
                    uuid = null,
                    number = null,
                    numberMinor = null,
                    icon = null,
                    currentEventId = null,
                    nextEventId = null,
                    services = null,
                    tagIds = null,
                ),
            ),
        )
    }
    acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
}

private fun PhaseOneSessionMetadata.bindCurrentRecording(
    generation: GatewayGeneration,
    id: Long,
    sizeBytes: Long,
) {
    bindGeneration(generation)
    acceptMetadata(
        MetadataEvent.DvrEntryAdded(
            generation,
            GatewayDvrEntry(
                id = DvrEntryId(id),
                uuid = "recording-uuid",
                files = listOf(
                    GatewayDvrRecordingFile(
                        fileId = 11L,
                        path = "/recording.ts",
                        start = Instant.fromEpochSeconds(1L),
                        stop = null,
                        sizeBytes = sizeBytes,
                    ),
                ),
                path = "/recording.ts",
                state = DvrEntryState.RECORDING,
                dataSizeBytes = sizeBytes,
            ),
        ),
    )
    acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
}

private fun PhaseOneSessionMetadata.updateCurrentRecording(
    generation: GatewayGeneration,
    id: Long,
    path: String,
) {
    acceptMetadata(
        MetadataEvent.DvrEntryUpdated(
            generation,
            GatewayDvrEntry(
                id = DvrEntryId(id),
                uuid = "recording-uuid",
                files = listOf(
                    GatewayDvrRecordingFile(
                        fileId = 11L,
                        path = path,
                        start = Instant.fromEpochSeconds(1L),
                        stop = null,
                        sizeBytes = 64L,
                    ),
                ),
                path = path,
                state = DvrEntryState.RECORDING,
                dataSizeBytes = 64L,
            ),
            GatewayDvrUpdateProvenance.FULL,
        ),
    )
}

private fun stream(): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(0L),
    type = SubscriptionStreamType.H264,
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
    rate = null,
    rdsUecp = null,
    codecMetadata = null,
)
