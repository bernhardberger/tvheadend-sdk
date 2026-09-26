@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

internal class FixtureResumeLandingTest {
    private val table = FixtureKeyframes.parse(
        """
        byte_position,source_ms
        1000,8780
        2000,10780
        3000,12780
        """.trimIndent(),
    )

    // Two samples share a size, so only their data tells them apart.
    private val first = SampleIdentity(sizeBytes = 4_000, crc32 = 0x1111L)
    private val second = SampleIdentity(sizeBytes = 4_000, crc32 = 0x2222L)
    private val third = SampleIdentity(sizeBytes = 5_000, crc32 = 0x1111L)
    private val identities = FixtureKeyframeIdentities.align(
        table,
        listOf(
            ExtractedKeyframe(timeUs = 1_000_000L, identity = first),
            ExtractedKeyframe(timeUs = 3_000_000L, identity = second),
            ExtractedKeyframe(timeUs = 5_000_000L, identity = third),
        ),
    )

    @Test
    fun `the landing keyframe data names the source keyframe that bounds the first frame`() {
        val landing = identities.verifyRenderedLanding(first, keyframeToFirstFrameUs = 0L, targetMs = 8_000L)

        assertEquals(FixtureLanding.Verified(table.keyframes[0], 8_780L..8_780L), landing)
        assertEquals(table.keyframes[1], identities.identify(second))
        assertNull(identities.identify(SampleIdentity(sizeBytes = 4_000, crc32 = 0x3333L)))
    }

    @Test
    fun `a keyframe with the same size but other data cannot stand in for the landing keyframe`() {
        // The 10.78 s keyframe lies inside 7..11 s itself, but it is not the keyframe that was read.
        val landing = identities.verifyRenderedLanding(second, keyframeToFirstFrameUs = 0L, targetMs = 5_000L)

        assertTrue(landing is FixtureLanding.Rejected, "$landing")
        assertTrue(identities.verifyRenderedLanding(first, 0L, targetMs = 5_000L) is FixtureLanding.Rejected)
    }

    @Test
    fun `a keyframe inside the window does not accept a first frame presented outside it`() {
        // Keyframe 10.78 s lies inside 7..11 s for an 8 s target; its first frame 0.4 s later does not.
        val landing = identities.verifyRenderedLanding(second, keyframeToFirstFrameUs = 400_000L, targetMs = 8_000L)

        assertTrue(landing is FixtureLanding.Rejected, "$landing")
    }

    @Test
    fun `the first frame source time is rounded outward before the window check`() {
        assertEquals(
            FixtureLanding.Verified(table.keyframes[1], 11_000L..11_000L),
            identities.verifyRenderedLanding(second, keyframeToFirstFrameUs = 220_000L, targetMs = 8_000L),
        )
        assertTrue(identities.verifyRenderedLanding(second, 220_001L, targetMs = 8_000L) is FixtureLanding.Rejected)
        assertEquals(
            FixtureLanding.Verified(table.keyframes[0], 7_000L..7_000L),
            identities.verifyRenderedLanding(first, keyframeToFirstFrameUs = -1_780_000L, targetMs = 8_000L),
        )
        assertTrue(identities.verifyRenderedLanding(first, -1_780_001L, targetMs = 8_000L) is FixtureLanding.Rejected)
    }

    @Test
    fun `a landing keyframe without data or without a source match is a clear failure`() {
        assertEquals(
            FixtureLanding.Rejected("the landing keyframe read carried no sample data"),
            identities.verifyRenderedLanding(null, 0L, targetMs = 8_000L),
        )
        val unknown = SampleIdentity(sizeBytes = 4_000, crc32 = 0x3333L)
        assertEquals(
            FixtureLanding.Rejected("landing keyframe $unknown matches no source keyframe"),
            identities.verifyRenderedLanding(unknown, 0L, targetMs = 8_000L),
        )
    }

