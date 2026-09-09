@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
internal class Media3SampleQueueInstrumentationTest {
    @Test
    fun playing_content_seek_settles_at_both_edges_after_delayed_media() = assertContentSeeks(paused = false)

    @Test
    fun paused_content_seek_renders_new_first_frame_at_both_edges_without_resume() = assertContentSeeks(paused = true)

    @Test
    fun finite_paused_IDR_renders_and_continues() = assertContentSeeks(paused = true, finitePaused = true)

    private fun assertContentSeeks(paused: Boolean, finitePaused: Boolean = false) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val channel = RecordedMuxCapture.load(assets).channel(16, 1, 2)
        val rawPackets = h264PacketsWithContinuedAudio(channel)
        val connection = ScriptedSubscriptionConnection()
        connection.scriptSubscribe(SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, 120)))
        val manager = createSubscriptionManager(connection, Dispatchers.Default).apply { startAdmission() }
        val controls = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val frames = AtomicInteger()
        val surfaceFrames = AtomicInteger()
        val discontinuities = AtomicInteger()
        val error = AtomicBoolean()
        val videoCounters = AtomicReference<DecoderCounters>()
        val codecStarts = AtomicInteger()
        val renderedFrames = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        val expectedSpeeds = mutableListOf<Int>()
        val textureThread = android.os.HandlerThread("finite-preview-surface").apply { start() }
        val textureHandler = android.os.Handler(textureThread.looper)
        val texture = androidx.media3.common.util.EGLSurfaceTexture(textureHandler) { surfaceFrames.incrementAndGet() }
        val textureReady = CountDownLatch(1)
        textureHandler.post { texture.init(androidx.media3.common.util.EGLSurfaceTexture.SECURE_MODE_NONE); textureReady.countDown() }
        assertTrue(textureReady.await(5, TimeUnit.SECONDS))
        val surface = Surface(texture.surfaceTexture)
        lateinit var player: ExoPlayer
        var initialized = false
        fun position(): Long {
            var result = 0L
            instrumentation.runOnMainSync {
                assertEquals("Seek must preserve application pause intent", !paused, player.playWhenReady)
                result = player.currentPosition
            }
            return result
        }
        suspend fun awaitCondition(message: String, condition: () -> Boolean) {
            val met = withTimeoutOrNull(15_000) {
                while (!condition()) {
                    assertTrue(message, !error.get())
                    delay(10)
                }
                true
            }
            val counters = videoCounters.get()
            assertEquals("$message frames=${frames.get()} surface=${surfaceFrames.get()} discontinuities=${discontinuities.get()} queued=${counters?.queuedInputBufferCount} rendered=${counters?.renderedOutputBufferCount} codecs=${codecStarts.get()}", true, met)
        }
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext))
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(100, 2_000, 50, 50).build())
                    .build()
                initialized = true
                player.volume = 0f
                player.setVideoSurface(surface)
                player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                    renderedFrames.add(presentationTimeUs to surfaceFrames.get())
                }
                player.playWhenReady = finitePaused || !paused
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, counters: DecoderCounters) {
                        videoCounters.set(counters)
                    }
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                        initializedTimestampMs: Long, initializationDurationMs: Long) { codecStarts.incrementAndGet() }
                })
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                    override fun onPlayerError(failure: PlaybackException) { error.set(true) }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_INTERNAL) discontinuities.incrementAndGet()
                    }
                })
                player.setMediaSource(createTvheadendLiveMediaSource(
                    FixedSubscriptionLiveTarget(manager, SubscriptionChannelId(1)),
                    SubscriptionOptions(timeshiftPeriod = 120.seconds), controls,
                ))
                player.prepare()
            }
            val registration = withTimeout(10_000) { connection.awaitCollectionRegistered() }
            connection.emit(registration, SubscriptionEvent.Started(channel.streams, null, SubscriptionCondition.NO_DETAIL))
            (if (finitePaused) rawPackets else channel.packets).forEach { connection.emit(registration, it.toEvent(assets)) }
            awaitCondition("Initial frame", { frames.get() > 0 })
            if (finitePaused) {
                awaitCondition("Initially playing", {
                    var playing = false
                    instrumentation.runOnMainSync { playing = player.isPlaying && player.currentPosition > 0 }
                    playing
                })
                instrumentation.runOnMainSync { player.pause() }
            }
            val attachment = checkNotNull(controls.mappingAttachment())
            connection.emit(registration, SubscriptionEvent.Timeshift(0, 0, 0, 120_000_000, if (paused) 0 else 100))
            awaitCondition("Observed bounds", { attachment.timeline() != null })
            if (paused) { controls.setSpeed(0); expectedSpeeds += 0 }
            for (edge in listOf(0L, 120L)) {
                val target = TimeshiftContentTarget(attachment, edge.seconds)
                val beforeFrames = frames.get()
                val beforeSurfaceFrames = surfaceFrames.get()
                val beforeCodecStarts = codecStarts.get()
                val beforeDiscontinuities = discontinuities.get()
                val beforeCommands = connection.seekTargets.size
                val seek = async { controls.seekContent(target) }
                awaitCondition("Seek command admission", { connection.seekTargets.size > beforeCommands })
                // Old in-flight packets preceding the ordered acknowledgement must be discarded.
                channel.packets.take(3).forEach { connection.emit(registration, it.toEvent(assets)) }
                connection.emit(registration, SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED,
                    if (edge == 0L) 0 else null, null))
                val result = seek.await() as TimeshiftContentSeekResult.Completed
                assertEquals(TimeshiftCommandResult.ACCEPTED, result.command)
                assertNotNull(result.seek)
                if (edge != 0L) assertNull(result.readerReached)
                delay(250)
                assertEquals("Acknowledgement alone must not settle playback", beforeDiscontinuities, discontinuities.get())
                assertEquals("Acknowledgement alone must not render a new first frame", beforeFrames, frames.get())
                assertEquals(TimeshiftPlaybackPosition.Unavailable, controls.playbackPosition(attachment, position().milliseconds))
                // Capture streams have independent timestamp origins. Align the scripted resumed
                // A/V segment while preserving each stream's sample spacing and decode offsets.
                val origins = channel.packets.groupBy { it.streamOrdinal }
                    .mapValues { (_, packets) -> packets.minOf { it.presentationTimeUs } }
                if (finitePaused) {
                    val videos = rawPackets.filter { it.streamOrdinal == 1 }
                    connection.emit(registration, videos.first().toEvent(assets))
                    awaitCondition("Finite paused renderer frame", { frames.get() > beforeFrames })
                    awaitCondition("Finite paused surface frame", { surfaceFrames.get() > beforeSurfaceFrames })
                    awaitCondition("Maintained codec recreation", { codecStarts.get() > beforeCodecStarts })
                    val previewTime = renderedFrames.last().first
                    lateinit var snapshot: PlaybackPlayerSnapshot
                    instrumentation.runOnMainSync {
                        snapshot = ExoPlayerCoordinatorPlaybackAccess(player, PlaybackRecoveryPolicy()) {}.snapshot()
                    }
                    assertEquals("Old exact mapping cannot represent truncated first sample", TimeshiftPlaybackPosition.Unavailable,
                        controls.playbackPosition(attachment, snapshot.position))
                    assertTrue("Real clock precision maps only observed sample", (controls.playbackPosition(attachment,
                        snapshot.position, snapshot.positionResolutionUs) as? TimeshiftPlaybackPosition.Estimate)?.seek === result.seek)
                    val settled = position()
                    delay(300)
                    assertEquals("Paused clock", settled, position())
                    assertEquals("No automatic resume", expectedSpeeds, connection.speeds)
                    val continuationStart = renderedFrames.size
                    controls.setSpeed(100)
                    expectedSpeeds += 100
                    connection.emit(registration, SubscriptionEvent.Speed(100))
                    instrumentation.runOnMainSync { player.play() }
                    videos.drop(1).forEach { connection.emit(registration, it.toEvent(assets)); delay(10) }
                    delay(300)
                    instrumentation.runOnMainSync { assertTrue("Audio still absent", !player.isPlaying) }
                    rawPackets.filter { it.streamOrdinal == 2 }.forEach { connection.emit(registration, it.toEvent(assets)); delay(10) }
                    awaitCondition("Resume advances with references", {
                        var advancing = false
                        instrumentation.runOnMainSync { advancing = player.isPlaying && player.currentPosition > settled + 100 }
                        advancing
                    })
                    awaitCondition("Dependent continuation reaches surface", {
                        renderedFrames.drop(continuationStart).any { (timeUs, surfacesAtCallback) ->
                            timeUs > previewTime + 80_000 && surfaceFrames.get() > surfacesAtCallback
                        }
                    })
                    instrumentation.runOnMainSync { player.pause() }
                    controls.setSpeed(0)
                    expectedSpeeds += 0
                    connection.emit(registration, SubscriptionEvent.Speed(0))
                    assertTrue("No decoder failure through continuation", !error.get())
                    continue
                }
                channel.packets.forEach {
                    val origin = origins.getValue(it.streamOrdinal)
                    val packet = it.toEvent(assets)
                    connection.emit(registration, SubscriptionEvent.Packet(
                        frameType = packet.frameType, streamIndex = packet.streamIndex,
                        durationUs = packet.durationUs, payload = packet.payload,
                        presentationTimeUs = edge * 1_000_000 + it.presentationTimeUs - origin,
                        decodingTimeUs = it.decodingTimeUs?.let { time -> edge * 1_000_000 + time - origin },
                    ))
                }
                awaitCondition("New period discontinuity", { discontinuities.get() > beforeDiscontinuities })
                awaitCondition("New decoded first frame", { frames.get() > beforeFrames })
                awaitCondition("Correlated post-command playback sample", {
                    (controls.playbackPosition(attachment, position().milliseconds) as? TimeshiftPlaybackPosition.Estimate)
                        ?.seek === result.seek
                })
                val settled = position()
                if (paused) {
                    delay(250)
                    assertEquals("Paused seek must not advance the playback clock", settled, position())
                }
                assertTrue("No player failure", !error.get())
                assertEquals("No unsolicited server resume", if (paused) listOf(0) else emptyList<Int>(), connection.speeds)
            }
            if (finitePaused) {
                val retired = attachment
                instrumentation.runOnMainSync {
                    player.setMediaSource(createTvheadendLiveMediaSource(
                        FixedSubscriptionLiveTarget(manager, SubscriptionChannelId(1)),
                        SubscriptionOptions(timeshiftPeriod = 120.seconds), controls,
                    ))
                    player.prepare()
                }
                val replacement = withTimeout(10_000) { connection.awaitCollectionRegistered() }
                assertEquals(TimeshiftPlaybackPosition.Unavailable, controls.playbackPosition(retired, position().milliseconds, 1_000))
                val beforeLateFrames = frames.get()
                var oldCollectorRetired = false
                try {
                    connection.emit(registration, rawPackets.first().toEvent(assets))
                } catch (_: IllegalStateException) {
                    oldCollectorRetired = true
                }
                assertTrue("Replacement retires old collector", oldCollectorRetired)
                delay(300)
                assertEquals("Retired subscription cannot render into replacement", beforeLateFrames, frames.get())
                connection.emit(replacement, SubscriptionEvent.Started(channel.streams, null, SubscriptionCondition.NO_DETAIL))
                rawPackets.forEach { connection.emit(replacement, it.toEvent(assets)) }
                awaitCondition("Replacement owns new rendered frame", { frames.get() > beforeLateFrames })
                instrumentation.runOnMainSync { player.release(); initialized = false }
                delay(100)
                val releasedFrames = frames.get()
                var releasedCollector = false
                try {
                    connection.emit(replacement, rawPackets.first().toEvent(assets))
                } catch (_: IllegalStateException) {
                    releasedCollector = true
                }
                assertTrue("Release retires collector", releasedCollector)
                delay(300)
                assertEquals("Release fences late media", releasedFrames, frames.get())
                assertEquals("Only explicit test speed commands", expectedSpeeds, connection.speeds)
            }
        } finally {
            instrumentation.runOnMainSync { if (initialized) player.release() }
            manager.closeAndJoin()
            surface.release()
            textureHandler.post { texture.release(); textureThread.quitSafely() }
            textureThread.join(5_000)
        }
    }

    private fun h264PacketsWithContinuedAudio(channel: CapturedChannel): List<CapturedPacket> {
        // The capture's audio ends before its first video. Continue it at the recorded cadence,
        // retaining the common raw timeline rather than independently aligning A/V origins.
        val audio = channel.packets.filter { it.streamOrdinal == 2 }
        val cycleUs = audio.last().presentationTimeUs + audio.last().durationUs - audio.first().presentationTimeUs
        return channel.packets + (1L..4L).flatMap { cycle ->
            audio.map { packet -> packet.copy(
                presentationTimeUs = packet.presentationTimeUs + cycle * cycleUs,
                decodingTimeUs = packet.decodingTimeUs?.plus(cycle * cycleUs),
            ) }
        }
    }

    @Test
    fun finite_IDR_flush_preserves_complete_reader_continuation() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val channel = RecordedMuxCapture.load(assets).channel(16, 1)
        val queue = SampleQueue.createWithoutDrm(DefaultAllocator(true, 64 * 1024))
        try {
            val reader = (createElementaryStreamReader(channel.streams.single()) as ReaderResult.Supported).reader
            val adapter = SubscriptionElementaryStreamAdapter(reader, QueueOutput(queue), 0)
            adapter.accept(channel.packets.first().toEvent(assets))
            assertEquals("packetFinished is not sampleMetadata", 0, queue.writeIndex)
            channel.packets.drop(1).forEach { adapter.accept(it.toEvent(assets)) }
            reader.endOfInputReached()
            val expected = readSamples(queue, FormatHolder(), DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL))
            queue.reset()
            adapter.accept(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, null, null))
            assertTrue(adapter.acceptFiniteIdr(channel.packets.first().toEvent(assets)))
            assertEquals("Finite IDR flush commits one sample", 1, queue.writeIndex)
            channel.packets.drop(1).forEach { adapter.accept(it.toEvent(assets)) }
            adapter.end()
            val actual = readSamples(queue, FormatHolder(), DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL))
            assertEquals("Continuation preserves sample timestamps, sizes and keyframe flags", expected, actual)
        } finally {
            queue.release()
        }
    }

    @Test
    fun bundledFfmpegContingencySupportsRequiredAudioFormats() {
        assertTrue("FFmpeg native library must load", FfmpegLibrary.isAvailable())
        assertTrue("FFmpeg must support MPEG audio", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_MPEG))
        assertTrue("FFmpeg must support AC-3", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3))
        assertTrue("FFmpeg must support E-AC-3", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3))
    }

    @Test
    fun recorded_mux_packets_feed_actual_readers_into_sample_queues() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val capture = RecordedMuxCapture.load(assets)
        val fixtures = listOf(
            ReaderFixture(16, 1, SubscriptionStreamType.H264, MimeTypes.VIDEO_H264, true),
            ReaderFixture(4, 1, SubscriptionStreamType.MPEG2_VIDEO, MimeTypes.VIDEO_MPEG2, true),
            ReaderFixture(4, 2, SubscriptionStreamType.MPEG2_AUDIO, MimeTypes.AUDIO_MPEG_L2, false),
            ReaderFixture(4, 3, SubscriptionStreamType.AC3, MimeTypes.AUDIO_AC3, false),
        )

        fixtures.forEach { fixture ->
            assertFixture(assets, capture.fixture(fixture), fixture)
        }
    }

    @Test
    fun recorded_H264_and_MPEG_audio_render_through_live_media_source() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val capture = RecordedMuxCapture.load(assets)

        assertChannelRenders(
            instrumentation,
            assets,
            capture.channel(16, 1, 2).let { it.copy(packets = h264PacketsWithContinuedAudio(it)) },
            MimeTypes.AUDIO_MPEG_L2,
        )
    }

    @Test
    fun recorded_H262_and_MPEG_audio_render_through_live_media_source() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val capture = RecordedMuxCapture.load(assets)

        assertChannelRenders(
            instrumentation,
            assets,
            capture.channel(4, 1, 2),
            MimeTypes.AUDIO_MPEG_L2,
        )
    }

    @Test
    fun recorded_H262_and_AC3_render_through_live_media_source() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val capture = RecordedMuxCapture.load(assets)

        assertChannelRenders(
            instrumentation,
            assets,
            capture.channel(4, 1, 3),
            MimeTypes.AUDIO_AC3,
        )
    }

    @Test
    fun stripped_DVB_subtitle_segments_are_explicitly_selected_decoded_and_unsubscribed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connection = ScriptedSubscriptionConnection()
        val subscriptions = createSubscriptionManager(connection, Dispatchers.Default).apply { startAdmission() }
        val overrideApplied = AtomicBoolean()
        val selected = CountDownLatch(1)
        val cueDelivered = CountDownLatch(1)
        val track = AtomicReference<DvbTrackObservation?>()
        val failure = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        var playerInitialized = false
        var subscriptionsClosed = false

        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                )
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(100, 2_000, 50, 50)
                            .build(),
                    )
                    .build()
                playerInitialized = true
                player.volume = 0f
                player.addListener(
                    object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            tracks.groups.forEach { group ->
                                for (index in 0 until group.length) {
                                    val format = group.getTrackFormat(index)
                                    if (
                                        format.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES ||
                                        format.codecs != MimeTypes.APPLICATION_DVBSUBS
                                    ) {
                                        continue
                                    }
                                    val observation = DvbTrackObservation(
                                        supported = group.isTrackSupported(index),
                                        selected = group.isTrackSelected(index),
                                    )
                                    track.set(observation)
                                    if (observation.supported && overrideApplied.compareAndSet(false, true)) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                            .setOverrideForType(
                                                TrackSelectionOverride(group.mediaTrackGroup, index),
                                            )
                                            .build()
                                    }
                                    if (observation.selected) selected.countDown()
                                }
                            }
                        }

                        override fun onCues(cueGroup: CueGroup) {
                            if (
                                cueGroup.cues.any { cue ->
                                    cue.bitmap?.let { bitmap -> bitmap.width > 0 && bitmap.height > 0 } == true
                                }
                            ) {
                                cueDelivered.countDown()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            failure.compareAndSet(null, error)
                            selected.countDown()
                            cueDelivered.countDown()
                        }
                    },
                )
                player.setMediaSource(
                    createTvheadendLiveMediaSource(
                        FixedSubscriptionLiveTarget(subscriptions, SubscriptionChannelId(1L)),
                    ),
                )
                player.prepare()
                player.play()
            }

            val registration = runBlocking {
                val registered = withTimeout(10_000L) { connection.awaitCollectionRegistered() }
                connection.emit(
                    registered,
                    SubscriptionEvent.Started(
                        listOf(syntheticDvbStream()),
                        null,
                        SubscriptionCondition.NO_DETAIL,
                    ),
                )
                registered
            }
            assertTrue("DVB subtitle track must become explicitly selected", selected.await(10L, TimeUnit.SECONDS))
            assertNull("Playback must not fail while selecting DVB subtitles", failure.get())
            assertTrue("DVB subtitle selection override must be applied", overrideApplied.get())
            assertEquals(DvbTrackObservation(supported = true, selected = true), track.get())

            runBlocking {
                connection.emit(
                    registration,
                    SubscriptionEvent.Packet(
                        frameType = MuxFrameType.UNKNOWN,
                        streamIndex = StreamIndex(0L),
                        decodingTimeUs = 500_000L,
                        presentationTimeUs = 500_000L,
                        durationUs = 1L,
                        payload = FixtureBinary(syntheticDvbSubtitleSegments()),
                    ),
                )
            }
            assertTrue("Maintained DVB decoder must emit a non-empty bitmap cue", cueDelivered.await(15L, TimeUnit.SECONDS))
            assertNull("DVB subtitle playback must not fail", failure.get())
            assertEquals(0, connection.unsubscribeCount)

            instrumentation.runOnMainSync {
                player.release()
                playerInitialized = false
            }
            runBlocking {
                withTimeout(10_000L) {
                    while (connection.unsubscribeCount == 0) delay(10L)
                }
            }
            assertEquals("Media-period release must unsubscribe exactly once", 1, connection.unsubscribeCount)
            runBlocking { subscriptions.closeAndJoin() }
            subscriptionsClosed = true
            assertEquals("Manager teardown must not unsubscribe again", 1, connection.unsubscribeCount)
        } finally {
            instrumentation.runOnMainSync {
                if (playerInitialized) player.release()
            }
            if (!subscriptionsClosed) runBlocking { subscriptions.closeAndJoin() }
        }
    }

    private fun assertChannelRenders(
        instrumentation: android.app.Instrumentation,
        assets: AssetManager,
        channel: CapturedChannel,
        expectedAudioMimeType: String,
    ) {
        val connection = ScriptedSubscriptionConnection()
        val subscriptions = createSubscriptionManager(connection, Dispatchers.Default).apply { startAdmission() }
        val rendered = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val audio = AudioPlaybackObservation(expectedAudioMimeType)
        val videoDecoder = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        lateinit var player: ExoPlayer
        var playerInitialized = false
        var subscriptionsClosed = false

        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                )
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(100, 2_000, 50, 50)
                            .build(),
                    )
                    .build()
                playerInitialized = true
                player.volume = 0f
                player.setVideoSurface(surface)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) ready.countDown()
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            audio.observeTracks(tracks)
                        }

                        override fun onRenderedFirstFrame() {
                            rendered.countDown()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            failure.compareAndSet(null, error)
                            ready.countDown()
                            audio.pipeline.countDown()
                            videoDecoder.countDown()
                            rendered.countDown()
                        }
                    },
                )
                player.addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onAudioEnabled(
                            eventTime: AnalyticsListener.EventTime,
                            decoderCounters: DecoderCounters,
                        ) {
                            audio.rendererEnabled.set(true)
                        }

                        override fun onAudioInputFormatChanged(
                            eventTime: AnalyticsListener.EventTime,
                            format: androidx.media3.common.Format,
                            decoderReuseEvaluation: DecoderReuseEvaluation?,
                        ) {
                            if (format.sampleMimeType == expectedAudioMimeType) {
                                audio.inputFormatReceived.set(true)
                            }
                        }

                        override fun onAudioDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long,
                        ) {
                            audio.decoderInitialized.set(true)
                            audio.pipeline.countDown()
                        }

                        override fun onAudioTrackInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            audioTrackConfig: AudioSink.AudioTrackConfig,
                        ) {
                            audio.trackInitialized.set(true)
                            audio.pipeline.countDown()
                        }

                        override fun onAudioPositionAdvancing(
                            eventTime: AnalyticsListener.EventTime,
                            playoutStartSystemTimeMs: Long,
                        ) {
                            audio.positionAdvancing.set(true)
                        }

                        override fun onAudioDisabled(
                            eventTime: AnalyticsListener.EventTime,
                            decoderCounters: DecoderCounters,
                        ) {
                            audio.renderedOutputBufferCount.set(decoderCounters.renderedOutputBufferCount)
                            audio.rendererDisabled.countDown()
                        }

                        override fun onAudioCodecError(
                            eventTime: AnalyticsListener.EventTime,
                            audioCodecError: Exception,
                        ) {
                            audio.codecError.set(true)
                        }

                        override fun onAudioSinkError(
                            eventTime: AnalyticsListener.EventTime,
                            audioSinkError: Exception,
                        ) {
                            audio.sinkError.set(true)
                        }

                        override fun onVideoDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long,
                        ) {
                            videoDecoder.countDown()
                        }
                    },
                )
                player.setMediaSource(
                    createTvheadendLiveMediaSource(
                        FixedSubscriptionLiveTarget(subscriptions, SubscriptionChannelId(1L)),
                    ),
                )
                player.prepare()
                player.play()
            }

            runBlocking {
                val registration = withTimeout(10_000L) { connection.awaitCollectionRegistered() }
                connection.emit(
                    registration,
                    SubscriptionEvent.Started(channel.streams, null, SubscriptionCondition.NO_DETAIL),
                )
                channel.packets.forEach { packet -> connection.emit(registration, packet.toEvent(assets)) }
            }

            assertTrue("Playback must become ready", ready.await(15L, TimeUnit.SECONDS))
            val track = audio.track.get()
            assertNotNull("Playback must expose the expected audio track (${audio.summary()})", track)
            assertTrue("Playback must support the expected audio track (${audio.summary()})", track!!.supported)
            assertTrue("Playback must select the expected audio track (${audio.summary()})", track.selected)
            assertTrue(
                "Playback must initialize the audio pipeline (${audio.summary()})",
                audio.pipeline.await(15L, TimeUnit.SECONDS),
            )
            assertTrue("Playback must initialize a video decoder", videoDecoder.await(15L, TimeUnit.SECONDS))
            assertTrue("Playback must render a video frame", rendered.await(15L, TimeUnit.SECONDS))
            assertNull("Playback must not fail", failure.get())
            assertEquals("The live subscription must remain open before release", 0, connection.unsubscribeCount)
            instrumentation.runOnMainSync {
                player.release()
                playerInitialized = false
            }
            assertTrue(
                "Playback must disable the audio renderer during release (${audio.summary()})",
                audio.rendererDisabled.await(10L, TimeUnit.SECONDS),
            )
            assertTrue(
                "Playback must submit audio output to the sink (${audio.summary()})",
                audio.renderedOutputBufferCount.get() > 0,
            )
            runBlocking {
                withTimeout(10_000L) {
                    while (connection.unsubscribeCount == 0) delay(10L)
                }
            }
            assertEquals("Media-period release must unsubscribe exactly once", 1, connection.unsubscribeCount)
            runBlocking { subscriptions.closeAndJoin() }
            subscriptionsClosed = true
            assertEquals("Manager teardown must not unsubscribe again", 1, connection.unsubscribeCount)
        } finally {
            instrumentation.runOnMainSync {
                if (playerInitialized) player.release()
            }
            if (!subscriptionsClosed) runBlocking { subscriptions.closeAndJoin() }
            surface.release()
            texture.release()
        }
    }

    private fun assertFixture(
        assets: AssetManager,
        captured: CapturedStream,
        fixture: ReaderFixture,
    ) {
        val result = createElementaryStreamReader(captured.stream)
        assertTrue("${fixture.type} must have a Media3 reader", result is ReaderResult.Supported)
        val queue = SampleQueue.createWithoutDrm(DefaultAllocator(true, 64 * 1024))
        try {
            val reader = (result as ReaderResult.Supported).reader
            val adapter = SubscriptionElementaryStreamAdapter(reader, QueueOutput(queue), 0)
            captured.packets.forEach { packet -> adapter.accept(packet.toEvent(assets)) }
            adapter.end()

            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            assertEquals(
                C.RESULT_FORMAT_READ,
                queue.read(holder, buffer, SampleStream.FLAG_REQUIRE_FORMAT, false),
            )
            val format = holder.format
            assertNotNull("${fixture.type} must publish a Format", format)
            assertEquals(fixture.mimeType, format!!.sampleMimeType)
            if (fixture.video) {
                assertTrue("${fixture.type} must publish codec initialization data", format.initializationData.isNotEmpty())
            } else {
                assertTrue("${fixture.type} must publish a sample rate", format.sampleRate > 0)
                assertTrue("${fixture.type} must publish a channel count", format.channelCount > 0)
            }

            val samples = readSamples(queue, holder, buffer)
            assertTrue("${fixture.type} must emit samples", samples.isNotEmpty())
            assertTrue("${fixture.type} must emit non-empty samples", samples.all { it.size > 0 })
            val captureRange = captured.packets.minOf { it.presentationTimeUs }..
                captured.packets.maxOf { it.presentationTimeUs }
            assertTrue(
                "${fixture.type} sample times must come from the capture",
                samples.all { it.timeUs in captureRange },
            )
            if (fixture.video) {
                assertTrue("${fixture.type} must emit a keyframe", samples.any { it.keyFrame })
            }
        } finally {
            queue.release()
        }
    }

    private fun readSamples(
        queue: SampleQueue,
        holder: FormatHolder,
        buffer: DecoderInputBuffer,
    ): List<SampleMetadata> = buildList {
        while (true) {
            buffer.clear()
            when (queue.read(holder, buffer, 0, false)) {
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) return@buildList
                    buffer.flip()
                    add(SampleMetadata(buffer.timeUs, buffer.isKeyFrame, buffer.data?.remaining() ?: 0))
                }
                C.RESULT_FORMAT_READ -> Unit
                C.RESULT_NOTHING_READ -> return@buildList
                else -> error("Unexpected SampleQueue result")
            }
        }
    }
}

