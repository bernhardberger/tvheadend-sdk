@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.DvrEntry
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

internal class PlaybackSurfaceActivity : Activity(), SurfaceHolder.Callback {
    internal val surfaceReady = CompletableDeferred<Surface>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            SurfaceView(this).apply {
                holder.addCallback(this@PlaybackSurfaceActivity)
            },
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady.complete(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int): Unit = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder): Unit = Unit
}

@RunWith(AndroidJUnit4::class)
internal class PlaybackDeviceVerificationInstrumentationTest {
    @Test(timeout = TEST_TIMEOUT_MS)
    fun timeshift_seek_return_to_live_and_completed_recording_resume_on_device() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profile = consumePrivateProfile(instrumentation.targetContext.filesDir)
        val session = createTvheadendSession()
        val playbackSurface = launchPlaybackSurface(instrumentation)
        val render = RenderObservation()
        lateinit var player: ExoPlayer
        var playerInitialized = false
        var resume: TvheadendRecordingResume? = null

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val ready = withTimeout(CONNECTION_TIMEOUT_MS) {
                session.state.first(SessionState::isTerminalForP45Verification)
            }
            assertTrue(
                "Real-server session must become ready; failure=${(ready as? SessionState.Unavailable)?.reason}",
                ready is SessionState.Ready,
            )
            val serverVersion = (ready as SessionState.Ready).capabilities.serverVersion ?: "unknown"
            val recording = selectRecording(session.dvrRepository.entries.value)

            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                ).build()
                playerInitialized = true
                player.volume = 0f
                player.setVideoSurface(playbackSurface.surface)
                player.addListener(render.playerListener)
                player.addAnalyticsListener(render.analyticsListener)
            }

            val timeshift = TimeshiftObservingOpener(session.subscriptions)
            instrumentation.runOnMainSync {
                player.setMediaSource(
                    createTvheadendLiveMediaSource(
                        subscriptions = timeshift,
                        channelId = SubscriptionChannelId(checkNotNull(recording.channelId).value),
                    ),
                )
                player.prepare()
                player.play()
            }
            val active = timeshift.awaitActive()
            assertNotNull("The server must grant a timeshift buffer", active.grantedTimeshiftPeriod)
            render.awaitPlayingVideo(instrumentation, player, "live playback")
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("p4_r2_server_version", serverVersion)
                    putString(
                        "p4_r2_timeshift_streams",
                        timeshift.observedStreamTypes().sortedBy(Enum<*>::name).joinToString(),
                    )
                    putLong("p4_r2_timestamp_anchors_before_seek", active.diagnostics.value.timestampAnchorCount)
                },
            )
            delay(TIMESHIFT_FILL_MS)

            verifySeek(
                instrumentation = instrumentation,
                player = player,
                render = render,
                timeshift = timeshift,
                active = active,
                target = SubscriptionSeekTarget.Relative(-TIMESHIFT_REWIND),
                label = "timeshift rewind",
                sessionState = { session.state.value },
                onClosure = { evidence ->
                    instrumentation.reportTimeshiftClosure(evidence, serverVersion, timeshift.observedStreamTypes())
                },
            )
            verifySeek(
                instrumentation = instrumentation,
                player = player,
                render = render,
                timeshift = timeshift,
                active = active,
                target = SubscriptionSeekTarget.Live,
                label = "return to live",
                sessionState = { session.state.value },
                onClosure = { evidence ->
                    instrumentation.reportTimeshiftClosure(evidence, serverVersion, timeshift.observedStreamTypes())
                },
            )
            render.stopAndAssertAudio(instrumentation, player, "timeshift playback")

            val recordingId = RecordingId(recording.id.value)
            val recordingDurationMs = checkNotNull(recording.durationMillis())
            val resumePositionMs = minOf(recordingDurationMs / 4L, MAX_RESUME_POSITION_MS)
                .coerceAtLeast(MIN_RESUME_POSITION_MS)
            val recordingFrameBaseline = playerSnapshot(instrumentation, player).renderedVideoFrames
            instrumentation.runOnMainSync {
                player.clearMediaItems()
                player.setMediaSource(
                    createTvheadendRecordingMediaSource(session.recordings, recordingId),
                )
                resume = createTvheadendRecordingResume(player)
                checkNotNull(resume).beginPlaybackTarget(recordingId, resumePositionMs.toDurationMilliseconds())
                player.prepare()
                player.play()
            }
            withTimeout(RENDER_TIMEOUT_MS) {
                while (true) {
                    val snapshot = playerSnapshot(instrumentation, player)
                    if (
                        snapshot.isPlaying &&
                        snapshot.durationMs > resumePositionMs &&
                        snapshot.positionMs >= resumePositionMs - RESUME_TOLERANCE_MS &&
                        snapshot.renderedVideoFrames > recordingFrameBaseline
                    ) {
                        break
                    }
                    render.assertHealthy("completed recording resume")
                    delay(POLL_INTERVAL_MS)
                }
            }
            render.stopAndAssertAudio(instrumentation, player, "completed recording resume")
            render.assertHealthy("P4-5 playback")

            val evidence = Bundle().apply {
                putString("p4_5_server_version", serverVersion)
                putString("p4_5_timeshift_streams", timeshift.observedStreamTypes().sortedBy(Enum<*>::name).joinToString())
                putLong("p4_5_timestamp_anchors", active.diagnostics.value.timestampAnchorCount)
                putString("p4_5_device_result", "timeshift-and-recording-resume-passed")
            }
            instrumentation.sendStatus(0, evidence)
        } finally {
            instrumentation.runOnMainSync {
                resume?.close()
                if (playerInitialized) player.release()
            }
            session.shutdown()
            playbackSurface.close(instrumentation)
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 120_000L
        const val TIMESHIFT_FILL_MS = 8_000L
        const val TEST_TIMEOUT_MS = 8 * 60 * 1_000L
        const val MAX_RESUME_POSITION_MS = 60_000L
        const val MIN_RESUME_POSITION_MS = 5_000L
        const val RESUME_TOLERANCE_MS = 2_000L
        val TIMESHIFT_REWIND: Duration = 5.seconds
    }
}

