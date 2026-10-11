@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import org.junit.Assert.assertTrue

/** Measures a real reader with a synchronous consuming sink, excluding transport and decoding. */
internal fun measurePacketAllocation(stream: SubscriptionStream, packets: List<SubscriptionEvent.Packet>) {
    val output = AllocationOutput()
    val reader = (createElementaryStreamReader(stream) as ReaderResult.Supported).reader
    var allocatedPayloadBytes = 0L
    var allocatedPayloadArrays = 0L
    val adapter = SubscriptionElementaryStreamAdapter(reader, output, 0, payloadAllocator = { size ->
        allocatedPayloadBytes += size
        allocatedPayloadArrays++
        ByteArray(size)
    })
    val cycleBytes = packets.sumOf { it.payload.size.toLong() }
    val cycles = maxOf(1, (128L * 1024 * 1024 / cycleBytes).toInt())
    fun replay(count: Int) {
        repeat(count) {
            for (packet in packets) adapter.accept(packet)
        }
    }
    replay(32)
    repeat(5) { trial ->
        val payloadBytesBefore = allocatedPayloadBytes
        val arraysBefore = allocatedPayloadArrays
        val samplesBefore = output.samples
        val bytesBefore = output.bytes
        val before = ArtCounters.read()
        val start = SystemClock.elapsedRealtimeNanos()
        replay(cycles)
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - start
        val after = ArtCounters.read()
        assertTrue("Reader must emit samples", output.samples > samplesBefore)
        assertTrue("Reader must consume payload", output.bytes > bytesBefore)
        val result = "packet-allocation type=${stream.type} trial=$trial packets=${cycles * packets.size} " +
            "inputBytes=${cycles * cycleBytes} payloadArrayBytes=${allocatedPayloadBytes - payloadBytesBefore} " +
            "payloadArrays=${allocatedPayloadArrays - arraysBefore} artBytes=${after.bytes - before.bytes} " +
            "gcCount=${after.collections - before.collections} gcTimeMs=${after.gcTimeMs - before.gcTimeMs} " +
            "blockingGcCount=${after.blockingCollections - before.blockingCollections} elapsedNs=$elapsedNs " +
            "sampleBytes=${output.bytes - bytesBefore} samples=${output.samples - samplesBefore}"
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$result\n") })
    }
    adapter.end()
}

private data class ArtCounters(val bytes: Long, val collections: Long, val gcTimeMs: Long, val blockingCollections: Long) {
    companion object {
        fun read(): ArtCounters = ArtCounters(
            stat("art.gc.bytes-allocated"), stat("art.gc.gc-count"),
            stat("art.gc.gc-time"), stat("art.gc.blocking-gc-count"),
        )

        private fun stat(name: String): Long = checkNotNull(Debug.getRuntimeStat(name)?.toLongOrNull()) {
            "Required ART allocation statistic is unavailable"
        }
    }
}

private class AllocationOutput : ExtractorOutput, TrackOutput {
    var samples = 0L
    var bytes = 0L

    override fun track(id: Int, type: Int): TrackOutput = this
    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
    override fun format(format: Format): Unit = Unit
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        error("In-memory reader expected")

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.skipBytes(length)
        bytes += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        samples++
    }
}
