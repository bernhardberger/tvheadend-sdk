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
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
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

internal class DvrResetResumeTest {
    @Test
    fun `all states and absent entries reset without optimistic mutation or confirmation wait`() = runTest {
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
        val requests = mutableListOf<DvrEntryId>()
        gateway.resetResumeBehavior = { candidate, id ->
            assertSame(generation, candidate)
            requests += id
            GatewayResult.Ok(Unit)
        }
        for (index in 0..states.size) {
            assertSame(DvrProgressResult.Accepted, metadata.dvrRepository.resetResume(proof, DvrEntryId(index.toLong())))
            assertSame(observation, metadata.observation.value)
        }
        assertEquals(10, requests.size)
        assertEquals(4L, observation.dvrEntry(DvrEntryId(0))?.playCount)
        assertEquals(60.seconds, observation.dvrEntry(DvrEntryId(0))?.playPosition)
        metadata.bindGeneration(GatewayGeneration())
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            metadata.dvrRepository.resetResume(proof, DvrEntryId(0)))
        assertEquals(10, requests.size)
    }

    @Test
    fun `reset shares progress failure uncertainty and retirement classification`() = runTest {
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
            for ((outcome, failure, uncertain) in outcomes) {
                val generation = GatewayGeneration()
                val gateway = MutationGateway()
                val coordinator = DvrProgressCoordinator(gateway)
                coordinator.bindGeneration(generation, 27)
                coordinator.startAdmission(generation)
                gateway.resetResumeBehavior = { _, _ ->
                    if (retire) coordinator.stopAdmission(connectionRetired = true)
                    outcome
                }
                val expected = when {
                    retire -> DvrProgressResult.Failed(TvheadendFailure.CONNECTION_CHANGED, uncertain)
                    failure != null -> DvrProgressResult.Failed(failure, uncertain)
                    else -> DvrProgressResult.Accepted
                }
                assertEquals(expected, coordinator.resetResume(generation, DvrEntryId(1)))
            }
        }
    }

    @Test
    fun `reset refusal does not poison progress but unsupported capability latches`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var unsupported = 0
        val coordinator = DvrProgressCoordinator(gateway, onProgressNotSupported = { unsupported++ })
        coordinator.bindGeneration(generation, 27)
        coordinator.startAdmission(generation)
        gateway.resetResumeBehavior = { _, _ -> GatewayResult.ServerRejected }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.SERVER_REJECTED, false),
            coordinator.resetResume(generation, DvrEntryId(1)))
        var progressCalls = 0
        gateway.progressBehavior = { _, _, _ -> progressCalls++; GatewayResult.Ok(Unit) }
        assertSame(DvrProgressResult.Accepted,
            coordinator.reportProgress(generation, DvrEntryId(2), DvrPlaybackProgress.checkpoint(2.seconds)))
        assertEquals(0, unsupported)
        gateway.resetResumeBehavior = { _, _ -> GatewayResult.NotSupported }
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, false),
            coordinator.resetResume(generation, DvrEntryId(1)))
        assertEquals(1, unsupported)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, false),
            coordinator.reportProgress(generation, DvrEntryId(2), DvrPlaybackProgress.checkpoint(2.seconds)))
        assertEquals(1, progressCalls)
    }

    @Test
    fun `reset cancellation before dispatch and after reply propagates`() = runTest {
        for (cancelBefore in listOf(true, false)) {
            val generation = GatewayGeneration()
            val gateway = MutationGateway()
            var commands = 0
            gateway.resetResumeBehavior = { _, _ ->
                commands++
                currentCoroutineContext().cancel()
                GatewayResult.Ok(Unit)
            }
            val coordinator = DvrProgressCoordinator(gateway)
            coordinator.bindGeneration(generation, 27)
            coordinator.startAdmission(generation)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                if (cancelBefore) currentCoroutineContext().cancel()
                coordinator.resetResume(generation, DvrEntryId(1))
                error("Cancelled reset command returned a result")
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(if (cancelBefore) 0 else 1, commands)
        }
    }

    @Test
    fun `unsent reset enforces observation readiness and protocol fences`() = runTest {
        val generation = GatewayGeneration()
        val gateway = MutationGateway()
        var dispatched = false
        gateway.resetResumeBehavior = { _, _ -> dispatched = true; GatewayResult.Ok(Unit) }
        val coordinator = DvrProgressCoordinator(gateway)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            coordinator.resetResume(generation, DvrEntryId(1)))
        coordinator.bindGeneration(generation, 26)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_READY, false),
            coordinator.resetResume(generation, DvrEntryId(1)))
        coordinator.startAdmission(generation)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.NOT_SUPPORTED, false),
            coordinator.resetResume(generation, DvrEntryId(1)))
        coordinator.bindGeneration(GatewayGeneration(), 27)
        assertEquals(DvrProgressResult.Failed(TvheadendFailure.OBSERVATION_EXPIRED, false),
            coordinator.resetResume(generation, DvrEntryId(1)))
        assertTrue(!dispatched)
    }
}
