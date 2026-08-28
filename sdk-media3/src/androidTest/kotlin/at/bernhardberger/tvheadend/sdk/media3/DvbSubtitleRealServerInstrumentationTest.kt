@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.SessionCommandResult
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.core.createTvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.ActiveSubscription
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTracks
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class DvbSubtitleRealServerInstrumentationTest {
    @Test(timeout = LIVE_TEST_TIMEOUT_MS)
    fun active_HTSP_DVB_subtitle_descriptor_packet_selection_cue_and_cleanup(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val privateProfile = File(instrumentation.targetContext.filesDir, PRIVATE_PROFILE_FILE_NAME)
        val profile = consumePrivateProfile(instrumentation.targetContext.filesDir)
        val session = createTvheadendSession()
        var sessionShutdown = false
        val verification = try {
            assertEquals(SessionCommandResult.STARTED, session.connect(profile))
            val ready = withTimeout(CONNECTION_TIMEOUT_MS) {
                session.observation.first { observation ->
                    observation.sessionState is SessionState.Ready ||
                        observation.sessionState is SessionState.Unavailable
                }
            }.sessionState
            assertTrue("P7-C3 real-server session must become Ready", ready is SessionState.Ready)
            val probe = discoverDvbTargets(session)
            val attempts = ArrayList<DvbAttempt>()
            for (target in probe.targets.sortedByDescending(DvbTarget::probePacketCount)) {
                val attempt = verifyDvbTarget(instrumentation, session, target)
                attempts += attempt
                if (attempt.overrideApplied && attempt.cueDelivered && attempt.subscriptionClosed) break
            }
            DvbVerification(probe, attempts)
        } finally {
            withContext(NonCancellable) {
                session.shutdown()
                sessionShutdown = true
                check(!privateProfile.exists()) { "One-use private profile was not consumed" }
            }
        }

        val successful = verification.attempts.firstOrNull { attempt ->
            attempt.descriptorPresent && attempt.descriptorInRange && attempt.descriptorCorrelated &&
                attempt.packetCount > 0L && attempt.trackSupported && attempt.overrideApplied && attempt.trackSelected &&
                attempt.cueDelivered && attempt.subscriptionClosed && attempt.playerFailure == null
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putInt("p7_c3_channels_probed", verification.probe.channelsProbed)
                putInt("p7_c3_candidates", verification.probe.targets.size)
                putInt("p7_c3_candidates_attempted", verification.attempts.size)
                putBoolean("p7_c3_descriptor_present", verification.attempts.any(DvbAttempt::descriptorPresent))
                putBoolean("p7_c3_descriptor_in_range", verification.attempts.any(DvbAttempt::descriptorInRange))
                putBoolean("p7_c3_descriptor_correlated", verification.attempts.any(DvbAttempt::descriptorCorrelated))
                putLong("p7_c3_subtitle_packets", verification.attempts.sumOf(DvbAttempt::packetCount))
                putBoolean("p7_c3_text_track_supported", verification.attempts.any(DvbAttempt::trackSupported))
                putBoolean("p7_c3_selection_override_applied", verification.attempts.any(DvbAttempt::overrideApplied))
                putBoolean("p7_c3_text_track_selected", verification.attempts.any(DvbAttempt::trackSelected))
                putBoolean("p7_c3_nonempty_bitmap_cue", verification.attempts.any(DvbAttempt::cueDelivered))
                putBoolean("p7_c3_subscription_closed", verification.attempts.any(DvbAttempt::subscriptionClosed))
                putBoolean("p7_c3_profile_consumed", !privateProfile.exists())
                putBoolean("p7_c3_session_shutdown", sessionShutdown)
            },
        )

        assertTrue("A bounded channel probe must find a valid DVB subtitle descriptor", verification.probe.targets.isNotEmpty())
        assertNotNull(
            "A live DVB subtitle packet must be explicitly selected and decoded into a non-empty cue",
            successful,
        )
        assertTrue("The one-use private profile must be deleted", !privateProfile.exists())
        assertTrue("The real-server session must be shut down", sessionShutdown)
    }
}