@RunWith(AndroidJUnit4::class)
internal class TimeshiftClosureIsolationInstrumentationTest {
    @Test(timeout = ISOLATION_TEST_TIMEOUT_MS)
    fun timeshift_closure_without_media3_consumer_on_device() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profile = consumePrivateProfile(instrumentation.targetContext.filesDir)
        val session = createTvheadendSession()
        var active: ActiveSubscription? = null

        try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val ready = withTimeout(ISOLATION_CONNECTION_TIMEOUT_MS) {
                session.state.first(SessionState::isTerminalForP45Verification)
            }
            assertTrue(
                "Real-server session must become ready; failure=${(ready as? SessionState.Unavailable)?.reason}",
                ready is SessionState.Ready,
            )
            val recording = selectRecording(session.dvrRepository.entries.value)
            val observation = TimeshiftIsolationObservation()
            val opened = session.subscriptions.open(
                channelId = SubscriptionChannelId(checkNotNull(recording.channelId).value),
                consumer = observation,
                timeshiftPeriod = ISOLATION_TIMESHIFT_PERIOD,
            )
            val subscription = when (opened) {
                is SubscriptionOpenResult.Opened -> opened.subscription
                else -> throw AssertionError("Live diagnostic subscription must open; result=$opened")
            }
            active = subscription
            assertNotNull("The server must grant a timeshift buffer", subscription.grantedTimeshiftPeriod)

            val preSeekTerminal = withTimeoutOrNull(ISOLATION_FILL_MS) {
                subscription.state.first { state -> state is SubscriptionState.Terminal } as SubscriptionState.Terminal
            }
            if (preSeekTerminal != null) {
                instrumentation.reportTimeshiftIsolation(
                    phase = "before-seek",
                    ready = ready as SessionState.Ready,
                    active = subscription,
                    observation = observation,
                    anchorBefore = subscription.diagnostics.value.timestampAnchorCount,
                    rewindResult = null,
                    returnToLiveResult = null,
                    sessionAtOutcome = session.state.value,
                    sessionAfterObservation = session.state.value,
                )
                return@runBlocking
            }
            assertTrue("The no-op consumer must receive subscription packets", observation.packetCount() > 0)

            val diagnosticsBefore = subscription.diagnostics.value
            val rewindResult = subscription.seek(SubscriptionSeekTarget.Relative(-ISOLATION_REWIND))
            val rewindTerminal = withTimeoutOrNull(ISOLATION_OUTCOME_MS) {
                subscription.state.first { state -> state is SubscriptionState.Terminal } as SubscriptionState.Terminal
            }
            if (rewindTerminal != null) {
                val sessionAtOutcome = session.state.value
                delay(SESSION_STATE_OBSERVATION_MS)
                instrumentation.reportTimeshiftIsolation(
                    phase = "post-rewind-terminal",
                    ready = ready as SessionState.Ready,
                    active = subscription,
                    observation = observation,
                    anchorBefore = diagnosticsBefore.timestampAnchorCount,
                    rewindResult = rewindResult,
                    returnToLiveResult = null,
                    sessionAtOutcome = sessionAtOutcome,
                    sessionAfterObservation = session.state.value,
                )
                return@runBlocking
            }

            val anchorBeforeReturnToLive = subscription.diagnostics.value.timestampAnchorCount
            val returnToLiveResult = subscription.seek(SubscriptionSeekTarget.Live)
            val returnToLiveTerminal = withTimeoutOrNull(ISOLATION_OUTCOME_MS) {
                subscription.state.first { state -> state is SubscriptionState.Terminal } as SubscriptionState.Terminal
            }
            val sessionAtOutcome = session.state.value
            if (returnToLiveTerminal != null) delay(SESSION_STATE_OBSERVATION_MS)
            instrumentation.reportTimeshiftIsolation(
                phase = if (returnToLiveTerminal == null) {
                    "post-return-to-live-survived"
                } else {
                    "post-return-to-live-terminal"
                },
                ready = ready as SessionState.Ready,
                active = subscription,
                observation = observation,
                anchorBefore = anchorBeforeReturnToLive,
                rewindResult = rewindResult,
                returnToLiveResult = returnToLiveResult,
                sessionAtOutcome = sessionAtOutcome,
                sessionAfterObservation = session.state.value,
            )
        } finally {
            try {
                active?.close()
            } finally {
                session.shutdown()
            }
        }
    }
}

private class TimeshiftIsolationObservation : SubscriptionEventConsumer {
    private val lock = Any()
    private val streams = mutableSetOf<SubscriptionStreamType>()
    private var packetCount = 0L
    private var acceptedSkipObservedAtNs: Long? = null
    private var anchorObservedAtNs: Long? = null
    private var terminalObservedAtNs: Long? = null
    private var terminalEvent: String? = null
    private var serverQueuePacketCount: Long? = null
    private var serverQueueDropCount: Long? = null

