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
    fun `finite packet can contain dependent pictures after its IDR but not before it`() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x10)
        val dependent = byteArrayOf(0, 0, 1, 0x61, 0x10)
        for ((bytes, accepted) in listOf((idr + dependent) to true, (dependent + idr) to false)) {
            val reader = RecordingReader()
            val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
            assertEquals(accepted, adapter.acceptFiniteIdr(packet(CountingBinary(bytes), MuxFrameType.I, 0)))
            assertEquals(accepted, "flush" in reader.calls)
            assertEquals(0, reader.seekCount)
        }
    }

    @Test
    fun `finite flush requires actual IDR NAL and never resets continuation reader`() {
        val nonIdr = checkNotNull(javaClass.getResourceAsStream("/synthetic-nonidr.h264")).use { it.readBytes() }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 1, 0x61, 0x10), nonIdr)) {
            val reader = RecordingReader()
            val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
            assertFalse(adapter.acceptFiniteIdr(packet(CountingBinary(bytes), MuxFrameType.I, 0)))
            assertFalse("flush" in reader.calls)
        }
        val reader = RecordingReader()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
        assertTrue(adapter.acceptFiniteIdr(packet(CountingBinary(byteArrayOf(0, 0, 0, 1, 0x65, 0x10)), MuxFrameType.I, 0)))
        assertEquals(listOf("started", "consume", "finished", "flush"), reader.calls)
        adapter.accept(packet(CountingBinary(byteArrayOf(0, 0, 1, 0x61, 0x10)), MuxFrameType.P, 40_000))
        assertEquals(0, reader.seekCount)
        assertEquals(1, reader.calls.count { it == "flush" })
    }

    @Test
    fun `first packet allocates exact payload once and copies once`() {
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
    fun `successive packets reuse capacity but expose only current bytes including empty input`() {
        val reader = RecordingReader()
        val allocations = mutableListOf<Int>()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0, payloadAllocator = {
            allocations += it
            ByteArray(it)
        })
        for (size in listOf(8, 3, 0, 12, 4)) {
            val expected = ByteArray(size) { size.toByte() }
            val binary = CountingBinary(expected)
            adapter.accept(packet(binary, MuxFrameType.P, 0))
            assertEquals(expected.toList(), reader.bytes.toList())
            assertEquals(1, binary.copyCount)
        }
        assertEquals(listOf(8, 12), allocations)
    }

    @Test
    fun `oversized packets do not grow retained scratch capacity`() {
        val allocations = mutableListOf<Int>()
        val reader = RecordingReader()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0, payloadAllocator = {
            allocations += it
            ByteArray(it)
        })
        // The per-reader retention bound is 256 KiB; larger packets remain supported.
        val oversized = 256 * 1024 + 1
        for (size in listOf(3, oversized, 2, oversized, 1)) {
            val bytes = ByteArray(size) { size.toByte() }
            adapter.accept(packet(CountingBinary(bytes), MuxFrameType.P, 0))
            assertEquals(bytes.toList(), reader.bytes.toList())
        }
        assertEquals(listOf(3, oversized, oversized), allocations)
    }

    @Test
    fun `finite IDR validation cannot find an IDR in a previous larger packet tail`() {
        val reader = RecordingReader()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
        val larger = ByteArray(8) + byteArrayOf(0, 0, 0, 1, 0x65, 0x10)
        assertTrue(adapter.acceptFiniteIdr(packet(CountingBinary(larger), MuxFrameType.I, 0)))
        assertFalse(adapter.acceptFiniteIdr(packet(CountingBinary(byteArrayOf(0, 0, 1)), MuxFrameType.I, 0)))
        assertEquals(1, reader.calls.count { it == "flush" })
        assertEquals(listOf<Byte>(0, 0, 1), reader.bytes.toList())
    }

    @Test
    fun `incomplete copy into reused capacity fails before invoking the reader`() {
        val reader = RecordingReader()
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
        adapter.accept(packet(CountingBinary(ByteArray(8)), MuxFrameType.P, 0))
        reader.calls.clear()
        val incomplete = object : SubscriptionBinary {
            override val size: Int = 3
            override fun copyInto(destination: ByteArray, destinationOffset: Int): Int = 2
        }
        assertThrows(IllegalStateException::class.java) {
            adapter.accept(packet(incomplete, MuxFrameType.P, 0))
        }
        assertTrue(reader.calls.isEmpty())
    }

    @Test
    fun `only accepted skip and dropped markers reset reader state`() {
        val reader = RecordingReader()
        var outputResetCount = 0
        val adapter = SubscriptionElementaryStreamAdapter(
            reader,
            EmptyExtractorOutput,
            0,
            onDiscontinuity = { outputResetCount += 1 },
        )

        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.REJECTED, null, null))
        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.UNKNOWN, null, null))
        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.ACCEPTED, null, null))
        adapter.accept(SubscriptionEvent.Dropped(3L))

        assertEquals(2, reader.seekCount)
        assertEquals(2, outputResetCount)
    }

    @Test
    fun `discontinuities release previous scratch capacity`() {
        val allocations = mutableListOf<Int>()
        val adapter = SubscriptionElementaryStreamAdapter(RecordingReader(), EmptyExtractorOutput, 0, payloadAllocator = {
            allocations += it
            ByteArray(it)
        })
        adapter.accept(packet(CountingBinary(ByteArray(8)), MuxFrameType.P, 0))
        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.REJECTED, null, null))
        adapter.accept(packet(CountingBinary(ByteArray(4)), MuxFrameType.P, 0))
        adapter.accept(SubscriptionEvent.Skipped(null, SkipOutcome.ACCEPTED, null, null))
        adapter.accept(packet(CountingBinary(ByteArray(3)), MuxFrameType.P, 0))
        adapter.accept(SubscriptionEvent.Dropped(3L))
        adapter.accept(packet(CountingBinary(ByteArray(2)), MuxFrameType.P, 0))
        assertEquals(listOf(8, 3, 2), allocations)
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

    @Test
    fun `parser failure releases oversized array from reusable input wrapper`() {
        val reader = RecordingReader(throwOnConsume = true)
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
        assertThrows(IllegalStateException::class.java) {
            adapter.accept(packet(CountingBinary(ByteArray(256 * 1024 + 1)), MuxFrameType.P, 0))
        }
        val input = checkNotNull(reader.lastInput)
        assertEquals(0, input.data.size)
        assertEquals(0, input.limit())
    }

    @Test
    fun `terminal flush failure still releases retained scratch and remains terminal`() {
        val reader = RecordingReader(throwOnEnd = true)
        val adapter = SubscriptionElementaryStreamAdapter(reader, EmptyExtractorOutput, 0)
        adapter.accept(packet(CountingBinary(ByteArray(8)), MuxFrameType.P, 0))
        assertThrows(IllegalStateException::class.java) { adapter.end() }
        // A terminal adapter rejects further input, so inspect its retained reference directly.
        val scratch = adapter.javaClass.getDeclaredField("payloadBuffer").apply { isAccessible = true }
        assertEquals(0, (scratch.get(adapter) as ByteArray).size)
        adapter.end()
        assertEquals(1, reader.calls.count { it == "flush" })
        assertThrows(IllegalStateException::class.java) {
            adapter.accept(packet(CountingBinary(ByteArray(1)), MuxFrameType.P, 0))
        }
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
    private val throwOnEnd: Boolean = false,
) : ElementaryStreamReader {
    internal val calls = mutableListOf<String>()
    internal var timeUs: Long = 0L
    internal var flags: Int = 0
    internal var bytes: ByteArray = byteArrayOf()
    internal var seekCount: Int = 0
    internal var lastInput: ParsableByteArray? = null

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
        lastInput = data
        if (throwOnConsume) error("fixed parser failure")
        bytes = data.data.copyOfRange(data.position, data.limit())
        data.position = data.limit()
    }

    override fun packetFinished() {
        calls += "finished"
    }

    override fun endOfInputReached() {
        calls += "flush"
        if (throwOnEnd) error("fixed flush failure")
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
