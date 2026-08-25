@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.TsExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GrowingTsSeekFeasibilityInstrumentationTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun updated_estimated_map_drives_progressive_seek_and_later_growth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.context.assets.open(FIXTURE_ASSET).use { input -> input.readBytes() }
        assertEquals(FIXTURE_SHA256, fixture.sha256())
        assertEquals("Fixture must end on a TS packet boundary", 0, fixture.size % TS_PACKET_BYTES)

        val initialBytes = ((fixture.size / 2) / TS_PACKET_BYTES) * TS_PACKET_BYTES
        val data = AppendableTsData(fixture, initialBytes)
        val observation = GrowingTsObservation(initialBytes)
        val extractors = ExtractorsFactory {
            arrayOf(FeasibilityGrowingTsExtractor(observation, data::availableBytes))
        }
        val mediaSource = ProgressiveMediaSource.Factory(data.factory, extractors)
            .createMediaSource(MediaItem.fromUri(TEST_URI))
        val playerFailure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        var playerCreated = false

        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(500, 60_000, 100, 100)
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .setBackBuffer(0, false)
                            .build(),
                    )
                    .build()
                playerCreated = true
                player.volume = 0f
                player.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playerFailure.compareAndSet(null, error)
                        }
                    },
                )
                player.setMediaSource(mediaSource)
                player.prepare()
            }

            assertTrue("TsExtractor never published its stock unseekable map", observation.awaitUnseekable())
            assertFalse(
                "TsExtractor's stock unseekable map must be definitive",
                observation.delegateMapIsEstimated(),
            )
            assertTrue("The initial append boundary was not reached", data.awaitTemporaryEnd())
            assertTrue("No estimated seekable map was published", observation.awaitSeekableMap())
            val acceptedDurationMs = awaitSeekableTimeline(instrumentation, player, playerFailure)

            assertTrue("ProgressiveMediaPeriod did not expose a finite updated duration", acceptedDurationMs > 0L)
            assertTrue("The wrapper must publish more than one dynamic map", observation.mapUpdateCount() > 1)
            assertTrue(
                "The fixture must expose a maintained Media3 video reader",
                observation.videoMimeType() in setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_MPEG2),
            )
            assertNotEquals("Temporary EOF must not end playback", Player.STATE_ENDED, playerState(instrumentation, player))

            val plan = observation.seekPlan()
            instrumentation.runOnMainSync {
                player.setPlaybackSpeed(PLAYBACK_SPEED)
                player.play()
            }
            awaitPlayerPosition(
                instrumentation = instrumentation,
                player = player,
                failure = playerFailure,
                positionMs = plan.playUntilUs / 1_000L,
            )
            assertNull(
                "A nonzero extractor seek occurred before the player seek",
                observation.extractorSeekOrNull(),
            )
            assertFalse(
                "A nonzero data-source reopen occurred before the player seek",
                data.hasNonzeroOpen(),
            )
            instrumentation.runOnMainSync {
                player.pause()
                player.seekTo(plan.target.timeUs / 1_000L)
                player.play()
            }

            assertTrue("The progressive seek never reached Extractor.seek", observation.awaitExtractorSeek())
            assertTrue("The progressive seek did not reopen at a nonzero byte", data.awaitNonzeroOpen())
            val seek = observation.extractorSeek()
            assertTrue("The seek byte must be nonzero", seek.position > 0L)
            assertEquals("The seek byte must remain packet-aligned", 0L, seek.position % TS_PACKET_BYTES)
            assertTrue("The seek byte must remain within proven initial bytes", seek.position < initialBytes)
            assertEquals("The reopen must use the selected map point", plan.target.position, seek.position)
            assertEquals(
                "DataSource.open and Extractor.seek must use the same byte",
                data.nonzeroOpenPosition(),
                seek.position,
            )
            assertTrue(
                "Extractor.seek must retain the requested timeline target",
                abs(seek.timeUs - plan.target.timeUs) <= SEEK_REQUEST_TOLERANCE_US,
            )
            assertTrue("No keyframe was extracted after the seek", observation.awaitPostSeekKeyframe())
            val postSeekKeyframe = observation.postSeekKeyframe()
            val landingErrorUs = plan.target.timeUs - postSeekKeyframe.timeUs
            assertTrue(
                "The first post-seek keyframe must not skip past the requested target",
                landingErrorUs >= 0L,
            )
            assertTrue(
                "The first post-seek keyframe exceeded the documented tolerance: $landingErrorUs us",
                landingErrorUs <= KEYFRAME_LANDING_TOLERANCE_US,
            )

            val mapsBeforeGrowth = observation.mapUpdateCount()
            data.appendRemaining()
            assertTrue("The extractor did not read appended bytes", observation.awaitGrowthRead())
            assertTrue("No valid video keyframe was extracted from appended bytes", observation.awaitGrowthKeyframe())
            assertTrue("The map was not updated after appended media", observation.awaitGrowthMap())
            assertTrue("Later growth must enlarge the dynamic map", observation.mapUpdateCount() > mapsBeforeGrowth)
            assertEquals(
                "The proof must perform exactly one nonzero extractor seek",
                1,
                observation.extractorSeekCount(),
            )
            assertNotEquals("Later growth must not end playback", Player.STATE_ENDED, playerState(instrumentation, player))
            assertNull("Progressive playback failed", playerFailure.get())

            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p7_f1_result", "progressive-ts-seek-and-growth-passed")
                    putString("p7_f1_video_mime", observation.videoMimeType())
                    putInt("p7_f1_map_updates", observation.mapUpdateCount())
                    putLong("p7_f1_seek_position", seek.position)
                    putLong("p7_f1_target_time_us", plan.target.timeUs)
                    putLong("p7_f1_keyframe_landing_error_us", landingErrorUs)
                    putInt("p7_f1_initial_bytes", initialBytes)
                    putInt("p7_f1_final_bytes", fixture.size)
                },
            )
        } finally {
            data.finish()
            if (playerCreated) {
                instrumentation.runOnMainSync { player.release() }
            }
        }
    }
}