    override fun tracksReady(tracks: SubscriptionTracks) {
        synchronized(lock) {
            streams.clear()
            streams += tracks.streams.map { stream -> stream.type }
        }
    }

    override suspend fun accept(event: SubscriptionEvent) {
        synchronized(lock) {
            when (event) {
                is SubscriptionEvent.Skipped -> if (event.outcome == SkipOutcome.ACCEPTED) {
                    acceptedSkipObservedAtNs = SystemClock.elapsedRealtimeNanos()
                    anchorObservedAtNs = null
                    terminalObservedAtNs = null
                    terminalEvent = null
                }
                is SubscriptionEvent.Packet -> {
                    packetCount += 1L
                    if (acceptedSkipObservedAtNs != null && anchorObservedAtNs == null) {
                        anchorObservedAtNs = SystemClock.elapsedRealtimeNanos()
                    }
                }
                is SubscriptionEvent.Queue -> {
                    serverQueuePacketCount = event.packetCount
                    serverQueueDropCount = event.bFrameDropCount + event.pFrameDropCount + event.iFrameDropCount
                }
                is SubscriptionEvent.Stopped -> {
                    terminalObservedAtNs = SystemClock.elapsedRealtimeNanos()
                    terminalEvent = "Stopped"
                }
                is SubscriptionEvent.Terminated -> {
                    terminalObservedAtNs = SystemClock.elapsedRealtimeNanos()
                    terminalEvent = "Terminated:${event.reason.name}"
                }
                else -> Unit
            }
        }
    }

    fun packetCount(): Long = synchronized(lock) { packetCount }

    fun serverQueuePacketCount(): Long? = synchronized(lock) { serverQueuePacketCount }

    fun serverQueueDropCount(): Long? = synchronized(lock) { serverQueueDropCount }

    fun streamTypes(): Set<SubscriptionStreamType> = synchronized(lock) { streams.toSet() }

    fun terminalEvent(): String = synchronized(lock) { terminalEvent ?: "none" }

    fun anchorToTerminalMs(): Long? = synchronized(lock) {
        elapsedMilliseconds(anchorObservedAtNs, terminalObservedAtNs)
    }

    fun skipToTerminalMs(): Long? = synchronized(lock) {
        elapsedMilliseconds(acceptedSkipObservedAtNs, terminalObservedAtNs)
    }
}

private suspend fun verifySeek(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
    render: RenderObservation,
    timeshift: TimeshiftObservingOpener,
    active: ActiveSubscription,
    target: SubscriptionSeekTarget,
    label: String,
    sessionState: () -> SessionState,
    onClosure: (TimeshiftClosureEvidence) -> Unit,
) {
    val epoch = timeshift.currentEpoch()
    val diagnosticsBefore = active.diagnostics.value
    val beforeSeek = playerSnapshot(instrumentation, player)
    instrumentation.sendStatus(
        0,
        Bundle().apply {
            putLong("p4_r2_consumer_packets_before_seek", timeshift.packetCount())
            putLong("p4_r2_client_drops_before_seek", diagnosticsBefore.droppedPacketCount)
            putLong("p4_r2_server_queue_packets_before_seek", timeshift.serverQueuePacketCount() ?: -1L)
            putLong("p4_r2_server_queue_drops_before_seek", timeshift.serverQueueDropCount() ?: -1L)
        },
    )
    val seekResult = active.seek(target)
    assertEquals(
        "$label must be accepted; subscription=${active.state.value.diagnosticCategory()}, " +
            "session=${sessionState().diagnosticCategory()}, " +
            "player=${beforeSeek.playbackState.diagnosticCategory()}, " +
            "renderFailure=${render.failureCategory()}, " +
            "consumerFailure=${timeshift.consumerFailureCategory() ?: "none"}, " +
            "anchorToTerminalMs=${timeshift.anchorToTerminalMs() ?: -1L}, " +
            "skipToTerminalMs=${timeshift.skipToTerminalMs() ?: -1L}, " +
            "anchorCount=${active.diagnostics.value.timestampAnchorCount}",
        SubscriptionSeekResult.Accepted,
        seekResult,
    )
    val samples = timeshift.awaitSynchronizedSamples(
        afterEpoch = epoch,
        active = active,
        anchorBefore = diagnosticsBefore.timestampAnchorCount,
        discardedBefore = diagnosticsBefore.rebaseDiscardedPacketCount,
        sessionState = sessionState,
        onClosure = onClosure,
    )
    assertTrue(
        "$label must keep audio and video within the synchronization bound",
        abs(samples.audioPresentationTimeUs - samples.videoPresentationTimeUs) <= MAX_AV_SEPARATION_US,
    )
    val anchored = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
        active.diagnostics.first { diagnostics -> diagnostics.timestampAnchorCount > diagnosticsBefore.timestampAnchorCount }
    }
    assertNotNull("$label must publish a new timestamp anchor", anchored)
    render.awaitPlayingVideo(
        instrumentation,
        player,
        label,
        beforeSeek.renderedVideoFrames,
        beforeSeek.positionMs,
    )
}