private suspend fun discoverDvbTargets(session: TvheadendSession): DvbProbe {
    val targets = ArrayList<DvbTarget>()
    var channelsProbed = 0
    val channels = (session.observation.value.channelState as ChannelRepositoryState.Current)
        .catalog.channels
    for (channel in channels.take(MAXIMUM_PROBE_CHANNELS)) {
        channelsProbed += 1
        val counter = DvbProbePacketCounter()
        val binding = session.livePlaybackBindingOrNull(channel.id) ?: continue
        val opened = withTimeoutOrNull(PROBE_OPEN_TIMEOUT_MS) {
            binding.open(counter, SubscriptionOptions())
        } as? SubscriptionOpenResult.Opened ?: continue
        try {
            val playable = opened.subscription.state.value as? SubscriptionState.Playable ?: continue
            val stream = playable.tracks.streams.firstOrNull(SubscriptionStream::isValidDvbDescriptor) ?: continue
            delay(PROBE_PACKET_WINDOW_MS)
            targets += DvbTarget(
                channelId = channel.id,
                compositionId = checkNotNull(stream.compositionId),
                ancillaryId = checkNotNull(stream.ancillaryId),
                probePacketCount = counter.count(stream.index),
            )
        } finally {
            withContext(NonCancellable) { opened.subscription.close() }
        }
        if (targets.size >= MAXIMUM_DVB_TARGETS) break
    }
    return DvbProbe(channelsProbed, targets)
}

private suspend fun verifyDvbTarget(
    instrumentation: android.app.Instrumentation,
    session: TvheadendSession,
    target: DvbTarget,
): DvbAttempt {
    val opener = DvbObservingTarget(
        BoundCoordinatorLiveTarget(session.requireLivePlaybackBinding(target.channelId)),
        target,
    )
    val overrideApplied = AtomicBoolean()
    val trackSupported = AtomicBoolean()
    val trackSelected = AtomicBoolean()
    val cueDelivered = AtomicBoolean()
    val playerFailure = AtomicReference<String?>()
    lateinit var player: ExoPlayer
    var playerCreated = false

    try {
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(
                instrumentation.targetContext,
                createTvheadendRenderersFactory(instrumentation.targetContext),
            ).setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 5_000, 100, 100)
                    .build(),
            ).build()
            playerCreated = true
            player.volume = 0f
            player.addListener(
                object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        tracks.groups.forEach { group ->
                            for (index in 0 until group.length) {
                                val format = group.getTrackFormat(index)
                                if (
                                    format.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES ||
                                    format.codecs != MimeTypes.APPLICATION_DVBSUBS ||
                                    format.initializationData.singleOrNull()
                                        ?.contentEquals(target.initializationData()) != true
                                ) {
                                    continue
                                }
                                if (group.isTrackSupported(index)) trackSupported.set(true)
                                if (group.isTrackSelected(index)) trackSelected.set(true)
                                if (group.isTrackSupported(index) && overrideApplied.compareAndSet(false, true)) {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                                        .build()
                                }
                            }
                        }
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        if (
                            cueGroup.cues.any { cue ->
                                cue.bitmap?.let { bitmap -> bitmap.width > 0 && bitmap.height > 0 } == true
                            }
                        ) {
                            cueDelivered.set(true)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerFailure.compareAndSet(null, error.errorCodeName)
                    }
                },
            )
            player.setMediaSource(createTvheadendLiveMediaSource(opener))
            player.prepare()
            player.play()
        }

        val deadline = SystemClock.elapsedRealtime() + CUE_WINDOW_MS
        while (
            SystemClock.elapsedRealtime() < deadline &&
            !cueDelivered.get() &&
            playerFailure.get() == null
        ) {
            delay(DVB_POLL_INTERVAL_MS)
        }
    } finally {
        if (playerCreated) instrumentation.runOnMainSync { player.release() }
    }

    val active = withTimeoutOrNull(SUBSCRIPTION_CLOSE_TIMEOUT_MS) { opener.active.await() }
    val subscriptionClosed = active != null && withTimeoutOrNull(SUBSCRIPTION_CLOSE_TIMEOUT_MS) {
        active.state.first { state -> state is SubscriptionState.Terminal }
    } != null
    return DvbAttempt(
        descriptorPresent = opener.descriptorPresent.get(),
        descriptorInRange = opener.descriptorInRange.get(),
        descriptorCorrelated = opener.descriptorCorrelated.get(),
        packetCount = opener.packetCount.get(),
        trackSupported = trackSupported.get(),
        overrideApplied = overrideApplied.get(),
        trackSelected = trackSelected.get(),
        cueDelivered = cueDelivered.get(),
        subscriptionClosed = subscriptionClosed,
        playerFailure = playerFailure.get(),
    )
}

