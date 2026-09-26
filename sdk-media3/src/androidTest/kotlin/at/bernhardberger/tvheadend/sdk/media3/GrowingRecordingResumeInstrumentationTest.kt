@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.app.Instrumentation
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * The coordinator runs on [Dispatchers.Default] and every wait in this class suspends, so its
 * actor, checkpoint ticker and report worker run during playback exactly as in an application.
 *
 * Landing is proven against the source by sample content. A test-only wrapper around the
 * production video renderer records, on the playback thread, the renderer reset of the resume
 * seek, every sample it reads with the size and CRC-32 of the sample data it received, and every
 * frame it renders; the wrapper only reads the input buffer and leaves its position and limit
 * unchanged. Before playback, each fixture is parsed on the device with the production TS
 * extractor, and its video keyframe samples are aligned with the FFmpeg keyframe table committed
 * next to the fixture, so each keyframe's data names exactly one source keyframe and its source
 * time. The landing keyframe, the first sample the renderer reads after the resume seek, is
 * identified by its data alone; which reader delivered it or how far readers ran ahead does not
 * matter, and a landing keyframe that matches no source keyframe fails the test. Player sample
 * times have a different origin than the fixture's source times, but within one post-seek
 * extractor run their differences are exact, so the first rendered frame's source time is the
 * landing keyframe's source time plus the rendered frame's distance from that keyframe's sample
 * time. Measurements are published as instrumentation status keys prefixed with
 * `growing_resume_`, even when an assertion fails; keyframe tables and landing observations are
 * published before they are validated.
 */