private class FeasibilityGrowingTsExtractor(
    private val observation: GrowingTsObservation,
    private val availableBytes: () -> Int,
    private val delegate: Extractor = TsExtractor(SubtitleParser.Factory.UNSUPPORTED),
) : Extractor {
    private val trackOutputs = ArrayList<IndexedTrackOutput>()
    private var downstream: ExtractorOutput? = null
    private var currentReadPosition = 0L
    private var sentInitialMap = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        downstream = output
        delegate.init(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val indexed = IndexedTrackOutput(
                        delegate = output.track(id, type),
                        trackType = type,
                        readPosition = { currentReadPosition },
                        observation = observation,
                    )
                    trackOutputs += indexed
                    return indexed
                }

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) {
                    observation.onDelegateSeekMap(seekMap)
                    if (!sentInitialMap) {
                        sentInitialMap = true
                        // ProgressiveMediaSource rejects estimated updates after a definitive map.
                        output.seekMap(EstimatedUnseekableSeekMap)
                    }
                }
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        currentReadPosition = input.position
        val result = delegate.read(input, seekPosition)
        observation.onReadPosition(input.position)
        observation.nextSeekMap(availableBytes())?.let { seekMap ->
            checkNotNull(downstream).seekMap(seekMap)
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        trackOutputs.forEach(IndexedTrackOutput::resetForSeek)
        observation.onExtractorSeek(position, timeUs)
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
}

