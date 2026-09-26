@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.app.Instrumentation
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.DvrPlaybackProgress
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.core.DvrProgressResult
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resumes growing recordings on a real, playing ExoPlayer through the production coordinator,
 * growing source, extractor and resume helper while the fixture keeps growing at the recording
 * rate. Each fixture starts half written (about 12 s) and never completes.
 *
 * Measurements are published as instrumentation status keys prefixed with `growing_resume_`.
 */
@RunWith(AndroidJUnit4::class)
internal class GrowingRecordingResumeInstrumentationTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun coordinator_resume_lands_near_saved_position_and_stop_reports_no_earlier_position() {
        val status = Bundle()
        RESUME_FIXTURES.forEach { fixture ->
            val saved = 8.seconds
            val reports = withCoordinatorSession(fixture, fixture.halfBytes(), saved) { session ->
                session.recording.startAppending()
                session.install()
                val seek = session.awaitResumeSeek()
                assertEquals("${fixture.key} resume target", saved.inWholeMilliseconds, seek.targetMs)
                val landedMs = session.awaitPlayingPositionAfterSeek()
                assertTrue(
                    "${fixture.key} landed at $landedMs ms instead of about ${seek.targetMs} ms",
                    abs(landedMs - seek.targetMs) <= RESUME_POSITION_TOLERANCE_MS,
                )
                status.putLong("growing_resume_${fixture.key}_prepare_to_seek_ms", seek.afterPrepareMs)
                status.putLong("growing_resume_${fixture.key}_saved_ms", saved.inWholeMilliseconds)
                status.putLong("growing_resume_${fixture.key}_landed_ms", landedMs)
                status.putLong("growing_resume_${fixture.key}_video_frames_before_seek", seek.videoFramesBefore)
                status.putLong("growing_resume_${fixture.key}_audio_buffers_before_seek", seek.audioBuffersBefore)
                SystemClock.sleep(PLAY_AFTER_LANDING_MS)
                session.coordinator.stop()
            }
            assertTrue("${fixture.key} stop after resume must report progress", reports.isNotEmpty())
            reports.forEach { progress ->
                assertTrue(
                    "${fixture.key} reported ${progress.position} before the saved $saved",
                    progress.position >= saved,
                )
            }
            status.putLong("growing_resume_${fixture.key}_last_report_s", reports.last().position.inWholeSeconds)
        }
        instrumentation.sendStatus(0, status)
    }

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun coordinator_resume_within_the_edge_margin_lands_before_the_live_extent() {
        val status = Bundle()
        RESUME_FIXTURES.forEach { fixture ->
            val saved = 11.seconds
            withCoordinatorSession(fixture, fixture.halfBytes(), saved) { session ->
                session.onResumeSeek = { session.recording.startAppending() }
                session.install()
                val seek = session.awaitResumeSeek()
                val savedMs = saved.inWholeMilliseconds
                assertTrue(
                    "${fixture.key} saved position must lie within the margin of the ${seek.durationMs} ms extent",
                    seek.durationMs > savedMs && seek.durationMs - savedMs < GROWING_RESUME_EDGE_MARGIN_MILLIS,
                )
                val expected = minOf(savedMs, seek.durationMs - GROWING_RESUME_EDGE_MARGIN_MILLIS)
                assertEquals("${fixture.key} edge resume target", expected, seek.targetMs)
                val landedMs = session.awaitPlayingPositionAfterSeek()
                assertTrue(
                    "${fixture.key} edge resume landed at $landedMs ms instead of about $expected ms",
                    abs(landedMs - expected) <= RESUME_POSITION_TOLERANCE_MS,
                )
                status.putLong("growing_resume_${fixture.key}_edge_extent_ms", seek.durationMs)
                status.putLong("growing_resume_${fixture.key}_edge_target_ms", seek.targetMs)
                status.putLong("growing_resume_${fixture.key}_edge_landed_ms", landedMs)
                session.coordinator.stop()
            }
        }
        instrumentation.sendStatus(0, status)
    }

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun coordinator_stop_before_the_resume_applies_writes_no_earlier_position() {
        RESUME_FIXTURES.forEach { fixture ->
            val saved = 8.seconds
            // Two seconds of media is shorter than the edge margin, so the resume must wait for a
            // later extent while playback runs from 0:00 and cadence checkpoints fall due.
            val initialBytes = fixture.bytesFor(seconds = 2)
            val reports = withCoordinatorSession(fixture, initialBytes, saved) { session ->
                session.install()
                val early = session.awaitState { it.isPlaying && it.positionMs > 0L && it.renderedFrames > 0L }
                SystemClock.sleep(STOP_BEFORE_SETTLE_PLAY_MS)
                assertNull("${fixture.key} resume must still be pending", session.resumeSeek.get())
                assertTrue(early.positionMs < saved.inWholeMilliseconds)
                session.coordinator.stop()
                assertNull("${fixture.key} stopped target must not seek", session.resumeSeek.get())
            }
            assertEquals("${fixture.key} must keep the saved server position", emptyList<DvrPlaybackProgress>(), reports)
        }
    }

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun viewer_seek_before_the_growing_timeline_is_seekable_cancels_resume() {
        RESUME_FIXTURES.forEach { fixture ->
            withResumeHelper(fixture, current = true) { session ->
                session.recording.startAppending()
                instrumentation.runOnMainSync {
                    session.resume.beginPlaybackTarget(8_000L.milliseconds)
                    session.player.prepare()
                    session.player.seekTo(2_000L)
                    assertEquals(RecordingResumeOutcome.CANCELLED, session.resume.outcome)
                    assertNull(session.resume.pendingPosition)
                }
                session.probe.awaitState { it.seekable && it.isPlaying && it.renderedFrames > 0L }
                val observeUntil = SystemClock.elapsedRealtime() + 3_000L
                while (SystemClock.elapsedRealtime() < observeUntil) {
                    assertTrue(
                        "Cancelled resume must not jump to the saved position",
                        session.probe.snapshot().positionMs < 8_000L - RESUME_POSITION_TOLERANCE_MS,
                    )
                    SystemClock.sleep(RESUME_POLL_INTERVAL_MS)
                }
            }
        }
    }

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun growing_timeline_that_never_becomes_seekable_abandons_while_playing() {
        val fixture = RESUME_FIXTURES.last()
        withResumeHelper(fixture, current = false) { session ->
            session.recording.startAppending()
            instrumentation.runOnMainSync {
                session.resume.beginPlaybackTarget(8_000L.milliseconds)
                session.player.prepare()
            }
            session.probe.awaitState { it.renderedFrames > 0L && it.isPlaying }
            instrumentation.runOnMainSync { assertNull(session.resume.outcome) }
            val deadline = SystemClock.elapsedRealtime() + GROWING_RESUME_SETTLE_TIMEOUT_MILLIS + 5_000L
            var outcome: RecordingResumeOutcome? = null
            while (outcome == null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(RESUME_POLL_INTERVAL_MS)
                instrumentation.runOnMainSync { outcome = session.resume.outcome }
            }
            assertEquals(RecordingResumeOutcome.ABANDONED, outcome)
            assertFalse(session.probe.snapshot().seekable)
            assertNull("Abandoned resume must not seek", session.probe.seek.get())
        }
    }

    private fun withCoordinatorSession(
        fixture: ResumeFixture,
        initialBytes: Int,
        saved: Duration,
        block: suspend (CoordinatorSession) -> Unit,
    ): List<DvrPlaybackProgress> {
        val recording = GrowingFixtureRecording(fixture.load(), initialBytes, isCurrent = true)
        val target = FixtureRecordingTarget(recording, saved)
        withPlayer(recording) { player, probe ->
            val coordinator = createTvheadendPlaybackCoordinator(
                player = player,
                progressPolicy = DvrProgressPolicy(checkpointInterval = 1.seconds),
            )
            val session = CoordinatorSession(coordinator, target, recording, probe)
            probe.onSeek = { session.onResumeSeek() }
            runBlocking {
                coordinator.withLifetime(COORDINATOR_DRAIN_TIMEOUT) { block(session) }
            }
        }
        return target.reports
    }

    private fun withResumeHelper(
        fixture: ResumeFixture,
        current: Boolean,
        block: (HelperSession) -> Unit,
    ) {
        val recording = GrowingFixtureRecording(fixture.load(), fixture.halfBytes(), isCurrent = current)
        val identity = RecordingMediaIdentity()
        withPlayer(recording) { player, probe ->
            var resume: TvheadendRecordingResume? = null
            instrumentation.runOnMainSync {
                player.setMediaSource(
                    createTvheadendGrowingRecordingMediaSource(
                        lease = recording,
                        identity = identity,
                        readAheadBytes = RESUME_READ_AHEAD_BYTES,
                    ),
                )
                resume = createTvheadendRecordingResume(player, identity, RecordingResumeMode.GROWING)
            }
            val active = checkNotNull(resume)
            try {
                block(HelperSession(player, active, recording, probe))
            } finally {
                instrumentation.runOnMainSync { active.close() }
            }
        }
    }

    private fun withPlayer(recording: GrowingFixtureRecording, block: (ExoPlayer, ResumePlayerProbe) -> Unit) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        var created: ExoPlayer? = null
        try {
            val probe = AtomicReference<ResumePlayerProbe>()
            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                ).setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(500, 1_000, 100, 100)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .setBackBuffer(0, false)
                        .build(),
                ).build()
                created = player
                player.volume = 0f
                player.setVideoSurface(surface)
                player.playWhenReady = true
                probe.set(ResumePlayerProbe(instrumentation, player).also(player::addListener))
            }
            block(checkNotNull(created), probe.get())
        } finally {
            recording.close()
            created?.let { player -> instrumentation.runOnMainSync { player.release() } }
            surface.release()
            texture.release()
        }
    }

    private inner class CoordinatorSession(
        val coordinator: TvheadendPlaybackCoordinator,
        private val target: FixtureRecordingTarget,
        val recording: GrowingFixtureRecording,
        private val probe: ResumePlayerProbe,
    ) {
        var onResumeSeek: () -> Unit = {}
        val resumeSeek: AtomicReference<ResumeSeek?> get() = probe.seek

        suspend fun install() {
            val result = coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME)
            assertEquals(PlaybackTargetResult.STARTED, result)
        }

        fun awaitResumeSeek(): ResumeSeek {
            probe.awaitState { probe.seek.get() != null }
            return checkNotNull(probe.seek.get())
        }

        fun awaitPlayingPositionAfterSeek(): Long =
            probe.awaitState { probe.seek.get() != null && it.isPlaying && it.playbackState == Player.STATE_READY }
                .positionMs

        fun awaitState(predicate: (ResumePlayerSnapshot) -> Boolean): ResumePlayerSnapshot = probe.awaitState(predicate)
    }

    private class HelperSession(
        val player: ExoPlayer,
        val resume: TvheadendRecordingResume,
        val recording: GrowingFixtureRecording,
        val probe: ResumePlayerProbe,
    )
}

