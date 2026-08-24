@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.DvrSnapshot
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageRequestResult
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.core.SessionOperationFailure
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ChannelId as SdkChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnection
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class ConnectionOwnerTest {
    @Test
    fun `construction is idle and readiness waits for the SDK metadata fence`() = runTest {
        val order = mutableListOf<String>()
        val gateway = FakeProtocolGateway(order)
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, streaming = true, dvrAccess = false)
        val metadata = PhaseOneSessionMetadata()
        val owner = owner(
            gateway = gateway,
            children = RecordingSessionChildren(order),
            metadata = metadata,
        )

        assertEquals(SessionState.Disconnected, owner.state.value)
        assertSame(metadata, owner.channelRepository)
        assertSame(metadata.epgRepository, owner.epgRepository)
        assertSame(metadata.dvrRepository, owner.dvrRepository)
        assertEquals(ChannelRepositoryState.Empty, metadata.channelsAndTags.value)
        assertEquals(EpgRepositoryState.Empty, owner.epgRepository.state.value)
        assertEquals(DvrRepositoryState.Empty, owner.dvrRepository.state.value)
        assertTrue(order.isEmpty(), "Construction must not launch lifecycle work")

        assertEquals(SessionCommandResult.STARTED, owner.connect(ServerProfile(" server ")))
        runCurrent()

        assertEquals(SessionState.Synchronizing, owner.state.value)
        assertEquals(
            listOf(
                "failures.collect",
                "connect",
                "generation.bind",
                "metadata.collect",
                "admission.start",
                "enable",
            ),
            order,
        )
        assertTrue("admission.start" in order, "Live admission did not start before metadata sync")
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Synchronizing)
        assertTrue(owner.epgRepository.state.value is EpgRepositoryState.Synchronizing)
        assertTrue(owner.dvrRepository.state.value is DvrRepositoryState.Synchronizing)

        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(generation, channelMetadata(id = 1)),
        )
        gateway.emitMetadata(
            MetadataEvent.DvrEntryAdded(generation, dvrMetadata(id = 8)),
        )
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Synchronizing)

        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()

        assertEquals(
            SessionState.Ready(
                ServerCapabilities.create(
                    streaming = CapabilityAccess.ALLOWED,
                    dvrWrite = CapabilityAccess.DENIED,
                ),
            ),
            owner.state.value,
        )
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Current)
        assertTrue(owner.epgRepository.state.value is EpgRepositoryState.Current)
        assertTrue(owner.dvrRepository.state.value is DvrRepositoryState.Current)
        assertTrue(
            owner.dvrRepository.configurationsState.value is DvrConfigurationsState.Synchronizing,
        )
        assertTrue(owner.dvrRepository.diskSpaceState.value is DvrDiskSpaceState.Synchronizing)
        assertEquals(listOf(1L), owner.channelRepository.channels.value.map { it.id.value })
        assertEquals(listOf(8L), owner.dvrRepository.entries.value.map { it.id.value })
        assertEquals(
            listOf(
                "failures.collect",
                "connect",
                "generation.bind",
                "metadata.collect",
                "admission.start",
                "enable",
                "background.prepare",
                "background.start",
            ),
            order,
        )
        owner.shutdown()
    }

    @Test
    fun `EPG requester is bound before ready and network work starts afterward`() = runTest {
        val order = mutableListOf<String>()
        val gateway = FakeProtocolGateway(order)
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway = gateway,
            metadata = metadata,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(0)
            },
        )
        lateinit var owner: ConnectionOwner
        var requestResult: EpgCoverageRequestResult? = null
        gateway.beforeReadyCommit = {
            assertEquals(SessionState.Synchronizing, owner.state.value)
            assertFalse("epg.query" in order, "Background network work started before Ready")
            requestResult = metadata.epgRepository.requestCoverage(
                SdkChannelId(1),
                Instant.fromEpochSeconds(0) + 8.hours,
            )
        }
        owner = owner(gateway, children, metadata)

        try {
            owner.connect(ServerProfile("server"))
            runCurrent()
            gateway.emitMetadata(MetadataEvent.ChannelAdded(generation, channelMetadata(1)))
            gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
            runCurrent()

            assertEquals(EpgCoverageRequestResult.ACCEPTED, requestResult)
            assertTrue(owner.state.value is SessionState.Ready)
            assertTrue("epg.query" in order, "Background network work did not start after Ready")
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `readiness does not wait for independently supervised configuration and disk refresh`() = runTest {
        val holdConfigs = CompletableDeferred<Unit>()
        val holdDisk = CompletableDeferred<Unit>()
        val configFinished = CompletableDeferred<Unit>()
        val diskFinished = CompletableDeferred<Unit>()
        val diskStarted = CompletableDeferred<Unit>()
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, streaming = true, dvrAccess = true)
        gateway.dvrConfigsBehavior = {
            try {
                holdConfigs.await()
                GatewayResult.Ok(emptyList())
            } finally {
                configFinished.complete(Unit)
            }
        }
        gateway.diskSpaceBehavior = {
            diskStarted.complete(Unit)
            try {
                holdDisk.await()
                GatewayResult.Ok(DvrDiskSpace(1, 2, 3))
            } finally {
                diskFinished.complete(Unit)
            }
        }
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        val owner = owner(gateway, children, metadata)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()

        assertTrue(owner.state.value is SessionState.Ready)
        assertTrue(
            owner.dvrRepository.configurationsState.value is DvrConfigurationsState.Synchronizing,
        )
        assertTrue(diskStarted.isCompleted, "Disk enrichment was serialized behind configuration")
        assertTrue(owner.dvrRepository.diskSpaceState.value is DvrDiskSpaceState.Synchronizing)
        owner.shutdown()
        assertTrue(configFinished.isCompleted, "Configuration enrichment was not joined")
        assertTrue(diskFinished.isCompleted, "Disk enrichment was not joined")
    }

    @Test
    fun `configuration access denial latches denied writes without blocking ready`() = runTest {
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, streaming = true, dvrAccess = true)
        gateway.dvrConfigsBehavior = { GatewayResult.AccessDenied }
        gateway.diskSpaceBehavior = { GatewayResult.Timeout }
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        val owner = owner(gateway, children, metadata)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()

        assertEquals(
            SessionState.Ready(
                ServerCapabilities.create(
                    streaming = CapabilityAccess.ALLOWED,
                    dvrWrite = CapabilityAccess.DENIED,
                ),
            ),
            owner.state.value,
        )
        assertEquals(DvrConfigurationsState.Denied, owner.dvrRepository.configurationsState.value)
        assertEquals(DvrDiskSpaceState.Unknown, owner.dvrRepository.diskSpaceState.value)
        owner.shutdown()
    }

    @Test
    fun `mutation admission and proof republish capabilities for only the ready generation`() = runTest {
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, dvrAccess = null)
        gateway.dvrConfigsBehavior = { GatewayResult.Timeout }
        lateinit var owner: ConnectionOwner
        lateinit var metadata: PhaseOneSessionMetadata
        val coordinator = DvrMutationCoordinator(
            gateway = gateway,
            isSessionReady = { commandGeneration ->
                owner.isDvrMutationReady(commandGeneration)
            },
            onDvrAccessProof = { proofGeneration, allowed ->
                owner.applyDvrAccessProof(proofGeneration, allowed)
            },
        )
        metadata = PhaseOneSessionMetadata(
            mutationCommands = coordinator,
            onDvrMetadataAccepted = coordinator::acceptMetadata,
        )
        owner = owner(
            gateway = gateway,
            metadata = metadata,
            dvrMutations = coordinator,
        )
        val request = DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))

        assertSame(DvrMutationResult.NotReady, owner.dvrRepository.scheduleEntry(request))
        owner.connect(ServerProfile("server"))
        runCurrent()
        assertSame(DvrMutationResult.NotReady, owner.dvrRepository.scheduleEntry(request))
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()
        assertEquals(
            CapabilityAccess.UNKNOWN,
            (owner.state.value as SessionState.Ready).capabilities.dvrWrite,
        )

        gateway.scheduleDvrBehavior = { _, _ -> GatewayResult.AccessDenied }
        assertSame(DvrMutationResult.AccessDenied, owner.dvrRepository.scheduleEntry(request))
        assertEquals(
            CapabilityAccess.DENIED,
            (owner.state.value as SessionState.Ready).capabilities.dvrWrite,
        )

        gateway.scheduleDvrBehavior = { commandGeneration, _ ->
            gateway.emitMetadata(
                MetadataEvent.DvrEntryAdded(commandGeneration, dvrMetadata(7)),
            )
            GatewayResult.Ok(DvrEntryId(7))
        }
        val confirmed = owner.dvrRepository.scheduleEntry(request)
        assertTrue(confirmed is DvrMutationResult.Confirmed)
        assertEquals(listOf(7L), owner.dvrRepository.entries.value.map { entry -> entry.id.value })
        assertEquals(
            CapabilityAccess.ALLOWED,
            (owner.state.value as SessionState.Ready).capabilities.dvrWrite,
        )

        owner.disconnect()
        assertSame(DvrMutationResult.NotReady, owner.dvrRepository.scheduleEntry(request))
        owner.shutdown()
    }

    @Test
    fun `later progress proof cannot be overwritten by an earlier mutation publication`() = runTest {
        val earlierProofPrepared = CompletableDeferred<Unit>()
        val releaseEarlierProof = CompletableDeferred<Unit>()
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, dvrAccess = null)
        gateway.dvrConfigsBehavior = { GatewayResult.Timeout }
        lateinit var owner: ConnectionOwner
        lateinit var metadata: PhaseOneSessionMetadata
        val onProof: suspend (GatewayGeneration, Boolean) -> Unit = { proofGeneration, allowed ->
            owner.applyDvrAccessProof(proofGeneration, allowed)
        }
        val mutations = DvrMutationCoordinator(
            gateway = gateway,
            isSessionReady = { commandGeneration -> owner.isDvrMutationReady(commandGeneration) },
            onDvrAccessProof = onProof,
        )
        val progress = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = { commandGeneration -> owner.isDvrMutationReady(commandGeneration) },
            onDvrAccessProof = onProof,
            onProgressNotSupported = { commandGeneration ->
                owner.applyRecordingProgressNotSupported(commandGeneration)
            },
        )
        metadata = PhaseOneSessionMetadata(
            mutationCommands = mutations,
            progressCommands = progress,
            onDvrMetadataAccepted = mutations::acceptMetadata,
        )
        owner = owner(
            gateway = gateway,
            metadata = metadata,
            dvrMutations = mutations,
            dvrProgress = progress,
            beforeDvrCapabilityPublication = { allowed ->
                if (allowed) {
                    earlierProofPrepared.complete(Unit)
                    releaseEarlierProof.await()
                }
            },
        )
        val request = DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))
        val report = DvrPlaybackProgress.checkpoint(30.seconds)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()
        assertEquals(
            CapabilityAccess.UNKNOWN,
            (owner.state.value as SessionState.Ready).capabilities.dvrWrite,
        )

        gateway.scheduleDvrBehavior = { commandGeneration, _ ->
            gateway.emitMetadata(MetadataEvent.DvrEntryAdded(commandGeneration, dvrMetadata(7)))
            GatewayResult.Ok(DvrEntryId(7))
        }
        gateway.reportDvrProgressBehavior = { _, _, _ -> GatewayResult.AccessDenied }
        val earlierMutation = async { owner.dvrRepository.scheduleEntry(request) }
        earlierProofPrepared.await()

        assertSame(
            DvrProgressResult.AccessDenied,
            owner.dvrRepository.reportProgress(DvrEntryId(7), report),
        )
        assertEquals(
            CapabilityAccess.DENIED,
            (owner.state.value as SessionState.Ready).capabilities.dvrWrite,
        )

        releaseEarlierProof.complete(Unit)
        runCurrent()
        assertTrue(earlierMutation.await() is DvrMutationResult.Confirmed)
        assertEquals(
            CapabilityAccess.DENIED,
            (owner.state.value as SessionState.Ready).capabilities.dvrWrite,
        )
        owner.shutdown()
    }

    @Test
    fun `progress reports require ready v27 and latch not supported until reconnect`() = runTest {
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, protocolVersion = 26)
        lateinit var owner: ConnectionOwner
        lateinit var metadata: PhaseOneSessionMetadata
        val onProof: suspend (GatewayGeneration, Boolean) -> Unit = { proofGeneration, allowed ->
            owner.applyDvrAccessProof(proofGeneration, allowed)
        }
        val mutations = DvrMutationCoordinator(
            gateway = gateway,
            isSessionReady = { commandGeneration -> owner.isDvrMutationReady(commandGeneration) },
            onDvrAccessProof = onProof,
        )
        val progress = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = { commandGeneration -> owner.isDvrMutationReady(commandGeneration) },
            onDvrAccessProof = onProof,
            onProgressNotSupported = { commandGeneration ->
                owner.applyRecordingProgressNotSupported(commandGeneration)
            },
        )
        metadata = PhaseOneSessionMetadata(
            mutationCommands = mutations,
            progressCommands = progress,
            onDvrMetadataAccepted = mutations::acceptMetadata,
        )
        owner = owner(
            gateway = gateway,
            metadata = metadata,
            dvrMutations = mutations,
            dvrProgress = progress,
        )
        val report = DvrPlaybackProgress.checkpoint(30.seconds)

        assertSame(
            RecordingProgressCapability.UNKNOWN,
            owner.recordingProgressCapability.value,
        )
        assertSame(
            DvrProgressResult.NotReady,
            owner.dvrRepository.reportProgress(DvrEntryId(7), report),
        )
        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()
        assertSame(
            RecordingProgressCapability.UNSUPPORTED,
            owner.recordingProgressCapability.value,
        )
        assertSame(
            DvrProgressResult.NotSupported,
            owner.dvrRepository.reportProgress(DvrEntryId(7), report),
        )
        assertEquals(0, gateway.progressReportCount)

        owner.disconnect()
        runCurrent()
        assertSame(
            RecordingProgressCapability.UNKNOWN,
            owner.recordingProgressCapability.value,
        )
        val next = GatewayGeneration()
        gateway.connectResults += connected(next, protocolVersion = 27)
        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(next))
        runCurrent()
        assertSame(
            RecordingProgressCapability.SUPPORTED,
            owner.recordingProgressCapability.value,
        )
        gateway.reportDvrProgressBehavior = { _, _, _ -> GatewayResult.NotSupported }
        assertSame(
            DvrProgressResult.NotSupported,
            owner.dvrRepository.reportProgress(DvrEntryId(7), report),
        )
        assertEquals(1, gateway.progressReportCount)
        assertSame(
            RecordingProgressCapability.UNSUPPORTED,
            owner.recordingProgressCapability.value,
        )
        assertSame(
            DvrProgressResult.NotSupported,
            owner.dvrRepository.reportProgress(DvrEntryId(7), report),
        )
        assertEquals(1, gateway.progressReportCount)

        owner.disconnect()
        runCurrent()
        assertSame(
            RecordingProgressCapability.UNKNOWN,
            owner.recordingProgressCapability.value,
        )
        val restored = GatewayGeneration()
        gateway.connectResults += connected(restored, protocolVersion = 27)
        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(restored))
        runCurrent()
        assertSame(
            RecordingProgressCapability.SUPPORTED,
            owner.recordingProgressCapability.value,
        )
        gateway.reportDvrProgressBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
        assertSame(
            DvrProgressResult.Accepted,
            owner.dvrRepository.reportProgress(DvrEntryId(7), report),
        )
        assertEquals(2, gateway.progressReportCount)
        assertEquals(
            DvrRepositoryState.Current(DvrSnapshot.create()),
            owner.dvrRepository.state.value,
        )
        owner.shutdown()
    }

    @Test
    fun `unknown negotiated version keeps recording progress capability fail closed`() = runTest {
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation, protocolVersion = null)
        val owner = owner(gateway)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()

        assertTrue(owner.state.value is SessionState.Ready)
        assertSame(
            RecordingProgressCapability.UNKNOWN,
            owner.recordingProgressCapability.value,
        )
        owner.shutdown()
    }

    @Test
    fun `hundreds of channels start EPG enrichment without delaying ready`() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        gateway.queryBehavior = { queryGeneration, _, _ ->
            assertSame(generation, queryGeneration)
            queryStarted.complete(Unit)
            releaseQuery.await()
            GatewayResult.Ok(emptyList())
        }
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway = gateway,
            metadata = metadata,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(0)
            },
        )
        val owner = owner(gateway, children, metadata)

        try {
            owner.connect(ServerProfile("server"))
            runCurrent()
            (1L..500L).forEach { channelId ->
                gateway.emitMetadata(
                    MetadataEvent.ChannelAdded(generation, channelMetadata(channelId)),
                )
            }
            gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
            runCurrent()

            assertTrue(queryStarted.isCompleted, "EPG background query did not start")
            assertTrue(owner.state.value is SessionState.Ready)

            releaseQuery.complete(Unit)
            advanceTimeBy(250)
            runCurrent()

            assertTrue(owner.state.value is SessionState.Ready)
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `persistent EPG query rejection still reaches ready`() = runTest {
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        gateway.queryBehavior = { _, _, _ -> GatewayResult.NotSupported }
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway = gateway,
            metadata = metadata,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(0)
            },
        )
        val owner = owner(gateway, children, metadata)

        try {
            owner.connect(ServerProfile("server"))
            runCurrent()
            gateway.emitMetadata(MetadataEvent.ChannelAdded(generation, channelMetadata(1)))
            gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
            runCurrent()
            advanceTimeBy(250)
            runCurrent()

            assertTrue(owner.state.value is SessionState.Ready)
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `independent EPG query cancellation keeps enrichment live and ready`() = runTest {
        val order = mutableListOf<String>()
        val gateway = FakeProtocolGateway(order)
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        var queryAttempts = 0
        gateway.queryBehavior = { _, _, _ ->
            queryAttempts += 1
            if (queryAttempts == 1) {
                throw CancellationException("fixed independent cancellation")
            }
            GatewayResult.Ok(emptyList())
        }
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway = gateway,
            metadata = metadata,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochSeconds(0)
            },
        )
        val owner = owner(gateway, children, metadata)

        try {
            owner.connect(ServerProfile("server"))
            runCurrent()
            gateway.emitMetadata(MetadataEvent.ChannelAdded(generation, channelMetadata(1)))
            gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
            runCurrent()
            advanceTimeBy(250)
            runCurrent()

            assertTrue(owner.state.value is SessionState.Ready)
            assertFalse("disconnect" in order, "Cancelled background EPG work tore down transport")
            assertTrue(metadata.epgRepository.state.value is EpgRepositoryState.Current)
            assertEquals(
                EpgCoverageRequestResult.ACCEPTED,
                metadata.epgRepository.requestCoverage(
                    SdkChannelId(1),
                    Instant.fromEpochSeconds(0) + 8.hours,
                ),
            )
            advanceTimeBy(10.minutes)
            runCurrent()
            assertEquals(2, queryAttempts)
            assertTrue(owner.state.value is SessionState.Ready)
        } finally {
            owner.shutdown()
        }
    }

    @Test
    fun `reconnect performs a fresh sync and rejects stale generation signals`() = runTest {
        val gateway = FakeProtocolGateway()
        val firstGeneration = GatewayGeneration()
        val secondGeneration = GatewayGeneration()
        gateway.connectResults += connected(firstGeneration)
        gateway.connectResults += connected(secondGeneration)
        val metadata = PhaseOneSessionMetadata()
        val owner = owner(gateway, metadata = metadata)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(firstGeneration, channelMetadata(id = 1)),
        )
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(firstGeneration))
        runCurrent()
        assertTrue(owner.state.value is SessionState.Ready)
        val firstSnapshot =
            (metadata.channelsAndTags.value as ChannelRepositoryState.Current).catalog

        gateway.emitFailure(
            GatewayConnectionFailureEvent(
                failure = GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
                generation = firstGeneration,
            ),
        )
        runCurrent()
        assertEquals(
            SessionState.Unavailable(SessionFailure.TransportUnavailable),
            owner.state.value,
        )
        assertSame(
            firstSnapshot,
            (metadata.channelsAndTags.value as ChannelRepositoryState.Stale).catalog,
        )

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(SessionState.Synchronizing, owner.state.value)
        assertEquals(2, gateway.connectCalls)
        assertSame(
            firstSnapshot,
            (metadata.channelsAndTags.value as ChannelRepositoryState.Synchronizing).staleCatalog,
        )

        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(firstGeneration, channelMetadata(id = 99)),
        )
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(firstGeneration))
        gateway.emitFailure(
            GatewayConnectionFailureEvent(
                failure = GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
                generation = firstGeneration,
            ),
        )
        runCurrent()
        assertEquals(SessionState.Synchronizing, owner.state.value)

        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(secondGeneration, channelMetadata(id = 2)),
        )
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(secondGeneration))
        runCurrent()
        assertTrue(owner.state.value is SessionState.Ready)
        assertEquals(
            listOf(2L),
            (metadata.channelsAndTags.value as ChannelRepositoryState.Current)
                .catalog.channels.map { it.id.value },
        )
        owner.shutdown()
    }

    @Test
    fun `failure after early admission joins subscriptions before replacement bind`() = runTest {
        val gateway = FakeProtocolGateway()
        val firstGeneration = GatewayGeneration()
        val secondGeneration = GatewayGeneration()
        gateway.connectResults += connected(firstGeneration)
        gateway.connectResults += connected(secondGeneration)
        val metadata = PhaseOneSessionMetadata()
        val children = PlaybackSessionChildren(
            gateway,
            metadata,
            StandardTestDispatcher(testScheduler),
        )
        val owner = owner(gateway, children, metadata)

        owner.connect(ServerProfile("server"))
        runCurrent()
        assertEquals(SessionState.Synchronizing, owner.state.value)

        gateway.emitFailure(
            GatewayConnectionFailureEvent(
                failure = GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
                generation = firstGeneration,
            ),
        )
        runCurrent()
        assertEquals(
            SessionState.Unavailable(SessionFailure.TransportUnavailable),
            owner.state.value,
        )

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, gateway.connectCalls)
        assertEquals(SessionState.Synchronizing, owner.state.value)
        owner.shutdown()
    }

    @Test
    fun `ready publication is committed atomically only while generation is live`() = runTest {
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        gateway.invalidateOnReadyCommit = true
        val metadata = PhaseOneSessionMetadata()
        val owner = owner(gateway, metadata = metadata)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()

        assertEquals(
            SessionState.Unavailable(SessionFailure.TransportUnavailable),
            owner.state.value,
        )
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Stale)
        owner.shutdown()
    }

    @Test
    fun `shutdown excludes ready while a commit is waiting at the state barrier`() = runTest {
        val readyCommitReached = CompletableDeferred<Unit>()
        val releaseReadyCommit = CountDownLatch(1)
        val gateway = FakeProtocolGateway()
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        gateway.beforeReadyCommit = {
            readyCommitReached.complete(Unit)
            releaseReadyCommit.await()
        }
        val owner = owner(
            gateway = gateway,
            metadata = PhaseOneSessionMetadata(),
            defaultDispatcher = Dispatchers.Default,
        )

        try {
            owner.connect(ServerProfile("server"))
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    owner.state.first { state -> state is SessionState.Synchronizing }
                }
            }
            gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
            readyCommitReached.await()

            val shutdown = async { owner.shutdown() }
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    owner.state.first { state -> state is SessionState.Disconnected }
                }
            }
            releaseReadyCommit.countDown()
            shutdown.await()

            assertEquals(false, gateway.readyCommitResult)
            assertEquals(SessionState.Disconnected, owner.state.value)
        } finally {
            releaseReadyCommit.countDown()
            owner.shutdown()
        }
    }

    @Test
    fun `runtime loss marks the published catalog stale before cleanup can suspend`() = runTest {
        val order = mutableListOf<String>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val children = BlockingSessionChildren(order, cleanupStarted, releaseCleanup)
        val gateway = FakeProtocolGateway(order)
        val generation = GatewayGeneration()
        gateway.connectResults += connected(generation)
        val metadata = PhaseOneSessionMetadata()
        val owner = owner(gateway, children, metadata)

        owner.connect(ServerProfile("server"))
        runCurrent()
        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(generation, channelMetadata(id = 1)),
        )
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(generation))
        runCurrent()
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Current)

        gateway.emitFailure(
            GatewayConnectionFailureEvent(
                failure = GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
                generation = generation,
            ),
        )
        runCurrent()
        cleanupStarted.await()

        assertEquals(
            SessionState.Unavailable(SessionFailure.TransportUnavailable),
            owner.state.value,
        )
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Stale)

        releaseCleanup.complete(Unit)
        runCurrent()
        owner.shutdown()
    }

    @Test
    fun `transient failures use exact capped backoff and explicit retry interrupts delay`() = runTest {
        val gateway = FakeProtocolGateway().apply {
            defaultConnectResult = failed(GatewayConnectionFailure.SERVER_UNREACHABLE)
        }
        val owner = owner(gateway)

        owner.connect(ServerProfile("server"))
        runCurrent()
        assertEquals(1, gateway.connectCalls)
        assertEquals(SessionState.Unavailable(SessionFailure.ServerUnreachable), owner.state.value)

        listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L).forEachIndexed { index, delaySeconds ->
            advanceTimeBy(delaySeconds.seconds)
            runCurrent()
            assertEquals(index + 2, gateway.connectCalls)
        }

        assertEquals(SessionCommandResult.STARTED, owner.retry())
        runCurrent()
        assertEquals(9, gateway.connectCalls)
        owner.shutdown()
    }

    @Test
    fun `connection failure policies are complete and terminal failures require profile change`() = runTest {
        val cases = listOf(
            ConnectionFailureCase(
                GatewayConnectionFailure.AUTHENTICATION_REJECTED,
                SessionFailure.AuthenticationRejected,
                SessionCommandResult.RETRY_NOT_ALLOWED,
            ),
            ConnectionFailureCase(
                GatewayConnectionFailure.PERMISSION_DENIED,
                SessionFailure.PermissionDenied,
                SessionCommandResult.RETRY_NOT_ALLOWED,
            ),
            ConnectionFailureCase(
                GatewayConnectionFailure.SERVER_UNREACHABLE,
                SessionFailure.ServerUnreachable,
                SessionCommandResult.STARTED,
            ),
            ConnectionFailureCase(
                GatewayConnectionFailure.NETWORK_UNAVAILABLE,
                SessionFailure.NetworkUnavailable,
                SessionCommandResult.STARTED,
            ),
            ConnectionFailureCase(
                GatewayConnectionFailure.INCOMPATIBLE_SERVER,
                SessionFailure.IncompatibleServer,
                SessionCommandResult.RETRY_NOT_ALLOWED,
            ),
            ConnectionFailureCase(
                GatewayConnectionFailure.NO_CHANNELS,
                SessionFailure.NoChannels,
                SessionCommandResult.STARTED,
            ),
            ConnectionFailureCase(
                GatewayConnectionFailure.TRANSPORT_UNAVAILABLE,
                SessionFailure.TransportUnavailable,
                SessionCommandResult.STARTED,
            ),
        )

        cases.forEachIndexed { index, case ->
            val gateway = FakeProtocolGateway().apply {
                defaultConnectResult = failed(case.gatewayFailure)
            }
            val owner = owner(gateway)
            val profile = ServerProfile("server-$index")
            owner.connect(profile)
            runCurrent()

            assertEquals(SessionState.Unavailable(case.sessionFailure), owner.state.value)
            assertEquals(case.retryResult, owner.retry())
            if (case.retryResult == SessionCommandResult.STARTED) {
                runCurrent()
                assertEquals(2, gateway.connectCalls)
            } else {
                assertEquals(1, gateway.connectCalls)
                assertEquals(SessionCommandResult.NO_CHANGE, owner.connect(profile))
                assertEquals(
                    SessionCommandResult.STARTED,
                    owner.connect(ServerProfile("replacement-$index")),
                )
                runCurrent()
                assertEquals(2, gateway.connectCalls)
            }
            owner.shutdown()
        }
    }

    @Test
    fun `synchronization outcomes keep safe policy and payload-free failures`() = runTest {
        val cases = listOf(
            SynchronizationFailureCase(
                GatewayResult.ServerRejected,
                SessionOperationFailure.SERVER_REJECTED,
                SessionCommandResult.STARTED,
            ),
            SynchronizationFailureCase(
                GatewayResult.AccessDenied,
                SessionOperationFailure.ACCESS_DENIED,
                SessionCommandResult.RETRY_NOT_ALLOWED,
            ),
            SynchronizationFailureCase(
                GatewayResult.ConnectionLimit,
                SessionOperationFailure.CONNECTION_LIMIT,
                SessionCommandResult.STARTED,
            ),
            SynchronizationFailureCase(
                GatewayResult.Timeout,
                SessionOperationFailure.TIMEOUT,
                SessionCommandResult.STARTED,
            ),
            SynchronizationFailureCase(
                GatewayResult.TransportUnavailable,
                SessionOperationFailure.TRANSPORT_UNAVAILABLE,
                SessionCommandResult.STARTED,
            ),
            SynchronizationFailureCase(
                GatewayResult.NotSupported,
                SessionOperationFailure.NOT_SUPPORTED,
                SessionCommandResult.RETRY_NOT_ALLOWED,
            ),
        )

        cases.forEachIndexed { index, case ->
            val gateway = FakeProtocolGateway().apply {
                defaultConnectResult = connected(GatewayGeneration())
                enableResult = case.gatewayResult
            }
            val owner = owner(gateway)
            owner.connect(ServerProfile("server-$index"))
            runCurrent()

            assertEquals(
                SessionState.Unavailable(
                    SessionFailure.SynchronizationFailed(case.operationFailure),
                ),
                owner.state.value,
            )
            assertEquals(case.retryResult, owner.retry())
            owner.shutdown()
        }
    }

    @Test
    fun `profile replacement joins a suspended connect before starting its successor`() = runTest {
        val gateway = FakeProtocolGateway()
        val firstStarted = CompletableDeferred<Unit>()
        gateway.connectBehavior = {
            if (connectCalls == 1) {
                firstStarted.complete(Unit)
                awaitCancellation()
            }
            failed(GatewayConnectionFailure.NO_CHANNELS)
        }
        val owner = owner(gateway)

        owner.connect(ServerProfile("first"))
        runCurrent()
        firstStarted.await()
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals(SessionCommandResult.STARTED, owner.connect(ServerProfile("second")))
        }
        runCurrent()
        replacement.join()
        runCurrent()

        assertEquals(2, gateway.connectCalls)
        assertEquals(1, gateway.maximumConcurrentConnects)
        owner.shutdown()
    }

    @Test
    fun `profile replacement and explicit disconnect clear the prior server catalog`() = runTest {
        val gateway = FakeProtocolGateway()
        val firstGeneration = GatewayGeneration()
        val secondGeneration = GatewayGeneration()
        gateway.connectResults += connected(firstGeneration)
        gateway.connectResults += failed(GatewayConnectionFailure.NO_CHANNELS)
        gateway.connectResults += connected(secondGeneration)
        gateway.connectResults += failed(GatewayConnectionFailure.NO_CHANNELS)
        val owner = owner(gateway)

        owner.connect(ServerProfile("first"))
        runCurrent()
        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(firstGeneration, channelMetadata(id = 1)),
        )
        gateway.emitMetadata(MetadataEvent.EventAdded(firstGeneration, epgMetadata(1, 1)))
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(firstGeneration))
        runCurrent()
        assertEquals(listOf(1L), owner.channelRepository.channels.value.map { it.id.value })
        assertEquals(listOf(1L), owner.epgRepository.events.value.map { it.id.value })
        gateway.emitMetadata(MetadataEvent.DvrEntryAdded(firstGeneration, dvrMetadata(1)))
        runCurrent()
        assertEquals(listOf(1L), owner.dvrRepository.entries.value.map { it.id.value })

        owner.connect(ServerProfile("second"))
        runCurrent()
        assertEquals(SessionState.Unavailable(SessionFailure.NoChannels), owner.state.value)
        assertEquals(ChannelRepositoryState.Empty, owner.channelRepository.state.value)
        assertEquals(EpgRepositoryState.Empty, owner.epgRepository.state.value)
        assertEquals(DvrRepositoryState.Empty, owner.dvrRepository.state.value)
        assertEquals(DvrConfigurationsState.Unknown, owner.dvrRepository.configurationsState.value)
        assertEquals(DvrDiskSpaceState.Unknown, owner.dvrRepository.diskSpaceState.value)

        owner.connect(ServerProfile("first"))
        runCurrent()
        gateway.emitMetadata(
            MetadataEvent.ChannelAdded(secondGeneration, channelMetadata(id = 2)),
        )
        gateway.emitMetadata(MetadataEvent.EventAdded(secondGeneration, epgMetadata(2, 2)))
        gateway.emitMetadata(MetadataEvent.InitialSyncCompleted(secondGeneration))
        runCurrent()
        assertEquals(listOf(2L), owner.channelRepository.channels.value.map { it.id.value })
        assertEquals(listOf(2L), owner.epgRepository.events.value.map { it.id.value })

        owner.disconnect()
        assertEquals(ChannelRepositoryState.Empty, owner.channelRepository.state.value)
        assertEquals(EpgRepositoryState.Empty, owner.epgRepository.state.value)
        assertEquals(DvrRepositoryState.Empty, owner.dvrRepository.state.value)
        owner.connect(ServerProfile("third"))
        runCurrent()
        assertEquals(SessionState.Unavailable(SessionFailure.NoChannels), owner.state.value)
        assertEquals(ChannelRepositoryState.Empty, owner.channelRepository.state.value)
        assertEquals(EpgRepositoryState.Empty, owner.epgRepository.state.value)
        assertEquals(DvrRepositoryState.Empty, owner.dvrRepository.state.value)
        owner.shutdown()
    }

    @Test
    fun `same normalized profile is a no-op while credential changes replace it`() = runTest {
        val gateway = FakeProtocolGateway().apply {
            defaultConnectResult = failed(GatewayConnectionFailure.NO_CHANNELS)
        }
        val owner = owner(gateway)
        val original = ServerProfile(
            host = " server ",
            authentication = ServerAuthentication.Password(" user ", "secret"),
        )

        owner.connect(original)
        runCurrent()
        assertEquals(
            SessionCommandResult.NO_CHANGE,
            owner.connect(
                ServerProfile(
                    host = "server",
                    authentication = ServerAuthentication.Password("user", "secret"),
                ),
            ),
        )
        assertEquals(
            SessionCommandResult.STARTED,
            owner.connect(
                ServerProfile(
                    host = "server",
                    authentication = ServerAuthentication.Password("user", "different"),
                ),
            ),
        )
        runCurrent()
        assertEquals(2, gateway.connectCalls)
        owner.shutdown()
    }

    @Test
    fun `disconnect is reusable and shutdown is exactly ordered and idempotent`() = runTest {
        val order = mutableListOf<String>()
        val gateway = FakeProtocolGateway(order).apply {
            defaultConnectResult = failed(GatewayConnectionFailure.NO_CHANNELS)
        }
        val owner = owner(gateway, RecordingSessionChildren(order))

        owner.connect(ServerProfile("server"))
        runCurrent()
        owner.disconnect()
        owner.disconnect()
        assertEquals(SessionState.Disconnected, owner.state.value)
        assertEquals(SessionCommandResult.STARTED, owner.connect(ServerProfile("server")))
        runCurrent()

        order.clear()
        owner.shutdown()
        owner.shutdown()

        assertEquals(
            listOf("admission.stop", "epg.join", "subscriptions.join", "disconnect", "shutdown"),
            order,
        )
        assertEquals(SessionState.Disconnected, owner.state.value)
        assertEquals(SessionCommandResult.SHUT_DOWN, owner.connect(ServerProfile("other")))
        assertEquals(SessionCommandResult.SHUT_DOWN, owner.retry())
    }

    @Test
    fun `admitted disconnect finishes after caller cancellation`() = runTest {
        val order = mutableListOf<String>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val children = BlockingSessionChildren(order, cleanupStarted, releaseCleanup)
        val gateway = FakeProtocolGateway(order).apply {
            defaultConnectResult = failed(GatewayConnectionFailure.NO_CHANNELS)
        }
        val owner = owner(gateway, children)
        owner.connect(ServerProfile("server"))
        runCurrent()

        val disconnect = launch(start = CoroutineStart.UNDISPATCHED) { owner.disconnect() }
        cleanupStarted.await()
        disconnect.cancel()
        releaseCleanup.complete(Unit)
        runCurrent()
        disconnect.join()

        assertEquals(SessionState.Disconnected, owner.state.value)
        assertTrue("disconnect" in order, "Admitted disconnect did not close the gateway")
        assertEquals(SessionCommandResult.STARTED, owner.connect(ServerProfile("server")))
        runCurrent()
        owner.shutdown()
    }

    @Test
    fun `shutdown completes later stages and all callers when one stage fails`() = runTest {
        val order = mutableListOf<String>()
        val gateway = FakeProtocolGateway(order)
        val owner = owner(gateway, ThrowingSessionChildren(order))

        val first = runCatching { owner.shutdown() }
        val second = runCatching { owner.shutdown() }

        assertTrue(first.isFailure, "Shutdown failure was not reported")
        assertTrue(second.isFailure, "Concurrent shutdown result was not retained")
        assertEquals(
            listOf("admission.stop", "epg.join", "subscriptions.join", "disconnect", "shutdown"),
            order,
        )
        assertEquals(SessionCommandResult.SHUT_DOWN, owner.connect(ServerProfile("server")))
    }

    @Test
    fun `profiles and authentication validate normalize and redact secrets`() {
        val authentication = ServerAuthentication.Password(" user ", " exact password ")
        val profile = ServerProfile(" server ", authentication = authentication)

        assertTrue(profile.host == "server", "Host normalization failed")
        assertTrue(authentication.username == "user", "Username normalization failed")
        assertTrue(authentication.password == " exact password ", "Password preservation failed")
        assertTrue(profile.toString() == "ServerProfile(<redacted>)", "Profile rendering was not redacted")
        assertTrue(
            authentication.toString() == "ServerAuthentication.Password(<redacted>)",
            "Authentication rendering was not redacted",
        )
        assertFalse(profile.toString().contains("server"), "Profile rendering exposed its host")
        assertFalse(authentication.toString().contains("user"), "Authentication rendering exposed a username")
    }

    @Test
    fun `password credential accessors are hidden from Java source`() {
        val exposedStringAccessor = ServerAuthentication.Password::class.java.declaredMethods.any { method ->
            method.parameterCount == 0 &&
                method.returnType == String::class.java &&
                method.name != "toString" &&
                !method.isSynthetic
        }

        assertFalse(exposedStringAccessor, "Credential accessors must be JVM-synthetic")
    }

    private fun TestScope.owner(
        gateway: FakeProtocolGateway,
        children: SessionChildren = SessionChildren.None,
        metadata: PhaseOneSessionMetadata = PhaseOneSessionMetadata(),
        dvrMutations: DvrMutationLifecycle = DvrMutationLifecycle.None,
        dvrProgress: DvrProgressLifecycle = DvrProgressLifecycle.None,
        defaultDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        beforeDvrCapabilityPublication: suspend (Boolean) -> Unit = {},
    ): ConnectionOwner = ConnectionOwner(
        gateway = gateway,
        metadata = metadata,
        children = children,
        dvrMutations = dvrMutations,
        dvrProgress = dvrProgress,
        defaultDispatcher = defaultDispatcher,
        backoff = ExponentialReconnectBackoff(nextJitter = { 0.5 }),
        beforeDvrCapabilityPublication = beforeDvrCapabilityPublication,
    )
}

