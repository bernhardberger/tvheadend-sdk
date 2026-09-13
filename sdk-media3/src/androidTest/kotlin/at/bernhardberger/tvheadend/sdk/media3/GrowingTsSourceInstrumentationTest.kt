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
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
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
        val mediaIdentity = RecordingMediaIdentity()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val failure = AtomicReference<PlaybackException?>()
        val videoMimeTypes = Collections.synchronizedSet(mutableSetOf<String>())
        val durations = Collections.synchronizedList(mutableListOf<Long>())
        lateinit var player: ExoPlayer
        var playerCreated = false

        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                ).setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 1_000, 100, 100)
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
                        identity = mediaIdentity,
                        readAheadBytes = FIXTURE_READ_AHEAD_BYTES,
                    ),
                )
                player.prepare()
            }

            val initialTimeline = awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.seekable && snapshot.durationMs > 5_000L && snapshot.renderedFrames > 0L &&
                    fixture.mimeType in videoMimeTypes && snapshot.playbackState == Player.STATE_READY
            }
            assertEquals("Duration discovery must not move paused playback", 0L, initialTimeline.positionMs)
            assertTrue("Initial recorded extent must be about12s", kotlin.math.abs(initialTimeline.durationMs - 12_000L) < 500L)
            val targetMs = initialTimeline.durationMs * 3 / 4
            val frameBaseline = initialTimeline.renderedFrames
            instrumentation.runOnMainSync { player.seekTo(targetMs) }
            val sought = awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.renderedFrames > frameBaseline && snapshot.playbackState == Player.STATE_READY &&
                    kotlin.math.abs(snapshot.positionMs - targetMs) <= SEEK_POSITION_TOLERANCE_MS
            }
            instrumentation.runOnMainSync { assertTrue("Seek must preserve pause", !player.playWhenReady) }
            recording.appendThreeQuarters()
            val afterGrowth = awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.durationMs > initialTimeline.durationMs + 5_000L
            }
            assertEquals("Growth must not reset position", sought.positionMs, afterGrowth.positionMs)
            assertTrue("Three-quarter extent must be about18s", kotlin.math.abs(afterGrowth.durationMs - 18_000L) < 500L)
            val appendedTargetMs = afterGrowth.durationMs * 3 / 4
            instrumentation.runOnMainSync { player.seekTo(appendedTargetMs); player.play() }
            awaitFixtureState(instrumentation, player, failure) { snapshot ->
                snapshot.renderedFrames > afterGrowth.renderedFrames + 5 &&
                    snapshot.positionMs >= appendedTargetMs && snapshot.playbackState == Player.STATE_READY
            }
            recording.appendRemaining()
            var previous = fixturePlayerSnapshot(instrumentation, player)
            val observeUntil = SystemClock.elapsedRealtime() + 7_000L
            while (SystemClock.elapsedRealtime() < observeUntil) {
                SystemClock.sleep(100L)
                val next = fixturePlayerSnapshot(instrumentation, player)
                assertNull("Playback failed during growth", failure.get())
                assertTrue("Unsolicited position reset during growth", next.positionMs >= previous.positionMs)
                assertTrue("Timeline end regressed during growth", next.durationMs >= previous.durationMs)
                previous = next
            }
            assertTrue("Full recorded extent must be about24s", kotlin.math.abs(previous.durationMs - 24_000L) < 500L)
            assertTrue("Playback transport failure must retry", recording.awaitRetryOpen())
            assertTrue("Ordered duration events must not regress", synchronized(durations) {
                durations.zipWithNext().all { (before, after) -> after >= before }
            })
            recording.finish()
            awaitFixtureState(instrumentation, player, failure) { it.playbackState == Player.STATE_ENDED }
            val beforeBackwardSeek = fixturePlayerSnapshot(instrumentation, player)
            instrumentation.runOnMainSync { player.pause(); player.seekTo(2_000L) }
            awaitFixtureState(instrumentation, player, failure) {
                it.playbackState == Player.STATE_READY && it.renderedFrames > beforeBackwardSeek.renderedFrames &&
                    kotlin.math.abs(it.positionMs - 2_000L) <= SEEK_POSITION_TOLERANCE_MS
            }
            assertNull("Production fixture playback failed", failure.get())
            val distinctMapUpdates = durations.size
            assertTrue(recording.openPositions().all { position -> position % GROWING_TS_PACKET_BYTES == 0L })
            return FixtureResult(fixture.mimeType, distinctMapUpdates, recording.openPositions().max())
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
    private val retryOpen = CountDownLatch(1)
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

    fun appendThreeQuarters() {
        lock.withLock {
            availableBytes = bytes.size * 3 / 4 / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES
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

    fun awaitRetryOpen(): Boolean = retryOpen.await(FIXTURE_ASSERTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    fun openPositions(): List<Long> = synchronized(opens) { opens.toList() }

    private inner class Reader(
        private var position: Int,
    ) : GrowingRecordingFileReader {
        override val sizeBytes: Long = availableBytes.toLong()
        override val isFinal: Boolean get() = finished
        override suspend fun refreshSize(): RecordingFileResult<Long?> = RecordingFileResult.Ok(availableBytes.toLong())
        override suspend fun seek(position: Long): RecordingFileResult<Unit> = lock.withLock {
            check(position in 0..availableBytes.toLong())
            this.position = position.toInt()
            RecordingFileResult.Ok(Unit)
        }
        private val openedAfterRetry = retryFailureDelivered.get()
        @Volatile
        private var closed = false

        override suspend fun read(
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> = lock.withLock {
            if (openedAfterRetry && length == FIXTURE_READ_AHEAD_BYTES) retryOpen.countDown()
            while (!closed && !finished && position >= availableBytes) {
                changed.await()
            }
            if (closed || position >= availableBytes && finished) {
                return@withLock RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
            }
            // Only the playback reader uses this configured chunk size; duration probes must
            // not consume the deliberately injected playback-loader retry.
            if (retryFailureArmed && length == FIXTURE_READ_AHEAD_BYTES && position >= retryFailurePosition()) {
                retryFailureArmed = false
                retryFailureDelivered.set(true)
                return@withLock RecordingFileResult.Failed(RecordingFileFailure.TIMEOUT)
            }
            val copied = minOf(length, availableBytes - position)
            bytes.copyInto(destination, destinationOffset, position, position + copied)
            position += copied
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
private const val FIXTURE_READ_AHEAD_BYTES: Int = GROWING_TS_PACKET_BYTES * 256
private const val SEEK_POSITION_TOLERANCE_MS: Long = 2_000L
private const val FIXTURE_ASSERTION_TIMEOUT_SECONDS: Long = 45L
private const val FIXTURE_POLL_INTERVAL_MS: Long = 50L
private const val FIXTURE_TEST_TIMEOUT_MS: Long = 180_000L