/** Records the first seek and its timing on the application looper; all other reads hop to it. */
private class ResumePlayerProbe(
    private val instrumentation: Instrumentation,
    private val player: ExoPlayer,
) : Player.Listener {
    val seek = AtomicReference<ResumeSeek?>()
    private val failure = AtomicReference<PlaybackException?>()
    private var preparedAt = -1L
    var onSeek: () -> Unit = {}

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING && preparedAt < 0L) preparedAt = SystemClock.elapsedRealtime()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || seek.get() != null) return
        seek.set(
            ResumeSeek(
                targetMs = newPosition.positionMs,
                durationMs = player.duration,
                afterPrepareMs = SystemClock.elapsedRealtime() - preparedAt,
                videoFramesBefore = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                audioBuffersBefore = player.audioDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
            ),
        )
        onSeek()
    }

    override fun onPlayerError(error: PlaybackException) {
        failure.compareAndSet(null, error)
    }

    fun snapshot(): ResumePlayerSnapshot {
        val snapshot = AtomicReference<ResumePlayerSnapshot>()
        instrumentation.runOnMainSync {
            snapshot.set(
                ResumePlayerSnapshot(
                    positionMs = player.currentPosition,
                    renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                    playbackState = player.playbackState,
                    isPlaying = player.isPlaying,
                    seekable = player.isCurrentMediaItemSeekable,
                ),
            )
        }
        return snapshot.get()
    }

    fun awaitState(predicate: (ResumePlayerSnapshot) -> Boolean): ResumePlayerSnapshot {
        val deadline = SystemClock.elapsedRealtime() + RESUME_ASSERTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { error -> throw AssertionError("Fixture playback failed", error) }
            val snapshot = snapshot()
            if (predicate(snapshot)) return snapshot
            SystemClock.sleep(RESUME_POLL_INTERVAL_MS)
        }
        throw AssertionError("Growing resume did not reach the required player state")
    }
}