@RunWith(AndroidJUnit4::class)
internal class GrowingRecordingResumeInstrumentationTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun coordinator_resume_renders_near_saved_position_and_checkpoints_before_stop() = publishingStatus { status ->
        RESUME_FIXTURES.forEach { fixture ->
            val saved = 8.seconds
            val prefix = "growing_resume_${fixture.key}"
            status.putLong("${prefix}_saved_ms", saved.inWholeMilliseconds)
            val identities = fixture.keyframeIdentities(status, prefix)
            val target = withCoordinatorSession(fixture, fixture.halfBytes(), saved) { session ->
                session.recording.startAppending()
                session.install()
                val seek = session.awaitResumeSeek()
                seek.putInto(status, prefix)
                assertEquals("${fixture.key} resume target", saved.inWholeMilliseconds, seek.targetMs)
                val landing = session.awaitRenderedLanding(seek, identities, status, prefix)
                session.verifySourceLanding(fixture, identities, seek, landing, status, prefix)

                // Checkpoints must be written by the running cadence while playback continues,
                // not only by the terminal report that stop() produces.
                val checkpoints = session.awaitCheckpointsWhilePlaying()
                status.putInt("${prefix}_checkpoints_before_stop", checkpoints.size)
                status.putLong("${prefix}_first_checkpoint_s", checkpoints.first().position.inWholeSeconds)
                checkpoints.forEach { progress ->
                    assertFalse("${fixture.key} checkpoint must not mark watched", progress.markWatched)
                }
                // Stop while a checkpoint delivery is held at the reporting boundary, so the stop
                // report is queued behind it and can only arrive once the lifetime drains.
                session.stopWhileReportInFlight()
            }
            // The coordinator lifetime has drained; every delivery has reached the target.
            val reports = target.reports
            val afterStop = reports.filter(ReportRecord::afterStopRequested)
            afterStop.lastOrNull()?.let { status.putLong("${prefix}_stop_report_s", it.progress.position.inWholeSeconds) }
            assertEquals("${fixture.key} stop must deliver exactly one report", 1, afterStop.size)
            assertEquals("${fixture.key} stop report must be the last delivery", afterStop.single(), reports.last())
            reports.forEach { record ->
                assertTrue(
                    "${fixture.key} reported ${record.progress.position} before the saved $saved",
                    record.progress.position >= saved,
                )
            }
            val beforeStop = reports.filterNot(ReportRecord::afterStopRequested)
            assertTrue(
                "${fixture.key} stop report must not precede the checkpoints",
                afterStop.single().progress.position >= beforeStop.maxOf { it.progress.position },
            )
        }
    }

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun coordinator_resume_within_the_edge_margin_renders_before_the_live_extent() = publishingStatus { status ->
        RESUME_FIXTURES.forEach { fixture ->
            val saved = 11.seconds
            val prefix = "growing_resume_${fixture.key}_edge"
            val identities = fixture.keyframeIdentities(status, prefix)
            withCoordinatorSession(fixture, fixture.halfBytes(), saved) { session ->
                session.onResumeSeek = { session.recording.startAppending() }
                session.install()
                val seek = session.awaitResumeSeek()
                seek.putInto(status, prefix)
                status.putLong("${prefix}_extent_ms", seek.durationMs)
                status.putLong("${prefix}_target_ms", seek.targetMs)
                val savedMs = saved.inWholeMilliseconds
                assertTrue(
                    "${fixture.key} saved position must lie within the margin of the ${seek.durationMs} ms extent",
                    seek.durationMs > savedMs && seek.durationMs - savedMs < GROWING_RESUME_EDGE_MARGIN_MILLIS,
                )
                val expected = minOf(savedMs, seek.durationMs - GROWING_RESUME_EDGE_MARGIN_MILLIS)
                assertEquals("${fixture.key} edge resume target", expected, seek.targetMs)
                val landing = session.awaitRenderedLanding(seek, identities, status, prefix)
                session.verifySourceLanding(fixture, identities, seek, landing, status, prefix)
                session.coordinator.stop()
            }
        }
    }

    /** Publishes [status] even when an assertion fails, so a failed device run keeps its measurements. */
    private fun publishingStatus(block: (Bundle) -> Unit) {
        val status = Bundle()
        try {
            block(status)
        } finally {
            instrumentation.sendStatus(0, status)
        }
    }

    @Test(timeout = RESUME_TEST_TIMEOUT_MS)
    fun coordinator_stop_before_the_resume_applies_writes_no_earlier_position() {
        RESUME_FIXTURES.forEach { fixture ->
            val saved = 8.seconds
            // Two seconds of media is shorter than the edge margin, so the resume must wait for a
            // later extent while playback runs from 0:00 and several cadence intervals fall due.
            val initialBytes = fixture.bytesFor(seconds = 2)
            val target = withCoordinatorSession(fixture, initialBytes, saved) { session ->
                session.install()
                val early = session.awaitState { it.isPlaying && it.positionMs > 0L && it.renderedFrames > 0L }
                delay(STOP_BEFORE_SETTLE_PLAY_MS)
                assertNull("${fixture.key} resume must still be pending", session.resumeSeek.get())
                assertTrue(early.positionMs < saved.inWholeMilliseconds)
                assertEquals(
                    "${fixture.key} cadence must not checkpoint below the pending resume",
                    emptyList<DvrPlaybackProgress>(),
                    session.reports(),
                )
                session.coordinator.stop()
                assertNull("${fixture.key} stopped target must not seek", session.resumeSeek.get())
            }
            assertEquals("${fixture.key} must keep the saved server position", emptyList<ReportRecord>(), target.reports)
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
                    delay(RESUME_POLL_INTERVAL_MS)
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
                delay(RESUME_POLL_INTERVAL_MS)
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
    ): FixtureRecordingTarget {
        val recording = GrowingFixtureRecording(fixture.load(), initialBytes, isCurrent = true)
        val target = FixtureRecordingTarget(recording, saved)
        withPlayer(recording) { player, probe ->
            val coordinator = createTvheadendPlaybackCoordinator(
                player = player,
                progressPolicy = DvrProgressPolicy(checkpointInterval = 1.seconds),
            )
            val session = CoordinatorSession(coordinator, target, recording, probe)
            probe.onSeek = { session.onResumeSeek() }
            // The coordinator gets its own dispatcher rather than the instrumentation thread's
            // event loop, so the cadence cannot be starved by the test body.
            runBlocking(Dispatchers.Default) {
                coordinator.withLifetime(COORDINATOR_DRAIN_TIMEOUT) { block(session) }
            }
        }
        return target
    }

    private fun withResumeHelper(
        fixture: ResumeFixture,
        current: Boolean,
        block: suspend (HelperSession) -> Unit,
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
                runBlocking(Dispatchers.Default) { block(HelperSession(player, active, recording, probe)) }
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
            val videoLog = VideoRendererLog()
            val production = createTvheadendRenderersFactory(instrumentation.targetContext)
            val recordingFactory = RenderersFactory { handler, video, audio, text, metadata ->
                production.createRenderers(handler, video, audio, text, metadata).map { renderer ->
                    if (renderer.trackType == C.TRACK_TYPE_VIDEO) InputRecordingVideoRenderer(renderer, videoLog) else renderer
                }.toTypedArray()
            }
            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(instrumentation.targetContext, recordingFactory).setLoadControl(
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
                val playerProbe = ResumePlayerProbe(instrumentation, player, videoLog)
                player.addListener(playerProbe)
                player.setVideoFrameMetadataListener(playerProbe)
                probe.set(playerProbe)
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

        fun reports(): List<DvrPlaybackProgress> = target.reports.map(ReportRecord::progress)

        suspend fun install() {
            val result = coordinator.setRecordingTarget(target, RecordingPlaybackStart.RESUME)
            assertEquals(PlaybackTargetResult.STARTED, result)
        }

        suspend fun awaitResumeSeek(): ResumeSeek {
            probe.awaitState { probe.seek.get() != null }
            return checkNotNull(probe.seek.get())
        }

        /**
         * Awaits the validated landing. Whatever the renderer log already shows is published first,
         * so a landing that fails validation or never completes still carries its observations.
         */
        suspend fun awaitRenderedLanding(
            seek: ResumeSeek,
            identities: FixtureKeyframeIdentities,
            status: Bundle,
            prefix: String,
        ): RenderedLanding {
            var landing: RenderedLanding? = null
            try {
                probe.awaitState { snapshot ->
                    probe.publishLandingObservations(seek, identities, status, prefix)
                    landing = probe.renderedLanding(seek)
                    landing != null && snapshot.isPlaying
                }
            } finally {
                probe.publishLandingObservations(seek, identities, status, prefix)
            }
            return checkNotNull(landing)
        }

        /**
         * Bounds the source time of the first rendered frame. The landing keyframe's data names
         * one source keyframe; shifted by the rendered frame's distance from the keyframe
         * sample, its source time must lie near the resume target.
         */
        fun verifySourceLanding(
            fixture: ResumeFixture,
            identities: FixtureKeyframeIdentities,
            seek: ResumeSeek,
            landing: RenderedLanding,
            status: Bundle,
            prefix: String,
        ) {
            val verdict = identities.verifyRenderedLanding(
                keyframe = landing.keyframeIdentity,
                keyframeToFirstFrameUs = landing.keyframeToFirstFrameUs,
                targetMs = seek.targetMs,
            )
            if (verdict is FixtureLanding.Verified) {
                status.putLong("${prefix}_first_rendered_source_min_ms", verdict.firstFrameSourceMs.first)
                status.putLong("${prefix}_first_rendered_source_max_ms", verdict.firstFrameSourceMs.last)
            }
            assertTrue("${fixture.key} landing source check: $verdict", verdict is FixtureLanding.Verified)
        }

        suspend fun awaitCheckpointsWhilePlaying(): List<DvrPlaybackProgress> {
            val snapshot = probe.awaitState { target.reports.isNotEmpty() }
            assertTrue(
                "Checkpoints must be written while playback continues",
                snapshot.playbackState == Player.STATE_READY || snapshot.playbackState == Player.STATE_BUFFERING,
            )
            return reports()
        }

        suspend fun stopWhileReportInFlight() {
            val held = target.holdNextReport()
            withTimeout(REPORT_HOLD_TIMEOUT) { held.reached.await() }
            target.stopRequested = true
            coordinator.stop()
            held.release.complete(Unit)
        }

        suspend fun awaitState(predicate: (ResumePlayerSnapshot) -> Boolean): ResumePlayerSnapshot =
            probe.awaitState(predicate)
    }

    private class HelperSession(
        val player: ExoPlayer,
        val resume: TvheadendRecordingResume,
        val recording: GrowingFixtureRecording,
        val probe: ResumePlayerProbe,
    )
}

/**
 * Records the first seek and every rendered video frame. Player callbacks run on the application
 * looper; frame metadata runs on the playback thread and joins [videoLog] in renderer order. All
 * other reads hop to the application looper.
 */
private class ResumePlayerProbe(
    private val instrumentation: Instrumentation,
    private val player: ExoPlayer,
    val videoLog: VideoRendererLog,
) : Player.Listener, VideoFrameMetadataListener {
    val seek = AtomicReference<ResumeSeek?>()
    private val failure = AtomicReference<PlaybackException?>()
    private val preparedAtNanos = AtomicLong(-1L)
    private val frames = mutableListOf<RenderedFrame>()
    var onSeek: () -> Unit = {}

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING) {
            preparedAtNanos.compareAndSet(-1L, SystemClock.elapsedRealtimeNanos())
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || seek.get() != null) return
        val now = SystemClock.elapsedRealtimeNanos()
        seek.set(
            ResumeSeek(
                targetMs = newPosition.positionMs,
                durationMs = player.duration,
                atNanos = now,
                afterPrepareMs = (now - preparedAtNanos.get()) / NANOS_PER_MILLI,
                framesRenderedBefore = synchronized(frames) { frames.size.toLong() },
                videoDecoderBuffersBefore = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                audioDecoderBuffersBefore = player.audioDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
            ),
        )
        onSeek()
    }

    override fun onPlayerError(error: PlaybackException) {
        failure.compareAndSet(null, error)
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        val atNanos = SystemClock.elapsedRealtimeNanos()
        synchronized(frames) { frames += RenderedFrame(presentationTimeUs, atNanos) }
        videoLog.record(VideoRendererEvent.Rendered(presentationTimeUs, atNanos))
    }

    /**
     * Returns the first frame the video renderer rendered after its reset to the resume target,
     * with the first sample it read after that reset, once [LANDING_CONTINUOUS_FRAMES] frames
     * continue it with strictly increasing, closely spaced presentation times. The reset flushes
     * the decoder, so every later frame is post-seek output. A second reset before the landing
     * frame or broken continuity fails the test instead of choosing another frame.
     */
    fun renderedLanding(seek: ResumeSeek): RenderedLanding? {
        val resumed = eventsAfterResumeReset(seek)?.second ?: return null
        val rendered = resumed.filterIsInstance<VideoRendererEvent.Rendered>()
        if (rendered.size < LANDING_CONTINUOUS_FRAMES) return null
        val landing = rendered.first()
        val beforeLanding = resumed.takeWhile { event -> event !== landing }
        assertEquals(
            "Renderer was reset again before the first resumed frame",
            emptyList<VideoRendererEvent>(),
            beforeLanding.filterIsInstance<VideoRendererEvent.Reset>(),
        )
        val keyframe = beforeLanding.filterIsInstance<VideoRendererEvent.Input>().firstOrNull()
        assertTrue("No sample was read before the first resumed frame", keyframe != null)
        assertTrue("The first sample read after the resume seek is not a keyframe: $keyframe", checkNotNull(keyframe).keyframe)
        rendered.take(LANDING_CONTINUOUS_FRAMES).zipWithNext().forEach { (previous, next) ->
            val step = next.presentationTimeUs - previous.presentationTimeUs
            assertTrue(
                "Resumed output is not continuous: ${previous.presentationTimeUs} us then ${next.presentationTimeUs} us",
                step in 1..MAX_RESUMED_FRAME_STEP_US,
            )
        }
        return RenderedLanding(
            presentationTimeUs = landing.presentationTimeUs,
            keyframeSampleTimeUs = keyframe.timeUs,
            keyframeIdentity = keyframe.identity,
        )
    }

    /**
     * Publishes what the renderer log shows after the resume seek without judging it: the reset,
     * the first sample read before the first rendered frame with its data identity and matched
     * source keyframe, and the first rendered frames. Absent observations are left out or marked,
     * so a run that fails [renderedLanding] or never completes it still reports its inputs.
     */
    fun publishLandingObservations(
        seek: ResumeSeek,
        identities: FixtureKeyframeIdentities,
        status: Bundle,
        prefix: String,
    ) {
        val (reset, resumed) = eventsAfterResumeReset(seek) ?: run {
            status.putBoolean("${prefix}_landing_reset_found", false)
            return
        }
        status.putBoolean("${prefix}_landing_reset_found", true)
        status.putLong("${prefix}_landing_reset_position_us", reset.periodPositionUs)
        val rendered = resumed.filterIsInstance<VideoRendererEvent.Rendered>()
        val first = rendered.firstOrNull()
        val beforeFirst = if (first == null) resumed else resumed.takeWhile { event -> event !== first }
        status.putInt("${prefix}_landing_resets_before_first_frame", beforeFirst.count { it is VideoRendererEvent.Reset })
        status.putInt("${prefix}_landing_inputs_before_first_frame", beforeFirst.count { it is VideoRendererEvent.Input })
        status.putInt("${prefix}_landing_rendered_frames", rendered.size)
        status.putString(
            "${prefix}_landing_rendered_pts_us",
            rendered.take(LANDING_CONTINUOUS_FRAMES).joinToString(",") { it.presentationTimeUs.toString() },
        )
        val keyframe = beforeFirst.filterIsInstance<VideoRendererEvent.Input>().firstOrNull()
        if (keyframe != null) {
            status.putLong("${prefix}_landing_input_keyframe_pts_ms", keyframe.timeUs / MICROS_PER_MILLI)
            status.putBoolean("${prefix}_landing_input_is_keyframe", keyframe.keyframe)
            val identity = keyframe.identity
            status.putLong("${prefix}_k_size", identity?.sizeBytes?.toLong() ?: -1L)
            status.putString("${prefix}_k_crc32", identity?.let { "%08x".format(it.crc32) } ?: "none")
            val source = identity?.let(identities::identify)
            status.putString(
                "${prefix}_k_match",
                when {
                    identity == null -> "no_sample_data"
                    source == null -> "unmatched"
                    else -> "matched"
                },
            )
            status.putLong("${prefix}_k_matched_source_ms", source?.sourceMs ?: -1L)
            status.putLong("${prefix}_k_matched_byte", source?.bytePosition ?: -1L)
        }
        if (first != null) {
            status.putLong(
                "${prefix}_prepare_to_first_post_seek_frame_ms",
                (first.atNanos - preparedAtNanos.get()) / NANOS_PER_MILLI,
            )
            status.putLong("${prefix}_first_post_seek_frame_pts_ms", first.presentationTimeUs / MICROS_PER_MILLI)
            keyframe?.let { status.putLong("${prefix}_first_frame_after_keyframe_us", first.presentationTimeUs - it.timeUs) }
        }
    }

    /** The renderer reset to the resume target and every renderer event after it, or null before that reset. */
    private fun eventsAfterResumeReset(seek: ResumeSeek): Pair<VideoRendererEvent.Reset, List<VideoRendererEvent>>? {
        val events = videoLog.events()
        val targetUs = seek.targetMs * MICROS_PER_MILLI
        val reset = events.indexOfFirst { event ->
            event is VideoRendererEvent.Reset && kotlin.math.abs(event.periodPositionUs - targetUs) <= MICROS_PER_MILLI
        }
        if (reset < 0) return null
        return events[reset] as VideoRendererEvent.Reset to events.drop(reset + 1)
    }

    fun snapshot(): ResumePlayerSnapshot {
        val snapshot = AtomicReference<ResumePlayerSnapshot>()
        instrumentation.runOnMainSync {
            snapshot.set(
                ResumePlayerSnapshot(
                    positionMs = player.currentPosition,
                    renderedFrames = synchronized(frames) { frames.size.toLong() },
                    playbackState = player.playbackState,
                    isPlaying = player.isPlaying,
                    seekable = player.isCurrentMediaItemSeekable,
                ),
            )
        }
        return snapshot.get()
    }

    suspend fun awaitState(predicate: (ResumePlayerSnapshot) -> Boolean): ResumePlayerSnapshot {
        val deadline = SystemClock.elapsedRealtime() + RESUME_ASSERTION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { error -> throw AssertionError("Fixture playback failed", error) }
            val snapshot = snapshot()
            if (predicate(snapshot)) return snapshot
            delay(RESUME_POLL_INTERVAL_MS)
        }
        throw AssertionError("Growing resume did not reach the required player state")
    }
}