    @Test
    fun `alignment requires equal counts, matching relative times and unique sample data`() {
        val aligned = listOf(
            ExtractedKeyframe(1_000_000L, first),
            ExtractedKeyframe(3_001_000L, second),
            ExtractedKeyframe(4_999_000L, third),
        )
        assertEquals(3, FixtureKeyframeIdentities.align(table, aligned).size)

        assertThrows(IllegalArgumentException::class.java) {
            FixtureKeyframeIdentities.align(table, aligned.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixtureKeyframeIdentities.align(table, aligned.mapIndexed { i, k -> if (i == 2) k.copy(timeUs = 5_001_001L) else k })
        }
        assertThrows(IllegalArgumentException::class.java) {
            FixtureKeyframeIdentities.align(table, aligned.mapIndexed { i, k -> if (i == 2) k.copy(identity = first) else k })
        }
    }

    @Test
    fun `written sample identity covers only bytes after the start and leaves the buffer unchanged`() {
        val sample = byteArrayOf(0, 0, 1, 9, 42, 7, 3)
        val buffer = ByteBuffer.allocate(32)
        // Bytes a decoder might already hold before the sample, such as codec configuration.
        buffer.put(byteArrayOf(5, 5))
        val start = buffer.position()
        buffer.put(sample)

        val identity = SampleIdentity.written(buffer, start)

        assertEquals(SampleIdentity.of(sample, 0, sample.size), identity)
        assertEquals(start + sample.size, buffer.position())
        assertEquals(32, buffer.limit())
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `production TS extractor keyframe samples name each fixture keyframe uniquely`() {
        IDENTITY_FIXTURES.forEach { fixture ->
            val bytes = fixture.bytes()
            val table = fixture.table()

            val extracted = extractVideoKeyframes(bytes)
            val identities = FixtureKeyframeIdentities.align(table, extracted)

            assertEquals(fixture.keyframeCount, identities.size, fixture.asset)
            extracted.zip(table.keyframes).forEach { (sample, source) ->
                assertEquals(source, identities.identify(sample.identity), fixture.asset)
            }
            println("fixture-keyframe-identities asset=${fixture.asset} ${extracted.map(ExtractedKeyframe::identity)}")
        }
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `keyframe samples read after a mid GOP extractor seek keep their full parse identity`() {
        IDENTITY_FIXTURES.forEach { fixture ->
            val bytes = fixture.bytes()
            val table = fixture.table()
            val identities = FixtureKeyframeIdentities.align(table, extractVideoKeyframes(bytes))

            table.keyframes.zipWithNext().forEach { (previous, keyframe) ->
                // Just past the previous keyframe's first packet, and the last packet before this one.
                listOf(previous.bytePosition + GROWING_TS_PACKET_BYTES, keyframe.bytePosition - GROWING_TS_PACKET_BYTES)
                    .map { position -> position.toInt() / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES }
                    .forEach { seekPosition ->
                        val landing = extractVideoKeyframes(bytes, seekPosition).first()

                        assertEquals(
                            keyframe,
                            identities.identify(landing.identity),
                            "${fixture.asset} seek to byte $seekPosition read ${landing.identity}",
                        )
                    }
            }
        }
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun enforceLegacyLimits() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
            // Media3 warnings format through Android stubs that return null on the JVM.
            Log.setLogLevel(Log.LOG_LEVEL_OFF)
        }

        @JvmStatic
        @AfterAll
        fun restoreLogging() {
            Log.setLogLevel(Log.LOG_LEVEL_ALL)
        }
    }
}

private class IdentityFixture(val asset: String, val keyframeCount: Int) {
    fun bytes(): ByteArray = File("src/androidTest/assets/$asset").readBytes()

    fun table(): FixtureKeyframes =
        FixtureKeyframes.parse(File("src/androidTest/assets/${asset.substringBeforeLast('/')}/keyframes.csv").readText())
}

private val IDENTITY_FIXTURES = listOf(
    IdentityFixture("p7-f1/pass-through.ts", keyframeCount = 13),
    IdentityFixture("p7-f3/h264-synthetic.ts", keyframeCount = 12),
)