private class AudioPlaybackObservation(private val expectedMimeType: String) {
    val pipeline = CountDownLatch(1)
    val rendererDisabled = CountDownLatch(1)
    val track = AtomicReference<AudioTrackObservation?>()
    val rendererEnabled = AtomicBoolean()
    val inputFormatReceived = AtomicBoolean()
    val decoderInitialized = AtomicBoolean()
    val trackInitialized = AtomicBoolean()
    val positionAdvancing = AtomicBoolean()
    val codecError = AtomicBoolean()
    val sinkError = AtomicBoolean()
    val renderedOutputBufferCount = AtomicInteger()

    fun observeTracks(tracks: Tracks) {
        tracks.groups.forEach { group ->
            for (index in 0 until group.length) {
                if (group.getTrackFormat(index).sampleMimeType == expectedMimeType) {
                    track.set(
                        AudioTrackObservation(
                            support = group.getTrackSupport(index),
                            supported = group.isTrackSupported(index),
                            selected = group.isTrackSelected(index),
                        ),
                    )
                }
            }
        }
    }

    fun summary(): String {
        val observedTrack = track.get()
        return "present=${observedTrack != null}, " +
            "support=${observedTrack?.support ?: C.FORMAT_UNSUPPORTED_TYPE}, " +
            "selected=${observedTrack?.selected ?: false}, " +
            "rendererEnabled=${rendererEnabled.get()}, " +
            "inputFormat=${inputFormatReceived.get()}, " +
            "decoderInitialized=${decoderInitialized.get()}, " +
            "trackInitialized=${trackInitialized.get()}, " +
            "positionAdvancing=${positionAdvancing.get()}, " +
            "renderedOutputBuffers=${renderedOutputBufferCount.get()}, " +
            "codecError=${codecError.get()}, sinkError=${sinkError.get()}"
    }
}

