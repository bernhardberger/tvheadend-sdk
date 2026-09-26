@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
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

    private fun resumeOverGrowingFixture(asset: String, videoMime: String, savedMillis: Long): FixtureResume {
        val bytes = File("src/androidTest/assets/$asset").readBytes()
        val initialBytes = bytes.size / 2 / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES
        val lease = ProbeLease(bytes, initialBytes)
        val seeks = CopyOnWriteArrayList<Long>()
        var extentMillis = -1L
        val resume = RecordingResumeStateMachine(seekTo = { seeks += it }, scheduleTimeout = ::schedule)
        val extractor = GrowingTsExtractor(delegate = DeclaredVideoTrack(videoMime), lease = lease, onSeekMap = { map ->
            looper.execute {
                map.durationMillis()?.let { extentMillis = it }
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
            return onLooper { FixtureResume(resume.outcome, seeks.toList(), extentMillis, settledAfter) }
        } finally {
            extractor.release()
            onLooper { resume.close() }
        }
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
    )

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

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT

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