/** Captures each progress report where it reaches the target, with whether stop was already requested. */
private class FixtureRecordingTarget(
    private val lease: GrowingRecordingFileLease,
    savedPosition: Duration,
) : CoordinatorRecordingTarget {
    private val captured = mutableListOf<ReportRecord>()
    private val hold = AtomicReference<ReportHold?>()

    @Volatile
    var stopRequested: Boolean = false

    val reports: List<ReportRecord>
        get() = synchronized(captured) { captured.toList() }

    override val startedGrowing: Boolean = true

    override val admission: CoordinatorRecordingAdmission = CoordinatorRecordingAdmission.GrowingStartOverOnly(
        progressCapability = RecordingProgressCapability.SUPPORTED,
        resumePosition = savedPosition,
    )

    override suspend fun openRecording(): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)

    override fun bindGrowingRecording(): RecordingFileResult<GrowingRecordingFileLease> = RecordingFileResult.Ok(lease)

    /** Makes the next delivery wait at the reporting boundary until the returned hold is released. */
    fun holdNextReport(): ReportHold = ReportHold().also { check(hold.compareAndSet(null, it)) }

    override suspend fun reportProgress(
        growingLease: GrowingRecordingFileLease?,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult {
        synchronized(captured) { captured += ReportRecord(progress, afterStopRequested = stopRequested) }
        hold.getAndSet(null)?.let { active ->
            active.reached.complete(Unit)
            active.release.await()
        }
        return DvrProgressResult.Accepted
    }
}