private fun channelMetadata(id: Long): GatewayChannelMetadata = GatewayChannelMetadata(
    id = ChannelId(id),
    name = null,
    uuid = null,
    number = null,
    numberMinor = null,
    icon = null,
    currentEventId = null,
    nextEventId = null,
    services = null,
    tagIds = null,
)

private fun epgMetadata(id: Long, channelId: Long): GatewayEpgEvent = GatewayEpgEvent(
    id = EventId(id),
    channelId = ChannelId(channelId),
    start = Instant.fromEpochSeconds(10),
    stop = Instant.fromEpochSeconds(20),
)

private fun dvrMetadata(id: Long): GatewayDvrEntry = GatewayDvrEntry(
    id = DvrEntryId(id),
    title = "entry",
)

private class ConnectionFailureCase(
    internal val gatewayFailure: GatewayConnectionFailure,
    internal val sessionFailure: SessionFailure,
    internal val retryResult: SessionCommandResult,
)

private class SynchronizationFailureCase(
    internal val gatewayResult: GatewayResult<Unit>,
    internal val operationFailure: SessionOperationFailure,
    internal val retryResult: SessionCommandResult,
)

private fun connected(
    generation: GatewayGeneration,
    streaming: Boolean? = null,
    dvrAccess: Boolean? = null,
    protocolVersion: Int? = 43,
): GatewayConnectResult.Connected = GatewayConnectResult.Connected(
    GatewayConnection(
        generation = generation,
        protocolVersion = protocolVersion,
        dvrAccess = dvrAccess,
        serverFacts = GatewayServerFacts(
            serverName = null,
            serverVersion = null,
            webRoot = null,
            language = null,
            serverCapabilities = null,
            apiVersion = null,
            admin = null,
            streaming = streaming,
            dvr = null,
            failedDvr = null,
            anonymous = null,
            limitAll = null,
            limitDvr = null,
            limitStreaming = null,
            uiLevel = null,
            uiLanguage = null,
        ),
    ),
)