private data class AudioTrackObservation(
    val support: Int,
    val supported: Boolean,
    val selected: Boolean,
)

private data class DvbTrackObservation(
    val supported: Boolean,
    val selected: Boolean,
)

private data class ReaderFixture(
    val channelOrdinal: Int,
    val streamOrdinal: Int,
    val type: SubscriptionStreamType,
    val mimeType: String,
    val video: Boolean,
)

private data class SampleMetadata(
    val timeUs: Long,
    val keyFrame: Boolean,
    val size: Int,
)

private data class CapturedPacket(
    val streamOrdinal: Int,
    val frameType: Int,
    val decodingTimeUs: Long?,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val size: Int,
    val sha256: String,
    val file: String,
) {
    fun toEvent(assets: AssetManager): SubscriptionEvent.Packet {
        val bytes = assets.open("recorded-mux/$file").use { it.readBytes() }
        check(bytes.size == size) { "Recorded fixture size does not match its manifest" }
        check(bytes.sha256() == sha256) { "Recorded fixture hash does not match its manifest" }
        return SubscriptionEvent.Packet(
            frameType = when (frameType) {
                73 -> MuxFrameType.I
                80 -> MuxFrameType.P
                66 -> MuxFrameType.B
                else -> MuxFrameType.UNKNOWN
            },
            streamIndex = StreamIndex(streamOrdinal.toLong()),
            decodingTimeUs = decodingTimeUs,
            presentationTimeUs = presentationTimeUs,
            durationUs = durationUs,
            payload = FixtureBinary(bytes),
        )
    }
}