private class IndexedTrackOutput(
    private val delegate: TrackOutput,
    private val trackType: Int,
    private val readPosition: () -> Long,
    private val observation: GrowingTsObservation,
) : TrackOutput {
    private var sampleStartPosition: Long? = null

    override fun format(format: Format) {
        delegate.format(format)
        if (trackType == C.TRACK_TYPE_VIDEO) observation.onVideoFormat(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        rememberSampleStart()
        return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        rememberSampleStart()
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (trackType == C.TRACK_TYPE_VIDEO && flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
            observation.onVideoKeyframe(timeUs, sampleStartPosition ?: readPosition(), readPosition())
        }
        sampleStartPosition = readPosition()
    }

    fun resetForSeek() {
        sampleStartPosition = null
    }

    private fun rememberSampleStart() {
        if (sampleStartPosition == null) sampleStartPosition = readPosition()
    }
}

private class GrowingTsObservation(
    private val growthBoundary: Int,
) {
    private val monitor = Any()
    private val points = ArrayList<IndexedTsPoint>()
    private val unseekable = CountDownLatch(1)
    private val seekableMap = CountDownLatch(1)
    private val extractorSeek = CountDownLatch(1)
    private val postSeekKeyframe = CountDownLatch(1)
    private val growthRead = CountDownLatch(1)
    private val growthKeyframe = CountDownLatch(1)
    private val growthMap = CountDownLatch(1)
    private var publishedPointCount = 0
    private var mapUpdates = 0
    private var nonzeroSeeks = 0
    private var latestMap: EstimatedTsSeekMap? = null
    private var delegateMapEstimated: Boolean? = null
    private var seek: ExtractorSeek? = null
    private var firstPostSeekKeyframe: IndexedTsPoint? = null
    private var firstGrowthKeyframe: IndexedTsPoint? = null
    private var videoMime: String? = null

    fun onDelegateSeekMap(seekMap: SeekMap) {
        if (!seekMap.isSeekable) {
            synchronized(monitor) {
                if (delegateMapEstimated == null) delegateMapEstimated = seekMap.isEstimated
            }
            unseekable.countDown()
        }
    }

    fun onVideoFormat(format: Format) {
        synchronized(monitor) {
            videoMime = format.sampleMimeType
        }
    }

    fun onReadPosition(position: Long) {
        if (position > growthBoundary) growthRead.countDown()
    }

    fun onVideoKeyframe(timeUs: Long, samplePosition: Long, readPosition: Long) {
        if (timeUs < 0L) return
        val indexed = IndexedTsPoint(
            timeUs = timeUs,
            position = packetAlignedSeekPosition(samplePosition),
            sourceReadPosition = readPosition,
        )
        synchronized(monitor) {
            if (seek != null && firstPostSeekKeyframe == null) {
                firstPostSeekKeyframe = indexed
                postSeekKeyframe.countDown()
            }
            if (seek != null && readPosition > growthBoundary && firstGrowthKeyframe == null) {
                firstGrowthKeyframe = indexed
                growthKeyframe.countDown()
            }
            if (points.none { point -> point.timeUs == indexed.timeUs }) {
                points += indexed
                points.sortBy(IndexedTsPoint::timeUs)
            }
        }
    }

    fun nextSeekMap(availableBytes: Int): SeekMap? = synchronized(monitor) {
        val maximumPosition = ((availableBytes / TS_PACKET_BYTES) * TS_PACKET_BYTES - TS_PACKET_BYTES)
            .coerceAtLeast(0)
        val snapshot = points
            .asSequence()
            .filter { point -> point.position <= maximumPosition }
            .distinctBy { point -> point.timeUs }
            .toList()
        if (snapshot.size < MINIMUM_INDEX_POINTS || snapshot.size == publishedPointCount) {
            return@synchronized null
        }
        val seekablePoints = snapshot.count { point -> point.position > 0L && point.timeUs > 0L }
        if (seekablePoints < MINIMUM_INDEX_POINTS || snapshot.last().timeUs <= snapshot.first().timeUs) {
            return@synchronized null
        }
        publishedPointCount = snapshot.size
        mapUpdates += 1
        val map = EstimatedTsSeekMap(snapshot)
        latestMap = map
        seekableMap.countDown()
        if (snapshot.any { point -> point.sourceReadPosition > growthBoundary }) {
            growthMap.countDown()
        }
        map
    }

    fun onExtractorSeek(position: Long, timeUs: Long) {
        // BundledExtractorsAdapter initializes every extractor with a bootstrap seek to zero.
        if (position == 0L && timeUs == 0L) return
        val firstSeek = synchronized(monitor) {
            nonzeroSeeks += 1
            if (seek != null) {
                false
            } else {
                seek = ExtractorSeek(position, timeUs)
                firstPostSeekKeyframe = null
                true
            }
        }
        if (firstSeek) extractorSeek.countDown()
    }

    fun awaitUnseekable(): Boolean = unseekable.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitSeekableMap(): Boolean = seekableMap.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitExtractorSeek(): Boolean = extractorSeek.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitPostSeekKeyframe(): Boolean =
        postSeekKeyframe.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitGrowthRead(): Boolean = growthRead.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitGrowthKeyframe(): Boolean = growthKeyframe.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitGrowthMap(): Boolean = growthMap.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun mapUpdateCount(): Int = synchronized(monitor) { mapUpdates }

    fun delegateMapIsEstimated(): Boolean = synchronized(monitor) { checkNotNull(delegateMapEstimated) }

    fun extractorSeekCount(): Int = synchronized(monitor) { nonzeroSeeks }

    fun videoMimeType(): String? = synchronized(monitor) { videoMime }

    fun extractorSeekOrNull(): ExtractorSeek? = synchronized(monitor) { seek }

    fun extractorSeek(): ExtractorSeek = synchronized(monitor) { checkNotNull(seek) }

    fun postSeekKeyframe(): IndexedTsPoint = synchronized(monitor) { checkNotNull(firstPostSeekKeyframe) }

    fun seekPlan(): SeekPlan = synchronized(monitor) {
        val map = checkNotNull(latestMap)
        val candidates = map.points.filter { point ->
            point.position > 0L && point.timeUs > 0L && point.timeUs % 1_000L == 0L
        }
        check(candidates.size >= 3) { "The fixture did not expose enough nonzero keyframe points" }
        val target = candidates[candidates.size / 3]
        val playUntilUs = minOf(map.indexedDurationUs - PLAYBACK_TAIL_US, target.timeUs + PLAY_PAST_TARGET_US)
        check(playUntilUs - target.timeUs >= MINIMUM_PLAY_PAST_TARGET_US) {
            "The fixture did not expose enough media after the selected seek point"
        }
        SeekPlan(target, playUntilUs)
    }

    private fun packetAlignedSeekPosition(observedPosition: Long): Long =
        ((observedPosition - SEEK_PREROLL_BYTES).coerceAtLeast(0L) / TS_PACKET_BYTES) * TS_PACKET_BYTES
}

private class EstimatedTsSeekMap(
    val points: List<IndexedTsPoint>,
) : SeekMap {
    val indexedDurationUs: Long = points.last().timeUs

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = indexedDurationUs

    override fun isEstimated(): Boolean = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val floor = points.lastOrNull { point -> point.timeUs <= timeUs } ?: points.first()
        val ceiling = points.firstOrNull { point -> point.timeUs >= timeUs } ?: floor
        val first = SeekPoint(floor.timeUs, floor.position)
        if (ceiling == floor) return SeekMap.SeekPoints(first)
        return SeekMap.SeekPoints(first, SeekPoint(ceiling.timeUs, ceiling.position))
    }
}