private class FixtureRecordingTarget(
    private val lease: GrowingRecordingFileLease,
    savedPosition: Duration,
) : CoordinatorRecordingTarget {
    private val captured = mutableListOf<DvrPlaybackProgress>()

    val reports: List<DvrPlaybackProgress>
        get() = synchronized(captured) { captured.toList() }

    override val startedGrowing: Boolean = true

    override val admission: CoordinatorRecordingAdmission = CoordinatorRecordingAdmission.GrowingStartOverOnly(
        progressCapability = RecordingProgressCapability.SUPPORTED,
        resumePosition = savedPosition,
    )

    override suspend fun openRecording(): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)

    override fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> = RecordingFileResult.Ok(lease)

    override suspend fun reportProgress(
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        synchronized(captured) { captured += progress }
        return DvrProgressResult.Accepted
    }
}

private class ResumeFixture(val key: String, val asset: String, val sha256: String, val durationSeconds: Int) {
    private var bytes: ByteArray? = null

    fun load(): ByteArray = bytes ?: InstrumentationRegistry.getInstrumentation().context.assets.open(asset)
        .use { input -> input.readBytes() }
        .also { loaded ->
            assertEquals(sha256, loaded.sha256())
            bytes = loaded
        }

    fun halfBytes(): Int = load().size / 2 / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES

