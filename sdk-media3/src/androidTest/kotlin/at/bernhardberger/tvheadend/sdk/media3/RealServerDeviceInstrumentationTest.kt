@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ServerAuthentication
import at.bernhardberger.tvheadend.sdk.core.ServerProfile
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionFailure
import at.bernhardberger.tvheadend.sdk.core.SessionOperationFailure
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCloseResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionDiagnostics
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class RealServerDeviceInstrumentationTest {
    @Test
    fun real_server_live_tv_matrix_renders_zaps_and_copies_each_payload_at_most_once() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configurationFile = File(instrumentation.targetContext.filesDir, PRIVATE_CONFIGURATION_FILE)
        assumeTrue("Private real-server verification is not provisioned", configurationFile.isFile)
        val configurationText = try {
            configurationFile.readText()
        } catch (_: Exception) {
            throw AssertionError("Private real-server verification could not be read")
        }
        check(configurationFile.delete()) { "Private real-server verification could not be consumed" }
        val configuration = try {
            RealServerConfiguration.parse(configurationText)
        } catch (_: Exception) {
            throw AssertionError("Private real-server verification is invalid")
        }
        val session = createTvheadendSession()
        val copyObservation = PayloadCopyObservation()
        val unsupportedStreams = Collections.synchronizedSet(mutableSetOf<SubscriptionStreamType>())
        val observedStreams = Collections.synchronizedSet(mutableSetOf<SubscriptionStreamType>())
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val currentPlayback = AtomicReference<PlaybackObservation?>()
        lateinit var player: ExoPlayer
        var playerInitialized = false
        var activeOpener: ObservedSubscriptionOpener? = null

        try {
            assertEquals(
                SessionCommandResult.STARTED,
                session.connect(configuration.profile),
            )
            val sessionState = withTimeout(CONNECTION_TIMEOUT_MS) {
                session.observation.first { observation ->
                    observation.sessionState.isTerminalForVerification()
                }
            }.sessionState
            val unavailableReason = (sessionState as? SessionState.Unavailable)?.reason
            assertTrue(
                "Real-server session must become ready; failure=$unavailableReason",
                sessionState is SessionState.Ready,
            )

            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(
                    instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext),
                ).build()
                playerInitialized = true
                player.volume = 0f
                player.setVideoSurface(surface)
                player.addListener(
                    object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            currentPlayback.get()?.observeTracks(tracks)
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            currentPlayback.get()?.observeVideoSize(videoSize)
                        }

                        override fun onRenderedFirstFrame() {
                            currentPlayback.get()?.firstFrame?.countDown()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            currentPlayback.get()?.fail()
                        }
                    },
                )
                player.addAnalyticsListener(
                    object : AnalyticsListener {
                        override fun onAudioInputFormatChanged(
                            eventTime: AnalyticsListener.EventTime,
                            format: Format,
                            decoderReuseEvaluation: DecoderReuseEvaluation?,
                        ) {
                            currentPlayback.get()?.observeAudioInputFormat(format)
                        }

                        override fun onVideoDecoderInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            decoderName: String,
                            initializedTimestampMs: Long,
                            initializationDurationMs: Long,
                        ) {
                            currentPlayback.get()?.videoDecoder?.countDown()
                        }

                        override fun onAudioTrackInitialized(
                            eventTime: AnalyticsListener.EventTime,
                            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                        ) {
                            currentPlayback.get()?.audioTrack?.countDown()
                        }

                        override fun onAudioDisabled(
                            eventTime: AnalyticsListener.EventTime,
                            decoderCounters: DecoderCounters,
                        ) {
                            currentPlayback.get()?.observeAudioDisabled(decoderCounters)
                        }

                        override fun onAudioCodecError(
                            eventTime: AnalyticsListener.EventTime,
                            audioCodecError: Exception,
                        ) {
                            currentPlayback.get()?.fail()
                        }

                        override fun onAudioSinkError(
                            eventTime: AnalyticsListener.EventTime,
                            audioSinkError: Exception,
                        ) {
                            currentPlayback.get()?.fail()
                        }
                    },
                )
            }

            val matrix = configuration.channels + buildList {
                repeat(configuration.zapIterations) { index ->
                    add(if (index % 2 == 0) configuration.sdChannel else configuration.hdChannel)
                }
            }
            matrix.forEach { channel ->
                val previousOpener = activeOpener
                val previousPlayback = currentPlayback.get()
                if (previousPlayback != null) {
                    instrumentation.runOnMainSync {
                        player.stop()
                        player.clearMediaItems()
                    }
                    previousOpener?.assertClosed()
                    previousPlayback.assertAudioOutput()
                    currentPlayback.set(null)
                }
                val playback = PlaybackObservation(channel)
                val opener = ObservedSubscriptionOpener(
                    delegate = session.subscriptions,
                    copyObservation = copyObservation,
                    onTracks = { tracks ->
                        val types = tracks.streams.mapTo(mutableSetOf()) { stream -> stream.type }
                        check(types == channel.expectedStreams) {
                            "${channel.role} stream matrix did not match"
                        }
                        observedStreams += types
                    },
                )
                currentPlayback.set(playback)
                instrumentation.runOnMainSync {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setPreferredAudioMimeType(channel.expectedAudioMimeType)
                        .build()
                    player.setMediaSource(
                        createTvheadendLiveMediaSource(
                            subscriptions = opener,
                            channelId = channel.id,
                            onUnsupportedStream = { stream -> unsupportedStreams += stream },
                        ),
                    )
                    player.prepare()
                    player.play()
                }

                playback.assertRendered()
                activeOpener = opener
            }

            instrumentation.runOnMainSync {
                player.stop()
                player.clearMediaItems()
            }
            activeOpener?.assertClosed()
            currentPlayback.get()?.assertAudioOutput()
            currentPlayback.set(null)
            instrumentation.runOnMainSync {
                player.release()
                playerInitialized = false
            }
            assertTrue("Real playback must consume packet payloads", copyObservation.copiedPackets.get() > 0)
            assertTrue("A packet payload must be copied at most once", copyObservation.maximumCopies.get() <= 1)
            assertTrue(
                "The configured server codec matrix must be observed",
                observedStreams == configuration.availableStreams,
            )
            assertTrue(
                "Configured unsupported streams must remain typed",
                unsupportedStreams == configuration.unsupportedStreams,
            )
        } finally {
            currentPlayback.set(null)
            instrumentation.runOnMainSync {
                if (playerInitialized) player.release()
            }
            session.shutdown()
            surface.release()
            texture.release()
        }
    }

    private companion object {
        const val PRIVATE_CONFIGURATION_FILE = "p1-8-real-server.json"
        const val CONNECTION_TIMEOUT_MS = 30_000L
    }
}