private class ReportHold {
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}

private data class ReportRecord(val progress: DvrPlaybackProgress, val afterStopRequested: Boolean)

private class ResumeFixture(val key: String, val asset: String, val sha256: String, val durationSeconds: Int) {
    private var bytes: ByteArray? = null

    fun load(): ByteArray = bytes ?: InstrumentationRegistry.getInstrumentation().context.assets.open(asset)
        .use { input -> input.readBytes() }
        .also { loaded ->
            assertEquals(sha256, loaded.sha256())
            bytes = loaded
        }

    /**
     * Parses the whole fixture with the production TS extractor on this device and aligns its video
     * keyframe samples with the committed FFmpeg keyframe table; alignment failures fail the test
     * before playback starts.
     */
    fun keyframeIdentities(status: Bundle, prefix: String): FixtureKeyframeIdentities {
        val table = FixtureKeyframes.parse(
            InstrumentationRegistry.getInstrumentation().context.assets
                .open("${asset.substringBeforeLast('/')}/keyframes.csv")
                .use { input -> input.readBytes().decodeToString() },
        )
        status.putInt("${prefix}_table_keyframes", table.keyframes.size)
        status.putString(
            "${prefix}_table_keyframe_list",
            table.keyframes.joinToString(";") { "${it.bytePosition}@${it.sourceMs}ms" },
        )
        val extracted = extractVideoKeyframes(load())
        status.putInt("${prefix}_extracted_keyframes", extracted.size)
        status.putString(
            "${prefix}_extracted_keyframe_list",
            extracted.joinToString(";") { keyframe ->
                "${keyframe.timeUs}us:${keyframe.identity.sizeBytes}:${"%08x".format(keyframe.identity.crc32)}"
            },
        )
        return FixtureKeyframeIdentities.align(table, extracted)
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
    val atNanos: Long,
    val afterPrepareMs: Long,
    val framesRenderedBefore: Long,
    val videoDecoderBuffersBefore: Long,
    val audioDecoderBuffersBefore: Long,
) {
    fun putInto(status: Bundle, prefix: String) {
        status.putLong("${prefix}_prepare_to_seek_ms", afterPrepareMs)
        status.putLong("${prefix}_frames_rendered_before_seek", framesRenderedBefore)
        status.putLong("${prefix}_video_decoder_rendered_buffers_before_seek", videoDecoderBuffersBefore)
        status.putLong("${prefix}_audio_decoder_output_buffers_before_seek", audioDecoderBuffersBefore)
    }
}

private data class RenderedFrame(val presentationTimeUs: Long, val atNanos: Long)

/** The first frame rendered after the resume seek and the landing keyframe sample it followed. */
private data class RenderedLanding(
    val presentationTimeUs: Long,
    val keyframeSampleTimeUs: Long,
    val keyframeIdentity: SampleIdentity?,
) {
    /** Exact within one post-seek extractor run, whose timestamp offset does not change. */
    val keyframeToFirstFrameUs: Long get() = presentationTimeUs - keyframeSampleTimeUs
}

/** Ordered video renderer events of one player; every event is recorded on its playback thread. */
private class VideoRendererLog {
    private val events = mutableListOf<VideoRendererEvent>()

