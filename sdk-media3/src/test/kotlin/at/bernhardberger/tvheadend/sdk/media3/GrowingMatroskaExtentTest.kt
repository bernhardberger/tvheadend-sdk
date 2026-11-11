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
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import java.io.File
import java.util.HexFormat
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
    fun `nonzero origin uses the absolute sample timestamp domain`() = runTest {
        val bytes = growingMatroskaFixture("nonzero-origin.mkv")
        val probe = GrowingMatroskaExtentProbe(ProbeLease(bytes, bytes.size))
        try {
            val extent = requireNotNull(probe.sample())
            // FFmpeg output_ts_offset=0.28: video keyframes are at 0.28, 1.28, 2.28, 3.28 s.
            assertEquals(280L, extent.origin)
            assertEquals(280_000L, extent.timeUs(280L))
            assertEquals(3_280_000L, extent.durationUs)
            for (target in listOf(0L, 279_000L, 280_000L, 1_000_000L, 1_280_000L, 3_279_000L)) {
                val seeker = GrowingMatroskaContainer.createBinarySearchSeeker(extent)
                seeker.setSeekTargetUs(target)
                var input = input(bytes, seeker.seekMap.getSeekPoints(target).first.position) {}
                while (seeker.isSeeking) {
                    val holder = PositionHolder()
                    if (seeker.handlePendingSeek(input, holder) == Extractor.RESULT_SEEK) input = input(bytes, holder.position) {}
                }
                val cluster = requireNotNull(findMatroskaCluster(bytes, 0, bytes.size.toLong(), input.position.toInt(), bytes.size))
                val expected = if (target < 280_000L) 280L else 280L + ((target - 280_000L) / 1_000_000L) * 1_000L
                assertEquals(expected, cluster.timecode, "target=$target")
            }
        } finally {
            probe.close()
        }
    }

    @Test
    fun `extent rejects block tracks absent from Tracks`() = runTest {
        val bytes = growingMatroskaFixture()
        for (track in 1..2) {
            val number = byteArrayOf(0xd7.toByte(), 0x81.toByte(), track.toByte())
            val at = (0..bytes.size - number.size).first { bytes.copyOfRange(it, it + number.size).contentEquals(number) }
            bytes[at + 2] = (track + 6).toByte() // Neither encoded block track is declared now.
        }
        val probe = GrowingMatroskaExtentProbe(ProbeLease(bytes, bytes.size))
        try {
            assertNull(probe.sample())
        } finally {
            probe.close()
        }
    }

    @Test
    fun `binary search lands on last cluster at or before target with bounded reads`() = runTest {
        val bytes = growingMatroskaFixture()
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
                var retainedPeek = 0L
                val countRead: (Int) -> Unit = { bytesRead += it }
                val countPeek: (Long) -> Unit = { retainedPeek = maxOf(retainedPeek, it) }
                seeker.setSeekTargetUs(target)
                var position = seeker.seekMap.getSeekPoints(target).first.position
                var input = input(bytes, position, countRead, countPeek)
                var steps = 0
                while (seeker.isSeeking) {
                    check(++steps < 100)
                    val holder = PositionHolder()
                    if (seeker.handlePendingSeek(input, holder) == Extractor.RESULT_SEEK) {
                        position = holder.position
                        input = input(bytes, position, countRead, countPeek)
                    }
                }
                val cluster = requireNotNull(findMatroskaCluster(bytes, 0, bytes.size.toLong(), input.position.toInt(), bytes.size))
                assertEquals(input.position, cluster.position)
                checks += Executable {
                    assertEquals((clusters.lastOrNull { it.timecode * 1_000L <= target } ?: clusters.first()).position, cluster.position, "target=$target")
                }
                checks += Executable {
                    assertTrue(bytesRead <= maximumClusterSize * 8, "Seek read $bytesRead bytes for target=$target")
                }
                checks += Executable {
                    assertTrue(retainedPeek <= 16 * 1024 + 256, "Search must not retain the scanned payload")
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
        for (boundary in listOf("114d9b7480", "1941a46980")) {
            val followed = plain + HexFormat.of().parseHex(boundary)
            assertEquals(42L, findMatroskaCluster(followed, 0, followed.size.toLong(), 0, followed.size)?.timecode)
        }
    }

    @Test
    fun `cluster headers reject unknown sized children and fake following boundaries`() {
        val unknownChild = HexFormat.of().parseHex("1f43b67589e7812aa3ff81000000")
        val fakeBoundary = HexFormat.of().parseHex("1f43b67589e7812aa384810000001254c367ff")
        val overflowingTime = HexFormat.of().parseHex("1f43b67590e7887fffffffffffffffa38481000000")
        assertAll(
            { assertNull(findMatroskaCluster(unknownChild, 0, unknownChild.size.toLong(), 0, unknownChild.size)) },
            { assertNull(findMatroskaCluster(fakeBoundary, 0, fakeBoundary.size.toLong(), 0, fakeBoundary.size)) },
            { assertNull(findMatroskaCluster(overflowingTime, 0, overflowingTime.size.toLong(), 0, overflowingTime.size)) },
        )
    }

    @Test
    fun `tail windows reject payload lookalikes across the seam without rereading the tail`() = runTest {
        val fixture = growingMatroskaFixture()
        val first = requireNotNull(findMatroskaCluster(fixture, 0, fixture.size.toLong(), 0, fixture.size))
        val prefix = fixture.copyOf(first.endPosition.toInt())
        // Fixed EBML headers describe a 12 MiB Cluster at 1.28 s with one padded SimpleBlock.
        // FFmpeg cannot generate the deliberately malformed nested headers in this payload.
        val cluster = ByteArray(8 + 12 * 1024 * 1024)
        HexFormat.of().parseHex("1f43b67510c00000e7820500a310bffff781000080").copyInto(cluster)
        val plain = prefix + cluster
        val plainLease = ProbeLease(plain, plain.size)
        val plainProbe = GrowingMatroskaExtentProbe(plainLease)
        try {
            assertEquals(prefix.size.toLong(), requireNotNull(plainProbe.sample()).lastClusterPosition)
            assertTrue(plainLease.bytesRead <= plain.size + 1024 * 1024 + 64, "Read each tail byte at most once, plus head and boundary headers")
        } finally {
            plainProbe.close()
        }
        val fakePosition = cluster.size - 256 * 1024 - 8
        HexFormat.of().parseHex("1f43b6752186a0e7820af0a38481000000").copyInto(cluster, fakePosition)
        // Its following Tags ID looks plausible, but its unknown size is invalid here.
        HexFormat.of().parseHex("1254c367ff").copyInto(cluster, fakePosition + 7 + 100_000)
        val bytes = prefix + cluster
        val lease = ProbeLease(bytes, bytes.size)
        val probe = GrowingMatroskaExtentProbe(lease)
        try {
            val extent = requireNotNull(probe.sample())
            assertAll(
                { assertEquals(prefix.size.toLong(), extent.lastClusterPosition) },
                { assertEquals(1_280_000L, extent.durationUs) },
                { assertTrue(lease.bytesRead <= bytes.size + 1024 * 1024 + 64, "Read each tail byte at most once, plus head and boundary headers") },
            )
        } finally {
            probe.close()
        }
    }

    @Test
    fun `adversarial timestamp distribution converges within the search step bound`() = runTest {
        val bytes = growingMatroskaFixture()
        val probe = GrowingMatroskaExtentProbe(ProbeLease(bytes, bytes.size))
        try {
            val size = 1L shl 60
            val extent = requireNotNull(probe.sample()).copy(sizeBytes = size, lastClusterPosition = size - 100, durationUs = 10_000_000L)
            // A deterministic sparse timestamp distribution keeps the target just below the ceiling.
            val pattern = HexFormat.of().parseHex("1f43b6758ae7822710a38481000000ec80")
            val seeker = GrowingMatroskaContainer.createBinarySearchSeeker(extent)
            seeker.setSeekTargetUs(9_999_000L)
            var position = seeker.seekMap.getSeekPoints(9_999_000L).first.position
            var steps = 0
            while (seeker.isSeeking) {
                check(++steps < 1_000)
                var offset = position
                val input = DefaultExtractorInput(DataReader { destination, start, length ->
                    repeat(length) { destination[start + it] = pattern[((offset + it) % pattern.size).toInt()] }
                    offset += length
                    length
                }, position, size)
                val holder = PositionHolder()
                if (seeker.handlePendingSeek(input, holder) == Extractor.RESULT_SEEK) position = holder.position else position = input.position
            }
            assertAll(
                { assertTrue(steps <= 66, "A seek must stop after at most 64 timestamp searches") },
                { assertEquals(extent.firstClusterPosition, position) },
            )
        } finally {
            probe.close()
        }
    }

    @Test
    fun `synthetic prefixes produce increasing estimated extents and final confirmation`() = runTest {
        val bytes = growingMatroskaFixture()
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

    private fun input(bytes: ByteArray, position: Long, onRead: (Int) -> Unit = {}, onPeek: (Long) -> Unit = {}): ExtractorInput {
        var offset = position.toInt()
        val input = DefaultExtractorInput(DataReader { destination, start, length ->
            if (offset == bytes.size) C.RESULT_END_OF_INPUT else {
                val count = minOf(length, bytes.size - offset)
                bytes.copyInto(destination, start, offset, offset + count)
                offset += count
                onRead(count)
                count
            }
        }, position, bytes.size.toLong())
        return object : ExtractorInput by input {
            override fun advancePeekPosition(length: Int) {
                input.advancePeekPosition(length)
                onPeek(input.peekPosition - input.position)
            }

            override fun peekFully(target: ByteArray, offset: Int, length: Int) {
                input.peekFully(target, offset, length)
                onPeek(input.peekPosition - input.position)
            }
        }
    }
}

internal fun growingMatroskaFixture(name: String = "synthetic.mkv"): ByteArray =
    File("src/test/resources/growing-matroska/$name").readBytes()
