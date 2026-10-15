@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.AutorecRuleCreate
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.AutorecRuleUpdate
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrCutpoint
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
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
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrStats
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayRecordingFile
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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class, at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)
internal class DvrMutationCoordinatorTest {
    @Test
    fun `scheduled update resolves explicit or observed config including empty default name`() = runTest {
        for (explicit in listOf(false, true)) {
            for (name in listOf("custom", "")) {
                val generation = GatewayGeneration()
                val gateway = MutationGateway()
                val current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = DvrConfigId("current"))
                val target = if (explicit) DvrConfigId("chosen") else current.configId!!
                var lookups = 0
                gateway.configsBehavior = {
                    assertSame(generation, it)
                    lookups++
                    GatewayResult.Ok(listOf(DvrConfiguration(target, name, "")))
                }
                val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
                coordinator.bindGeneration(generation)
                coordinator.startAdmission(generation)
                gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }

                val result = coordinator.updateEntry(generation, current.id, DvrEntryUpdate(configId = target.takeIf { explicit }))

                assertTrue(result is DvrMutationResult.AcceptedButUnconfirmed)
                assertEquals(1, lookups)
                assertEquals(1, gateway.sentConfigNames.size)
                assertTrue(gateway.sentConfigNames.single() == name, "Resolved config name must reach the request")
            }
        }
    }

    @Test
    fun `scheduled update refuses missing or ambiguous config without dispatch`() = runTest {
        val target = DvrConfigId("target")
        for (configs in listOf(
            emptyList(),
            listOf(DvrConfiguration(target, "same", ""), DvrConfiguration(DvrConfigId("other"), "same", "")),
            listOf(DvrConfiguration(target, "first", ""), DvrConfiguration(target, "second", "")),
        )) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            gateway.configsBehavior = { GatewayResult.Ok(configs) }
            val current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = target)
            val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            assertEquals(
                DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
                coordinator.updateEntry(generation, current.id, DvrEntryUpdate(title = "changed")),
            )
            assertTrue(gateway.sentConfigNames.isEmpty())
        }
    }

    @Test
    fun `non scheduled and unconfigured updates retain request shape without lookup`() = runTest {
        for (state in listOf(null, DvrEntryState.RECORDING, DvrEntryState.COMPLETED, DvrEntryState.MISSED, DvrEntryState.SCHEDULED)) {
            for (explicit in listOf(null, DvrConfigId("chosen"))) {
                if (state == DvrEntryState.SCHEDULED && explicit != null) continue
                val generation = GatewayGeneration()
                val gateway = MutationGateway()
                gateway.configsBehavior = { error("Unexpected config lookup") }
                val current = DvrEntry.create(DvrEntryId(7), state = state,
                    configId = DvrConfigId("observed").takeUnless { state == DvrEntryState.SCHEDULED })
                val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
                coordinator.bindGeneration(generation)
                coordinator.startAdmission(generation)
                gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
                assertTrue(coordinator.updateEntry(generation, current.id, DvrEntryUpdate(configId = explicit)) is DvrMutationResult.AcceptedButUnconfirmed)
                assertTrue(gateway.sentConfigNames.single() == explicit?.value, "Legacy config field must be preserved")
            }
        }
    }

    @Test
    fun `config lookup failures are unsent failures without write proof or server text`() = runTest {
        val failures = listOf(
            GatewayResult.AccessDenied to TvheadendFailure.ACCESS_DENIED,
            GatewayResult.ConnectionLimit to TvheadendFailure.CONNECTION_LIMIT,
            GatewayResult.Timeout to TvheadendFailure.TIMEOUT,
            GatewayResult.TransportUnavailable to TvheadendFailure.TRANSPORT_UNAVAILABLE,
            GatewayResult.NotSupported to TvheadendFailure.NOT_SUPPORTED,
            GatewayResult.ObservationExpired to TvheadendFailure.OBSERVATION_EXPIRED,
            GatewayResult.MalformedReply to TvheadendFailure.MALFORMED_RESPONSE,
            GatewayResult.ServerRejected("private config name and uuid") to TvheadendFailure.SERVER_REJECTED,
        )
        for ((response, expected) in failures) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val proofs = mutableListOf<Boolean>()
            val current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = DvrConfigId("target"))
            val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current }, onDvrAccessProof = { _, allowed -> proofs += allowed })
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            gateway.configsBehavior = { response }
            val result = coordinator.updateEntry(generation, current.id, DvrEntryUpdate()) as DvrMutationResult.Failed
            assertEquals(DvrMutationResult.Failed(expected, false), result)
            assertEquals(null, result.serverMessage)
            assertFalse(result.toString().contains("private"))
            assertTrue(gateway.sentConfigNames.isEmpty())
            assertTrue(proofs.isEmpty())
        }
    }

    @Test
    fun `config lookup revalidates observation and generation before dispatch`() = runTest {
        for (change in listOf("config", "state", "delete", "generation")) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val configId = DvrConfigId("target")
            var current: DvrEntry? = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = configId)
            val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            gateway.configsBehavior = {
                when (change) {
                    "config" -> current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = DvrConfigId("other"))
                    "state" -> current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.RECORDING, configId = configId)
                    "delete" -> current = null
                    "generation" -> coordinator.bindGeneration(GatewayGeneration())
                }
                GatewayResult.Ok(listOf(DvrConfiguration(configId, "name", "")))
            }
            assertEquals(
                DvrMutationResult.Failed(if (change == "generation") TvheadendFailure.CONNECTION_CHANGED else TvheadendFailure.OBSERVATION_EXPIRED, false),
                coordinator.updateEntry(generation, DvrEntryId(7), DvrEntryUpdate()),
            )
            assertTrue(gateway.sentConfigNames.isEmpty())
        }
    }

    @Test
    fun `config lookup retirement cancels read and caller cancellation propagates`() = runTest {
        for (retire in listOf(false, true)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = DvrConfigId("target"))
            val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            var cancelled = false
            gateway.configsBehavior = { try { awaitCancellation() } finally { cancelled = true } }
            val operation = async { coordinator.updateEntry(generation, current.id, DvrEntryUpdate()) }
            runCurrent()
            if (retire) {
                coordinator.stopAdmission(connectionRetired = true)
                assertEquals(DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, false), operation.await())
            } else {
                operation.cancel()
                operation.join()
                assertTrue(operation.isCancelled)
            }
            assertTrue(cancelled)
            assertTrue(gateway.sentConfigNames.isEmpty())
        }
    }

    @Test
    fun `config lookup thrown cancellation propagates unchanged`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = DvrConfigId("target"))
        val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        val cancellation = CancellationException("cancelled")
        gateway.configsBehavior = { throw cancellation }
        var caught: CancellationException? = null
        try {
            coordinator.updateEntry(generation, current.id, DvrEntryUpdate())
        } catch (exception: CancellationException) {
            caught = exception
        }
        assertSame(cancellation, caught)
        assertTrue(gateway.sentConfigNames.isEmpty())
    }

    @Test
    fun `metadata during config lookup cannot confirm unsent write and write errors redact config`() = runTest {
        for (reject in listOf(false, true)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val configId = DvrConfigId("private-id")
            var current = DvrEntry.create(DvrEntryId(7), state = DvrEntryState.SCHEDULED, configId = configId)
            val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> current })
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            gateway.configsBehavior = {
                current = DvrEntry.create(current.id, state = DvrEntryState.SCHEDULED, configId = configId, title = "fresh")
                coordinator.acceptMetadata(MetadataEvent.DvrEntryUpdated(generation, GatewayDvrEntry(id = current.id, state = DvrEntryState.SCHEDULED)))
                GatewayResult.Ok(listOf(DvrConfiguration(configId, "private-name", "")))
            }
            gateway.updateBehavior = { _, _, _ ->
                if (reject) GatewayResult.ServerRejected("private-name private-id") else GatewayResult.Ok(Unit)
            }
            val result = coordinator.updateEntry(generation, current.id, DvrEntryUpdate())
            if (reject) {
                assertEquals(DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, false), result)
                assertEquals(null, (result as DvrMutationResult.Failed).serverMessage)
                assertFalse(result.toString().contains("private"))
            } else {
                assertTrue(result is DvrMutationResult.AcceptedButUnconfirmed)
            }
        }
    }

    @Test
    fun `retirement during registration distinguishes connection loss from unsent shutdown`() = runTest {
        val retirements: List<Pair<TvheadendFailure, (DvrMutationCoordinator) -> Unit>> = listOf(
            TvheadendFailure.CONNECTION_CHANGED to { it.bindGeneration(GatewayGeneration()) },
            TvheadendFailure.CONNECTION_CHANGED to { it.stopAdmission(connectionRetired = true) },
            TvheadendFailure.NOT_READY to { it.stopAdmission() },
        )
        for ((expected, retire) in retirements) {
            for (ready in listOf(false, true)) {
                val generation = GatewayGeneration()
                val gateway = MutationGateway()
                var calls = 0
                lateinit var coordinator: DvrMutationCoordinator
                coordinator = DvrMutationCoordinator(gateway, isSessionReady = {
                    retire(coordinator)
                    ready
                })
                gateway.scheduleBehavior = { _, _ ->
                    calls += 1
                    GatewayResult.Ok(DvrEntryId(7))
                }
                coordinator.bindGeneration(generation)
                coordinator.startAdmission(generation)

                assertEquals(
                    DvrMutationResult.Failed(expected, mayHaveApplied = false),
                    coordinator.scheduleEntry(generation, scheduleRequest()),
                )
                assertEquals(0, calls)
            }
        }
    }

    @Test
    fun `successful replies after replacement cannot apply proof for create or mutation`() = runTest {
        for (create in listOf(false, true)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val proofs = mutableListOf<Boolean>()
            val coordinator = DvrMutationCoordinator(gateway, onDvrAccessProof = { _, allowed -> proofs += allowed })
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            gateway.scheduleBehavior = { _, _ ->
                coordinator.bindGeneration(GatewayGeneration())
                GatewayResult.Ok(DvrEntryId(7))
            }
            gateway.updateBehavior = { _, _, _ ->
                coordinator.bindGeneration(GatewayGeneration())
                GatewayResult.Ok(Unit)
            }

            val result = if (create) {
                coordinator.scheduleEntry(generation, scheduleRequest())
            } else {
                coordinator.updateEntry(generation, DvrEntryId(7), DvrEntryUpdate())
            }
            assertEquals(DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, true), result)
            assertTrue(proofs.isEmpty())
        }
    }

    @Test
    fun `shutdown retires a dispatched create but rejects a subsequent unsent create as not ready`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        gateway.scheduleBehavior = { _, _ -> awaitCancellation() }
        val pending = async { coordinator.scheduleEntry(generation, scheduleRequest()) }
        runCurrent()
        coordinator.stopAdmission()

        assertEquals(DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, true), pending.await())
        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, false),
            coordinator.scheduleEntry(generation, scheduleRequest()),
        )
    }

    @Test
    fun `confirmation already delivered cannot succeed after its generation is replaced`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        lateinit var coordinator: DvrMutationCoordinator
        coordinator = DvrMutationCoordinator(gateway, onDvrAccessProof = { _, _ ->
            coordinator.acceptMetadata(dvrUpdated(generation, 7))
            coordinator.bindGeneration(GatewayGeneration())
        })
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, true),
            coordinator.updateEntry(generation, DvrEntryId(7), DvrEntryUpdate()),
        )
    }

    @Test
    fun `confirmation timeout racing generation replacement cannot return accepted but unconfirmed`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway, DvrMutationSettings(confirmationTimeout = 1.seconds))
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
        val pending = async { coordinator.updateEntry(generation, DvrEntryId(7), DvrEntryUpdate()) }
        runCurrent()
        launch {
            delay(1.seconds)
            coordinator.bindGeneration(GatewayGeneration())
        }
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, true), pending.await())
    }

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

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.NOT_READY, mayHaveApplied = false),
            coordinator.scheduleEntry(generation, scheduleRequest()),
        )
        assertEquals(0, commandCount)

        ready = true
        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.ACCESS_DENIED, mayHaveApplied = false),
            coordinator.scheduleEntry(generation, scheduleRequest()),
        )
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

        val result = async { coordinator.scheduleEntry(generation, scheduleRequest()) }
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
            GatewayResult.ServerRejected()
        }

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, mayHaveApplied = false),
            coordinator.scheduleEntry(generation, scheduleRequest()),
        )

        gateway.scheduleBehavior = { _, _ -> GatewayResult.Ok(DvrEntryId(8)) }
        val timed = async { coordinator.scheduleEntry(generation, scheduleRequest()) }
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
            GatewayResult.AccessDenied to
                DvrMutationResult.Failed(TvheadendFailure.ACCESS_DENIED, mayHaveApplied = false),
            GatewayResult.ServerRejected(" Access denied\n") to
                DvrMutationResult.Failed(TvheadendFailure.SERVER_REJECTED, mayHaveApplied = false),
            GatewayResult.MalformedReply to
                DvrMutationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true),
            GatewayResult.ConnectionLimit to
                DvrMutationResult.Failed(TvheadendFailure.CONNECTION_LIMIT, mayHaveApplied = false),
            GatewayResult.Timeout to DvrMutationResult.Failed(TvheadendFailure.TIMEOUT, mayHaveApplied = true),
            GatewayResult.TransportUnavailable to
                DvrMutationResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, mayHaveApplied = true),
            GatewayResult.NotSupported to
                DvrMutationResult.Failed(TvheadendFailure.NOT_SUPPORTED, mayHaveApplied = false),
            GatewayResult.ObservationExpired to
                DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false),
        )
        cases.forEach { (gatewayResult, expected) ->
            gateway.scheduleBehavior = { _, _ -> gatewayResult }
            val actual = coordinator.scheduleEntry(generation, scheduleRequest()) as DvrMutationResult.Failed
            assertEquals(expected, actual)
            assertEquals(if (gatewayResult is GatewayResult.ServerRejected) "Access denied" else null,
                actual.serverMessage)
        }
        assertEquals(listOf(false), proofs)

        val cancellation = CancellationException("fixed cancellation")
        gateway.updateBehavior = { _, _, _ -> throw cancellation }
        var caught: CancellationException? = null
        try {
            coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate())
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
    }

    @Test
    fun `mutation uncertainty follows dispatch and refusal even when the generation retires`() = runTest {
        val replies = listOf(
            GatewayResult.ServerRejected() to false,
            GatewayResult.ConnectionLimit to false,
            GatewayResult.NotSupported to false,
            GatewayResult.ObservationExpired to false,
            GatewayResult.MalformedReply to true,
            GatewayResult.Timeout to true,
            GatewayResult.TransportUnavailable to true,
        )
        for ((reply, mayHaveApplied) in replies) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val coordinator = DvrMutationCoordinator(gateway)
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            gateway.updateBehavior = { _, _, _ ->
                coordinator.stopAdmission()
                reply
            }

            assertEquals(
                DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied),
                coordinator.updateEntry(generation, DvrEntryId(7), DvrEntryUpdate()),
            )
        }
    }

    @Test
    fun `access denial after dispatch and retirement cannot update proof and did not apply`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val proofs = mutableListOf<Boolean>()
        val coordinator = DvrMutationCoordinator(
            gateway = gateway,
            onDvrAccessProof = { _, allowed -> proofs += allowed },
        )
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        var calls = 0
        gateway.updateBehavior = { _, _, _ ->
            calls += 1
            coordinator.stopAdmission()
            GatewayResult.AccessDenied
        }

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = false),
            coordinator.updateEntry(generation, DvrEntryId(7), DvrEntryUpdate()),
        )
        assertEquals(1, calls)
        assertEquals(emptyList<Boolean>(), proofs)
    }

    @Test
    fun `malformed add reply is reported without retrying an uncertain command`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        var calls = 0
        gateway.scheduleBehavior = { _, _ ->
            calls += 1
            GatewayResult.MalformedReply
        }

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.MALFORMED_RESPONSE, mayHaveApplied = true),
            coordinator.scheduleEntry(generation, scheduleRequest()),
        )
        coordinator.acceptMetadata(dvrAdded(generation, 7))
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(1, calls)
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

        val result = async {
            coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate())
        }
        commandStarted.await()
        coordinator.stopAdmission(connectionRetired = true)
        coordinator.acceptMetadata(dvrUpdated(generation, 1))
        runCurrent()

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true),
            result.await(),
        )
        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = false),
            coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate()),
        )
    }

    @Test
    fun `accepted mutation may have applied on retirement while queued stale mutation was never sent`() = runTest {
        val generationA = GatewayGeneration()
        val generationB = GatewayGeneration()
        val gateway = MutationGateway()
        val dispatched = mutableListOf<GatewayGeneration>()
        val coordinator = DvrMutationCoordinator(gateway)
        coordinator.bindGeneration(generationA)
        coordinator.startAdmission(generationA)
        gateway.updateBehavior = { generation, _, _ ->
            dispatched += generation
            GatewayResult.Ok(Unit)
        }

        val inFlight = async {
            coordinator.updateEntry(generationA, DvrEntryId(7), DvrEntryUpdate(title = "first"))
        }
        runCurrent()
        val queued = async {
            coordinator.updateEntry(generationA, DvrEntryId(7), DvrEntryUpdate(title = "second"))
        }
        runCurrent()
        assertEquals(listOf(generationA), dispatched)

        coordinator.stopAdmission()
        coordinator.bindGeneration(generationB)
        coordinator.startAdmission(generationB)
        runCurrent()

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true),
            inFlight.await(),
        )
        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, mayHaveApplied = false),
            queued.await(),
        )
        assertEquals(listOf(generationA), dispatched)
    }

    @Test
    fun `acceptance before confirmation registration retirement may have applied`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        lateinit var coordinator: DvrMutationCoordinator
        coordinator = DvrMutationCoordinator(
            gateway = gateway,
            onDvrAccessProof = { _, allowed ->
                assertTrue(allowed)
                coordinator.stopAdmission()
            },
        )
        gateway.scheduleBehavior = { _, _ -> GatewayResult.Ok(DvrEntryId(7)) }
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)

        assertEquals(
            DvrMutationResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied = true),
            coordinator.scheduleEntry(generation, scheduleRequest()),
        )
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

        val first = async {
            coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate(title = "first"))
        }
        runCurrent()
        val second = async {
            coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate(title = "second"))
        }
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
    fun `delete confirms a full file-missing update before or after acknowledgement`() = runTest {
        for (beforeReply in listOf(false, true)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val coordinator = DvrMutationCoordinator(gateway)
            val update = dvrUpdated(generation, 1, DvrEntryState.COMPLETED,
                failure = GatewayDvrFailure.FILE_MISSING)
            gateway.deleteBehavior = { _, _ ->
                if (beforeReply) coordinator.acceptMetadata(update)
                GatewayResult.Ok(Unit)
            }
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            val deleted = async { coordinator.deleteEntry(generation, DvrEntryId(1)) }
            runCurrent()
            if (!beforeReply) {
                assertFalse(deleted.isCompleted)
                coordinator.acceptMetadata(update)
                runCurrent()
            }
            assertTrue(deleted.isCompleted)
            assertTrue(deleted.await() is DvrMutationResult.Confirmed)
        }
    }

    @Test
    fun `delete ignores stats-only file-missing and unrelated full updates`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway().apply { deleteBehavior = { _, _ -> GatewayResult.Ok(Unit) } }
        val coordinator = DvrMutationCoordinator(gateway)
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)
        // An event observed before command registration cannot confirm a later command.
        coordinator.acceptMetadata(dvrUpdated(generation, 1, failure = GatewayDvrFailure.FILE_MISSING))
        val deleted = async { coordinator.deleteEntry(generation, DvrEntryId(1)) }
        runCurrent()
        coordinator.acceptMetadata(dvrUpdated(generation, 1, DvrEntryState.COMPLETED,
            true, GatewayDvrFailure.FILE_MISSING))
        coordinator.acceptMetadata(dvrUpdated(generation, 2, failure = GatewayDvrFailure.FILE_MISSING))
        coordinator.acceptMetadata(dvrUpdated(GatewayGeneration(), 1, failure = GatewayDvrFailure.FILE_MISSING))
        for (failure in listOf(null, GatewayDvrFailure.NONE, GatewayDvrFailure.ABORTED, GatewayDvrFailure.PRESENT)) {
            coordinator.acceptMetadata(dvrUpdated(generation, 1, DvrEntryState.COMPLETED, failure = failure))
        }
        runCurrent()
        assertFalse(deleted.isCompleted)
        advanceTimeBy(6.seconds)
        assertTrue(deleted.await() is DvrMutationResult.AcceptedButUnconfirmed)
    }

    @Test
    fun `stats-only and still-recording updates never confirm a pending stop or cancel`() = runTest {
        for (cancel in listOf(false, true)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val coordinator = DvrMutationCoordinator(gateway)
            gateway.stopBehavior = { _, _ -> GatewayResult.Ok(Unit) }
            gateway.cancelBehavior = { _, _ -> GatewayResult.Ok(Unit) }
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            suspend fun mutate() = if (cancel) {
                coordinator.cancelEntry(generation, DvrEntryId(1))
            } else {
                coordinator.stopEntry(generation, DvrEntryId(1))
            }

            val unconfirmed = async { mutate() }
            runCurrent()
            coordinator.acceptMetadata(
                dvrUpdated(generation, 1, DvrEntryState.RECORDING, statsOnly = true),
            )
            coordinator.acceptMetadata(
                dvrUpdated(generation, 1, DvrEntryState.COMPLETED, statsOnly = true),
            )
            coordinator.acceptMetadata(dvrUpdated(generation, 1, DvrEntryState.RECORDING))
            coordinator.acceptMetadata(dvrUpdated(generation, 1, DvrEntryState.RECORDING_ERROR))
            coordinator.acceptMetadata(dvrUpdated(generation, 2, DvrEntryState.COMPLETED))
            runCurrent()
            assertFalse(unconfirmed.isCompleted)
            advanceTimeBy(5.seconds + 1.seconds)
            assertTrue(unconfirmed.await() is DvrMutationResult.AcceptedButUnconfirmed)

            for (ended in listOf(DvrEntryState.COMPLETED, DvrEntryState.MISSED)) {
                val confirmed = async { mutate() }
                runCurrent()
                coordinator.acceptMetadata(dvrUpdated(generation, 1, ended))
                runCurrent()
                assertTrue(confirmed.await() is DvrMutationResult.Confirmed, ended.toString())
            }
        }
    }

    @Test
    fun `one ended update confirms only the operation pending when it arrived`() = runTest {
        data class Case(
            val cancel: Boolean,
            val followUp: ((GatewayGeneration) -> MetadataEvent)?,
        )
        val cases = listOf(
            Case(cancel = false, followUp = null),
            Case(cancel = false, followUp = { dvrUpdated(it, 1, DvrEntryState.COMPLETED) }),
            Case(cancel = true, followUp = null),
            Case(cancel = true, followUp = { MetadataEvent.DvrEntryDeleted(it, DvrEntryId(1)) }),
        )
        for (case in cases) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            val coordinator = DvrMutationCoordinator(gateway)
            gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
            gateway.stopBehavior = { _, _ -> GatewayResult.Ok(Unit) }
            gateway.cancelBehavior = { _, _ -> GatewayResult.Ok(Unit) }
            coordinator.bindGeneration(generation)
            coordinator.startAdmission(generation)
            val results = mutableListOf<DvrMutationResult<Unit>>()

            // The unconfined chain resumes inline inside acceptMetadata and starts its next mutation.
            val chain = launch(UnconfinedTestDispatcher(testScheduler)) {
                results += coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate(title = "title"))
                results += if (case.cancel) {
                    coordinator.cancelEntry(generation, DvrEntryId(1))
                } else {
                    coordinator.stopEntry(generation, DvrEntryId(1))
                }
            }
            runCurrent()
            coordinator.acceptMetadata(dvrUpdated(generation, 1, DvrEntryState.COMPLETED))

            assertEquals(1, results.size, case.toString())
            assertTrue(results.single() is DvrMutationResult.Confirmed)
            assertTrue(chain.isActive)
            case.followUp?.let { coordinator.acceptMetadata(it(generation)) }
            advanceTimeBy(5.seconds + 1.seconds)
            chain.join()
            assertTrue(
                if (case.followUp == null) {
                    results.last() is DvrMutationResult.AcceptedButUnconfirmed
                } else {
                    results.last() is DvrMutationResult.Confirmed
                },
                case.toString(),
            )
        }
    }

    @Test
    fun `stats-only updates never confirm a pending entry update`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)

        val result = async { coordinator.updateEntry(generation, DvrEntryId(1), DvrEntryUpdate(title = "title")) }
        runCurrent()
        coordinator.acceptMetadata(
            dvrUpdated(generation, 1, DvrEntryState.RECORDING, statsOnly = true),
        )
        runCurrent()
        assertFalse(result.isCompleted)
        coordinator.acceptMetadata(dvrUpdated(generation, 1, DvrEntryState.RECORDING))
        runCurrent()
        assertTrue(result.await() is DvrMutationResult.Confirmed)
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

        val result = async {
            coordinator.createAutorecRule(generation, AutorecRuleCreate("title"))
        }
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

    @Test
    fun `all-channel timerec metadata confirms matching creation`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        val acknowledgement = CompletableDeferred<GatewayResult<TimerecRuleId>>()
        val coordinator = DvrMutationCoordinator(gateway)
        gateway.createTimerecBehavior = { _, request ->
            assertEquals(null, request.channel)
            acknowledgement.await()
        }
        coordinator.bindGeneration(generation)
        coordinator.startAdmission(generation)

        val result = async {
            coordinator.createTimerecRule(generation, TimerecRuleCreate("title"))
        }
        runCurrent()
        coordinator.acceptMetadata(
            MetadataEvent.TimerecRuleAdded(
                generation,
                GatewayTimerecRule(
                    id = TimerecRuleId("rule"),
                    enabled = true,
                    title = "title",
                    channelId = null,
                    startMinutesSinceMidnight = null,
                    stopMinutesSinceMidnight = null,
                ),
            ),
        )
        acknowledgement.complete(GatewayResult.Ok(TimerecRuleId("rule")))
        runCurrent()

        val confirmed = result.await() as DvrMutationResult.Confirmed
        assertEquals(TimerecRuleId("rule"), confirmed.value)
    }
}