private class TimeshiftObservingOpener(
    private val delegate: SubscriptionOpener,
) : SubscriptionOpener {
    private val openResult = CompletableDeferred<SubscriptionOpenResult>()
    private val samples = TimeshiftSampleObservation()

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult {
        val observingConsumer = object : SubscriptionEventConsumer {
            override fun tracksReady(tracks: SubscriptionTracks) {
                samples.tracksReady(tracks)
                try {
                    consumer.tracksReady(tracks)
                } catch (failure: Exception) {
                    samples.recordConsumerFailure("TracksReady", null, failure)
                    throw failure
                }
            }

            override suspend fun accept(event: SubscriptionEvent) {
                samples.accept(event)
                try {
                    consumer.accept(event)
                } catch (failure: Exception) {
                    samples.recordConsumerFailure(
                        event.diagnosticCategory(),
                        (event as? SubscriptionEvent.Packet)?.streamIndex,
                        failure,
                    )
                    throw failure
                }
            }
        }
        val result = delegate.open(channelId, observingConsumer, REQUESTED_TIMESHIFT)
        openResult.complete(result)
        return result
    }

    suspend fun awaitActive(): ActiveSubscription = when (val result = withTimeout(RENDER_TIMEOUT_MS) { openResult.await() }) {
        is SubscriptionOpenResult.Opened -> result.subscription
        else -> throw AssertionError("Live subscription must open; result=$result")
    }

    fun currentEpoch(): Long = samples.currentEpoch()

    suspend fun awaitSynchronizedSamples(
        afterEpoch: Long,
        active: ActiveSubscription,
        anchorBefore: Long,
        discardedBefore: Long,
        sessionState: () -> SessionState,
        onClosure: (TimeshiftClosureEvidence) -> Unit,
    ): AvSamples = samples.awaitSynchronizedSamples(
        afterEpoch = afterEpoch,
        active = active,
        anchorBefore = anchorBefore,
        discardedBefore = discardedBefore,
        sessionState = sessionState,
        onClosure = onClosure,
    )

    fun observedStreamTypes(): Set<SubscriptionStreamType> = samples.observedStreamTypes()

    fun consumerFailureCategory(): String? = samples.consumerFailureCategory()

    fun anchorToTerminalMs(): Long? = samples.anchorToTerminalMs()

    fun skipToTerminalMs(): Long? = samples.skipToTerminalMs()

    fun packetCount(): Long = samples.packetCount()

    fun serverQueuePacketCount(): Long? = samples.serverQueuePacketCount()

    fun serverQueueDropCount(): Long? = samples.serverQueueDropCount()

    private companion object {
        val REQUESTED_TIMESHIFT: Duration = 5.minutes
    }
}

private class TimeshiftSampleObservation {
    private val lock = Any()
    private val streams = mutableMapOf<StreamIndex, SubscriptionStreamType>()
    private var epoch = 0L
    private var audioPresentationTimeUs: Long? = null
    private var videoPresentationTimeUs: Long? = null
    private var acceptedSkipObservedAtNs: Long? = null
    private var timestampAnchorObservedAtNs: Long? = null
    private var terminalObservedAtNs: Long? = null
    private var consumerFailureCategory: String? = null
    private var packetCount = 0L
    private var serverQueuePacketCount: Long? = null
    private var serverQueueDropCount: Long? = null

    fun tracksReady(tracks: SubscriptionTracks) {
        synchronized(lock) {
            streams.clear()
            tracks.streams.forEach { stream -> streams[stream.index] = stream.type }
        }
    }

    fun accept(event: SubscriptionEvent) {
        synchronized(lock) {
            when (event) {
                is SubscriptionEvent.Skipped -> if (event.outcome == at.bernhardberger.tvheadend.sdk.playback.SkipOutcome.ACCEPTED) {
                    epoch += 1L
                    audioPresentationTimeUs = null
                    videoPresentationTimeUs = null
                    acceptedSkipObservedAtNs = SystemClock.elapsedRealtimeNanos()
                    timestampAnchorObservedAtNs = null
                    terminalObservedAtNs = null
                }
                is SubscriptionEvent.Packet -> {
                    packetCount += 1L
                    event.presentationTimeUs?.let { presentationTimeUs ->
                        if (epoch > 0L && timestampAnchorObservedAtNs == null) {
                            timestampAnchorObservedAtNs = SystemClock.elapsedRealtimeNanos()
                        }
                        when (streams[event.streamIndex]) {
                            SubscriptionStreamType.MPEG2_VIDEO,
                            SubscriptionStreamType.H264,
                            SubscriptionStreamType.H265,
                            -> if (videoPresentationTimeUs == null) videoPresentationTimeUs = presentationTimeUs
                            SubscriptionStreamType.AAC,
                            SubscriptionStreamType.AC3,
                            SubscriptionStreamType.EAC3,
                            SubscriptionStreamType.MPEG2_AUDIO,
                            -> if (audioPresentationTimeUs == null) audioPresentationTimeUs = presentationTimeUs
                            SubscriptionStreamType.DVB_SUBTITLE,
                            SubscriptionStreamType.TEXT_SUBTITLE,
                            SubscriptionStreamType.TELETEXT,
                            SubscriptionStreamType.UNKNOWN,
                            null,
                            -> Unit
                        }
                    }
                }
                is SubscriptionEvent.Queue -> {
                    serverQueuePacketCount = event.packetCount
                    serverQueueDropCount = event.bFrameDropCount + event.pFrameDropCount + event.iFrameDropCount
                }
                is SubscriptionEvent.Stopped,
                is SubscriptionEvent.Terminated -> terminalObservedAtNs = SystemClock.elapsedRealtimeNanos()
                else -> Unit
            }
        }
    }

    fun currentEpoch(): Long = synchronized(lock) { epoch }