private data class CapturedStream(
    val stream: SubscriptionStream,
    val packets: List<CapturedPacket>,
)

private data class CapturedChannel(
    val streams: List<SubscriptionStream>,
    val packets: List<CapturedPacket>,
)

private class RecordedMuxCapture(
    private val streams: Map<Pair<Int, Int>, CapturedStream>,
    private val packetsByChannel: Map<Int, List<CapturedPacket>>,
) {
    fun fixture(fixture: ReaderFixture): CapturedStream =
        checkNotNull(streams[fixture.channelOrdinal to fixture.streamOrdinal]) {
            "Recorded fixture is missing"
        }.also { captured ->
            check(captured.stream.type == fixture.type) { "Recorded fixture has the wrong stream type" }
        }

    fun channel(channelOrdinal: Int, vararg streamOrdinals: Int): CapturedChannel {
        val selected = streamOrdinals.toSet()
        return CapturedChannel(
            streams = streamOrdinals.map { streamOrdinal ->
                checkNotNull(streams[channelOrdinal to streamOrdinal]) { "Recorded stream is missing" }.stream
            },
            packets = checkNotNull(packetsByChannel[channelOrdinal]) { "Recorded channel is missing" }
                .filter { packet -> packet.streamOrdinal in selected },
        )
    }

    companion object {
        fun load(assets: AssetManager): RecordedMuxCapture {
            val root = assets.open("recorded-mux/manifest.json").bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
            val packetsByChannel = linkedMapOf<Int, List<CapturedPacket>>()
            val streams = buildMap {
                val channels = root.getJSONArray("channels")
                for (channelIndex in 0 until channels.length()) {
                    val channel = channels.getJSONObject(channelIndex)
                    val channelOrdinal = channel.getInt("channelOrdinal")
                    val descriptors = channel.getJSONArray("streams")
                    val packets = channel.getJSONArray("packets")
                    packetsByChannel[channelOrdinal] = buildList {
                        for (packetIndex in 0 until packets.length()) {
                            add(packets.getJSONObject(packetIndex).toPacket())
                        }
                    }
                    for (streamIndex in 0 until descriptors.length()) {
                        val descriptor = descriptors.getJSONObject(streamIndex)
                        val streamOrdinal = descriptor.getInt("streamOrdinal")
                        put(
                            channelOrdinal to streamOrdinal,
                            CapturedStream(
                                descriptor.toStream(),
                                buildList {
                                    for (packetIndex in 0 until packets.length()) {
                                        val packet = packets.getJSONObject(packetIndex)
                                        if (packet.getInt("streamOrdinal") == streamOrdinal) {
                                            add(packet.toPacket())
                                        }
                                    }
                                },
                            ),
                        )
                    }
                }
            }
            val manifestFiles = packetsByChannel.values.flatten().mapTo(mutableSetOf()) { it.file }
            val assetFiles = checkNotNull(assets.list("recorded-mux"))
                .filterTo(mutableSetOf()) { it.endsWith(".bin") }
            check(assetFiles == manifestFiles) { "Recorded fixture assets do not match the manifest" }
            return RecordedMuxCapture(streams, packetsByChannel)
        }
    }
}

