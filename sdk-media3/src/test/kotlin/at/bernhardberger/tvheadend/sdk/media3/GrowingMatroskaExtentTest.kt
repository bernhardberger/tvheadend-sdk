@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class GrowingMatroskaExtentTest {
    @Test
    fun `binary search lands on last cluster at or before target with bounded reads`() = runTest {
        val bytes = File("src/test/resources/growing-matroska/synthetic.mkv").readBytes()
        val probe = GrowingMatroskaExtentProbe(ProbeLease(bytes, bytes.size))
        try {
            val extent = requireNotNull(probe.sample())
            val clusters = buildList {
                var offset = extent.firstClusterPosition.toInt()
                while (offset < bytes.size) {
                    val cluster = findMatroskaCluster(bytes, 0, bytes.size.toLong(), offset, bytes.size) ?: break
                    add(cluster)
                    offset = cluster.endPosition.toInt()
                }
            }
            val maximumClusterSize = clusters.maxOf { it.endPosition - it.position }
            val targets = (0L..extent.durationUs step 137_000L).toList() +
                clusters.map { extent.timeUs(it.timecode) } + (extent.durationUs - 1L)
            val checks = mutableListOf<Executable>()
            val seeker = GrowingMatroskaContainer.createBinarySearchSeeker(extent)
            for (target in targets) {
                var bytesRead = 0
                val countRead: (Int) -> Unit = { bytesRead += it }
                seeker.setSeekTargetUs(target)
                var position = seeker.seekMap.getSeekPoints(target).first.position
                var input = input(bytes, position, countRead)
                var steps = 0
                while (seeker.isSeeking) {
                    check(++steps < 100)
                    val holder = PositionHolder()
                    if (seeker.handlePendingSeek(input, holder) == Extractor.RESULT_SEEK) {
                        position = holder.position
                        input = input(bytes, position, countRead)
                    }
                }
                val cluster = requireNotNull(findMatroskaCluster(bytes, 0, bytes.size.toLong(), input.position.toInt(), bytes.size))
                assertEquals(input.position, cluster.position)
                checks += Executable {
                    assertEquals(clusters.last { extent.timeUs(it.timecode) <= target }.position, cluster.position, "target=$target")
                }
                checks += Executable {
                    assertTrue(bytesRead <= maximumClusterSize * 8, "Seek read $bytesRead bytes for target=$target")
                }
            }
            assertAll(checks)
        } finally {
            probe.close()
        }
    }

    @Test
    fun `cluster headers accept CRC and Void before nonzero timecode and partial payload`() {
        val block = byteArrayOf(0xa3.toByte(), 0x84.toByte(), 0x81.toByte(), 0, 0, 0)
        val plain = byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75, 0x89.toByte(), 0xe7.toByte(), 0x81.toByte(), 42) + block
        assertEquals(42L, findMatroskaCluster(plain, 0, plain.size.toLong(), 0, plain.size)?.timecode)
        val crc = byteArrayOf(
            0x1f, 0x43, 0xb6.toByte(), 0x75, 0x20, 0, 32,
            0xbf.toByte(), 0x84.toByte(), 0, 0, 0, 0,
            0xec.toByte(), 0x81.toByte(), 0,
            0xe7.toByte(), 0x82.toByte(), 1, 24,
        ) + block
        assertEquals(280L, findMatroskaCluster(crc, 0, crc.size.toLong(), 0, crc.size)?.timecode)
        val payload = plain + byteArrayOf(0, 0, 0)
        assertNull(findMatroskaCluster(payload, 0, payload.size.toLong(), 0, payload.size))
    }

    @Test
    fun `synthetic prefixes produce increasing estimated extents and final confirmation`() = runTest {
        val bytes = File("src/test/resources/growing-matroska/synthetic.mkv").readBytes()
        val lease = ProbeLease(bytes, bytes.size / 2)
        val probe = GrowingMatroskaExtentProbe(lease)
        try {
            val prefix = requireNotNull(probe.sample())
            assertFalse(prefix.isFinal)
            assertTrue(prefix.durationUs > 0L)
            val initialBytesRead = lease.bytesRead
            lease.available = bytes.size
            val growing = requireNotNull(probe.sample())
            assertTrue(lease.bytesRead - initialBytesRead < 4_096, "A subsequent sample must read headers, not payloads")
            assertTrue(GrowingMatroskaContainer.isContinuous(prefix, growing))
            assertTrue(growing.durationUs > prefix.durationUs)
            lease.completed = true
            val final = requireNotNull(probe.sample())
            assertTrue(final.isFinal)
            assertEquals(growing.durationUs, final.durationUs)
            assertFalse(GrowingMatroskaContainer.isContinuous(growing, prefix))
            assertFalse(GrowingMatroskaContainer.isContinuous(growing, growing.copy(origin = growing.origin + 1)))
        } finally {
            probe.close()
        }
        assertEquals(lease.opens, lease.closes)
    }

    private fun input(bytes: ByteArray, position: Long, onRead: (Int) -> Unit): DefaultExtractorInput {
        var offset = position.toInt()
        return DefaultExtractorInput(DataReader { destination, start, length ->
            if (offset == bytes.size) C.RESULT_END_OF_INPUT else {
                val count = minOf(length, bytes.size - offset)
                bytes.copyInto(destination, start, offset, offset + count)
                offset += count
                onRead(count)
                count
            }
        }, position, bytes.size.toLong())
    }
}
