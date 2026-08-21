@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrSchedule
import at.bernhardberger.tvheadend.sdk.core.DvrScheduleRequest
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleId
import at.bernhardberger.tvheadend.sdk.core.TimerecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayConnectionFailureEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.ProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.gateway.ServerConfiguration
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class DvrMutationCoordinatorTest {
    @Test
    fun `lifecycle admission cannot dispatch before the session ready gate opens`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var ready = false
        var commandCount = 0
        val coordinator = DvrMutationCoordinator(
            gateway = gateway,
            isSessionReady = { candidate -> candidate === generation && ready },
        )
        gateway.scheduleBehavior = { _, _ ->
            commandCount += 1
            GatewayResult.AccessDenied
        }
        coordinator.bindGeneration(generation)
        assertTrue(coordinator.startAdmission(generation))

        assertSame(DvrMutationResult.NotReady, coordinator.scheduleEntry(scheduleRequest()))
        assertEquals(0, commandCount)

        ready = true
        assertSame(DvrMutationResult.AccessDenied, coordinator.scheduleEntry(scheduleRequest()))
        assertEquals(1, commandCount)
    }

    @Test
    fun `create correlates matching metadata that arrives before its identifier acknowledgement`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val acknowledgement = CompletableDeferred<GatewayResult<DvrEntryId>>()
        val proofs = mutableListOf<Boolean>()
        val coordinator = DvrMutationCoordinator(
            gateway = gateway,
            onDvrAccessProof = { _, allowed -> proofs += allowed },
        )
        gateway.scheduleBehavior = { _, _ -> acknowledgement.await() }
        coordinator.bindGeneration(generation)
        assertTrue(coordinator.startAdmission(generation))

        val result = async { coordinator.scheduleEntry(scheduleRequest()) }
        runCurrent()
        coordinator.acceptMetadata(dvrAdded(generation, 99))
        coordinator.acceptMetadata(dvrAdded(generation, 7))
        acknowledgement.complete(GatewayResult.Ok(DvrEntryId(7)))
        runCurrent()

        val confirmed = result.await() as DvrMutationResult.Confirmed
        assertEquals(DvrEntryId(7), confirmed.value)
        assertEquals(listOf(true), proofs)
    }

    @Test
    fun `rejection wins over early metadata and accepted commands time out distinctly`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(
            gateway = gateway,
            settings = DvrMutationSettings(1.seconds),
        )
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        gateway.scheduleBehavior = { _, _ ->
            coordinator.acceptMetadata(dvrAdded(generation, 7))
            GatewayResult.ServerRejected
        }

        assertSame(DvrMutationResult.ServerRejected, coordinator.scheduleEntry(scheduleRequest()))

        gateway.scheduleBehavior = { _, _ -> GatewayResult.Ok(DvrEntryId(8)) }
        val timed = async { coordinator.scheduleEntry(scheduleRequest()) }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        val accepted = timed.await() as DvrMutationResult.AcceptedButUnconfirmed
        assertEquals(DvrEntryId(8), accepted.value)
    }

    @Test
    fun `typed failures preserve proof rules and caller cancellation identity`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val proofs = mutableListOf<Boolean>()
        val coordinator = DvrMutationCoordinator(
            gateway = gateway,
            onDvrAccessProof = { proofGeneration, allowed ->
                assertSame(generation, proofGeneration)
                proofs += allowed
            },
        )
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        val cases = listOf(
            GatewayResult.AccessDenied to DvrMutationResult.AccessDenied,
            GatewayResult.ServerRejected to DvrMutationResult.ServerRejected,
            GatewayResult.ConnectionLimit to DvrMutationResult.ConnectionLimit,
            GatewayResult.Timeout to DvrMutationResult.Timeout,
            GatewayResult.TransportUnavailable to DvrMutationResult.TransportUnavailable,
            GatewayResult.NotSupported to DvrMutationResult.NotSupported,
        )
        cases.forEach { (gatewayResult, expected) ->
            gateway.scheduleBehavior = { _, _ -> gatewayResult }
            assertSame(expected, coordinator.scheduleEntry(scheduleRequest()))
        }
        assertEquals(listOf(false), proofs)

        val cancellation = CancellationException("fixed cancellation")
        gateway.updateBehavior = { _, _, _ -> throw cancellation }
        var caught: CancellationException? = null
        try {
            coordinator.updateEntry(DvrEntryId(1), DvrEntryUpdate())
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `generation retirement completes a pending call and stale events cannot confirm it`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val commandStarted = CompletableDeferred<Unit>()
        val coordinator = DvrMutationCoordinator(gateway)
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ ->
            commandStarted.complete(Unit)
            awaitCancellation()
        }

        val result = async { coordinator.updateEntry(DvrEntryId(1), DvrEntryUpdate()) }
        commandStarted.await()
        coordinator.stopAdmission()
        coordinator.acceptMetadata(dvrUpdated(generation, 1))
        runCurrent()

        assertSame(DvrMutationResult.TransportUnavailable, result.await())
        assertSame(DvrMutationResult.NotReady, coordinator.updateEntry(DvrEntryId(1), DvrEntryUpdate()))
    }

    @Test
    fun `one metadata event confirms only one globally serialized operation`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        var commandCount = 0
        gateway.updateBehavior = { _, _, _ ->
            commandCount += 1
            GatewayResult.Ok(Unit)
        }
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)

        val first = async { coordinator.updateEntry(DvrEntryId(1), DvrEntryUpdate(title = "first")) }
        runCurrent()
        val second = async { coordinator.updateEntry(DvrEntryId(1), DvrEntryUpdate(title = "second")) }
        runCurrent()
        assertEquals(1, commandCount)

        coordinator.acceptMetadata(dvrUpdated(generation, 1))
        runCurrent()
        assertTrue(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(2, commandCount)

        coordinator.acceptMetadata(dvrUpdated(generation, 1))
        runCurrent()
        assertTrue(second.isCompleted)
        assertTrue(first.await() is DvrMutationResult.Confirmed)
        assertTrue(second.await() is DvrMutationResult.Confirmed)
    }

    @Test
    fun `rule creation ignores ordinary DVR side effects and matches its own rule stream`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        val acknowledgement = CompletableDeferred<GatewayResult<AutorecRuleId>>()
        gateway.createAutorecBehavior = { _, _ -> acknowledgement.await() }
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)

        val result = async { coordinator.createAutorecRule(AutorecRuleCreate("title")) }
        runCurrent()
        coordinator.acceptMetadata(dvrAdded(generation, 1))
        coordinator.acceptMetadata(
            MetadataEvent.TimerecRuleAdded(
                generation,
                GatewayTimerecRule(TimerecRuleId("rule")),
            ),
        )
        runCurrent()
        assertFalse(result.isCompleted)
        coordinator.acceptMetadata(
            MetadataEvent.AutorecRuleAdded(
                generation,
                GatewayAutorecRule(AutorecRuleId("rule")),
            ),
        )
        acknowledgement.complete(GatewayResult.Ok(AutorecRuleId("rule")))
        runCurrent()

        val confirmed = result.await() as DvrMutationResult.Confirmed
        assertEquals(AutorecRuleId("rule"), confirmed.value)
    }
}