    fun record(event: VideoRendererEvent) {
        synchronized(events) { events += event }
    }

    fun events(): List<VideoRendererEvent> = synchronized(events) { events.toList() }
}

private sealed interface VideoRendererEvent {
    val atNanos: Long

    /** The renderer was enabled at or reset to [periodPositionUs]. */
    data class Reset(val periodPositionUs: Long, override val atNanos: Long) : VideoRendererEvent

    /**
     * The renderer read a sample with period time [timeUs] whose data has [identity], or null when
     * the read delivered no sample data.
     */
    data class Input(
        val timeUs: Long,
        val keyframe: Boolean,
        val identity: SampleIdentity?,
        override val atNanos: Long,
    ) : VideoRendererEvent

    /** The renderer is about to render a frame with period time [presentationTimeUs]. */
    data class Rendered(val presentationTimeUs: Long, override val atNanos: Long) : VideoRendererEvent
}

/**
 * Wraps the production video renderer without changing its behaviour: its sample stream is
 * wrapped to record reads, and the player still sees the original stream.
 */
private class InputRecordingVideoRenderer(
    renderer: Renderer,
    private val log: VideoRendererLog,
) : ForwardingRenderer(renderer) {
    private var original: SampleStream? = null
    private var streamOffsetUs = 0L

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        original = stream
        streamOffsetUs = offsetUs
        log.record(VideoRendererEvent.Reset(positionUs - offsetUs, SystemClock.elapsedRealtimeNanos()))
        super.enable(
            configuration,
            formats,
            RecordingSampleStream(stream, log),
            positionUs,
            joining,
            mayRenderStartOfStream,
            startPositionUs,
            offsetUs,
            mediaPeriodId,
        )
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        original = stream
        streamOffsetUs = offsetUs
        super.replaceStream(formats, RecordingSampleStream(stream, log), startPositionUs, offsetUs, mediaPeriodId)
    }

