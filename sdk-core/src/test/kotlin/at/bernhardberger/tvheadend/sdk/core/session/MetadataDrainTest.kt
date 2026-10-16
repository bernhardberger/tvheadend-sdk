@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class MetadataDrainTest {
    private val generation = GatewayGeneration()

    @Test
    fun `outside cancellation drops the remainder of a batch`() = runTest {
        lateinit var worker: kotlinx.coroutines.Job
        val recorder = RecordingMetadata(afterBatch = { worker.cancel() })
        worker = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            drainMetadata(flow {
                emit((1L..5L).map { MetadataEvent.EventAdded(generation, epgEvent(it)) })
            }, recorder, burstLimit = 2)
        }
        worker.start()
        worker.join()
        assertEquals(listOf("batch:1,2", "flush"), recorder.calls)
        org.junit.jupiter.api.Assertions.assertTrue(worker.isCancelled)
    }

    @Test
    fun `drained lists end at sync markers and never span generations`() = runTest {
        val recorder = RecordingMetadata()
        val next = GatewayGeneration()
        drainMetadata(flow {
            emit(listOf(
                MetadataEvent.EventAdded(generation, epgEvent(1)),
                MetadataEvent.InitialSyncCompleted(generation),
                MetadataEvent.EventAdded(generation, epgEvent(2)),
                MetadataEvent.EventAdded(next, epgEvent(3)),
            ))
        }, recorder)
        assertEquals(listOf("batch:1,sync", "flush", "batch:2", "flush", "batch:3", "flush"), recorder.calls)
    }

    @Test
    fun `upstream failure propagates after the accepted batch`() = runTest {
        val recorder = RecordingMetadata()
        val failure = IllegalStateException("Synthetic failure")
        val result = runCatching {
            drainMetadata(flow {
                emit(listOf(MetadataEvent.EventAdded(generation, epgEvent(1))))
                throw failure
            }, recorder)
        }
        assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals(listOf("batch:1", "flush"), recorder.calls)
    }

    @Test
    fun `a burst that is already buffered is reduced in order and published once`() = runTest {
        val recorder = RecordingMetadata()
        val burst = (1L..5L).map { id -> MetadataEvent.EventAdded(generation, epgEvent(id)) }

        drainMetadata(flow { emit(burst) }, recorder)

        assertEquals(listOf("batch:1,2,3,4,5", "flush"), recorder.calls)
    }

    @Test
    fun `a continuous stream still publishes at the burst limit`() = runTest {
        val recorder = RecordingMetadata()
        val stream = (1L..7L).map { id -> MetadataEvent.EventAdded(generation, epgEvent(id)) }

        drainMetadata(flow { emit(stream) }, recorder, burstLimit = 3)

        assertEquals(
            listOf("batch:1,2,3", "flush") +
                listOf("batch:4,5,6", "flush") +
                listOf("batch:7", "flush"),
            recorder.calls,
        )
    }

    @Test
    fun `events emitted after subscription are not missed and each idle event flushes`() = runTest {
        val recorder = RecordingMetadata()
        val source = MutableSharedFlow<List<MetadataEvent>>()
        val worker = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            drainMetadata(source, recorder)
        }

        source.emit(listOf(MetadataEvent.EventAdded(generation, epgEvent(1))))
        runCurrent()
        source.emit(listOf(MetadataEvent.EventAdded(generation, epgEvent(2))))
        runCurrent()

        assertEquals(listOf("batch:1", "flush", "batch:2", "flush"), recorder.calls)
        worker.cancel()
    }

    private fun epgEvent(id: Long): GatewayEpgEvent = GatewayEpgEvent(
        id = EventId(id),
        channelId = ChannelId(1),
        start = Instant.fromEpochSeconds(0),
        stop = Instant.fromEpochSeconds(10),
        title = null,
    )

    private class RecordingMetadata(
        private val delegate: PhaseOneSessionMetadata = PhaseOneSessionMetadata(),
        private val afterBatch: () -> Unit = {},
    ) : SessionMetadata by delegate {
        val calls = mutableListOf<String>()

        override fun acceptMetadataBatch(events: List<MetadataEvent>) {
            calls += "batch:${events.joinToString(",") { if (it is MetadataEvent.InitialSyncCompleted) "sync" else it.eventId().toString() }}"
            delegate.acceptMetadataBatch(events)
            afterBatch()
        }

        override fun flushDeferredMetadata() {
            calls += "flush"
            delegate.flushDeferredMetadata()
        }
    }
}

private fun MetadataEvent.eventId(): Long = (this as MetadataEvent.EventAdded).event.id.value