private class PlaybackObservation(private val channel: RealServerChannel) {
    val firstFrame = CountDownLatch(1)
    val videoDecoder = CountDownLatch(1)
    val audioTrack = CountDownLatch(1)
    private val expectedVideoTrack = CountDownLatch(1)
    private val expectedAudioTrack = CountDownLatch(1)
    private val expectedAudioInput = CountDownLatch(1)
    private val audioDisabled = CountDownLatch(1)
    private val renderedAudioBuffers = AtomicInteger()
    private val expectedAspect = CountDownLatch(if (channel.aspectRatio == null) 0 else 1)
    private val failed = AtomicBoolean()

    fun observeTracks(tracks: Tracks) {
        tracks.groups.forEach { group ->
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                if (
                    format.sampleMimeType == channel.expectedVideoMimeType &&
                    group.isTrackSupported(index) &&
                    group.isTrackSelected(index)
                ) {
                    expectedVideoTrack.countDown()
                }
                if (
                    format.sampleMimeType == channel.expectedAudioMimeType &&
                    group.isTrackSupported(index) &&
                    group.isTrackSelected(index)
                ) {
                    expectedAudioTrack.countDown()
                }
            }
        }
    }

    fun observeVideoSize(videoSize: VideoSize) {
        val expected = channel.aspectRatio ?: return
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        val actual = videoSize.width.toDouble() * videoSize.pixelWidthHeightRatio / videoSize.height
        if (abs(actual - expected) <= ASPECT_TOLERANCE) expectedAspect.countDown()
    }

    fun observeAudioInputFormat(format: Format) {
        if (format.sampleMimeType == channel.expectedAudioMimeType) expectedAudioInput.countDown()
    }

    fun observeAudioDisabled(decoderCounters: DecoderCounters) {
        renderedAudioBuffers.set(decoderCounters.renderedOutputBufferCount)
        audioDisabled.countDown()
    }

    fun fail() {
        failed.set(true)
        firstFrame.countDown()
        videoDecoder.countDown()
        audioTrack.countDown()
        expectedVideoTrack.countDown()
        expectedAudioTrack.countDown()
        expectedAudioInput.countDown()
        audioDisabled.countDown()
        expectedAspect.countDown()
    }

    fun assertRendered() {
        assertTrue("${channel.role} must expose its selected video track", expectedVideoTrack.await(30L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must select its required audio codec", expectedAudioTrack.await(30L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must feed its required audio codec", expectedAudioInput.await(30L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must initialize video decoding", videoDecoder.await(30L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must initialize audio output", audioTrack.await(30L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must render a video frame", firstFrame.await(30L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must preserve display aspect ratio", expectedAspect.await(30L, TimeUnit.SECONDS))
        assertFalse("${channel.role} playback must not fail", failed.get())
    }

    fun assertAudioOutput() {
        assertTrue("${channel.role} must disable its audio renderer", audioDisabled.await(10L, TimeUnit.SECONDS))
        assertTrue("${channel.role} must submit decoded audio output", renderedAudioBuffers.get() > 0)
        assertFalse("${channel.role} audio playback must not fail", failed.get())
    }

    private companion object {
        const val ASPECT_TOLERANCE = 0.03
    }
}

private class ObservedSubscriptionOpener(
    private val delegate: SubscriptionOpener,
    private val copyObservation: PayloadCopyObservation,
    private val onTracks: (SubscriptionTracks) -> Unit,
) : SubscriptionOpener {
    private val closed = CountDownLatch(1)
    private val cleanClose = AtomicBoolean()

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult {
        val observedConsumer = object : SubscriptionEventConsumer {
            override fun tracksReady(tracks: SubscriptionTracks) {
                onTracks(tracks)
                consumer.tracksReady(tracks)
            }

            override suspend fun accept(event: SubscriptionEvent) {
                if (event !is SubscriptionEvent.Packet) {
                    consumer.accept(event)
                    return
                }
                val payload = CountingSubscriptionBinary(event.payload, copyObservation)
                try {
                    consumer.accept(
                        SubscriptionEvent.Packet(
                            frameType = event.frameType,
                            streamIndex = event.streamIndex,
                            decodingTimeUs = event.decodingTimeUs,
                            presentationTimeUs = event.presentationTimeUs,
                            durationUs = event.durationUs,
                            payload = payload,
                        ),
                    )
                } finally {
                    check(payload.copyCount.get() <= 1) { "Subscription payload was copied more than once" }
                    if (payload.copyCount.get() == 1) copyObservation.copiedPackets.incrementAndGet()
                }
            }
        }
        return when (
            val result = delegate.open(channelId, observedConsumer, timeshiftPeriod)
        ) {
            is SubscriptionOpenResult.Opened -> SubscriptionOpenResult.Opened(
                object : ActiveSubscription {
                    override val state: StateFlow<SubscriptionState> = result.subscription.state
                    override val diagnostics: StateFlow<SubscriptionDiagnostics> = result.subscription.diagnostics
                    override val grantedTimeshiftPeriod: Duration? =
                        result.subscription.grantedTimeshiftPeriod

                    override suspend fun seek(
                        target: SubscriptionSeekTarget,
                    ): SubscriptionSeekResult = result.subscription.seek(target)

                    override suspend fun close(): SubscriptionCloseResult = try {
                        result.subscription.close().also { closeResult ->
                            cleanClose.set(closeResult == SubscriptionCloseResult.CLOSED)
                        }
                    } finally {
                        closed.countDown()
                    }
                },
            )
            else -> result
        }
    }

    fun assertClosed() {
        assertTrue("A replaced live subscription must terminate", closed.await(15L, TimeUnit.SECONDS))
        assertTrue("A replaced live subscription must unsubscribe cleanly", cleanClose.get())
    }
}

private class CountingSubscriptionBinary(
    private val delegate: SubscriptionBinary,
    private val observation: PayloadCopyObservation,
) : SubscriptionBinary {
    override val size: Int = delegate.size
    val copyCount = AtomicInteger()

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        val copies = copyCount.incrementAndGet()
        observation.maximumCopies.accumulateAndGet(copies) { current, candidate ->
            maxOf(current, candidate)
        }
        return delegate.copyInto(destination, destinationOffset)
    }
}

private class PayloadCopyObservation {
    val copiedPackets = AtomicInteger()
    val maximumCopies = AtomicInteger()
}

private data class RealServerConfiguration(
    val profile: ServerProfile,
    val channels: List<RealServerChannel>,
    val availableStreams: Set<SubscriptionStreamType>,
    val unsupportedStreams: Set<SubscriptionStreamType>,
    val zapIterations: Int,
) {
    val sdChannel: RealServerChannel = channels.single { channel -> channel.role == ChannelRole.SD }
    val hdChannel: RealServerChannel = channels.single { channel -> channel.role == ChannelRole.HD }

    companion object {
        fun parse(text: String): RealServerConfiguration {
            val root = JSONObject(text)
            root.requireKeys(
                "host",
                "htsp_port",
                "username",
                "password",
                "channels",
                "available_streams",
                "unsupported_streams",
                "zap_iterations",
            )
            val channels = root.getJSONArray("channels").mapObjects(RealServerChannel::parse)
            require(channels.size in 2..16) { "Real-server channel matrix is invalid" }
            require(channels.count { channel -> channel.role == ChannelRole.SD } == 1) {
                "Real-server SD role is invalid"
            }
            require(channels.count { channel -> channel.role == ChannelRole.HD } == 1) {
                "Real-server HD role is invalid"
            }
            require(channels.single { channel -> channel.role == ChannelRole.SD }.aspectRatio != null) {
                "Real-server SD aspect ratio is invalid"
            }
            val availableStreams = root.getJSONArray("available_streams").streamTypes()
            val unsupportedStreams = root.getJSONArray("unsupported_streams").streamTypes()
            require(availableStreams.isNotEmpty()) { "Real-server codec matrix is invalid" }
            require(availableStreams == channels.flatMapTo(mutableSetOf()) { channel -> channel.expectedStreams }) {
                "Real-server codec matrix is invalid"
            }
            require(unsupportedStreams == availableStreams.intersect(UNSUPPORTED_STREAMS)) {
                "Real-server unsupported matrix is invalid"
            }
            val exercisedStreams = channels.flatMapTo(mutableSetOf()) { channel ->
                setOf(channel.expectedVideoStream, channel.expectedAudioStream)
            }
            require(exercisedStreams.containsAll(availableStreams.intersect(AUDIO_VIDEO_STREAMS))) {
                "Real-server exercised codec matrix is invalid"
            }
            require(SubscriptionStreamType.DVB_SUBTITLE !in availableStreams) {
                "DVB subtitle fixture requires dedicated verification"
            }
            val zapIterations = root.getInt("zap_iterations")
            require(zapIterations in 2..10) { "Real-server zap count is invalid" }
            return RealServerConfiguration(
                profile = ServerProfile(
                    host = root.boundedString("host", 255),
                    port = root.getInt("htsp_port"),
                    authentication = ServerAuthentication.Password(
                        username = root.boundedString("username", 255),
                        password = root.boundedString("password", 1_024),
                    ),
                ),
                channels = channels,
                availableStreams = availableStreams,
                unsupportedStreams = unsupportedStreams,
                zapIterations = zapIterations,
            )
        }
    }
}

private data class RealServerChannel(
    val role: ChannelRole,
    val id: SubscriptionChannelId,
    val expectedStreams: Set<SubscriptionStreamType>,
    val expectedAudioStream: SubscriptionStreamType,
    val aspectRatio: Double?,
) {
    val expectedVideoStream: SubscriptionStreamType = when (role) {
        ChannelRole.SD -> SubscriptionStreamType.MPEG2_VIDEO
        ChannelRole.HD -> SubscriptionStreamType.H264
        ChannelRole.MATRIX -> expectedStreams.singleOrNull { stream -> stream in VIDEO_STREAMS }
            ?: error("Real-server matrix channel video is invalid")
    }.also { stream ->
        require(stream in expectedStreams) { "Real-server role video is invalid" }
    }
    val expectedVideoMimeType: String = expectedVideoStream.videoMimeType()
    val expectedAudioMimeType: String = expectedAudioStream.audioMimeType()

    companion object {
        fun parse(value: JSONObject): RealServerChannel {
            value.requireKeys(
                "role",
                "channel_id",
                "expected_streams",
                "expected_audio",
                "aspect_numerator",
                "aspect_denominator",
            )
            val numerator = value.optLongOrNull("aspect_numerator")
            val denominator = value.optLongOrNull("aspect_denominator")
            require((numerator == null) == (denominator == null)) { "Real-server aspect ratio is invalid" }
            denominator?.let { require(it > 0L) { "Real-server aspect ratio is invalid" } }
            val expectedStreams = value.getJSONArray("expected_streams").streamTypes()
            val expectedAudioStream = SubscriptionStreamType.valueOf(value.getString("expected_audio"))
            require(expectedAudioStream in expectedStreams) { "Real-server channel audio is invalid" }
            return RealServerChannel(
                role = ChannelRole.valueOf(value.getString("role")),
                id = SubscriptionChannelId(value.getLong("channel_id")),
                expectedStreams = expectedStreams,
                expectedAudioStream = expectedAudioStream,
                aspectRatio = numerator?.toDouble()?.div(checkNotNull(denominator).toDouble()),
            )
        }
    }
}

private enum class ChannelRole { SD, HD, MATRIX }

private fun SessionState.isTerminalForVerification(): Boolean = when (this) {
    is SessionState.Ready -> true
    SessionState.Disconnected,
    SessionState.Connecting,
    SessionState.Synchronizing,
    -> false
    is SessionState.Unavailable -> when (val failure = reason) {
        SessionFailure.ServerUnreachable,
        SessionFailure.NetworkUnavailable,
        SessionFailure.TransportUnavailable,
        -> false
        SessionFailure.AuthenticationRejected,
        SessionFailure.PermissionDenied,
        SessionFailure.IncompatibleServer,
        SessionFailure.NoChannels,
        SessionFailure.UnexpectedFailure,
        -> true
        is SessionFailure.SynchronizationFailed -> when (failure.failure) {
            SessionOperationFailure.CONNECTION_LIMIT,
            SessionOperationFailure.TIMEOUT,
            SessionOperationFailure.TRANSPORT_UNAVAILABLE,
            -> false
            SessionOperationFailure.SERVER_REJECTED,
            SessionOperationFailure.ACCESS_DENIED,
            SessionOperationFailure.NOT_SUPPORTED,
            -> true
        }
    }
}

private val VIDEO_STREAMS = setOf(
    SubscriptionStreamType.MPEG2_VIDEO,
    SubscriptionStreamType.H264,
    SubscriptionStreamType.H265,
)
private val AUDIO_VIDEO_STREAMS = VIDEO_STREAMS + setOf(
    SubscriptionStreamType.AAC,
    SubscriptionStreamType.AC3,
    SubscriptionStreamType.EAC3,
    SubscriptionStreamType.MPEG2_AUDIO,
)
private val UNSUPPORTED_STREAMS = setOf(
    SubscriptionStreamType.TEXT_SUBTITLE,
    SubscriptionStreamType.TELETEXT,
    SubscriptionStreamType.UNKNOWN,
)

private fun JSONObject.requireKeys(vararg expected: String) {
    require(keys().asSequence().toSet() == expected.toSet()) { "Private verification fields are invalid" }
}

private fun JSONObject.boundedString(name: String, maximumLength: Int): String = getString(name).also { value ->
    require(value.isNotBlank() && value.length <= maximumLength) { "Private verification value is invalid" }
}

private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)