    fun observedStreamTypes(): Set<SubscriptionStreamType> = synchronized(lock) { streams.values.toSet() }

    fun recordConsumerFailure(
        eventCategory: String,
        streamIndex: StreamIndex?,
        failure: Exception,
    ) {
        synchronized(lock) {
            val streamType = streamIndex?.let { index -> streams[index]?.name ?: "UNMAPPED" }
            consumerFailureCategory = listOfNotNull(
                eventCategory,
                streamType,
                failure.javaClass.simpleName,
                failure.safeStackOrigin(),
            ).joinToString(":")
        }
    }

    fun consumerFailureCategory(): String? = synchronized(lock) { consumerFailureCategory }

    fun anchorToTerminalMs(): Long? = synchronized(lock) {
        elapsedMilliseconds(timestampAnchorObservedAtNs, terminalObservedAtNs)
    }

    fun skipToTerminalMs(): Long? = synchronized(lock) {
        elapsedMilliseconds(acceptedSkipObservedAtNs, terminalObservedAtNs)
    }

    fun packetCount(): Long = synchronized(lock) { packetCount }

    fun serverQueuePacketCount(): Long? = synchronized(lock) { serverQueuePacketCount }

    fun serverQueueDropCount(): Long? = synchronized(lock) { serverQueueDropCount }

