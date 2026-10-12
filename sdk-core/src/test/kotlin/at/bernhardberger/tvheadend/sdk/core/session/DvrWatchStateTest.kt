@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrUpdateProvenance
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

internal class DvrWatchStateTest {
    @Test
    fun `every state and absent entries dispatch without optimistic mutation or confirmation wait`() = runTest {
        val gateway = MutationGateway()
        val generation = GatewayGeneration()
        val coordinator = DvrProgressCoordinator(gateway)
        coordinator.bindGeneration(generation, 27)
        coordinator.startAdmission(generation)
        val metadata = PhaseOneSessionMetadata(progressCommands = coordinator)
        metadata.bindGeneration(generation)
        val states = listOf(
            DvrEntryState.SCHEDULED, DvrEntryState.RECORDING, DvrEntryState.RECORDING_ERROR,
            DvrEntryState.COMPLETED, DvrEntryState.COMPLETED_ERROR, DvrEntryState.FILE_MISSING,
            DvrEntryState.MISSED, DvrEntryState.INVALID, DvrEntryState.UNKNOWN,
        )
        states.forEachIndexed { index, state ->
            metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
                id = DvrEntryId(index.toLong()), state = state, playCount = 4, playPosition = 60.seconds,
            )))
        }
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.publishSessionState(
            SessionState.Ready(ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN)),
            RecordingProgressCapability.UNKNOWN, generation,
        )
        val observation = metadata.observation.value
        val proof = checkNotNull(observation.currentSession)
        assertEquals(states, states.indices.map { observation.dvrEntry(DvrEntryId(it.toLong()))?.state })
        val requests = mutableListOf<Pair<DvrEntryId, Boolean>>()
        gateway.watchStateBehavior = { candidate, id, watched ->
            assertSame(generation, candidate)
            requests += id to watched
            GatewayResult.Ok(Unit)
        }
        // Includes an id absent from the cache. Successful replies emit no metadata.
        for (index in 0..states.size) {
            for (watched in listOf(true, false)) {
                assertSame(DvrProgressResult.Accepted,
                    metadata.dvrRepository.updateWatchState(proof, DvrEntryId(index.toLong()), watched))
                assertSame(observation, metadata.observation.value)
            }
        }
        assertEquals(20, requests.size)
        assertEquals(listOf(true, false), requests.takeLast(2).map { it.second })
        metadata.acceptMetadata(MetadataEvent.DvrEntryUpdated(generation, GatewayDvrEntry(
            id = DvrEntryId(0), playCount = 0, playPosition = 0.seconds,
        ), GatewayDvrUpdateProvenance.FULL))
        assertEquals(0L, metadata.observation.value.dvrEntry(DvrEntryId(0))?.playCount)
        assertEquals(0.seconds, metadata.observation.value.dvrEntry(DvrEntryId(0))?.playPosition)
        assertEquals(4L, observation.dvrEntry(DvrEntryId(0))?.playCount)
        metadata.bindGeneration(GatewayGeneration())
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            metadata.dvrRepository.updateWatchState(proof, DvrEntryId(0), true))
        assertEquals(20, requests.size)
    }

    @Test
    fun `watch failures preserve dispatch uncertainty even across retirement and propagate cancellation`() = runTest {
        val outcomes = listOf(
            Triple(GatewayResult.Ok(Unit), null, true),
            Triple(GatewayResult.ServerRejected, TvheadendFailure.SERVER_REJECTED, false),
            Triple(GatewayResult.AccessDenied, TvheadendFailure.ACCESS_DENIED, false),
            Triple(GatewayResult.NotSupported, TvheadendFailure.NOT_SUPPORTED, false),
            Triple(GatewayResult.ConnectionLimit, TvheadendFailure.CONNECTION_LIMIT, false),
            Triple(GatewayResult.Timeout, TvheadendFailure.TIMEOUT, true),
            Triple(GatewayResult.MalformedReply, TvheadendFailure.MALFORMED_RESPONSE, true),
            Triple(GatewayResult.TransportUnavailable, TvheadendFailure.TRANSPORT_UNAVAILABLE, true),
        )
        for (retire in listOf(false, true)) {
            for ((outcome, failure, mayHaveApplied) in outcomes) {
                val generation = GatewayGeneration()
                val gateway = MutationGateway()
                val coordinator = DvrProgressCoordinator(gateway)
                coordinator.bindGeneration(generation, 27)
                coordinator.startAdmission(generation)
                gateway.watchStateBehavior = { _, _, _ ->
                    if (retire) coordinator.stopAdmission(connectionRetired = true)
                    outcome
                }
                val expected = when {
                    retire -> DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, mayHaveApplied)
                    failure != null -> DvrProgressResult.Failed(failure, mayHaveApplied)
                    else -> DvrProgressResult.Accepted
                }
                assertEquals(expected, coordinator.updateWatchState(generation, DvrEntryId(1), true))
            }
        }
        val gateway = MutationGateway()
        val coordinator = DvrProgressCoordinator(gateway)
        val generation = GatewayGeneration()
        coordinator.bindGeneration(generation, 27)
        coordinator.startAdmission(generation)
        val cancellation = CancellationException("cancelled")
        gateway.watchStateBehavior = { _, _, _ -> throw cancellation }
        var caught: CancellationException? = null
        try {
            coordinator.updateWatchState(generation, DvrEntryId(1), false)
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(cancellation, caught)
        gateway.watchStateBehavior = { _, _, _ -> error("private transport detail") }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.TRANSPORT_UNAVAILABLE, true),
            coordinator.updateWatchState(generation, DvrEntryId(1), false))
    }

    @Test
    fun `per entry refusal cannot poison later progress but real unsupported capability does`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var unsupported = 0
        val proofs = mutableListOf<Boolean>()
        val coordinator = DvrProgressCoordinator(gateway,
            onProgressNotSupported = { unsupported++ },
            onDvrAccessProof = { _, allowed -> proofs += allowed })
        coordinator.bindGeneration(generation, 27)
        coordinator.startAdmission(generation)
        gateway.watchStateBehavior = { _, _, _ -> GatewayResult.ServerRejected }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.SERVER_REJECTED, false),
            coordinator.updateWatchState(generation, DvrEntryId(1), false))
        var progressCalls = 0
        gateway.progressBehavior = { _, id, _ ->
            assertEquals(DvrEntryId(2), id)
            progressCalls++
            GatewayResult.Ok(Unit)
        }
        assertSame(DvrProgressResult.Accepted,
            coordinator.reportProgress(generation, DvrEntryId(2), DvrPlaybackProgress.checkpoint(2.seconds)))
        assertEquals(0, unsupported)
        gateway.watchStateBehavior = { _, _, _ -> GatewayResult.NotSupported }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, false),
            coordinator.updateWatchState(generation, DvrEntryId(1), true))
        assertEquals(1, unsupported)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, false),
            coordinator.reportProgress(generation, DvrEntryId(2), DvrPlaybackProgress.checkpoint(2.seconds)))
        assertEquals(1, progressCalls)
        assertEquals(listOf(true), proofs)
    }

    @Test
    fun `cancellation before dispatch and after a reply propagates without a result`() = runTest {
        for (cancelBefore in listOf(true, false)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            var commands = 0
            gateway.watchStateBehavior = { _, _, _ ->
                commands++
                currentCoroutineContext().cancel()
                GatewayResult.Ok(Unit)
            }
            val coordinator = DvrProgressCoordinator(gateway)
            coordinator.bindGeneration(generation, 27)
            coordinator.startAdmission(generation)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                if (cancelBefore) currentCoroutineContext().cancel()
                coordinator.updateWatchState(generation, DvrEntryId(1), true)
                error("Cancelled watch command returned a result")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(if (cancelBefore) 0 else 1, commands)
        }
    }

    @Test
    fun `unsent watch calls enforce observation readiness and protocol fences`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var dispatched = false
        gateway.watchStateBehavior = { _, _, _ -> dispatched = true; GatewayResult.Ok(Unit) }
        val coordinator = DvrProgressCoordinator(gateway)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            coordinator.updateWatchState(generation, DvrEntryId(1), true))
        coordinator.bindGeneration(generation, 26)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_READY, false),
            coordinator.updateWatchState(generation, DvrEntryId(1), true))
        coordinator.startAdmission(generation)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, false),
            coordinator.updateWatchState(generation, DvrEntryId(1), true))
        coordinator.bindGeneration(GatewayGeneration(), 27)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            coordinator.updateWatchState(generation, DvrEntryId(1), true))
        assertTrue(!dispatched)
    }
}
