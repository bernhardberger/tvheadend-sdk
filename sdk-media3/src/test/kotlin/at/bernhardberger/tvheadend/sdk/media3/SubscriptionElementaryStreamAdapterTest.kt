@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubscriptionElementaryStreamAdapterTest {
    @Test
    fun `packet allocates exact payload once and copies once`() {
        val reader = RecordingReader()
        val binary = CountingBinary(byteArrayOf(1, 2, 3))
        val allocatedSizes = mutableListOf<Int>()
        val adapter = SubscriptionElementaryStreamAdapter(
            reader = reader,
            output = EmptyExtractorOutput,
            firstTrackId = 7,
            payloadAllocator = { size ->
                allocatedSizes += size
                ByteArray(size)
            },
        )

        adapter.accept(packet(binary, MuxFrameType.I, presentationTimeUs = 123_456L))

        assertEquals(listOf(3), allocatedSizes)
        assertEquals(1, binary.copyCount)
        assertEquals(listOf("started", "consume", "finished"), reader.calls)
        assertEquals(123_456L, reader.timeUs)
        assertEquals(
            TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR or
                TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR,
            reader.flags,
        )
        assertEquals(listOf<Byte>(1, 2, 3), reader.bytes.toList())
    }

    @Test
    fun `missing pts and absent frame type do not invent timing or random access`() {
        val reader = RecordingReader()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)

        adapter.accept(packet(CountingBinary(byteArrayOf(4)), MuxFrameType.UNKNOWN, null))

        assertEquals(C.TIME_UNSET, reader.timeUs)
        assertEquals(TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR, reader.flags)
    }

    @Test
    fun `only accepted skip and dropped markers reset reader state`() {
        val reader = RecordingReader()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)

        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.REJECTED, null, null))
        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.UNKNOWN, null, null))
        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.ACCEPTED, null, null))
        adapter.accept(SubscriptionEvent.Dropped(3L))

        assertEquals(2, reader.seekCount)
    }

    @Test
    fun `parser failure never finishes a partial packet`() {
        val reader = RecordingReader(throwOnConsume = true)
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)

        assertThrows(IllegalStateException::class.java) {
            adapter.accept(packet(CountingBinary(byteArrayOf(9)), MuxFrameType.P, 4L))
        }
        assertTrue("started" in reader.calls)
        assertTrue("consume" in reader.calls)
        assertFalse("finished" in reader.calls)
    }

    private fun packet(
        binary: SubscriptionBinary,
        frameType: MuxFrameType,
        presentationTimeUs: Long?,
    ): SubscriptionEvent.Packet = SubscriptionEvent.Packet(
        frameType = frameType,
        streamIndex = StreamIndex(0L),
        decodingTimeUs = 999L,
        presentationTimeUs = presentationTimeUs,
        durationUs = 7_000L,
        payload = binary,
    )
}

private class RecordingReader(
    private val throwOnConsume: Boolean = false,
) : ElementaryStreamReader {
    internal val calls = mutableListOf<String>()
    internal var timeUs: Long = 0L
    internal var flags: Int = 0
    internal var bytes: ByteArray = byteArrayOf()
    internal var seekCount: Int = 0

    override fun seek() {
        seekCount += 1
    }

    override fun createTracks(output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator): Unit = Unit

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        calls += "started"
        timeUs = pesTimeUs
        this.flags = flags
    }

    override fun consume(data: ParsableByteArray) {
        calls += "consume"
        if (throwOnConsume) error("fixed parser failure")
        bytes = data.data.copyOfRange(data.position, data.limit())
        data.position = data.limit()
    }

    override fun packetFinished() {
        calls += "finished"
    }
}

private class CountingBinary(private val bytes: ByteArray) : SubscriptionBinary {
    override val size: Int = bytes.size
    internal var copyCount: Int = 0

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        copyCount += 1
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}

private object EmptyExtractorOutput : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput = error("Reader unexpectedly requested a track")
    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
}
