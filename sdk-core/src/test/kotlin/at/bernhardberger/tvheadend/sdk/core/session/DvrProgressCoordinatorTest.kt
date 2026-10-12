@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointAction
import at.bernhardberger.tvheadend.sdk.core.DvrCutpointsResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
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
    fun `progress cancellation after reply propagates with and without a growing lease`() = runTest {
        for (useLease in listOf(false, true)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            var commands = 0
            val proofs = mutableListOf<Boolean>()
            val coordinator = DvrProgressCoordinator(
                gateway,
                onDvrAccessProof = { _, allowed -> proofs += allowed },
            )
            coordinator.bindGeneration(generation, 27)
            coordinator.startAdmission(generation)
            gateway.progressBehavior = { _, _, _ ->
                commands++
                currentCoroutineContext().cancel()
                GatewayResult.Ok(Unit)
            }
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                if (useLease) {
                    coordinator.reportProgress(TestGenerationBoundGrowingLease(generation, DvrEntryId(7)), checkpoint())
                } else {
                    coordinator.reportProgress(generation, DvrEntryId(7), checkpoint())
                }
                error("Cancelled progress command returned a result")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(1, commands)
            assertTrue(proofs.isEmpty(), "Cancelled reply must not update access proof")
        }
    }

    @Test
    fun `all unsent progress and cutpoint fences distinguish retirement from revoked admission`() = runTest {
        val retirements: List<Pair<TvheadendFailure, (DvrProgressCoordinator) -> Unit>> = listOf(
            TvheadendFailure.CONNECTION_CHANGED to { it.bindGeneration(GatewayGeneration(), 43) },
            TvheadendFailure.CONNECTION_CHANGED to { it.stopAdmission(connectionRetired = true) },
            TvheadendFailure.NOT_READY to { it.stopAdmission() },
        )
        for ((expected, retire) in retirements) {
            for (stage in listOf("readiness-false", "readiness-true", "target-false", "target-true")) {
                for (kind in listOf("progress", "growing", "cutpoints")) {
                    val generation = GatewayGeneration()
                    val gateway = MutationGateway()
                    var calls = 0
                    lateinit var coordinator: DvrProgressCoordinator
                    coordinator = DvrProgressCoordinator(gateway, isSessionReady = {
                        if (stage.startsWith("readiness")) retire(coordinator)
                        stage != "readiness-false"
                    })
                    coordinator.bindGeneration(generation, 43)
                    coordinator.startAdmission(generation)
                    val targetIsCurrent = {
                        if (stage.startsWith("target")) retire(coordinator)
                        stage != "target-false"
                    }
                    gateway.progressBehavior = { _, _, _ ->
                        calls += 1
                        GatewayResult.Ok(Unit)
                    }
                    gateway.cutpointsBehavior = { _, _ ->
                        calls += 1
                        GatewayResult.Ok(emptyList())
                    }
                    val context = "$kind/$stage/$expected"
                    if (kind == "cutpoints") {
                        assertEquals(
                            DvrCutpointsResult.Failed(expected),
                            coordinator.getCutpoints(generation, DvrEntryId(7), targetIsCurrent),
                            context,
                        )
                    } else {
                        val result = if (kind == "growing") {
                            val lease = TestGenerationBoundGrowingLease(generation, DvrEntryId(7))
                            lease.validate = targetIsCurrent
                            coordinator.reportProgress(lease, checkpoint())
                        } else {
                            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint(), targetIsCurrent)
                        }
                        assertEquals(DvrProgressResult.Failed(expected, false), result, context)
                    }
                    assertEquals(0, calls, context)
                }
            }
        }
    }

    @Test
    fun `growing lease admission distinguishes replacement retirement and shutdown before sending`() = runTest {
        val original = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrProgressCoordinator(gateway)
        val lease = TestGenerationBoundGrowingLease(original, DvrEntryId(7))
        var calls = 0
        gateway.progressBehavior = { _, _, _ ->
            calls += 1
            GatewayResult.Ok(Unit)
        }
        coordinator.bindGeneration(GatewayGeneration(), 43)
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, false),
            coordinator.reportProgress(lease, checkpoint()),
        )
        coordinator.stopAdmission(connectionRetired = true)
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, false),
            coordinator.reportProgress(lease, checkpoint()),
        )
        coordinator.stopAdmission()
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, false),
            coordinator.reportProgress(lease, checkpoint()),
        )
        coordinator.bindGeneration(original, 43)
        coordinator.stopAdmission()
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_READY, false),
            coordinator.reportProgress(lease, checkpoint()),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `growing progress and cutpoint replies cannot survive connection replacement`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrProgressCoordinator(gateway)
        coordinator.bindGeneration(generation, 43)
        coordinator.startAdmission(generation)
        gateway.progressBehavior = { _, _, _ ->
            coordinator.bindGeneration(GatewayGeneration(), 43)
            GatewayResult.Ok(Unit)
        }
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, true),
            coordinator.reportProgress(TestGenerationBoundGrowingLease(generation, DvrEntryId(7)), checkpoint()),
        )
        coordinator.bindGeneration(generation, 43)
        coordinator.startAdmission(generation)
        gateway.cutpointsBehavior = { _, _ ->
            coordinator.bindGeneration(GatewayGeneration(), 43)
            GatewayResult.Ok(emptyList())
        }
        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.CONNECTION_CHANGED),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
    }

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

        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        coordinator.bindGeneration(generation, protocolVersion = 26)
        assertTrue(coordinator.startAdmission(generation))
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        ready = true
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        assertEquals(0, commandCount)

        coordinator.bindGeneration(generation, protocolVersion = null)
        assertTrue(coordinator.startAdmission(generation))
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        assertEquals(0, commandCount)

        coordinator.bindGeneration(generation, protocolVersion = 27)
        assertTrue(coordinator.startAdmission(generation))
        ready = false
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        ready = true
        assertSame(
            DvrProgressResult.Accepted,
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
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

        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
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
            coordinator.reportProgress(next, DvrEntryId(7), checkpoint()),
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
            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.ACCESS_DENIED, mayHaveApplied = false),
                coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
            )
            gateway.progressBehavior = { _, _, _ -> GatewayResult.MalformedReply }
            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true),
                coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
            )
            assertEquals(listOf(false), proofs)
            assertTrue(unsupportedGenerations.isEmpty())
            gateway.progressBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
            assertSame(
                DvrProgressResult.Accepted,
                coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
            )
            assertEquals(listOf(false, true), proofs)

            val cancellation = CancellationException("private cancellation")
            gateway.progressBehavior = { _, _, _ -> throw cancellation }
            var caught: CancellationException? = null
            try {
                coordinator.reportProgress(generation, DvrEntryId(7), checkpoint())
            } catch (failure: CancellationException) {
                caught = failure
            }
            assertSame(cancellation, caught)

            val blocked = CompletableDeferred<GatewayResult<Unit>>()
            gateway.progressBehavior = { _, _, _ -> blocked.await() }
            val inFlight = async {
                coordinator.reportProgress(generation, DvrEntryId(8), checkpoint())
            }
            runCurrent()
            coordinator.stopAdmission()
            blocked.complete(GatewayResult.NotSupported)
            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = false),
                inFlight.await(),
            )
            assertEquals(emptyList<GatewayGeneration>(), unsupportedGenerations)
        }

    @Test
    fun `growing progress cannot rebind between lease validation and RPC admission`() = runTest {
        val original = GatewayGeneration()
        val replacement = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrProgressCoordinator(gateway = gateway)
        var commandCount = 0
        gateway.progressBehavior = { generation, id, _ ->
            assertSame(original, generation)
            assertEquals(DvrEntryId(7), id)
            commandCount += 1
            GatewayResult.Ok(Unit)
        }
        coordinator.bindGeneration(original, protocolVersion = 43)
        coordinator.startAdmission(original)
        val lease = TestGenerationBoundGrowingLease(original, DvrEntryId(7))

        assertSame(DvrProgressResult.Accepted, coordinator.reportProgress(lease, checkpoint()))
        lease.validate = {
            coordinator.bindGeneration(replacement, protocolVersion = 43)
            coordinator.startAdmission(replacement)
            true
        }

        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = false),
            coordinator.reportProgress(lease, checkpoint()),
        )
        assertEquals(1, commandCount)
    }

    @Test
    fun `growing progress revalidates same generation identity at final admission`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var targetCurrent = true
        var commandCount = 0
        val coordinator = DvrProgressCoordinator(
            gateway = gateway,
            isSessionReady = {
                targetCurrent = false
                true
            },
        )
        gateway.progressBehavior = { _, _, _ ->
            commandCount += 1
            GatewayResult.Ok(Unit)
        }
        coordinator.bindGeneration(generation, protocolVersion = 43)
        coordinator.startAdmission(generation)
        val lease = TestGenerationBoundGrowingLease(generation, DvrEntryId(7)).also {
            it.validate = { targetCurrent }
        }

        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = false),
            coordinator.reportProgress(lease, checkpoint()),
        )
        assertEquals(0, commandCount)
    }

    @Test
    fun `completed progress and cutpoints revalidate target identity at final admission`() =
        runTest {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            var targetCurrent = true
            var progressCommands = 0
            var cutpointCommands = 0
            val coordinator = DvrProgressCoordinator(
                gateway = gateway,
                isSessionReady = {
                    targetCurrent = false
                    true
                },
            )
            gateway.progressBehavior = { _, _, _ ->
                progressCommands += 1
                GatewayResult.Ok(Unit)
            }
            gateway.cutpointsBehavior = { _, _ ->
                cutpointCommands += 1
                GatewayResult.Ok(emptyList())
            }
            coordinator.bindGeneration(generation, protocolVersion = 43)
            coordinator.startAdmission(generation)

            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = false),
                coordinator.reportProgress(
                    generation,
                    DvrEntryId(7),
                    checkpoint(),
                    targetIsCurrent = { targetCurrent },
                ),
            )
            targetCurrent = true
            assertEquals(
                DvrCutpointsResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE),
                coordinator.getCutpoints(
                    generation,
                    DvrEntryId(7),
                    targetIsCurrent = { targetCurrent },
                ),
            )
            assertEquals(0, progressCommands)
            assertEquals(0, cutpointCommands)
        }

    @Test
    fun `progress uncertainty follows dispatch and refusal even when the generation retires`() = runTest {
        val replies = listOf(
            GatewayResult.Ok(Unit) to true,
            GatewayResult.ServerRejected to false,
            GatewayResult.AccessDenied to false,
            GatewayResult.ConnectionLimit to false,
            GatewayResult.NotSupported to false,
            GatewayResult.MalformedReply to true,
            GatewayResult.Timeout to true,
            GatewayResult.TransportUnavailable to true,
        )
        for ((reply, mayHaveApplied) in replies) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val coordinator = DvrProgressCoordinator(gateway)
            coordinator.bindGeneration(generation, protocolVersion = 43)
            coordinator.startAdmission(generation)
            gateway.progressBehavior = { _, _, _ ->
                coordinator.stopAdmission()
                reply
            }

            assertEquals(
                DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied),
                coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
            )
        }
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
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
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
        assertEquals(
            DvrProgressResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
            coordinator.reportProgress(generation, DvrEntryId(7), checkpoint()),
        )

        coordinator.bindGeneration(generation, protocolVersion = 11)
        assertTrue(coordinator.startAdmission(generation))
        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
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

        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
        coordinator.bindGeneration(generation, protocolVersion = 11)
        assertTrue(coordinator.startAdmission(generation))
        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
        ready = true
        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
        assertEquals(0, commandCount)

        coordinator.bindGeneration(generation, protocolVersion = 12)
        assertTrue(coordinator.startAdmission(generation))
        ready = false
        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.NOT_READY),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
        ready = true
        val available = coordinator.getCutpoints(
            generation,
            DvrEntryId(7),
        ) as DvrCutpointsResult.Available
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

        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
        assertEquals(
            DvrCutpointsResult.Failed(TvheadendFailure.NOT_SUPPORTED),
            coordinator.getCutpoints(generation, DvrEntryId(7)),
        )
        assertEquals(1, commandCount)

        coordinator.bindGeneration(next, protocolVersion = 43)
        coordinator.startAdmission(next)
        val blocked = CompletableDeferred<GatewayResult<List<DvrCutpoint>>>()
        gateway.cutpointsBehavior = { _, _ ->
            commandCount += 1
            blocked.await()
        }
        val inFlight = async { coordinator.getCutpoints(next, DvrEntryId(8)) }
        runCurrent()
        coordinator.stopAdmission()
        blocked.complete(GatewayResult.Ok(emptyList()))

        assertEquals(DvrCutpointsResult.Failed(TvheadendFailure.CONNECTION_CHANGED), inFlight.await())
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
            GatewayResult.ServerRejected to DvrCutpointsResult.Failed(TvheadendFailure.SERVER_REJECTED),
            GatewayResult.MalformedReply to DvrCutpointsResult.Failed(TvheadendFailure.MALFORMED_RESPONSE),
            GatewayResult.AccessDenied to DvrCutpointsResult.Failed(TvheadendFailure.ACCESS_DENIED),
            GatewayResult.ConnectionLimit to DvrCutpointsResult.Failed(TvheadendFailure.CONNECTION_LIMIT),
            GatewayResult.Timeout to DvrCutpointsResult.Failed(TvheadendFailure.TIMEOUT),
            GatewayResult.TransportUnavailable to DvrCutpointsResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE),
        )
        failures.forEach { (gatewayFailure, expected) ->
            gateway.cutpointsBehavior = { _, _ -> gatewayFailure }
            assertEquals(expected, coordinator.getCutpoints(generation, DvrEntryId(7)))
        }
        assertEquals(emptyList<Boolean>(), proofs, "Per-entry reads must not change write authority")

        val cancellation = CancellationException("private cancellation")
        gateway.cutpointsBehavior = { _, _ -> throw cancellation }
        var caught: CancellationException? = null
        try {
            coordinator.getCutpoints(generation, DvrEntryId(7))
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    private fun checkpoint(): DvrPlaybackProgress = DvrPlaybackProgress.checkpoint(30.seconds)
}

private class TestGenerationBoundGrowingLease(
    override val boundGeneration: GatewayGeneration,
    override val boundRecordingId: DvrEntryId,
) : GenerationBoundGrowingRecordingFileLease {
    var validate: () -> Boolean = { true }

    override val isCurrent: Boolean
        get() = validate()

    override fun isProgressBindingCurrent(): Boolean = validate()

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
        error("Test lease does not open recording files")
}