    suspend fun awaitSynchronizedSamples(
        afterEpoch: Long,
        active: ActiveSubscription,
        anchorBefore: Long,
        discardedBefore: Long,
        sessionState: () -> SessionState,
        onClosure: (TimeshiftClosureEvidence) -> Unit,
    ): AvSamples {
        var terminalStateObserved = false
        val samples = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            while (true) {
                if (active.state.value is SubscriptionState.Terminal) {
                    terminalStateObserved = true
                    return@withTimeoutOrNull null
                }
                synchronized(lock) {
                    val audio = audioPresentationTimeUs
                    val video = videoPresentationTimeUs
                    if (epoch > afterEpoch && audio != null && video != null) {
                        return@withTimeoutOrNull AvSamples(audio, video)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
            error("unreachable")
        }
        if (samples != null) return samples

        val sessionAtTerminal = if (terminalStateObserved) sessionState().diagnosticCategory() else null
        if (terminalStateObserved) delay(SESSION_STATE_OBSERVATION_MS)
        val sessionAfterObservation = if (terminalStateObserved) sessionState().diagnosticCategory() else null
        val state = active.state.value
        val diagnostics = active.diagnostics.value
        val sampleSnapshot = synchronized(lock) {
            TimeshiftSampleSnapshot(
                epochAdvanced = epoch > afterEpoch,
                hasAudio = audioPresentationTimeUs != null,
                hasVideo = videoPresentationTimeUs != null,
                acceptedSkipObservedAtNs = acceptedSkipObservedAtNs,
                anchorObservedAtNs = timestampAnchorObservedAtNs,
                terminalObservedAtNs = terminalObservedAtNs,
                packetCount = packetCount,
                serverQueuePacketCount = serverQueuePacketCount,
                serverQueueDropCount = serverQueueDropCount,
            )
        }
        val terminalReason = (state as? SubscriptionState.Terminal)?.reason?.javaClass?.simpleName
        if (terminalStateObserved && terminalReason != null) {
            val anchorToTerminalMs = elapsedMilliseconds(
                sampleSnapshot.anchorObservedAtNs,
                sampleSnapshot.terminalObservedAtNs,
            )
            val skipToTerminalMs = elapsedMilliseconds(
                sampleSnapshot.acceptedSkipObservedAtNs,
                sampleSnapshot.terminalObservedAtNs,
            )
            val evidence = TimeshiftClosureEvidence(
                terminalReason = terminalReason,
                sessionAtTerminal = checkNotNull(sessionAtTerminal),
                sessionAfterObservation = checkNotNull(sessionAfterObservation),
                anchorToTerminalMs = anchorToTerminalMs,
                skipToTerminalMs = skipToTerminalMs,
                anchorBefore = anchorBefore,
                anchorCount = diagnostics.timestampAnchorCount,
                anchorDelta = diagnostics.timestampAnchorCount - anchorBefore,
                discardedDelta = diagnostics.rebaseDiscardedPacketCount - discardedBefore,
                packetCount = sampleSnapshot.packetCount,
                clientDropCount = diagnostics.droppedPacketCount,
                serverQueuePacketCount = sampleSnapshot.serverQueuePacketCount,
                serverQueueDropCount = sampleSnapshot.serverQueueDropCount,
                epochAdvanced = sampleSnapshot.epochAdvanced,
                hasAudio = sampleSnapshot.hasAudio,
                hasVideo = sampleSnapshot.hasVideo,
            )
            onClosure(evidence)
            throw AssertionError(
                "Accepted seek terminated before post-anchor audio and video samples; " +
                    "terminal=${evidence.terminalReason}, " +
                    "sessionAtTerminal=${evidence.sessionAtTerminal}, " +
                    "sessionAfter${SESSION_STATE_OBSERVATION_MS}Ms=${evidence.sessionAfterObservation}, " +
                    "anchorToTerminalMs=${evidence.anchorToTerminalMs ?: -1L}, " +
                    "skipToTerminalMs=${evidence.skipToTerminalMs ?: -1L}, " +
                    "epochAdvanced=${evidence.epochAdvanced}, audio=${evidence.hasAudio}, " +
                    "video=${evidence.hasVideo}, anchorBefore=${evidence.anchorBefore}, " +
                    "anchorCount=${evidence.anchorCount}, anchorDelta=${evidence.anchorDelta}, " +
                    "discardedDelta=${evidence.discardedDelta}",
            )
        }

        throw AssertionError(
            "Accepted seek must publish post-anchor audio and video samples; " +
                "epochAdvanced=${sampleSnapshot.epochAdvanced}, audio=${sampleSnapshot.hasAudio}, " +
                "video=${sampleSnapshot.hasVideo}, state=${state.diagnosticCategory()}, " +
                "anchorCount=${diagnostics.timestampAnchorCount}, " +
                "discardedDelta=${diagnostics.rebaseDiscardedPacketCount - discardedBefore}",
        )
    }
}

private data class TimeshiftSampleSnapshot(
    val epochAdvanced: Boolean,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val acceptedSkipObservedAtNs: Long?,
    val anchorObservedAtNs: Long?,
    val terminalObservedAtNs: Long?,
    val packetCount: Long,
    val serverQueuePacketCount: Long?,
    val serverQueueDropCount: Long?,
)

private data class TimeshiftClosureEvidence(
    val terminalReason: String,
    val sessionAtTerminal: String,
    val sessionAfterObservation: String,
    val anchorToTerminalMs: Long?,
    val skipToTerminalMs: Long?,
    val anchorBefore: Long,
    val anchorCount: Long,
    val anchorDelta: Long,
    val discardedDelta: Long,
    val packetCount: Long,
    val clientDropCount: Long,
    val serverQueuePacketCount: Long?,
    val serverQueueDropCount: Long?,
    val epochAdvanced: Boolean,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
)

private data class AvSamples(
    val audioPresentationTimeUs: Long,
    val videoPresentationTimeUs: Long,
)

internal class RenderObservation {
    private val failed = AtomicBoolean()
    private val failureCategory = AtomicReference("none")
    private val selectedAudio = AtomicBoolean()
    private val selectedVideo = AtomicBoolean()
    private val audioDisableCount = AtomicInteger()
    private val lastRenderedAudioBuffers = AtomicInteger()

    val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            tracks.groups.forEach { group ->
                for (index in 0 until group.length) {
                    if (!group.isTrackSelected(index)) continue
                    when (androidx.media3.common.MimeTypes.getTrackType(group.getTrackFormat(index).sampleMimeType)) {
                        androidx.media3.common.C.TRACK_TYPE_AUDIO -> selectedAudio.set(true)
                        androidx.media3.common.C.TRACK_TYPE_VIDEO -> selectedVideo.set(true)
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            failureCategory.compareAndSet("none", "player:${error.errorCodeName}")
            failed.set(true)
        }

    }

    val analyticsListener = object : AnalyticsListener {
        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            lastRenderedAudioBuffers.set(decoderCounters.renderedOutputBufferCount)
            audioDisableCount.incrementAndGet()
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            failureCategory.compareAndSet("none", "audio-codec:${audioCodecError.javaClass.simpleName}")
            failed.set(true)
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            failureCategory.compareAndSet("none", "audio-sink:${audioSinkError.javaClass.simpleName}")
            failed.set(true)
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            failureCategory.compareAndSet("none", videoCodecError.safeVideoCodecCategory())
            failed.set(true)
        }
    }

    suspend fun awaitPlayingVideo(
        instrumentation: android.app.Instrumentation,
        player: ExoPlayer,
        label: String,
        frameBaseline: Long = 0L,
        positionBaselineMs: Long = -1L,
    ) {
        val rendered = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            while (true) {
                val snapshot = playerSnapshot(instrumentation, player)
                if (
                    snapshot.isPlaying &&
                    snapshot.renderedVideoFrames > frameBaseline &&
                    (positionBaselineMs < 0L || snapshot.positionMs > positionBaselineMs)
                ) {
                    break
                }
                assertHealthy(label)
                delay(POLL_INTERVAL_MS)
            }
            true
        } ?: false
        val finalSnapshot = playerSnapshot(instrumentation, player)
        assertTrue(
            "$label must continue rendered video; frames=${finalSnapshot.renderedVideoFrames}, " +
                "baseline=$frameBaseline, position=${finalSnapshot.positionMs}, positionBaseline=$positionBaselineMs",
            rendered,
        )
        assertTrue("$label must select an audio track", selectedAudio.get())
        assertTrue("$label must select a video track", selectedVideo.get())
    }

    suspend fun stopAndAssertAudio(
        instrumentation: android.app.Instrumentation,
        player: ExoPlayer,
        label: String,
    ) {
        val disableBaseline = audioDisableCount.get()
        instrumentation.runOnMainSync { player.stop() }
        withTimeout(RENDER_TIMEOUT_MS) {
            while (audioDisableCount.get() <= disableBaseline) {
                assertHealthy(label)
                delay(POLL_INTERVAL_MS)
            }
        }
        assertTrue("$label must submit decoded audio output", lastRenderedAudioBuffers.get() > 0)
        assertHealthy(label)
    }

    fun assertHealthy(label: String) {
        assertFalse("$label must not fail; category=${failureCategory.get()}", failed.get())
    }

    fun failureCategory(): String = failureCategory.get()
}

internal data class PlayerSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val renderedVideoFrames: Long,
    val playbackState: Int,
)

internal data class PlaybackSurface(
    val activity: PlaybackSurfaceActivity,
    val surface: Surface,
) {
    fun close(instrumentation: android.app.Instrumentation) {
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
    }
}