private fun scheduleRequest(): DvrScheduleRequest =
    DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))

private fun dvrAdded(generation: GatewayGeneration, id: Long): MetadataEvent.DvrEntryAdded =
    MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(DvrEntryId(id)))

private fun dvrUpdated(generation: GatewayGeneration, id: Long): MetadataEvent.DvrEntryUpdated =
    MetadataEvent.DvrEntryUpdated(generation, GatewayDvrEntry(DvrEntryId(id)))

internal class MutationGateway : ProtocolGateway {
    internal var scheduleBehavior: suspend (
        GatewayGeneration,
        DvrScheduleRequest,
    ) -> GatewayResult<DvrEntryId> = { _, _ -> GatewayResult.NotSupported }
    internal var updateBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
        DvrEntryUpdate,
    ) -> GatewayResult<Unit> = { _, _, _ -> GatewayResult.NotSupported }
    internal var stopBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
    ) -> GatewayResult<Unit> = { _, _ -> GatewayResult.NotSupported }
    internal var cancelBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
    ) -> GatewayResult<Unit> = { _, _ -> GatewayResult.NotSupported }
    internal var deleteBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
    ) -> GatewayResult<Unit> = { _, _ -> GatewayResult.NotSupported }
    internal var createAutorecBehavior: suspend (
        GatewayGeneration,
        AutorecRuleCreate,
    ) -> GatewayResult<AutorecRuleId> = { _, _ -> GatewayResult.NotSupported }
    internal var updateAutorecBehavior: suspend (
        GatewayGeneration,
        AutorecRuleId,
        AutorecRuleUpdate,
    ) -> GatewayResult<Unit> = { _, _, _ -> GatewayResult.NotSupported }
    internal var deleteAutorecBehavior: suspend (
        GatewayGeneration,
        AutorecRuleId,
    ) -> GatewayResult<Unit> = { _, _ -> GatewayResult.NotSupported }
    internal var createTimerecBehavior: suspend (
        GatewayGeneration,
        TimerecRuleCreate,
    ) -> GatewayResult<TimerecRuleId> = { _, _ -> GatewayResult.NotSupported }
    internal var updateTimerecBehavior: suspend (
        GatewayGeneration,
        TimerecRuleId,
        TimerecRuleUpdate,
    ) -> GatewayResult<Unit> = { _, _, _ -> GatewayResult.NotSupported }
    internal var deleteTimerecBehavior: suspend (
        GatewayGeneration,
        TimerecRuleId,
    ) -> GatewayResult<Unit> = { _, _ -> GatewayResult.NotSupported }

    override val connectionState: MutableStateFlow<GatewayState> =
        MutableStateFlow(GatewayState.Disconnected)
    override val metadata: Flow<MetadataEvent> = emptyFlow()
    override val connectionFailures: Flow<GatewayConnectionFailureEvent> = emptyFlow()

    override suspend fun connect(server: ServerConfiguration): GatewayConnectResult =
        error("Connection lifecycle is not used")

    override suspend fun disconnect() = Unit

    override suspend fun shutdown() = Unit

    override fun <T> commitIfLive(generation: GatewayGeneration, block: () -> T): T = block()

    override suspend fun enableInitialMetadata(generation: GatewayGeneration): GatewayResult<Unit> =
        GatewayResult.NotSupported

    override suspend fun queryEpg(
        generation: GatewayGeneration,
        channelId: ChannelId,
        maxTime: Instant,
    ): GatewayResult<List<GatewayEpgQueryEvent>> = GatewayResult.NotSupported

    override suspend fun getDvrConfigs(
        generation: GatewayGeneration,
    ): GatewayResult<List<DvrConfiguration>> = GatewayResult.NotSupported

    override suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace> = GatewayResult.NotSupported

    override suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId> = scheduleBehavior(generation, request)

    override suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
    ): GatewayResult<Unit> = updateBehavior(generation, id, update)

    override suspend fun stopDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = stopBehavior(generation, id)

    override suspend fun cancelDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = cancelBehavior(generation, id)

    override suspend fun deleteDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = deleteBehavior(generation, id)

    override suspend fun createAutorecRule(
        generation: GatewayGeneration,
        request: AutorecRuleCreate,
    ): GatewayResult<AutorecRuleId> = createAutorecBehavior(generation, request)

    override suspend fun updateAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
        update: AutorecRuleUpdate,
    ): GatewayResult<Unit> = updateAutorecBehavior(generation, id, update)

    override suspend fun deleteAutorecRule(
        generation: GatewayGeneration,
        id: AutorecRuleId,
    ): GatewayResult<Unit> = deleteAutorecBehavior(generation, id)

    override suspend fun createTimerecRule(
        generation: GatewayGeneration,
        request: TimerecRuleCreate,
    ): GatewayResult<TimerecRuleId> = createTimerecBehavior(generation, request)

    override suspend fun updateTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
        update: TimerecRuleUpdate,
    ): GatewayResult<Unit> = updateTimerecBehavior(generation, id, update)

    override suspend fun deleteTimerecRule(
        generation: GatewayGeneration,
        id: TimerecRuleId,
    ): GatewayResult<Unit> = deleteTimerecBehavior(generation, id)

    override fun subscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): Flow<SubscriptionEvent> = emptyFlow()

    override suspend fun subscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
        channelId: ChannelId,
    ): SubscriptionOperationResult<SubscriptionConfirmation> =
        SubscriptionOperationResult.NotSupported

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.NotSupported
}
