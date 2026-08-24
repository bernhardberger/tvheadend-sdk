package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointAction
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
internal class DvrProgressCoordinatorTest {
    @Test
    fun `admission and protocol version gate progress without mutating confirmation state`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var ready = false
        var commandCount = 0
        val coordinator = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = { candidate -> candidate === generation && ready },
        )
        gateway.progressBehavior = { _, _, _ ->
            commandCount += 1
            GatewayResult.Ok(Unit)
        }

        assertSame(
            DvrProgressResult.NotReady,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        coordinator.bindGeneration(generation, protocolVersion = 26)
        assertTrue(coordinator.startAdmission(generation))
        assertSame(
            DvrProgressResult.NotReady,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        ready = true
        assertSame(
            DvrProgressResult.NotSupported,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertEquals(0, commandCount)

        coordinator.bindGeneration(generation, protocolVersion = null)
        assertTrue(coordinator.startAdmission(generation))
        assertSame(
            DvrProgressResult.NotSupported,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertEquals(0, commandCount)

        coordinator.bindGeneration(generation, protocolVersion = 27)
        assertTrue(coordinator.startAdmission(generation))
        ready = false
        assertSame(
            DvrProgressResult.NotReady,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        ready = true
        assertSame(
            DvrProgressResult.Accepted,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertEquals(1, commandCount)
    }

    @Test
    fun `not supported latches for the generation and resets on rebind`() = runTest {
        val generation = GatewayGeneration()
        val next = GatewayGeneration()
        val gateway = MutationGateway()
        var commandCount = 0
        val unsupportedGenerations = mutableListOf<GatewayGeneration>()
        val coordinator = DvrProgressCoordinator(
            gateway = gateway,
            onProgressNotSupported = { unsupportedGenerations += it },
        )
        gateway.progressBehavior = { _, _, _ ->
            commandCount += 1
            GatewayResult.NotSupported
        }
        coordinator.bindGeneration(generation, protocolVersion = 27)
        coordinator.startAdmission(generation)

        assertSame(
            DvrProgressResult.NotSupported,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertSame(
            DvrProgressResult.NotSupported,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertEquals(1, commandCount)
        assertEquals(listOf(generation), unsupportedGenerations)

        coordinator.bindGeneration(next, protocolVersion = 27)
        coordinator.startAdmission(next)
        gateway.progressBehavior = { _, _, _ ->
            commandCount += 1
            GatewayResult.Ok(Unit)
        }
        assertSame(
            DvrProgressResult.Accepted,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertEquals(2, commandCount)
        assertEquals(listOf(generation), unsupportedGenerations)
    }

    @Test
    fun `typed failures preserve proof rules cancellation identity and stale generation retirement`() =
        runTest {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val proofs = mutableListOf<Boolean>()
            val unsupportedGenerations = mutableListOf<GatewayGeneration>()
            val coordinator = DvrProgressCoordinator(
                gateway = gateway,
                onDvrAccessProof = { _, allowed -> proofs += allowed },
                onProgressNotSupported = { unsupportedGenerations += it },
            )
            coordinator.bindGeneration(generation, protocolVersion = 43)
            coordinator.startAdmission(generation)

            gateway.progressBehavior = { _, _, _ -> GatewayResult.AccessDenied }
            assertSame(
                DvrProgressResult.AccessDenied,
                coordinator.reportProgress(DvrEntryId(7), checkpoint()),
            )
            gateway.progressBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
            assertSame(
                DvrProgressResult.Accepted,
                coordinator.reportProgress(DvrEntryId(7), checkpoint()),
            )
            assertEquals(listOf(false, true), proofs)

            val cancellation = CancellationException("private cancellation")
            gateway.progressBehavior = { _, _, _ -> throw cancellation }
            var caught: CancellationException? = null
            try {
                coordinator.reportProgress(DvrEntryId(7), checkpoint())
            } catch (failure: CancellationException) {
                caught = failure
            }
            assertSame(cancellation, caught)

            val blocked = CompletableDeferred<GatewayResult<Unit>>()
            gateway.progressBehavior = { _, _, _ -> blocked.await() }
            val inFlight = async { coordinator.reportProgress(DvrEntryId(8), checkpoint()) }
            runCurrent()
            coordinator.stopAdmission()
            blocked.complete(GatewayResult.NotSupported)
            assertSame(DvrProgressResult.TransportUnavailable, inFlight.await())
            assertEquals(emptyList<GatewayGeneration>(), unsupportedGenerations)
        }

    @Test
    fun `access proof runs after the coordinator lock is released`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val released = CountDownLatch(1)
        lateinit var coordinator: DvrProgressCoordinator
        coordinator = DvrProgressCoordinator(
            gateway = gateway,
            onDvrAccessProof = { _, _ ->
                val waiter = Thread {
                    coordinator.stopAdmission()
                    released.countDown()
                }
                waiter.start()
                check(released.await(1, TimeUnit.SECONDS)) {
                    "Coordinator lock was still held during access proof"
                }
                waiter.join()
            },
        )
        gateway.progressBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
        coordinator.bindGeneration(generation, protocolVersion = 27)
        coordinator.startAdmission(generation)

        assertSame(
            DvrProgressResult.Accepted,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
        assertSame(
            DvrProgressResult.NotReady,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )
    }

    @Test
    fun `readiness check runs after lock release and rechecks admission`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        lateinit var coordinator: DvrProgressCoordinator
        coordinator = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = {
                val released = CountDownLatch(1)
                val waiter = Thread {
                    coordinator.stopAdmission()
                    released.countDown()
                }
                waiter.start()
                check(released.await(1, TimeUnit.SECONDS)) {
                    "Coordinator lock was still held during the readiness check"
                }
                waiter.join()
                true
            },
        )

        coordinator.bindGeneration(generation, protocolVersion = 26)
        assertTrue(coordinator.startAdmission(generation))
        assertSame(
            DvrProgressResult.TransportUnavailable,
            coordinator.reportProgress(DvrEntryId(7), checkpoint()),
        )

        coordinator.bindGeneration(generation, protocolVersion = 11)
        assertTrue(coordinator.startAdmission(generation))
        assertSame(
            DvrCutpointsResult.TransportUnavailable,
            coordinator.getCutpoints(DvrEntryId(7)),
        )
    }

    @Test
    fun `cutpoints wait for admission readiness and protocol version 12`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var ready = false
        var commandCount = 0
        val coordinator = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = { candidate -> candidate === generation && ready },
        )
        val cutpoint = DvrCutpoint(
            1_000.milliseconds,
            2_000.milliseconds,
            DvrCutpointAction.CUT,
        )
        gateway.cutpointsBehavior = { candidate, id ->
            assertSame(generation, candidate)
            assertEquals(DvrEntryId(7), id)
            commandCount += 1
            GatewayResult.Ok(listOf(cutpoint))
        }

        assertSame(DvrCutpointsResult.NotReady, coordinator.getCutpoints(DvrEntryId(7)))
        coordinator.bindGeneration(generation, protocolVersion = 11)
        assertTrue(coordinator.startAdmission(generation))
        assertSame(DvrCutpointsResult.NotReady, coordinator.getCutpoints(DvrEntryId(7)))
        ready = true
        assertSame(DvrCutpointsResult.NotSupported, coordinator.getCutpoints(DvrEntryId(7)))
        assertEquals(0, commandCount)

        coordinator.bindGeneration(generation, protocolVersion = 12)
        assertTrue(coordinator.startAdmission(generation))
        ready = false
        assertSame(DvrCutpointsResult.NotReady, coordinator.getCutpoints(DvrEntryId(7)))
        ready = true
        val available = coordinator.getCutpoints(DvrEntryId(7)) as DvrCutpointsResult.Available
        assertEquals(listOf(cutpoint), available.cutpoints)
        assertEquals(1, commandCount)
    }

    @Test
    fun `cutpoint unsupported latch and in flight query are generation fenced`() = runTest {
        val generation = GatewayGeneration()
        val next = GatewayGeneration()
        val gateway = MutationGateway()
        var commandCount = 0
        val coordinator = DvrProgressCoordinator(gateway = gateway)
        gateway.cutpointsBehavior = { _, _ ->
            commandCount += 1
            GatewayResult.NotSupported
        }
        coordinator.bindGeneration(generation, protocolVersion = 43)
        coordinator.startAdmission(generation)

        assertSame(DvrCutpointsResult.NotSupported, coordinator.getCutpoints(DvrEntryId(7)))
        assertSame(DvrCutpointsResult.NotSupported, coordinator.getCutpoints(DvrEntryId(7)))
        assertEquals(1, commandCount)

        coordinator.bindGeneration(next, protocolVersion = 43)
        coordinator.startAdmission(next)
        val blocked = CompletableDeferred<GatewayResult<List<DvrCutpoint>>>()
        gateway.cutpointsBehavior = { _, _ ->
            commandCount += 1
            blocked.await()
        }
        val inFlight = async { coordinator.getCutpoints(DvrEntryId(8)) }
        runCurrent()
        coordinator.stopAdmission()
        blocked.complete(GatewayResult.Ok(emptyList()))

        assertSame(DvrCutpointsResult.TransportUnavailable, inFlight.await())
        assertEquals(2, commandCount)
    }

    @Test
    fun `cutpoint failures remain typed and caller cancellation propagates by identity`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val proofs = mutableListOf<Boolean>()
        val coordinator = DvrProgressCoordinator(
            gateway = gateway,
            onDvrAccessProof = { _, allowed -> proofs += allowed },
        )
        coordinator.bindGeneration(generation, protocolVersion = 43)
        coordinator.startAdmission(generation)

        val failures = listOf(
            GatewayResult.ServerRejected to DvrCutpointsResult.ServerRejected,
            GatewayResult.AccessDenied to DvrCutpointsResult.AccessDenied,
            GatewayResult.ConnectionLimit to DvrCutpointsResult.ConnectionLimit,
            GatewayResult.Timeout to DvrCutpointsResult.Timeout,
            GatewayResult.TransportUnavailable to DvrCutpointsResult.TransportUnavailable,
        )
        failures.forEach { (gatewayFailure, expected) ->
            gateway.cutpointsBehavior = { _, _ -> gatewayFailure }
            assertSame(expected, coordinator.getCutpoints(DvrEntryId(7)))
        }
        assertEquals(emptyList<Boolean>(), proofs, "Per-entry reads must not change write authority")

        val cancellation = CancellationException("private cancellation")
        gateway.cutpointsBehavior = { _, _ -> throw cancellation }
        var caught: CancellationException? = null
        try {
            coordinator.getCutpoints(DvrEntryId(7))
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    private fun checkpoint(): DvrPlaybackProgress = DvrPlaybackProgress.checkpoint(30.seconds)
}