    // The player matches renderers to periods by stream identity.
    override fun getStream(): SampleStream? = if (super.getStream() == null) null else original

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        log.record(VideoRendererEvent.Reset(positionUs - streamOffsetUs, SystemClock.elapsedRealtimeNanos()))
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }
}

private class RecordingSampleStream(
    private val stream: SampleStream,
    private val log: VideoRendererLog,
) : SampleStream {
    override fun isReady(): Boolean = stream.isReady

    override fun maybeThrowError() = stream.maybeThrowError()

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        // A renderer may already hold codec configuration in the buffer; the sample starts here.
        val sampleStart = buffer.data?.position()
        val result = stream.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ && readFlags and SampleStream.FLAG_PEEK == 0 && !buffer.isEndOfStream) {
            val data = buffer.data
            val identity = if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0 && data != null) {
                // Samples are written at the buffer position, even when a larger buffer replaced it.
                SampleIdentity.written(data, sampleStart ?: 0)
            } else {
                null
            }
            log.record(
                VideoRendererEvent.Input(buffer.timeUs, buffer.isKeyFrame, identity, SystemClock.elapsedRealtimeNanos()),
            )
        }
        return result
    }

    override fun skipData(positionUs: Long): Int = stream.skipData(positionUs)

    override fun getFlags(): Int = stream.flags
}

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
            while (true) {
                if (closed || readerClosed) return@withLock RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
                if (position < availableBytes) break
                changed.await()
            }
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

private const val RESUME_POSITION_TOLERANCE_MS: Long = 2_000L
private const val STOP_BEFORE_SETTLE_PLAY_MS: Long = 3_000L
private val REPORT_HOLD_TIMEOUT: Duration = 10.seconds

/** Both fixtures render 25 fps; a larger step between resumed frames is not continuous output. */
private const val MAX_RESUMED_FRAME_STEP_US: Long = 200_000L

/** Frames that must follow the landing frame before it counts as rendered playback. */
private const val LANDING_CONTINUOUS_FRAMES: Int = 10
private const val NANOS_PER_MILLI: Long = 1_000_000L
private const val MICROS_PER_MILLI: Long = 1_000L
private const val RESUME_ASSERTION_TIMEOUT_MS: Long = 45_000L
private const val RESUME_POLL_INTERVAL_MS: Long = 50L
private const val RESUME_TEST_TIMEOUT_MS: Long = 240_000L