    fun bytesFor(seconds: Int): Int =
        load().size / durationSeconds * seconds / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES
}

private data class ResumePlayerSnapshot(
    val positionMs: Long,
    val renderedFrames: Long,
    val playbackState: Int,
    val isPlaying: Boolean,
    val seekable: Boolean,
)

private data class ResumeSeek(
    val targetMs: Long,
    val durationMs: Long,
    val afterPrepareMs: Long,
    val videoFramesBefore: Long,
    val audioBuffersBefore: Long,
)

/**
 * A recording that grows at its own media rate once [startAppending] is called and never
 * completes; readers block at the current end until more bytes arrive or the test closes it.
 */
private class GrowingFixtureRecording(
    private val bytes: ByteArray,
    initialBytes: Int,
    override val isCurrent: Boolean,
) : GrowingRecordingFileLease {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val appender = Executors.newSingleThreadScheduledExecutor()
    private val appending = AtomicBoolean(false)

    @Volatile
    private var availableBytes = initialBytes

    @Volatile
    private var closed = false

    override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> {
        if (position !in 0L..availableBytes.toLong()) {
            return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        return RecordingFileResult.Ok(Reader(position.toInt()))
    }

    fun startAppending() {
        if (!appending.compareAndSet(false, true)) return
        val perTick = (bytes.size.toLong() * APPEND_INTERVAL_MS / FIXTURE_DURATION_MS).toInt() /
            GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES
        appender.scheduleWithFixedDelay(
            {
                lock.withLock {
                    availableBytes = minOf(bytes.size, availableBytes + perTick)
                    changed.signalAll()
                }
            },
            APPEND_INTERVAL_MS,
            APPEND_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun close() {
        appender.shutdownNow()
        lock.withLock {
            closed = true
            changed.signalAll()
        }
    }

    private inner class Reader(private var position: Int) : GrowingRecordingFileReader {
        @Volatile
        private var readerClosed = false

        override val sizeBytes: Long = availableBytes.toLong()
        override val isFinal: Boolean = false

        override suspend fun refreshSize(): RecordingFileResult<Long?> = RecordingFileResult.Ok(availableBytes.toLong())

        override suspend fun seek(position: Long): RecordingFileResult<Unit> = lock.withLock {
            check(position in 0..availableBytes.toLong())
            this.position = position.toInt()
            RecordingFileResult.Ok(Unit)
        }

        override suspend fun read(
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> = lock.withLock {
            while (!closed && !readerClosed && position >= availableBytes) changed.await()
            if (closed || readerClosed) return@withLock RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
            val copied = minOf(length, availableBytes - position)
            bytes.copyInto(destination, destinationOffset, position, position + copied)
            position += copied
            RecordingFileResult.Ok(copied)
        }

        override suspend fun close(): RecordingFileResult<Unit> {
            lock.withLock {
                readerClosed = true
                changed.signalAll()
            }
            return RecordingFileResult.Ok(Unit)
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private val RESUME_FIXTURES = listOf(
    ResumeFixture(
        key = "mpeg2",
        asset = "p7-f1/pass-through.ts",
        sha256 = "ac5450c47d40b34277e3c304392f2476273717c9fcf8c91b78252702052a2447",
        durationSeconds = 24,
    ),
    ResumeFixture(
        key = "h264",
        asset = "p7-f3/h264-synthetic.ts",
        sha256 = "46381f4fd260a7fefccddca432223e41bd04ab4d32c1406bbedc67d97227d950",
        durationSeconds = 24,
    ),
)
private val COORDINATOR_DRAIN_TIMEOUT: Duration = 10.seconds
private const val FIXTURE_DURATION_MS: Long = 24_000L
private const val APPEND_INTERVAL_MS: Long = 250L
private const val RESUME_READ_AHEAD_BYTES: Int = GROWING_TS_PACKET_BYTES * 256

/** Both fixtures use 50-frame GOPs at 25 fps (2 s); 250 ms covers polling and playback advance. */
private const val RESUME_POSITION_TOLERANCE_MS: Long = 2_250L
private const val PLAY_AFTER_LANDING_MS: Long = 2_500L
private const val STOP_BEFORE_SETTLE_PLAY_MS: Long = 1_500L
private const val RESUME_ASSERTION_TIMEOUT_MS: Long = 45_000L
private const val RESUME_POLL_INTERVAL_MS: Long = 50L
private const val RESUME_TEST_TIMEOUT_MS: Long = 240_000L