private fun failed(failure: GatewayConnectionFailure): GatewayConnectResult.Failed =
    GatewayConnectResult.Failed(failure)

private class FakeProtocolGateway(
    private val order: MutableList<String> = mutableListOf(),
) : ProtocolGateway {
    private val metadataEvents = MutableSharedFlow<MetadataEvent>()
    private val failureEvents = MutableSharedFlow<GatewayConnectionFailureEvent>()
    private val liveGenerations = Collections.newSetFromMap(
        IdentityHashMap<GatewayGeneration, Boolean>(),
    )
    private var concurrentConnects = 0
    private var liveCommitCalls = 0

    internal val connectResults = ArrayDeque<GatewayConnectResult>()
    internal var defaultConnectResult: GatewayConnectResult =
        failed(GatewayConnectionFailure.TRANSPORT_UNAVAILABLE)
    internal var enableResult: GatewayResult<Unit> = GatewayResult.Ok(Unit)
    internal var connectBehavior: (suspend FakeProtocolGateway.() -> GatewayConnectResult)? = null
    internal var queryBehavior: suspend (
        GatewayGeneration,
        ChannelId,
        Instant,
    ) -> GatewayResult<List<GatewayEpgQueryEvent>> = { _, _, _ -> GatewayResult.Ok(emptyList()) }
    internal var dvrConfigsBehavior: suspend (
        GatewayGeneration,
    ) -> GatewayResult<List<DvrConfiguration>> = { GatewayResult.Ok(emptyList()) }
    internal var diskSpaceBehavior: suspend (
        GatewayGeneration,
    ) -> GatewayResult<DvrDiskSpace> = { GatewayResult.Ok(DvrDiskSpace(1, 2, 3)) }
    internal var scheduleDvrBehavior: suspend (
        GatewayGeneration,
        DvrScheduleRequest,
    ) -> GatewayResult<DvrEntryId> = { _, _ -> GatewayResult.NotSupported }
    internal var reportDvrProgressBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
        DvrPlaybackProgress,
    ) -> GatewayResult<Unit> = { _, _, _ -> GatewayResult.NotSupported }
    internal var progressReportCount: Int = 0
    internal var connectCalls: Int = 0
    internal var maximumConcurrentConnects: Int = 0
    internal var invalidateOnReadyCommit: Boolean = false
    internal var beforeReadyCommit: () -> Unit = {}
    internal var readyCommitResult: Boolean? = null

    override val connectionState: MutableStateFlow<GatewayState> =
        MutableStateFlow(GatewayState.Disconnected)
    override val metadata: Flow<MetadataEvent> = flow {
        order += "metadata.collect"
        metadataEvents.collect { emit(it) }
    }
    override val connectionFailures: Flow<GatewayConnectionFailureEvent> = flow {
        order += "failures.collect"
        failureEvents.collect { emit(it) }
    }

    override suspend fun connect(server: ServerConfiguration): GatewayConnectResult {
        order += "connect"
        connectCalls += 1
        concurrentConnects += 1
        maximumConcurrentConnects = maxOf(maximumConcurrentConnects, concurrentConnects)
        return try {
            val result = connectBehavior?.invoke(this)
                ?: connectResults.removeFirstOrNull()
                ?: defaultConnectResult
            if (result is GatewayConnectResult.Connected) {
                liveGenerations += result.connection.generation
            }
            result
        } finally {
            concurrentConnects -= 1
        }
    }

    override suspend fun disconnect() {
        order += "disconnect"
        liveGenerations.clear()
    }

    override suspend fun shutdown() {
        order += "shutdown"
        liveGenerations.clear()
    }

    override fun <T> commitIfLive(
        generation: GatewayGeneration,
        block: () -> T,
    ): T? {
        liveCommitCalls += 1
        val call = liveCommitCalls
        if (invalidateOnReadyCommit && liveCommitCalls == 3) {
            liveGenerations.remove(generation)
        }
        if (call == 3) beforeReadyCommit()
        return if (generation in liveGenerations) {
            block().also { result ->
                if (call == 3 && result is Boolean) readyCommitResult = result
            }
        } else {
            null
        }
    }

    override suspend fun enableInitialMetadata(
        generation: GatewayGeneration,
    ): GatewayResult<Unit> {
        order += "enable"
        return enableResult
    }

    override suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>> {
        order += "epg.query"
        return queryBehavior(generation, channelId, maxTime)
    }

    override suspend fun getDvrConfigs(
        generation: GatewayGeneration,
    ): GatewayResult<List<DvrConfiguration>> {
        order += "dvr.configs"
        return dvrConfigsBehavior(generation)
    }

    override suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace> {
        order += "dvr.disk"
        return diskSpaceBehavior(generation)
    }

    override suspend fun getDvrCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<List<DvrCutpoint>> = GatewayResult.NotSupported

    override suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId> {
        order += "dvr.schedule"
        return scheduleDvrBehavior(generation, request)
    }

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
    ): GatewayResult<Unit> {
        progressReportCount += 1
        return reportDvrProgressBehavior(generation, id, progress)
    }

    override suspend fun openRecordingFile(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<GatewayRecordingFile> = GatewayResult.NotSupported

    override suspend fun seekRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
    ): GatewayResult<Long> = GatewayResult.NotSupported

    override suspend fun readRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): GatewayResult<Int> = GatewayResult.NotSupported

    override suspend fun closeRecordingFile(
        generation: GatewayGeneration,
        file: GatewayRecordingFile,
    ): GatewayResult<Unit> = GatewayResult.NotSupported

    override fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent> = emptyFlow()

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
        timeshiftPeriod: Duration,
    ): SubscriptionOperationResult<SubscriptionConfirmation> =
        SubscriptionOperationResult.TransportUnavailable

    override suspend fun skipSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.TransportUnavailable

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.TransportUnavailable

    internal suspend fun emitMetadata(event: MetadataEvent) {
        metadataEvents.emit(event)
    }

    internal suspend fun emitFailure(event: GatewayConnectionFailureEvent) {
        event.generation?.let(liveGenerations::remove)
        failureEvents.emit(event)
    }
}

