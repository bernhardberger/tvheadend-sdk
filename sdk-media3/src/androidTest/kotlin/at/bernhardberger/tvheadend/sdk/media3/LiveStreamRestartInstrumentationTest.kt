@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import at.bernhardberger.tvheadend.sdk.testing.SubscriptionBinaryFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline real-Player coverage; the application owns and releases the sole Player instance. */
@RunWith(AndroidJUnit4::class)
internal class LiveStreamRestartInstrumentationTest {
    @Test
    fun playingPlayerReselectsUnchangedAndChangedRestartTracks() = verifyRestart(playing = true)

    @Test
    fun pausedPlayerReselectsUnchangedAndChangedRestartTracksWithoutAutoplay() = verifyRestart(playing = false)

    private fun verifyRestart(playing: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Load existing Android capture assets before allocating any subscription owner.
        val mpeg = instrumentation.context.assets.open("recorded-mux/channel-004-stream-02-packet-001.bin")
            .use { it.readBytes() }
        val ac3 = instrumentation.context.assets.open("recorded-mux/channel-004-stream-03-packet-001.bin")
            .use { it.readBytes() }
        val connection = ScriptedSubscriptionConnection()
        val manager = createSubscriptionManager(connection, Dispatchers.Default).apply { startAdmission() }
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext,
                    createTvheadendRenderersFactory(instrumentation.targetContext))
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(100, 2_000, 50, 50).build())
                    .build().also {
                        it.volume = 0f
                        it.playWhenReady = playing
                        it.setMediaSource(createTvheadendLiveMediaSource(
                            FixedSubscriptionLiveTarget(manager, SubscriptionChannelId(1))))
                        it.prepare()
                    }
            }
            withTimeout(10_000L) { connection.awaitCollectionRegistered() }
            var previousUid: Any? = null
            repeat(3) { segment ->
                if (segment > 0) connection.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL))
                val changed = segment == 2
                val count = if (changed) 2 else 1
                val type = if (changed) SubscriptionStreamType.AC3 else SubscriptionStreamType.MPEG2_AUDIO
                // Exact packet durations recorded for these assets in recorded-mux/manifest.json.
                val durationUs = if (changed) 32_000L else 24_000L
                connection.emit(SubscriptionEvent.Started((0 until count).map { index ->
                    SubscriptionStream(StreamIndex(index.toLong()), type, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null)
                }, null, SubscriptionCondition.NO_DETAIL))
                repeat(128) { sample ->
                    repeat(count) { index ->
                        val time = 9_000_000_000L + segment * 10_000_000L + sample * durationUs
                        connection.emit(SubscriptionEvent.Packet(MuxFrameType.UNKNOWN, StreamIndex(index.toLong()),
                            time, time, durationUs,
                            SubscriptionBinaryFixture(if (changed) ac3 else mpeg)))
                    }
                }
                val expectedMime = if (changed) MimeTypes.AUDIO_AC3 else MimeTypes.AUDIO_MPEG_L2
                withTimeout(15_000L) {
                    while (true) {
                        var ready = false
                        instrumentation.runOnMainSync {
                            val current = checkNotNull(player)
                            assertTrue("Restart must not produce a Player error", current.playerError == null)
                            val timeline = current.currentTimeline
                            val uid = if (timeline.isEmpty) null else timeline.getUidOfPeriod(current.currentPeriodIndex)
                            ready = uid != null && uid != previousUid && current.playbackState == Player.STATE_READY &&
                                current.currentTracks.groups.size == count && current.currentTracks.groups.all {
                                    it.getTrackFormat(0).sampleMimeType == expectedMime
                                } && current.currentTracks.groups.any { it.isSelected }
                        }
                        if (ready) break
                        delay(20)
                    }
                }
                var position = 0L
                instrumentation.runOnMainSync {
                    val current = checkNotNull(player)
                    previousUid = current.currentTimeline.getUidOfPeriod(current.currentPeriodIndex)
                    assertEquals(playing, current.playWhenReady)
                    assertEquals(playing, current.isPlaying)
                    assertTrue("Restart period uses a near-zero origin", current.currentPosition < 10_000)
                    assertFalse(current.playbackState == Player.STATE_ENDED)
                    position = current.currentPosition
                }
                delay(200)
                instrumentation.runOnMainSync {
                    val current = checkNotNull(player)
                    if (playing) assertTrue("Intended playback resumes", current.currentPosition > position)
                    else assertEquals("Paused restart must not autoplay", position, current.currentPosition)
                }
                assertEquals(1, connection.subscribeCount)
                assertEquals(0, connection.unsubscribeCount)
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            withTimeout(10_000L) { manager.closeAndJoin() }
        }
        assertEquals(1, connection.unsubscribeCount)
    }
}
