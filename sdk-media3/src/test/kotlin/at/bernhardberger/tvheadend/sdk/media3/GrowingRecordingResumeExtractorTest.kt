@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.DataReader
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Drives the growing resume rule from the production extractor and extent probe over the committed
 * TS fixtures. The single-thread executor stands in for the player application looper.
 */
class GrowingRecordingResumeExtractorTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    private val looper: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @AfterEach
    fun stopLooper() {
        looper.shutdownNow()
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `growing fixtures become resumable well inside the settle bound`() {
        listOf(
            "p7-f1/pass-through.ts" to MimeTypes.VIDEO_MPEG2,
            "p7-f3/h264-synthetic.ts" to MimeTypes.VIDEO_H264,
        ).forEach { (asset, videoMime) ->
            listOf(8_000L to 8_000L, 30_000L to null).forEach { (saved, expectedSeek) ->
                val result = resumeOverGrowingFixture(asset, videoMime, saved)

                assertEquals(RecordingResumeOutcome.APPLIED, result.outcome, asset)
                assertTrue(
                    result.settledAfterMillis < GROWING_RESUME_SETTLE_TIMEOUT_MILLIS,
                    "$asset settled after ${result.settledAfterMillis} ms",
                )
                // The half fixture holds about 12 s; a saved position past it resumes before the edge.
                assertTrue(kotlin.math.abs(result.extentMillis - 12_000L) < 500L, "$asset extent ${result.extentMillis}")
                assertEquals(
                    expectedSeek ?: (result.extentMillis - GROWING_RESUME_EDGE_MARGIN_MILLIS),
                    result.seeks.single(),
                    asset,
                )
                println(
                    "growing-resume-measure asset=$asset saved=$saved seek=${result.seeks.single()} " +
                        "extent=${result.extentMillis} seekableAfterMs=${result.settledAfterMillis}",
                )
            }
        }
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `production growing seek from the resume target reads from the matching source keyframe`() {
        LANDING_FIXTURES.forEach { fixture ->
            // 8 s lies inside the half fixture; 11 s lies within the edge margin and is clamped.
            listOf(8_000L, 11_000L).forEach { saved ->
                val result = resumeOverGrowingFixture(fixture.asset, fixture.videoMime, saved, landingShiftMillis = 0L)
                // The expected target comes from the saved position and probed extent, not from
                // the seek the resume issued, so a wrong resume seek fails the source check.
                val target = minOf(saved, result.extentMillis - GROWING_RESUME_EDGE_MARGIN_MILLIS)
                val landing = checkNotNull(result.landing)
                val exact = fixture.keyframes.verifyLanding(landing.parserStart, target)

                assertTrue(exact is FixtureLanding.Verified, "${fixture.asset} saved=$saved: $exact")
                println(
                    "growing-resume-landing asset=${fixture.asset} saved=$saved target=$target " +
                        "readerOpen=${landing.readerOpen} parserStart=${landing.parserStart} " +
                        "sourceKeyframe=${(exact as FixtureLanding.Verified).keyframe}",
                )
            }
        }
    }

    @Test
    @Timeout(120, unit = TimeUnit.SECONDS)
    fun `landing check rejects playback that reads from the wrong source offset`() {
        LANDING_FIXTURES.forEach { fixture ->
            // Negative control: the same production seek path, deliberately six seconds early.
            val early = resumeOverGrowingFixture(fixture.asset, fixture.videoMime, 8_000L, landingShiftMillis = -6_000L)
            val target = early.seeks.single()
            val landing = checkNotNull(early.landing)

            assertEquals(8_000L, target, fixture.asset)
            assertTrue(fixture.keyframes.verifyLanding(landing.parserStart, target) is FixtureLanding.Rejected, fixture.asset)
            // The shifted landing is itself correct for the shifted target, so only the offset is wrong.
            assertTrue(fixture.keyframes.verifyLanding(landing.parserStart, target - 6_000L) is FixtureLanding.Verified, fixture.asset)
            // Negative control: playback that reads the recording from its first byte.
            assertTrue(fixture.keyframes.verifyLanding(0L, target) is FixtureLanding.Rejected, fixture.asset)
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun `unprobeable growing input abandons the resume at the settle bound`() {
        val lease = ProbeLease(ByteArray(GROWING_TS_PACKET_BYTES * 600), GROWING_TS_PACKET_BYTES * 600)
        val maps = CopyOnWriteArrayList<SeekMap>()
        val seeks = CopyOnWriteArrayList<Long>()
        val resume = RecordingResumeStateMachine(seekTo = { seeks += it }, scheduleTimeout = ::schedule)
        val extractor = GrowingTsExtractor(delegate = DeclaredVideoTrack(MimeTypes.VIDEO_H264), lease = lease, onSeekMap = { map ->
            maps += map
            looper.execute { resume.onMediaState(true, map.durationMillis(), map.isSeekable) }
        })
        try {
            val started = System.nanoTime()
            onLooper { resume.beginPlaybackTarget(8_000L, RecordingResumeMode.GROWING) }
            extractor.init(CapturingOutput)

            Thread.sleep(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS - 2_000L)
            assertNull(onLooper { resume.outcome }, "Resume must stay pending until the bound")
            assertEquals(8_000L, onLooper { resume.pendingPositionMillis })

            val deadline = started + TimeUnit.MILLISECONDS.toNanos(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS + 5_000L)
            while (System.nanoTime() < deadline && onLooper { resume.outcome } == null) Thread.sleep(50)

            assertEquals(RecordingResumeOutcome.ABANDONED, onLooper { resume.outcome })
            assertNull(onLooper { resume.pendingPositionMillis })
            assertTrue(maps.none(SeekMap::isSeekable))
            assertEquals(emptyList<Long>(), seeks)
            assertTrue(lease.opens >= 2, "The probe must have retried before the resume was abandoned")
        } finally {
            extractor.release()
        }
    }

    private fun resumeOverGrowingFixture(
        asset: String,
        videoMime: String,
        savedMillis: Long,
        landingShiftMillis: Long? = null,
    ): FixtureResume {
        val bytes = File("src/androidTest/assets/$asset").readBytes()
        val initialBytes = bytes.size / 2 / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES
        val lease = ProbeLease(bytes, initialBytes)
        val seeks = CopyOnWriteArrayList<Long>()
        var extentMillis = -1L
        var seekableMap: SeekMap? = null
        val delegate = DeclaredVideoTrack(videoMime)
        val resume = RecordingResumeStateMachine(seekTo = { seeks += it }, scheduleTimeout = ::schedule)
        val extractor = GrowingTsExtractor(delegate = delegate, lease = lease, onSeekMap = { map ->
            looper.execute {
                map.durationMillis()?.let { extentMillis = it }
                if (map.isSeekable) seekableMap = map
                resume.onMediaState(true, map.durationMillis(), map.isSeekable)
            }
        })
        try {
            val started = System.nanoTime()
            onLooper { resume.beginPlaybackTarget(savedMillis, RecordingResumeMode.GROWING) }
            extractor.init(CapturingOutput)
            val deadline = started + TimeUnit.MILLISECONDS.toNanos(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS + 5_000L)
            while (System.nanoTime() < deadline && onLooper { resume.outcome } == null) Thread.sleep(10)
            val settledAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val result = onLooper { FixtureResume(resume.outcome, seeks.toList(), extentMillis, settledAfter) }
            val shift = landingShiftMillis ?: return result
            val map = checkNotNull(onLooper { seekableMap }) { "$asset never published a seekable map" }
            val landing = seekThroughExtractor(extractor, delegate, bytes, initialBytes, map, result.seeks.single() + shift)
            return result.copy(landing = landing)
        } finally {
            extractor.release()
            onLooper { resume.close() }
        }
    }

    /**
     * Performs the seek the way ProgressiveMediaPeriod does: the loader reopens at the seek
     * point, the extractor resolves its binary search through RESULT_SEEK reopenings, and the
     * position where the TS parser finally receives input is where playback reads its media from.
     * The reader that feeds the parser may have been opened earlier because the search skips
     * forward inside a reader instead of reopening it.
     */
    private fun seekThroughExtractor(
        extractor: GrowingTsExtractor,
        delegate: DeclaredVideoTrack,
        bytes: ByteArray,
        availableBytes: Int,
        map: SeekMap,
        targetMillis: Long,
    ): SeekLanding {
        val targetUs = targetMillis * 1_000L
        var position = map.getSeekPoints(targetUs).first.position
        extractor.seek(position, targetUs)
        delegate.readPositions.clear()
        var input = fixtureInput(bytes, availableBytes, position)
        repeat(MAX_SEEK_READS) {
            val holder = PositionHolder()
            val result = extractor.read(input, holder)
            delegate.readPositions.firstOrNull()?.let { parserStart ->
                return SeekLanding(readerOpen = position, parserStart = parserStart - parserStart % GROWING_TS_PACKET_BYTES)
            }
            check(result == Extractor.RESULT_SEEK || result == Extractor.RESULT_CONTINUE) { "Seek read ended: $result" }
            if (result == Extractor.RESULT_SEEK) {
                position = holder.position
                input = fixtureInput(bytes, availableBytes, position)
            }
        }
        error("The growing seek did not resolve within $MAX_SEEK_READS reads")
    }

    private fun fixtureInput(bytes: ByteArray, availableBytes: Int, position: Long): ExtractorInput {
        var cursor = position.toInt()
        val reader = DataReader { buffer, offset, length ->
            if (cursor >= availableBytes) {
                C.RESULT_END_OF_INPUT
            } else {
                val copied = minOf(length, availableBytes - cursor)
                bytes.copyInto(buffer, offset, cursor, cursor + copied)
                cursor += copied
                copied
            }
        }
        return DefaultExtractorInput(reader, position, C.LENGTH_UNSET.toLong())
    }

    private fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        val future = looper.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }

    private fun <T> onLooper(block: () -> T): T = looper.submit(block).get(5, TimeUnit.SECONDS)

    private fun SeekMap.durationMillis(): Long? = durationUs.takeIf { it > 0L }?.let { it / 1_000L }

    private data class FixtureResume(
        val outcome: RecordingResumeOutcome?,
        val seeks: List<Long>,
        val extentMillis: Long,
        val settledAfterMillis: Long,
        val landing: SeekLanding? = null,
    )

    private data class SeekLanding(val readerOpen: Long, val parserStart: Long)

    /**
     * Stands in for TsExtractor's track declaration (its SparseArray bookkeeping needs the Android
     * runtime). The extent itself is still probed from the fixture bytes by the production probe.
     */
    private class DeclaredVideoTrack(private val mime: String) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            output.track(0, C.TRACK_TYPE_VIDEO).format(Format.Builder().setSampleMimeType(mime).build())
            output.endTracks()
            output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        }

        val readPositions = CopyOnWriteArrayList<Long>()

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            readPositions += input.position
            return Extractor.RESULT_END_OF_INPUT
        }

        override fun seek(position: Long, timeUs: Long): Unit = Unit

        override fun release(): Unit = Unit
    }

    private object CapturingOutput : ExtractorOutput {
        override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput =
            androidx.media3.extractor.DiscardingTrackOutput()

        override fun endTracks(): Unit = Unit

        override fun seekMap(seekMap: SeekMap): Unit = Unit
    }
}

private class LandingFixture(val asset: String, val videoMime: String) {
    val keyframes: FixtureKeyframes =
        FixtureKeyframes.parse(File("src/androidTest/assets/${asset.substringBeforeLast('/')}/keyframes.csv").readText())
}

private val LANDING_FIXTURES = listOf(
    LandingFixture("p7-f1/pass-through.ts", MimeTypes.VIDEO_MPEG2),
    LandingFixture("p7-f3/h264-synthetic.ts", MimeTypes.VIDEO_H264),
)
private const val MAX_SEEK_READS: Int = 256