private class DvbObservingTarget(
    private val delegate: CoordinatorLiveTarget,
    private val target: DvbTarget,
) : CoordinatorLiveTarget {
    val descriptorPresent = AtomicBoolean()
    val descriptorInRange = AtomicBoolean()
    val descriptorCorrelated = AtomicBoolean()
    val packetCount = AtomicLong()
    val active = CompletableDeferred<ActiveSubscription>()
    private val streamIndex = AtomicReference<StreamIndex?>()

    override val isCurrent: Boolean
        get() = delegate.isCurrent

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        val observingConsumer = object : SubscriptionEventConsumer {
            override fun tracksReady(tracks: SubscriptionTracks) {
                val dvbStreams = tracks.streams.filter { stream -> stream.type == SubscriptionStreamType.DVB_SUBTITLE }
                descriptorPresent.set(dvbStreams.isNotEmpty())
                descriptorInRange.set(dvbStreams.any(SubscriptionStream::isValidDvbDescriptor))
                val matching = dvbStreams.firstOrNull { stream ->
                    stream.compositionId == target.compositionId && stream.ancillaryId == target.ancillaryId
                }
                if (matching != null) {
                    streamIndex.set(matching.index)
                    descriptorCorrelated.set(true)
                }
                consumer.tracksReady(tracks)
            }

            override suspend fun accept(event: SubscriptionEvent) {
                if (event is SubscriptionEvent.Packet && event.streamIndex == streamIndex.get()) {
                    packetCount.incrementAndGet()
                }
                consumer.accept(event)
            }
        }
        val result = delegate.open(observingConsumer, options)
        if (result is SubscriptionOpenResult.Opened) active.complete(result.subscription)
        return result
    }
}

private class DvbProbePacketCounter : SubscriptionEventConsumer {
    private val lock = Any()
    private val counts = mutableMapOf<StreamIndex, Long>()

    override suspend fun accept(event: SubscriptionEvent) {
        if (event is SubscriptionEvent.Packet) {
            synchronized(lock) {
                counts[event.streamIndex] = (counts[event.streamIndex] ?: 0L) + 1L
            }
        }
    }

    fun count(streamIndex: StreamIndex): Long = synchronized(lock) { counts[streamIndex] ?: 0L }
}

private fun SubscriptionStream.isValidDvbDescriptor(): Boolean =
    type == SubscriptionStreamType.DVB_SUBTITLE && compositionId in 0L..0xffffL && ancillaryId in 0L..0xffffL

private fun DvbTarget.initializationData(): ByteArray = byteArrayOf(
    (compositionId ushr 8).toByte(),
    compositionId.toByte(),
    (ancillaryId ushr 8).toByte(),
    ancillaryId.toByte(),
)

private data class DvbTarget(
    val channelId: ChannelId,
    val compositionId: Long,
    val ancillaryId: Long,
    val probePacketCount: Long,
)

private data class DvbProbe(
    val channelsProbed: Int,
    val targets: List<DvbTarget>,
)

private data class DvbAttempt(
    val descriptorPresent: Boolean,
    val descriptorInRange: Boolean,
    val descriptorCorrelated: Boolean,
    val packetCount: Long,
    val trackSupported: Boolean,
    val overrideApplied: Boolean,
    val trackSelected: Boolean,
    val cueDelivered: Boolean,
    val subscriptionClosed: Boolean,
    val playerFailure: String?,
)

private data class DvbVerification(
    val probe: DvbProbe,
    val attempts: List<DvbAttempt>,
)

private const val PRIVATE_PROFILE_FILE_NAME = "p4-5-real-server.json"
private const val MAXIMUM_PROBE_CHANNELS = 48
private const val MAXIMUM_DVB_TARGETS = 6
private const val PROBE_OPEN_TIMEOUT_MS = 8_000L
private const val PROBE_PACKET_WINDOW_MS = 2_000L
private const val CONNECTION_TIMEOUT_MS = 45_000L
private const val CUE_WINDOW_MS = 60_000L
private const val SUBSCRIPTION_CLOSE_TIMEOUT_MS = 10_000L
private const val DVB_POLL_INTERVAL_MS = 100L
private const val LIVE_TEST_TIMEOUT_MS = 9 * 60 * 1_000L