internal suspend fun launchPlaybackSurface(
    instrumentation: android.app.Instrumentation,
): PlaybackSurface {
    val intent = Intent(instrumentation.context, PlaybackSurfaceActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val activity = instrumentation.startActivitySync(intent) as PlaybackSurfaceActivity
    return try {
        PlaybackSurface(
            activity = activity,
            surface = withTimeout(SURFACE_TIMEOUT_MS) { activity.surfaceReady.await() },
        )
    } catch (failure: Throwable) {
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        throw failure
    }
}

internal fun playerSnapshot(
    instrumentation: android.app.Instrumentation,
    player: ExoPlayer,
): PlayerSnapshot {
    val snapshot = AtomicReference<PlayerSnapshot>()
    instrumentation.runOnMainSync {
        snapshot.set(
            PlayerSnapshot(
                positionMs = player.currentPosition,
                durationMs = player.duration,
                isPlaying = player.isPlaying,
                renderedVideoFrames = player.videoDecoderCounters?.renderedOutputBufferCount?.toLong() ?: 0L,
                playbackState = player.playbackState,
            ),
        )
    }
    return checkNotNull(snapshot.get())
}

internal fun consumePrivateProfile(filesDirectory: File): ServerProfile {
    val configurationFile = File(filesDirectory, PRIVATE_CONFIGURATION_FILE_NAME)
    assumeTrue("Private real-server verification is not provisioned", configurationFile.isFile)
    val configurationText = try {
        configurationFile.readText()
    } catch (_: Exception) {
        throw AssertionError("Private real-server verification could not be read")
    }
    check(configurationFile.delete()) { "Private real-server verification could not be consumed" }
    return try {
        val root = JSONObject(configurationText)
        ServerProfile(
            host = root.boundedString("host", 255),
            port = root.getInt("htsp_port"),
            authentication = ServerAuthentication.Password(
                username = root.boundedString("username", 255),
                password = root.boundedString("password", 1_024),
            ),
        )
    } catch (_: Exception) {
        throw AssertionError("Private real-server verification is invalid")
    }
}

private fun selectRecording(entries: List<DvrEntry>): DvrEntry = entries
    .asSequence()
    .filter { entry -> entry.state == DvrEntryState.COMPLETED }
    .filter { entry -> entry.channelId != null }
    .filter { entry -> entry.files.orEmpty().any { file -> file.sizeBytes?.let { it > 0L } == true } }
    .filter { entry -> entry.durationMillis()?.let { it > MINIMUM_RECORDING_DURATION_MS } == true }
    .maxByOrNull { entry -> entry.stop ?: kotlin.time.Instant.DISTANT_PAST }
    ?: throw AssertionError("A playable completed recording is required")

private fun DvrEntry.durationMillis(): Long? {
    val start = start ?: return null
    val stop = stop ?: return null
    return (stop - start).inWholeMilliseconds.takeIf { duration -> duration > 0L }
}

private fun Long.toDurationMilliseconds(): Duration = milliseconds

private fun SessionState.isTerminalForP45Verification(): Boolean =
    this is SessionState.Ready || this is SessionState.Unavailable

private fun SessionState.diagnosticCategory(): String = when (this) {
    SessionState.Disconnected -> "Disconnected"
    SessionState.Connecting -> "Connecting"
    SessionState.Synchronizing -> "Synchronizing"
    is SessionState.Ready -> "Ready"
    is SessionState.Unavailable -> "Unavailable:${reason.javaClass.simpleName}"
}

private fun SubscriptionState.diagnosticCategory(): String = when (this) {
    SubscriptionState.Starting -> "Starting"
    is SubscriptionState.Playable -> "Playable"
    is SubscriptionState.Terminal -> "Terminal:${reason.javaClass.simpleName}"
}

private fun Int.diagnosticCategory(): String = when (this) {
    Player.STATE_IDLE -> "Idle"
    Player.STATE_BUFFERING -> "Buffering"
    Player.STATE_READY -> "Ready"
    Player.STATE_ENDED -> "Ended"
    else -> "Unknown"
}

private fun SubscriptionEvent.diagnosticCategory(): String = when (this) {
    is SubscriptionEvent.Terminated -> "Terminated:${reason.name}"
    else -> javaClass.simpleName
}

private fun Throwable.safeStackOrigin(): String = stackTrace.firstOrNull { frame ->
    frame.className.startsWith("androidx.media3.") ||
        frame.className.startsWith("at.bernhardberger.tvheadend.htsp.") ||
        frame.className.startsWith("at.bernhardberger.tvheadend.sdk.")
}?.let { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
    ?: "origin-unavailable"

private fun Exception.safeVideoCodecCategory(): String = when (this) {
    is MediaCodec.CodecException ->
        "video-codec:${diagnosticInfo.safeDiagnosticToken()}:recoverable=$isRecoverable:transient=$isTransient"
    else -> "video-codec:${javaClass.simpleName}"
}

private fun String.safeDiagnosticToken(): String = take(MAX_DIAGNOSTIC_TOKEN_LENGTH)
    .map { character ->
        if (
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '.' || character == '_' || character == '-'
        ) {
            character
        } else {
            '_'
        }
    }
    .joinToString("")
    .ifEmpty { "unavailable" }

private fun android.app.Instrumentation.reportTimeshiftClosure(
    evidence: TimeshiftClosureEvidence,
    serverVersion: String,
    streamTypes: Set<SubscriptionStreamType>,
) {
    sendStatus(
        0,
        Bundle().apply {
            putString("p4_r2_server_version", serverVersion)
            putString("p4_r2_timeshift_streams", streamTypes.sortedBy(Enum<*>::name).joinToString())
            putString("p4_r2_terminal_reason", evidence.terminalReason)
            putString("p4_r2_session_at_terminal", evidence.sessionAtTerminal)
            putString("p4_r2_session_after_observation", evidence.sessionAfterObservation)
            putLong("p4_r2_anchor_to_terminal_ms", evidence.anchorToTerminalMs ?: -1L)
            putLong("p4_r2_skip_to_terminal_ms", evidence.skipToTerminalMs ?: -1L)
            putLong("p4_r2_timestamp_anchors_before", evidence.anchorBefore)
            putLong("p4_r2_timestamp_anchors", evidence.anchorCount)
            putLong("p4_r2_timestamp_anchor_delta", evidence.anchorDelta)
            putLong("p4_r2_rebase_discards", evidence.discardedDelta)
            putLong("p4_r2_consumer_packets_at_terminal", evidence.packetCount)
            putLong("p4_r2_client_drops_at_terminal", evidence.clientDropCount)
            putLong("p4_r2_server_queue_packets_at_terminal", evidence.serverQueuePacketCount ?: -1L)
            putLong("p4_r2_server_queue_drops_at_terminal", evidence.serverQueueDropCount ?: -1L)
            putBoolean("p4_r2_epoch_advanced", evidence.epochAdvanced)
            putBoolean("p4_r2_audio_observed", evidence.hasAudio)
            putBoolean("p4_r2_video_observed", evidence.hasVideo)
        },
    )
}

private fun android.app.Instrumentation.reportTimeshiftIsolation(
    phase: String,
    ready: SessionState.Ready,
    active: ActiveSubscription,
    observation: TimeshiftIsolationObservation,
    anchorBefore: Long,
    rewindResult: SubscriptionSeekResult?,
    returnToLiveResult: SubscriptionSeekResult?,
    sessionAtOutcome: SessionState,
    sessionAfterObservation: SessionState,
) {
    val diagnostics = active.diagnostics.value
    sendStatus(
        0,
        Bundle().apply {
            putString("p4_r2_isolation_phase", phase)
            putString("p4_r2_server_version", ready.capabilities.serverVersion ?: "unknown")
            putString(
                "p4_r2_timeshift_streams",
                observation.streamTypes().sortedBy(Enum<*>::name).joinToString(),
            )
            putString("p4_r2_rewind_result", rewindResult?.javaClass?.simpleName ?: "not-issued")
            putString(
                "p4_r2_return_to_live_result",
                returnToLiveResult?.javaClass?.simpleName ?: "not-issued",
            )
            putString("p4_r2_subscription_state", active.state.value.diagnosticCategory())
            putString("p4_r2_terminal_event", observation.terminalEvent())
            putString("p4_r2_session_at_outcome", sessionAtOutcome.diagnosticCategory())
            putString("p4_r2_session_after_observation", sessionAfterObservation.diagnosticCategory())
            putLong("p4_r2_packet_count", observation.packetCount())
            putLong("p4_r2_client_drops", diagnostics.droppedPacketCount)
            putLong("p4_r2_server_queue_packets", observation.serverQueuePacketCount() ?: -1L)
            putLong("p4_r2_server_queue_drops", observation.serverQueueDropCount() ?: -1L)
            putLong("p4_r2_timestamp_anchors_before", anchorBefore)
            putLong("p4_r2_timestamp_anchors", diagnostics.timestampAnchorCount)
            putLong("p4_r2_timestamp_anchor_delta", diagnostics.timestampAnchorCount - anchorBefore)
            putLong("p4_r2_anchor_to_terminal_ms", observation.anchorToTerminalMs() ?: -1L)
            putLong("p4_r2_skip_to_terminal_ms", observation.skipToTerminalMs() ?: -1L)
        },
    )
}

private fun elapsedMilliseconds(startNs: Long?, stopNs: Long?): Long? {
    if (startNs == null || stopNs == null) return null
    return ((stopNs - startNs).coerceAtLeast(0L) / NANOSECONDS_PER_MILLISECOND)
}

private fun JSONObject.boundedString(name: String, maximumLength: Int): String = getString(name).also { value ->
    require(value.isNotBlank() && value.length <= maximumLength) { "Private verification value is invalid" }
}

private const val PRIVATE_CONFIGURATION_FILE_NAME = "p4-5-real-server.json"
internal const val RENDER_TIMEOUT_MS = 45_000L
internal const val SURFACE_TIMEOUT_MS = 10_000L
internal const val POLL_INTERVAL_MS = 100L
private const val SESSION_STATE_OBSERVATION_MS = 500L
private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
private const val MAX_DIAGNOSTIC_TOKEN_LENGTH = 96
private const val ISOLATION_TEST_TIMEOUT_MS = 4 * 60 * 1_000L
private const val ISOLATION_CONNECTION_TIMEOUT_MS = 120_000L
private const val ISOLATION_FILL_MS = 8_000L
private const val ISOLATION_OUTCOME_MS = 5_000L
private const val MAX_AV_SEPARATION_US = 2_000_000L
private const val MINIMUM_RECORDING_DURATION_MS = 30_000L
private val ISOLATION_TIMESHIFT_PERIOD: Duration = 5.minutes
private val ISOLATION_REWIND: Duration = 5.seconds
