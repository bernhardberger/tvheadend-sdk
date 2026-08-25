@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class GrowingTsSourceInstrumentationTest {
    @Test(timeout = FIXTURE_TEST_TIMEOUT_MS)
    fun production_source_renders_seeks_and_continues_growth_for_mpeg2_and_h264() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val results = FIXTURES.map { fixture -> verifyFixture(instrumentation, fixture) }

        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("p7_f3_fixture_codecs", results.joinToString { result -> result.mimeType })
                putInt("p7_f3_fixture_map_updates", results.sumOf(FixtureResult::mapUpdates))
                putLong("p7_f3_fixture_max_seek_byte", results.maxOf(FixtureResult::seekByte))
            },
        )
    }

    private fun verifyFixture(
        instrumentation: android.app.Instrumentation,
        fixture: FixtureDefinition,
    ): FixtureResult {
        val bytes = instrumentation.context.assets.open(fixture.asset).use { input -> input.readBytes() }
        assertEquals(fixture.sha256, bytes.sha256())
        assertEquals("Fixture must end on a TS packet boundary", 0, bytes.size % GROWING_TS_PACKET_BYTES)
        val initialBytes = ((bytes.size / 2) / GROWING_TS_PACKET_BYTES) * GROWING_TS_PACKET_BYTES
        val recording = AppendableGrowingRecording(bytes, initialBytes)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val failure = AtomicReference<PlaybackException?>()
        val latestSeekMap = AtomicReference<GrowingTsSeekMap?>()
        val videoMimeTypes = Collections.synchronizedSet(mutableSetOf<String>())
        val durations = Collections.synchronizedSet(mutableSetOf<Long>())
        lateinit var player: ExoPlayer
        var playerCreated = false

        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                ).setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 60_000, 100, 100)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .setBackBuffer(0, false)
                        .build(),
                ).build()
                playerCreated = true
                player.volume = 0f
                player.setVideoSurface(surface)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failure.compareAndSet(null, error)
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            tracks.groups.forEach { group ->
                                repeat(group.length) { index ->
                                    group.getTrackFormat(index).sampleMimeType
                                        ?.takeIf { mimeType -> mimeType.startsWith("video/") }
                                        ?.let(videoMimeTypes::add)
                                }
                            }
                        }

                        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                            player.duration.takeIf { duration -> duration > 0L }?.let(durations::add)
                        }
                    },
                )
                player.setMediaSource(
                    createTvheadendGrowingRecordingMediaSource(
                        lease = recording,
                        recordingId = FIXTURE_RECORDING_ID,
                        readAheadBytes = FIXTURE_READ_AHEAD_BYTES,
                        onSeekMap = { map ->
                            if (map is GrowingTsSeekMap) latestSeekMap.set(map)
                        },
                    ),
                )
                player.prepare()
                player.setPlaybackSpeed(FIXTURE_PLAYBACK_SPEED)
                player.play()
            }

            assertTrue("Production source did not reach temporary EOF", recording.awaitTemporaryEnd())
            val initialTimeline = awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.seekable && snapshot.durationMs > 0L && snapshot.renderedFrames > 0L &&
                    fixture.mimeType in videoMimeTypes && latestSeekMap.get() != null
            }
            val map = checkNotNull(latestSeekMap.get())
            val candidates = map.points.filter { point -> point.position > 0L && point.timeUs > 0L }
            assertTrue("Fixture must expose multiple nonzero production seek points", candidates.size >= 3)
            val target = candidates[candidates.size / 2]
            val targetMs = (target.timeUs + 999L) / 1_000L
            val expectedSeekPosition = map.getSeekPoints(targetMs * 1_000L).first.position
            val playUntilMs = minOf(
                initialTimeline.durationMs - PLAYBACK_TAIL_MS,
                targetMs + PLAY_PAST_SEEK_TARGET_MS,
            )
            assertTrue(
                "Fixture must play far enough past the seek target to discard it",
                playUntilMs - targetMs >= MINIMUM_PLAY_PAST_SEEK_TARGET_MS,
            )
            val beforeSeek = awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.positionMs >= playUntilMs
            }
            val frameBaseline = beforeSeek.renderedFrames
            instrumentation.runOnMainSync { player.seekTo(targetMs) }
            assertTrue("Estimated map did not cause a nonzero reopen", recording.awaitNonzeroOpen())
            val seekPosition = recording.firstNonzeroOpenPosition()
            assertTrue("Seek byte must be positive", seekPosition > 0L)
            assertEquals("Seek byte must remain packet-aligned", 0L, seekPosition % GROWING_TS_PACKET_BYTES)
            assertTrue("Seek byte must stay inside parsed bytes", seekPosition < initialBytes)
            assertEquals("Reopen must use the selected production map point", expectedSeekPosition, seekPosition)
            awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.renderedFrames > frameBaseline && snapshot.positionMs >= targetMs - SEEK_POSITION_TOLERANCE_MS
            }

            val durationBeforeGrowth = synchronized(durations) {
                durations.maxOrNull()
            } ?: initialTimeline.durationMs
            recording.appendRemaining()
            assertTrue("Recoverable transport failure did not trigger a retry reopen", recording.awaitRetryOpen())
            val retryPosition = recording.retryOpenPosition()
            assertTrue("Retry byte must be positive", retryPosition > 0L)
            assertEquals("Retry byte must remain packet-aligned", 0L, retryPosition % GROWING_TS_PACKET_BYTES)
            val retrySnapshot = fixturePlayerSnapshot(instrumentation, player)
            assertTrue("Production source did not read appended bytes", recording.awaitGrowthRead())
            val afterGrowth = awaitFixtureState(instrumentation, player, failure) { snapshot ->
                val latestDuration = synchronized(durations) { durations.maxOrNull() ?: 0L }
                latestDuration > durationBeforeGrowth &&
                    snapshot.renderedFrames > retrySnapshot.renderedFrames &&
                    snapshot.playbackState != Player.STATE_ENDED
            }
            assertNotEquals("Temporary EOF or append ended playback", Player.STATE_ENDED, afterGrowth.playbackState)
            assertNull("Production fixture playback failed", failure.get())
            val distinctMapUpdates = durations.size
            val maximumExpectedUpdates = (afterGrowth.durationMs / MAP_UPDATE_BOUND_MS).toInt() + 3
            assertTrue(
                "Estimated map updates exceeded the indexed-horizon bound",
                distinctMapUpdates <= maximumExpectedUpdates,
            )
            assertTrue(recording.openPositions().all { position -> position % GROWING_TS_PACKET_BYTES == 0L })
            return FixtureResult(fixture.mimeType, distinctMapUpdates, seekPosition)
        } finally {
            recording.finish()
            if (playerCreated) instrumentation.runOnMainSync { player.release() }
            surface.release()
            texture.release()
        }
    }
}

