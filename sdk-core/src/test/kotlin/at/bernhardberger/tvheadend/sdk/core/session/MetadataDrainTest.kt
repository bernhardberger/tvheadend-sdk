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
    fun `a burst that is already buffered is reduced in order and published once`() = runTest {
        val recorder = RecordingMetadata()
        val burst = (1L..5L).map { id -> MetadataEvent.EventAdded(generation, epgEvent(id)) }

        drainMetadata(flow { burst.forEach { emit(it) } }, recorder)

        assertEquals(burst.map { "accept:${it.eventId()}" } + "flush", recorder.calls)
    }

    @Test
    fun `a continuous stream still publishes at the burst limit`() = runTest {
        val recorder = RecordingMetadata()
        val stream = (1L..7L).map { id -> MetadataEvent.EventAdded(generation, epgEvent(id)) }

        drainMetadata(flow { stream.forEach { emit(it) } }, recorder, burstLimit = 3)

        assertEquals(
            listOf("accept:1", "accept:2", "accept:3", "flush") +
                listOf("accept:4", "accept:5", "accept:6", "flush") +
                listOf("accept:7", "flush"),
            recorder.calls,
        )
    }

    @Test
    fun `events emitted after subscription are not missed and each idle event flushes`() = runTest {
        val recorder = RecordingMetadata()
        val source = MutableSharedFlow<MetadataEvent>()
        val worker = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            drainMetadata(source, recorder)
        }

        source.emit(MetadataEvent.EventAdded(generation, epgEvent(1)))
        runCurrent()
        source.emit(MetadataEvent.EventAdded(generation, epgEvent(2)))
        runCurrent()

        assertEquals(listOf("accept:1", "flush", "accept:2", "flush"), recorder.calls)
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
    ) : SessionMetadata by delegate {
        val calls = mutableListOf<String>()

        override fun acceptMetadataDeferringEpg(event: MetadataEvent) {
            calls += "accept:${event.eventId()}"
            delegate.acceptMetadataDeferringEpg(event)
        }

        override fun flushDeferredMetadata() {
            calls += "flush"
            delegate.flushDeferredMetadata()
        }
    }
}

private fun MetadataEvent.eventId(): Long = (this as MetadataEvent.EventAdded).event.id.value