private fun scheduleRequest(): DvrScheduleRequest =
    DvrScheduleRequest(DvrSchedule.Programme(EventId(1)))

private fun dvrAdded(generation: GatewayGeneration, id: Long): MetadataEvent.DvrEntryAdded =
    MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(DvrEntryId(id)))

private fun dvrUpdated(
    generation: GatewayGeneration,
    id: Long,
    state: DvrEntryState? = null,
    statsOnly: Boolean = false,
    failure: GatewayDvrFailure? = null,
): MetadataEvent = if (statsOnly) {
    MetadataEvent.DvrEntryStatsUpdated(generation, GatewayDvrStats(DvrEntryId(id), null, checkNotNull(state), failure = failure))
} else {
    MetadataEvent.DvrEntryUpdated(
        generation,
        GatewayDvrEntry(DvrEntryId(id), state = state, failure = failure),
    )
}

internal class MutationGateway : ProtocolGateway {
    internal val sentConfigNames = mutableListOf<String?>()
    internal var configsBehavior: suspend (GatewayGeneration) -> GatewayResult<List<DvrConfiguration>> = {
        GatewayResult.NotSupported
    }
    internal var resetResumeBehavior: suspend (GatewayGeneration, DvrEntryId) -> GatewayResult<Unit> =
        { _, _ -> GatewayResult.NotSupported }