private object EstimatedUnseekableSeekMap : SeekMap {
    override fun isSeekable(): Boolean = false

    override fun getDurationUs(): Long = C.TIME_UNSET

    override fun isEstimated(): Boolean = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
        SeekMap.SeekPoints(SeekPoint(0L, 0L))
}

private data class IndexedTsPoint(
    val timeUs: Long,
    val position: Long,
    val sourceReadPosition: Long,
)

private data class ExtractorSeek(
    val position: Long,
    val timeUs: Long,
)

private data class SeekPlan(
    val target: IndexedTsPoint,
    val playUntilUs: Long,
)

private class AppendableTsData(
    private val bytes: ByteArray,
    initialBytes: Int,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val temporaryEnd = CountDownLatch(1)
    private val nonzeroOpen = CountDownLatch(1)
    private val firstNonzeroOpenPosition = AtomicLong(NO_OPEN_POSITION)

    @Volatile
    private var available = initialBytes

    @Volatile
    private var finished = false

    val factory: DataSource.Factory = DataSource.Factory { Source() }

    fun availableBytes(): Int = available

    fun awaitTemporaryEnd(): Boolean = temporaryEnd.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitNonzeroOpen(): Boolean = nonzeroOpen.await(ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun hasNonzeroOpen(): Boolean = firstNonzeroOpenPosition.get() != NO_OPEN_POSITION

    fun nonzeroOpenPosition(): Long = firstNonzeroOpenPosition.get().also { position ->
        check(position != NO_OPEN_POSITION)
    }

    fun appendRemaining() {
        lock.withLock {
            check(!finished) { "Cannot append after final input" }
            available = bytes.size
            changed.signalAll()
        }
    }

    fun finish() {
        lock.withLock {
            available = bytes.size
            finished = true
            changed.signalAll()
        }
    }

    private inner class Source : BaseDataSource(false) {
        private var position = 0
        private var opened = false
        private var closed = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            if (dataSpec.position !in 0L..bytes.size.toLong()) {
                throw IOException("Fixture position is outside the captured bytes")
            }
            position = dataSpec.position.toInt()
            opened = true
            closed = false
            if (dataSpec.position > 0L) {
                firstNonzeroOpenPosition.compareAndSet(NO_OPEN_POSITION, dataSpec.position)
                nonzeroOpen.countDown()
            }
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val copied = lock.withLock {
                while (!closed && !finished && position >= available) {
                    temporaryEnd.countDown()
                    try {
                        changed.await()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException().apply { initCause(interrupted) }
                    }
                }
                if (closed || (position >= available && finished)) return C.RESULT_END_OF_INPUT
                val count = minOf(length, available - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                count
            }
            bytesTransferred(copied)
            return copied
        }

        override fun getUri(): Uri = TEST_URI

        override fun close() {
            val wasOpened = opened
            opened = false
            lock.withLock {
                closed = true
                changed.signalAll()
            }
            if (wasOpened) transferEnded()
        }
    }
}

private fun awaitSeekableTimeline(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    failure: AtomicReference<PlaybackException?>,
): Long {
    val deadline = SystemClock.elapsedRealtime() + ASSERTION_TIMEOUT_SECONDS * 1_000L
    while (SystemClock.elapsedRealtime() < deadline) {
        failure.get()?.let { error -> throw AssertionError("Player failed before accepting the map", error) }
        var seekable = false
        var durationMs = C.TIME_UNSET
        instrumentation.runOnMainSync {
            seekable = player.isCurrentMediaItemSeekable
            durationMs = player.duration
        }
        if (seekable && durationMs > 0L) return durationMs
        Thread.sleep(P7_POLL_INTERVAL_MS)
    }
    throw AssertionError("ProgressiveMediaPeriod did not expose the updated seekable map")
}

private fun awaitPlayerPosition(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    failure: AtomicReference<PlaybackException?>,
    positionMs: Long,
) {
    val deadline = SystemClock.elapsedRealtime() + ASSERTION_TIMEOUT_SECONDS * 1_000L
    while (SystemClock.elapsedRealtime() < deadline) {
        failure.get()?.let { error -> throw AssertionError("Player failed before consuming the seek target", error) }
        var currentPositionMs = 0L
        instrumentation.runOnMainSync { currentPositionMs = player.currentPosition }
        if (currentPositionMs >= positionMs) return
        Thread.sleep(P7_POLL_INTERVAL_MS)
    }
    throw AssertionError("Player did not consume enough buffered media to require a data-source seek")
}

private fun playerState(instrumentation: android.app.Instrumentation, player: ExoPlayer): Int {
    var state = Player.STATE_IDLE
    instrumentation.runOnMainSync { state = player.playbackState }
    return state
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private const val FIXTURE_ASSET: String = "p7-f1/pass-through.ts"
private const val FIXTURE_SHA256: String = "ac5450c47d40b34277e3c304392f2476273717c9fcf8c91b78252702052a2447"
private val TEST_URI: Uri = Uri.parse("https://p7-f1.invalid/pass-through.ts")
private const val NO_OPEN_POSITION: Long = -1L
private const val TS_PACKET_BYTES: Int = 188
private const val MINIMUM_INDEX_POINTS: Int = 3
private const val SEEK_PREROLL_PACKETS: Long = 512L
private const val SEEK_PREROLL_BYTES: Long = SEEK_PREROLL_PACKETS * TS_PACKET_BYTES
private const val SEEK_REQUEST_TOLERANCE_US: Long = 1_000L
private const val KEYFRAME_LANDING_TOLERANCE_US: Long = 2_000_000L
private const val PLAY_PAST_TARGET_US: Long = 4_000_000L
private const val MINIMUM_PLAY_PAST_TARGET_US: Long = 1_000_000L
private const val PLAYBACK_TAIL_US: Long = 500_000L
private const val PLAYBACK_SPEED: Float = 4f
private const val ASSERTION_TIMEOUT_SECONDS: Long = 45L
private const val P7_POLL_INTERVAL_MS: Long = 50L
private const val TEST_TIMEOUT_MS: Long = 120_000L