private fun JSONArray.mapObjects(transform: (JSONObject) -> RealServerChannel): List<RealServerChannel> =
    buildList {
        for (index in 0 until length()) add(transform(getJSONObject(index)))
    }

private fun JSONArray.streamTypes(): Set<SubscriptionStreamType> = buildSet {
    for (index in 0 until length()) add(SubscriptionStreamType.valueOf(getString(index)))
}

private fun SubscriptionStreamType.videoMimeType(): String = when (this) {
    SubscriptionStreamType.MPEG2_VIDEO -> MimeTypes.VIDEO_MPEG2
    SubscriptionStreamType.H264 -> MimeTypes.VIDEO_H264
    SubscriptionStreamType.H265 -> MimeTypes.VIDEO_H265
    else -> error("Real-server video codec is invalid")
}

private fun SubscriptionStreamType.audioMimeType(): String = when (this) {
    SubscriptionStreamType.AAC -> MimeTypes.AUDIO_AAC
    SubscriptionStreamType.AC3 -> MimeTypes.AUDIO_AC3
    SubscriptionStreamType.EAC3 -> MimeTypes.AUDIO_E_AC3
    SubscriptionStreamType.MPEG2_AUDIO -> MimeTypes.AUDIO_MPEG_L2
    else -> error("Real-server audio codec is invalid")
}