    override suspend fun resetDvrResume(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<Unit> = resetResumeBehavior(generation, id)

    internal var watchStateBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
        Boolean,
    ) -> GatewayResult<Unit> = { _, _, _ -> GatewayResult.NotSupported }

    override suspend fun updateDvrWatchState(
        generation: GatewayGeneration,
        id: DvrEntryId,
        watched: Boolean,
    ): GatewayResult<Unit> = watchStateBehavior(generation, id, watched)

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
    internal var progressBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
        DvrPlaybackProgress,
    ) -> GatewayResult<Unit> = { _, _, _ -> GatewayResult.NotSupported }
    internal var cutpointsBehavior: suspend (
        GatewayGeneration,
        DvrEntryId,
    ) -> GatewayResult<List<DvrCutpoint>> = { _, _ -> GatewayResult.NotSupported }

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
    ): GatewayResult<List<DvrConfiguration>> = configsBehavior(generation)

    override suspend fun getDiskSpace(
        generation: GatewayGeneration,
    ): GatewayResult<DvrDiskSpace> = GatewayResult.NotSupported

    override suspend fun getDvrCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): GatewayResult<List<DvrCutpoint>> = cutpointsBehavior(generation, id)

    override suspend fun scheduleDvrEntry(
        generation: GatewayGeneration,
        request: DvrScheduleRequest,
    ): GatewayResult<DvrEntryId> = scheduleBehavior(generation, request)

    override suspend fun updateDvrEntry(
        generation: GatewayGeneration,
        id: DvrEntryId,
        update: DvrEntryUpdate,
        configName: String?,
    ): GatewayResult<Unit> {
        sentConfigNames += configName
        return updateBehavior(generation, id, update)
    }

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

    override suspend fun reportDvrProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): GatewayResult<Unit> = progressBehavior(generation, id, progress)

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
        SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)

    override suspend fun skipSubscription(
        generation: GatewayGeneration,
        id: SubscriptionId,
        target: SubscriptionSeekTarget,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)

    override suspend fun unsubscribe(
        generation: GatewayGeneration,
        id: SubscriptionId,
    ): SubscriptionOperationResult<Unit> = SubscriptionOperationResult.Failed(TvheadendFailure.NOT_SUPPORTED)
}
