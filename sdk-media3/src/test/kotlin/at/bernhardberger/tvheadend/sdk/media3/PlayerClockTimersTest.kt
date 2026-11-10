@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The SDK's timers on a real ExoPlayer whose FakeClock never advances by itself: each deadline is
 * reached only when the test moves that clock, never by the system clock the SDK used to read.
 * The units' own tests drive them through fakes; only a real player shows which clock they follow.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's API 36 image needs JDK internals that a JDK 21 test JVM does not open.
@Config(sdk = [34])
class PlayerClockTimersTest {
    private val clock = FakeClock(/* initialTimeMs = */ 10_000L, /* isAutoAdvancing = */ false)
    private val player: ExoPlayer = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
        .setClock(clock)
        .build()

    @Test
    fun `growing data source entry points reject the main thread`() = released {
        val lease = object : GrowingRecordingFileLease {
            override val isCurrent: Boolean = true
            override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
                throw AssertionError("Transport must not run on the main thread")
        }
        val identity = RecordingMediaIdentity()
        val operations: List<(DataSource) -> Unit> = listOf(
            { it.open(DataSpec.Builder().setUri(identity.uri).build()); Unit },
            { it.read(ByteArray(0), 0, 0); Unit },
            { it.close() },
        )
        operations.forEach { operation ->
            val source = createTvheadendGrowingRecordingDataSourceFactory(lease, identity).createDataSource()
            assertThrows(IllegalStateException::class.java) { operation(source) }
        }
    }

    @Test
    fun `recovery entry points reject a foreign application looper without player enforcement`() = released {
        for (entry in listOf("init", "begin", "close")) {
            var wrongLooper = entry == "init"
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                Player::class.java.classLoader, arrayOf(Player::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getApplicationLooper" -> if (wrongLooper) null else android.os.Looper.myLooper()
                    "getPlayWhenReady" -> true
                    "addListener", "removeListener" -> null
                    else -> throw AssertionError("Unexpected recovery player access")
                }
            } as Player
            val create = {
                TvheadendPlaybackRecovery(proxy, PlaybackRecoveryPolicy(), {}, scheduler = RecoveryScheduler { _, _ ->
                    ScheduledRecoveryTask {}
                })
            }
            if (entry == "init") {
                assertThrows(IllegalStateException::class.java) { create() }
            } else {
                val recovery = create()
                wrongLooper = true
                try {
                    assertThrows(IllegalStateException::class.java) {
                        if (entry == "begin") recovery.beginPlaybackTarget() else recovery.close()
                    }
                } finally {
                    wrongLooper = false
                    recovery.close()
                }
            }
        }
    }

    @Test
    fun `recording resume player adapter waits for seekability applies and detaches`() = released {
        val identity = RecordingMediaIdentity()
        var seekable = false
        val listeners = mutableSetOf<Player.Listener>()
        val seeks = mutableListOf<Long>()
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getApplicationLooper" -> android.os.Looper.myLooper()
                "getCurrentMediaItem" -> tvheadendRecordingMediaItem(identity)
                "getDuration" -> 360_000L
                "isCurrentMediaItemSeekable" -> seekable
                "addListener" -> { listeners += arguments!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= arguments!![0] as Player.Listener; null }
                "seekTo" -> { seeks += arguments!![0] as Long; null }
                else -> throw AssertionError("Unexpected resume player access")
            }
        } as Player
        val resume = createTvheadendRecordingResume(proxy, identity)
        try {
            resume.beginPlaybackTarget(90.seconds)
            assertTrue(seeks.isEmpty())
            seekable = true
            listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
            assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
            assertEquals(listOf(90_000L), seeks)
            listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_READY) }
            assertEquals("An applied resume does not seek again", listOf(90_000L), seeks)
        } finally {
            resume.close()
        }
        assertTrue(listeners.isEmpty())
    }

    @Test
    fun `a standalone recovery's watchdog runs on the player's clock`() = released {
        val recoveries = mutableListOf<PlaybackRecoveryReason>()
        createTvheadendPlaybackRecovery(player, PlaybackRecoveryPolicy(1_000L, 1_000L, 1_000L)) {
            recoveries += it
        }.use { recovery ->
            recovery.beginPlaybackTarget()
            player.setMediaSource(StalledMediaSource(MediaItem.fromUri("tvheadend-test:stalled")))
            player.prepare()
            player.play()
            player.handleCommands()
            assertEquals(Player.STATE_BUFFERING, player.playbackState)

            advanceBy(900L)
            assertTrue(recoveries.isEmpty())
            advanceBy(3_000L)
            assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), recoveries)
        }
    }

    @Test
    fun `a completed recording's progress floor lapses on the player's clock`() = released {
        val identity = RecordingMediaIdentity()
        player.setMediaSource(StalledMediaSource(tvheadendRecordingMediaItem(identity)))
        createTvheadendRecordingResume(player, identity).use { resume ->
            resume.beginPlaybackTarget(90.seconds)

            // The floor is the only deadline here, so one jump to just before it orders nothing away.
            clock.advanceTime(COMPLETED_RESUME_PROGRESS_FLOOR_TIMEOUT_MILLIS - 1L)
            player.handleCommands()
            assertEquals(90.seconds, resume.progressFloor)
            advanceBy(1L)
            assertNull(resume.progressFloor)
            assertEquals(90.seconds, resume.pendingPosition)
        }
    }

    @Test
    fun `DVR progress cadence waits on the player's clock`() = released {
        val timeSource = ExoPlayerCoordinatorPlaybackAccess(player, PlaybackRecoveryPolicy()) {}.progressTimeSource
        runTest {
            val start = timeSource.now()
            val tick = async { timeSource.wait(5.seconds) }
            runCurrent()
            // The coroutine clock alone never ends the wait.
            advanceTimeBy(10.seconds)
            runCurrent()
            assertFalse(tick.isCompleted)

            advanceBy(5_000L)
            runCurrent()
            assertTrue(tick.isCompleted)
            assertEquals(5.seconds, timeSource.now() - start)

            // As with delay, a positive wait below a millisecond still waits one.
            val brief = async { timeSource.wait(500.microseconds) }
            runCurrent()
            player.handleCommands()
            runCurrent()
            assertFalse(brief.isCompleted)
            advanceBy(1L)
            runCurrent()
            assertTrue(brief.isCompleted)
        }
    }

    private fun released(block: () -> Unit) {
        try {
            block()
        } finally {
            player.release()
        }
    }

    /** Moves only the player's clock, letting the application looper run what each step makes due. */
    private fun advanceBy(millis: Long) {
        var remaining = millis
        while (remaining > 0L) {
            val step = minOf(remaining, ADVANCE_STEP_MILLIS)
            clock.advanceTime(step)
            player.handleCommands()
            remaining -= step
        }
    }
}

private const val ADVANCE_STEP_MILLIS = 100L

/** An item whose preparation never publishes a timeline, so the player stays buffering. */
private class StalledMediaSource(private val item: MediaItem) : BaseMediaSource() {
    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) = Unit

    override fun maybeThrowSourceInfoRefreshError() = Unit

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = throw UnsupportedOperationException()

    override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

    override fun releaseSourceInternal() = Unit
}
