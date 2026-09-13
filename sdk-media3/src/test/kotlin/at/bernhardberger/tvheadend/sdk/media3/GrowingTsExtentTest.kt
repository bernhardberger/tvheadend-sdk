@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrowingTsExtentTest {
    companion object {
        @org.junit.jupiter.api.BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            androidx.media3.common.util.ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    @Test
    fun `completion discovered during media read does not finalize the earlier size snapshot`() = runTest {
        val bytes = File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
        val initialSize = bytes.size / 2 / 188 * 188
        val lease = ProbeLease(bytes, initialSize)
        lease.beforeRead = {
            lease.available = bytes.size
            lease.completed = true
            lease.beforeRead = {}
        }
        val probe = GrowingTsExtentProbe(lease)
        try {
            val earlier = probe.sample()!!
            assertEquals(initialSize.toLong(), earlier.sizeBytes)
            assertTrue(!earlier.isFinal)
            val finalExtent = probe.sample()!!
            assertEquals(bytes.size.toLong(), finalExtent.sizeBytes)
            assertTrue(finalExtent.isFinal)
            assertTrue(finalExtent.durationUs > earlier.durationUs + 5_000_000L)
        } finally { probe.close() }
        assertEquals(lease.opens, lease.closes)
    }

    @Test
    fun `retained probe reads the head once skips unchanged media and confirms completion`() = runTest {
        val bytes = File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
        val lease = ProbeLease(bytes, bytes.size / 2 / 188 * 188)
        val probe = GrowingTsExtentProbe(lease)
        try {
            val first = probe.sample()!!
            val initialBytes = lease.bytesRead
            assertEquals(1, lease.opens)
            assertEquals(first, probe.sample())
            assertEquals(initialBytes, lease.bytesRead)
            lease.available = bytes.size
            val grown = probe.sample()!!
            assertTrue(grown.durationUs > first.durationUs)
            assertEquals(initialBytes + GROWING_TS_SEARCH_BYTES, lease.bytesRead)
            assertEquals(1, lease.seeks.count { it == 0L })
            lease.completed = true
            assertTrue(probe.sample()!!.isFinal)
            assertEquals(initialBytes + GROWING_TS_SEARCH_BYTES, lease.bytesRead)
        } finally { probe.close() }
        assertEquals(1, lease.opens)
        assertEquals(1, lease.closes)
    }

    @Test
    fun `unexpected probe read failure degrades duration and closes the reader`() = runTest {
        val lease = ProbeLease(ByteArray(188 * 600), 188 * 600).apply {
            beforeRead = { throw IllegalStateException("synthetic transport failure") }
        }
        assertNull(probeGrowingTsExtent(lease))
        assertEquals(lease.opens, lease.closes)
    }

    @Test
    fun `a nearby genuine PCR wrap works but a backwards clock reset cannot become a 26 hour range`() {
        val wrap = 1L shl 33
        val head = pcrPackets(wrap - 450_000L)
        val tail = pcrPackets(450_000L)
        assertEquals(10_000_000L, growingTsExtentFromPackets(188_000L, head, tail)?.durationUs)
        assertNull(growingTsExtentFromPackets(188_000L, pcrPackets(900_000L), tail))
        assertNull(growingTsExtentFromPackets(188_000L, tail, tail))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `probe cancellation and timeout close the acquired reader`() = runTest {
        val lease = ProbeLease(ByteArray(188 * 600), 188 * 600).apply { suspendReads = true }
        val probe = launch { probeGrowingTsExtent(lease) }
        runCurrent()
        assertEquals(1, lease.opens)
        probe.cancelAndJoin()
        assertEquals(lease.opens, lease.closes)
        assertNull(probeGrowingTsExtent(lease))
        assertEquals(lease.opens, lease.closes)
    }

    @Test
    fun `preexisting extent is measured without playback and extends without scanning the body`() = runTest {
        for (asset in listOf("p7-f1/pass-through.ts", "p7-f3/h264-synthetic.ts")) {
            val bytes = File("src/androidTest/assets/$asset").readBytes()
            val lease = ProbeLease(bytes, bytes.size / 2 / 188 * 188)
            val initial = probeGrowingTsExtent(lease)
            assertNotNull(initial)
            // Independently checked fixture video PTS spans (ffprobe): half ~12s, full23.96s.
            assertTrue(kotlin.math.abs(initial!!.durationUs - 12_000_000L) < 500_000L)
            assertTrue(lease.bytesRead <= 2 * GROWING_TS_SEARCH_BYTES)
            assertEquals(lease.opens, lease.closes)
            lease.available = bytes.size
            val grown = probeGrowingTsExtent(lease)!!
            assertTrue(kotlin.math.abs(grown.durationUs - 24_000_000L) < 500_000L)
            assertEquals(initial.firstPcr, grown.firstPcr)
            assertEquals(initial.pcrPid, grown.pcrPid)
            assertTrue(lease.bytesRead <= 4 * GROWING_TS_SEARCH_BYTES)
            assertEquals(lease.opens, lease.closes)
        }
    }

    @Test
    fun `missing size and expired lease do not invent a duration and close probe handles`() = runTest {
        val bytes = File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
        val lease = ProbeLease(bytes, bytes.size)
        lease.knownSize = false
        assertNull(probeGrowingTsExtent(lease))
        assertEquals(lease.opens, lease.closes)
        assertEquals(0, lease.bytesRead)
        lease.knownSize = true
        lease.current = false
        assertNull(probeGrowingTsExtent(lease))
        assertEquals(lease.opens, lease.closes)
    }
}

internal class ProbeLease(private val bytes: ByteArray, @Volatile var available: Int) : GrowingRecordingFileLease {
    var opens = 0
    var closes = 0
    var bytesRead = 0
    var knownSize = true
    @Volatile var current = true
    var suspendReads = false
    var beforeRead: suspend () -> Unit = {}
    var afterClose: () -> Unit = {}
    @Volatile var completed = false
    var stats = 0
    val seeks = mutableListOf<Long>()
    override val isCurrent: Boolean get() = current

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> {
        opens++
        return RecordingFileResult.Ok(object : GrowingRecordingFileReader {
            override val sizeBytes: Long? = available.toLong().takeIf { knownSize }
            private var offset = position.toInt()
            override val isFinal: Boolean get() = completed
            override suspend fun refreshSize(): RecordingFileResult<Long?> {
                stats++
                return RecordingFileResult.Ok(available.toLong().takeIf { knownSize })
            }
            override suspend fun seek(position: Long): RecordingFileResult<Unit> {
                check(position in 0..available.toLong())
                offset = position.toInt()
                seeks += position
                return RecordingFileResult.Ok(Unit)
            }
            override suspend fun read(destination: ByteArray, destinationOffset: Int, length: Int): RecordingFileResult<Int> {
                beforeRead()
                if (suspendReads) awaitCancellation()
                val count = minOf(length, available - offset, 997)
                check(count > 0) { "Probe crossed the existing extent" }
                bytes.copyInto(destination, destinationOffset, offset, offset + count)
                offset += count
                bytesRead += count
                return RecordingFileResult.Ok(count)
            }
            override suspend fun close(): RecordingFileResult<Unit> {
                closes++
                afterClose()
                return RecordingFileResult.Ok(Unit)
            }
        })
    }
}

private fun pcrPackets(pcr: Long): ByteArray = ByteArray(188 * 5) { 0xff.toByte() }.apply {
    repeat(5) { packet ->
        val offset = packet * 188
        this[offset] = 0x47
        this[offset + 1] = 1
        this[offset + 2] = 0
        this[offset + 3] = 0x20
        this[offset + 4] = 7
        this[offset + 5] = 0x10
        this[offset + 6] = (pcr shr 25).toByte()
        this[offset + 7] = (pcr shr 17).toByte()
        this[offset + 8] = (pcr shr 9).toByte()
        this[offset + 9] = (pcr shr 1).toByte()
        this[offset + 10] = ((pcr and 1L) shl 7 or 0x7e).toByte()
        this[offset + 11] = 0
    }
}