private data class FixtureDefinition(
    val asset: String,
    val sha256: String,
    val mimeType: String,
)

private data class FixtureResult(
    val mimeType: String,
    val mapUpdates: Int,
    val seekByte: Long,
)

private data class FixturePlayerSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val renderedFrames: Long,
    val playbackState: Int,
    val seekable: Boolean,
)

private class AppendableGrowingRecording(
    private val bytes: ByteArray,
    initialBytes: Int,
) : GrowingRecordingFileLease {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val temporaryEnd = CountDownLatch(1)
    private val nonzeroOpen = CountDownLatch(1)
    private val growthRead = CountDownLatch(1)
    private val retryOpen = CountDownLatch(1)
    private val firstNonzeroOpen = AtomicLong(NO_OPEN_POSITION)
    private val retryOpenByte = AtomicLong(NO_OPEN_POSITION)
    private val retryFailureDelivered = AtomicBoolean(false)
    private val opens = Collections.synchronizedList(mutableListOf<Long>())

    @Volatile
    private var availableBytes = initialBytes

    @Volatile
    private var finished = false

    @Volatile
    private var retryFailureArmed = false

    override val isCurrent: Boolean = true

    override suspend fun open(
        position: Long,
    ): RecordingFileResult<GrowingRecordingFileReader> {
        if (position !in 0L..availableBytes.toLong()) {
            return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        opens += position
        if (retryFailureDelivered.get()) {
            retryOpenByte.compareAndSet(NO_OPEN_POSITION, position)
            retryOpen.countDown()
        }
        if (position > 0L) {
            firstNonzeroOpen.compareAndSet(NO_OPEN_POSITION, position)
            nonzeroOpen.countDown()
        }
        return RecordingFileResult.Ok(Reader(position.toInt()))
    }

    fun appendRemaining() {
        lock.withLock {
            check(!finished)
            availableBytes = bytes.size
            retryFailureArmed = true
            changed.signalAll()
        }
    }

    fun finish() {
        lock.withLock {
            availableBytes = bytes.size
            finished = true
            changed.signalAll()
        }
    }

    fun awaitTemporaryEnd(): Boolean = temporaryEnd.await(FIXTURE_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitNonzeroOpen(): Boolean = nonzeroOpen.await(FIXTURE_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitGrowthRead(): Boolean = growthRead.await(FIXTURE_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun awaitRetryOpen(): Boolean = retryOpen.await(FIXTURE_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun firstNonzeroOpenPosition(): Long = firstNonzeroOpen.get().also { position ->
        check(position != NO_OPEN_POSITION)
    }

    fun retryOpenPosition(): Long = retryOpenByte.get().also { position ->
        check(position != NO_OPEN_POSITION)
    }

    fun openPositions(): List<Long> = synchronized(opens) { opens.toList() }

    private inner class Reader(
        private var position: Int,
    ) : GrowingRecordingFileReader {
        @Volatile
        private var closed = false

        override suspend fun read(
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> = lock.withLock {
            while (!closed && !finished && position >= availableBytes) {
                temporaryEnd.countDown()
                changed.await()
            }
            if (closed || position >= availableBytes && finished) {
                return@withLock RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
            }
            if (retryFailureArmed && position >= retryFailurePosition()) {
                retryFailureArmed = false
                retryFailureDelivered.set(true)
                return@withLock RecordingFileResult.Failed(RecordingFileFailure.TIMEOUT)
            }
            val copied = minOf(length, availableBytes - position)
            bytes.copyInto(destination, destinationOffset, position, position + copied)
            position += copied
            if (position > bytes.size / 2) growthRead.countDown()
            RecordingFileResult.Ok(copied)
        }

        override suspend fun close(): RecordingFileResult<Unit> {
            lock.withLock {
                closed = true
                changed.signalAll()
            }
            return RecordingFileResult.Ok(Unit)
        }
    }

    private fun retryFailurePosition(): Int =
        bytes.size * 3 / 4 / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES
}

private fun awaitFixtureState(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    failure: AtomicReference<PlaybackException?>,
    predicate: (FixturePlayerSnapshot) -> Boolean,
): FixturePlayerSnapshot {
    val deadline = SystemClock.elapsedRealtime() + FIXTURE_ASSERTION_TIMEOUT_SECONDS * 1_000L
    while (SystemClock.elapsedRealtime() < deadline) {
        failure.get()?.let { error -> throw AssertionError("Fixture playback failed", error) }
        val snapshot = fixturePlayerSnapshot(instrumentation, player)
        if (predicate(snapshot)) return snapshot
        Thread.sleep(FIXTURE_POLL_INTERVAL_MS)
    }
    throw AssertionError("Fixture playback did not reach the required production-source state")
}

private fun fixturePlayerSnapshot(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
): FixturePlayerSnapshot {
    val snapshot = AtomicReference<FixturePlayerSnapshot>()
    instrumentation.runOnMainSync {
        snapshot.set(
            FixturePlayerSnapshot(
                positionMs = player.currentPosition,
                durationMs = player.duration,
                renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                playbackState = player.playbackState,
                seekable = player.isCurrentMediaItemSeekable,
            ),
        )
    }
    return snapshot.get()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private val FIXTURES = listOf(
    FixtureDefinition(
        asset = "p7-f1/pass-through.ts",
        sha256 = "ac5450c47d40b34277e3c304392f2476273717c9fcf8c91b78252702052a2447",
        mimeType = MimeTypes.VIDEO_MPEG2,
    ),
    FixtureDefinition(
        asset = "p7-f3/h264-synthetic.ts",
        sha256 = "46381f4fd260a7fefccddca432223e41bd04ab4d32c1406bbedc67d97227d950",
        mimeType = MimeTypes.VIDEO_H264,
    ),
)
private val FIXTURE_RECORDING_ID = RecordingId(7L)
private const val FIXTURE_READ_AHEAD_BYTES: Int = GROWING_TS_PACKET_BYTES * 256
private const val NO_OPEN_POSITION: Long = -1L
private const val SEEK_POSITION_TOLERANCE_MS: Long = 2_000L
private const val PLAY_PAST_SEEK_TARGET_MS: Long = 4_000L
private const val MINIMUM_PLAY_PAST_SEEK_TARGET_MS: Long = 1_000L
private const val PLAYBACK_TAIL_MS: Long = 500L
private const val MAP_UPDATE_BOUND_MS: Long = MINIMUM_GROWING_TS_MAP_ADVANCE_US / 1_000L
private const val FIXTURE_PLAYBACK_SPEED: Float = 4f
private const val FIXTURE_ASSERTION_TIMEOUT_SECONDS: Long = 45L
private const val FIXTURE_POLL_INTERVAL_MS: Long = 50L
private const val FIXTURE_TEST_TIMEOUT_MS: Long = 180_000L