private class RecordingSessionChildren(
    private val order: MutableList<String>,
) : SessionChildren {
    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult = SubscriptionOpenResult.NotReady

    override fun bindGeneration(generation: GatewayGeneration) {
        order += "generation.bind"
    }

    override fun startLiveAdmission(
        generation: GatewayGeneration,
        streamingAccess: CapabilityAccess,
    ): Boolean {
        order += "admission.start"
        return true
    }

    override fun stopAdmission() {
        order += "admission.stop"
    }

    override fun prepareBackgroundEnrichment(
        generation: GatewayGeneration,
        onDvrCapabilitiesChanged: suspend (SessionCapabilitiesSnapshot) -> Unit,
    ): Boolean {
        order += "background.prepare"
        return true
    }

    override fun startBackgroundEnrichment(generation: GatewayGeneration): Boolean {
        order += "background.start"
        return true
    }

    override suspend fun cancelAndJoinBackgroundEnrichment() {
        order += "epg.join"
    }

    override suspend fun closeAndJoinSubscriptions() {
        order += "subscriptions.join"
    }
}

private class BlockingSessionChildren(
    private val order: MutableList<String>,
    private val cleanupStarted: CompletableDeferred<Unit>,
    private val releaseCleanup: CompletableDeferred<Unit>,
) : SessionChildren {
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

    override fun stopAdmission() {
        order += "admission.stop"
    }

    override suspend fun cancelAndJoinBackgroundEnrichment() {
        order += "epg.join"
        cleanupStarted.complete(Unit)
        releaseCleanup.await()
    }

    override suspend fun closeAndJoinSubscriptions() {
        order += "subscriptions.join"
    }
}

private class ThrowingSessionChildren(
    private val order: MutableList<String>,
) : SessionChildren {
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

    override fun stopAdmission() {
        order += "admission.stop"
    }

    override suspend fun cancelAndJoinBackgroundEnrichment() {
        order += "epg.join"
        error("fixed cleanup failure")
    }

    override suspend fun closeAndJoinSubscriptions() {
        order += "subscriptions.join"
    }
}