private fun JSONObject.toStream(): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(getLong("streamOrdinal")),
    type = SubscriptionStreamType.valueOf(getString("streamType").replace("MPEG2VIDEO", "MPEG2_VIDEO").replace("MPEG2AUDIO", "MPEG2_AUDIO")),
    language = nullableString("language"),
    compositionId = nullableLong("compositionId"),
    ancillaryId = nullableLong("ancillaryId"),
    width = null,
    height = null,
    frameDuration = null,
    aspectNumerator = null,
    aspectDenominator = null,
    audioType = null,
    audioVersion = null,
    channelCount = null,
    rate = nullableLong("rate"),
    rdsUecp = null,
    codecMetadata = null,
)

private fun JSONObject.toPacket(): CapturedPacket = CapturedPacket(
    streamOrdinal = getInt("streamOrdinal"),
    frameType = getInt("frameType"),
    decodingTimeUs = nullableLong("decodingTimeUs"),
    presentationTimeUs = getLong("presentationTimeUs"),
    durationUs = getLong("durationUs"),
    size = getInt("size"),
    sha256 = getString("sha256"),
    file = getString("file"),
)

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private fun JSONObject.nullableLong(name: String): Long? = (opt(name) as? Number)?.toLong()

private fun JSONObject.nullableString(name: String): String? = opt(name) as? String

private class QueueOutput(private val queue: SampleQueue) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput = queue
    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
}

private fun syntheticDvbStream(): SubscriptionStream = SubscriptionStream(
    index = StreamIndex(0L),
    type = SubscriptionStreamType.DVB_SUBTITLE,
    language = "eng",
    compositionId = 1L,
    ancillaryId = 2L,
    width = null,
    height = null,
    frameDuration = null,
    aspectNumerator = null,
    aspectDenominator = null,
    audioType = null,
    audioVersion = null,
    channelCount = null,
    rate = null,
    rdsUecp = null,
    codecMetadata = null,
)

private fun syntheticDvbSubtitleSegments(): ByteArray = byteArrayOf(
    0x0f, 0x10, 0x00, 0x01, 0x00, 0x08, 0x05, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x0f, 0x11, 0x00, 0x01, 0x00, 0x0a, 0x01, 0x08, 0x00, 0x02, 0x00, 0x02, 0x24, 0x00, 0x00, 0x04,
)

private class FixtureBinary(private val bytes: ByteArray) : SubscriptionBinary {
    override val size: Int = bytes.size
    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}
