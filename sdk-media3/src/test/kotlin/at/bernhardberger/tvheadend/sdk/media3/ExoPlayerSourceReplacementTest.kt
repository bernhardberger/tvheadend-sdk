@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import android.os.Looper
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.FakeTvheadendSession
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the coordinator against a real ExoPlayer on its FakeClock, where the application and
 * playback loopers interleave exactly as Media3 orders them.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's API 36 image needs JDK internals that a JDK 21 test JVM does not open.
@Config(sdk = [34])
class ExoPlayerSourceReplacementTest {
    @Test
    fun `replacing a source whose failure has not reached the application prepares the replacement without that failure`() =
        withPlayer { harness ->
            // An open that is not Opened fails the retired source's preparation.
            harness.install(TestCoordinatorLiveTarget())
            harness.awaitHeldFailure()
            val errors = harness.recordErrors()
            val replacementOpened = CompletableDeferred<Unit>()
            harness.install(openingTarget(replacementOpened))
            harness.resume.countDown()
            harness.settle { replacementOpened.isCompleted }

            assertNull(harness.exoPlayer.playerError)
            assertTrue(errors.isEmpty())
            assertTrue(replacementOpened.isCompleted)
            assertEquals(Player.STATE_BUFFERING, harness.exoPlayer.playbackState)
        }

    @Test
    fun `replacing a healthy source never stops the player`() = withPlayer(holdFailure = false) { harness ->
        harness.install(TestCoordinatorLiveTarget { _, _ -> awaitCancellation() })
        TestPlayerRunHelper.advance(harness.exoPlayer).untilPendingCommandsAreFullyHandled()
        val states = mutableListOf<Int>()
        harness.exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                states += playbackState
            }
        })
        val replacementOpened = CompletableDeferred<Unit>()
        harness.install(openingTarget(replacementOpened))
        harness.settle { replacementOpened.isCompleted }

        // A stop would release the application's audio focus and report IDLE on every switch.
        assertFalse(Player.STATE_IDLE in states)
        assertTrue(replacementOpened.isCompleted)
    }

    @Test
    fun `an application taking over during the stop for a retired failure keeps its item unprepared`() =
        withPlayer { harness ->
            harness.install(TestCoordinatorLiveTarget())
            harness.awaitHeldFailure()
            val applicationItem = MediaItem.fromUri("tvheadend-test:application")
            harness.exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_IDLE) harness.exoPlayer.setMediaItem(applicationItem)
                }
            })
            val replacementOpened = CompletableDeferred<Unit>()
            harness.install(openingTarget(replacementOpened), PlaybackPlayerInstallStatus.DEPARTED)
            harness.resume.countDown()
            TestPlayerRunHelper.advance(harness.exoPlayer).untilPendingCommandsAreFullyHandled()

            assertSame(applicationItem, harness.exoPlayer.currentMediaItem)
            assertEquals(Player.STATE_IDLE, harness.exoPlayer.playbackState)
            assertFalse(replacementOpened.isCompleted)
        }

    @Test
    fun `an application moving to its own item during the stop for a retired failure keeps it`() =
        withPlayer { harness ->
            harness.install(TestCoordinatorLiveTarget())
            val applicationItem = MediaItem.fromUri("tvheadend-test:application")
            harness.exoPlayer.addMediaItem(applicationItem)
            harness.awaitHeldFailure()
            harness.exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // A seek keeps the timeline, so only the current item reveals the departure.
                    if (playbackState == Player.STATE_IDLE) harness.exoPlayer.seekTo(1, 0)
                }
            })
            val replacementOpened = CompletableDeferred<Unit>()
            harness.install(openingTarget(replacementOpened), PlaybackPlayerInstallStatus.DEPARTED)
            harness.resume.countDown()
            TestPlayerRunHelper.advance(harness.exoPlayer).untilPendingCommandsAreFullyHandled()

            assertSame(applicationItem, harness.exoPlayer.currentMediaItem)
            assertEquals(1, harness.exoPlayer.mediaItemCount)
            assertFalse(replacementOpened.isCompleted)
        }

    @Test
    fun `an application queueing its own item during the stop for a retired failure keeps its position`() =
        withPlayer { harness ->
            harness.install(TestCoordinatorLiveTarget())
            harness.awaitHeldFailure()
            val failedItem = harness.exoPlayer.currentMediaItem
            val applicationItem = MediaItem.fromUri("tvheadend-test:application")
            harness.exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_IDLE) harness.exoPlayer.addMediaItem(applicationItem)
                }
            })
            val replacementOpened = CompletableDeferred<Unit>()
            harness.install(openingTarget(replacementOpened), PlaybackPlayerInstallStatus.DEPARTED)
            harness.resume.countDown()
            TestPlayerRunHelper.advance(harness.exoPlayer).untilPendingCommandsAreFullyHandled()

            assertSame(failedItem, harness.exoPlayer.currentMediaItem)
            assertEquals(listOf(failedItem, applicationItem), List(harness.exoPlayer.mediaItemCount) {
                harness.exoPlayer.getMediaItemAt(it)
            })
            assertFalse(replacementOpened.isCompleted)
        }

    @Test
    fun `a failure surfacing after the replacement began still ends in the replacement's preparation`() =
        withPlayer { harness ->
            // The coordinator cannot see this stop before installing: the failing item is the
            // application's own, as when a failure lands after the installation's check.
            harness.exoPlayer.setMediaSource(FailingMediaSource())
            harness.exoPlayer.prepare()
            harness.awaitHeldFailure()
            val errors = harness.recordErrors()
            val errorStillSetAfterCycle = mutableListOf<Boolean>()
            harness.exoPlayer.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_PLAYER_ERROR)) errorStillSetAfterCycle += player.playerError != null
                }
            })
            val replacementOpened = CompletableDeferred<Unit>()
            harness.install(openingTarget(replacementOpened))
            harness.resume.countDown()
            harness.settle { replacementOpened.isCompleted }

            assertEquals(1, errors.size)
            // The documented application check: the error is already cleared once its cycle ends.
            assertEquals(listOf(false), errorStillSetAfterCycle)
            assertTrue(replacementOpened.isCompleted)
            assertNull(harness.exoPlayer.playerError)
            assertEquals(Player.STATE_BUFFERING, harness.exoPlayer.playbackState)
        }

    @Test
    fun `a live target is timeshift-ready only after its open has been consumed`() = withDeterministicLive { live ->
        val installed = async { live.setTarget() }
        runCurrent()
        assertTrue((installed.await() as LivePlaybackTargetResult.Bound).result.isStarted)
        // The playback thread prepares the source, which queues its open here and nowhere else:
        // however long real threads run, the server has not been asked yet.
        live.exoPlayer.handleCommands()
        assertEquals(0, live.connection.subscribeCount)
        assertSame(LiveTimeshiftState.Unavailable, live.coordinator.timeshiftState.value)

        runCurrent()
        live.startStream()
        live.settle()

        val ready = live.coordinator.timeshiftState.value as? LiveTimeshiftState.Available
        assertEquals(120.seconds, ready?.grantedPeriod)
    }

    @Test
    fun `a stalled live target's recovery watchdog runs on the player's clock`() =
        withDeterministicLive(PlaybackRecoveryPolicy(1_000L, 1_000L, 1_000L)) { live ->
            live.setTarget()
            live.settle()
            live.startStream()
            live.exoPlayer.play()
            live.settle()
            assertEquals(Player.STATE_BUFFERING, live.exoPlayer.playbackState)

            live.advanceBy(900.milliseconds)
            assertTrue(live.recoveries.isEmpty())
            // No packet ever arrives, so whichever budget applies runs out on the player's clock.
            live.advanceBy(3.seconds)
            assertEquals(listOf(PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED), live.recoveries)
        }

    /**
     * Runs [block] against the real coordinator, subscription manager and live source, with all SDK
     * work on the test scheduler and the player on a non-advancing clock, and releases them after.
     */
    private fun withDeterministicLive(
        recoveryPolicy: PlaybackRecoveryPolicy = PlaybackRecoveryPolicy(),
        block: suspend TestScope.(DeterministicLive) -> Unit,
    ) = runTest {
        val live = DeterministicLive(this, recoveryPolicy)
        try {
            block(live)
        } finally {
            live.close()
        }
    }

    private fun withPlayer(holdFailure: Boolean = true, block: (Harness) -> Unit) {
        val harness = Harness(holdFailure)
        try {
            block(harness)
        } finally {
            harness.resume.countDown()
            runBlocking { harness.coordinator.abandon() }
            harness.exoPlayer.release()
        }
    }

    private fun openingTarget(opened: CompletableDeferred<Unit>) = TestCoordinatorLiveTarget { _, _ ->
        opened.complete(Unit)
        awaitCancellation()
    }

    /**
     * A coordinator on a real ExoPlayer whose playback thread, once it stops for a failure, holds
     * there before posting that failure to the application until [resume] opens.
     */
    private class Harness(holdFailure: Boolean) {
        private val stopped = CountDownLatch(1)
        val resume = CountDownLatch(if (holdFailure) 1 else 0)
        val exoPlayer: ExoPlayer = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
            .setLoadControl(StopHoldingLoadControl(stopped, resume))
            .build()
        val coordinator = Media3PlaybackCoordinatorPlayer(
            ExoPlayerCoordinatorPlaybackAccess(exoPlayer, PlaybackRecoveryPolicy()) {},
            PlaybackPlayerEventAccumulator(),
        ) { _, _ -> RecordingAdmission.Completed(null) }

        /** The failure is held on the playback thread, so the application still reads BUFFERING. */
        fun awaitHeldFailure() {
            runMainLooperUntil { stopped.count == 0L }
            assertEquals(Player.STATE_BUFFERING, exoPlayer.playbackState)
        }

        fun settle(condition: () -> Boolean) {
            runMainLooperUntil { condition() || exoPlayer.playerError != null }
            TestPlayerRunHelper.advance(exoPlayer).untilPendingCommandsAreFullyHandled()
        }

        fun recordErrors(): List<PlaybackException> {
            val errors = mutableListOf<PlaybackException>()
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errors += error
                }
            })
            return errors
        }

        fun install(
            target: TestCoordinatorLiveTarget,
            expected: PlaybackPlayerInstallStatus = PlaybackPlayerInstallStatus.STARTED,
        ) {
            val token = PlaybackTargetToken()
            val result = runBlocking {
                // The test thread is the application looper, so the coordinator installs inline.
                coordinator.installLive(
                    PlayerOperationTicket(),
                    token,
                    target,
                    timeshiftControls = LiveTimeshiftControlBridge(token) {},
                )
            }
            assertEquals(expected, result.status)
        }
    }
}

/**
 * Lets the playback thread handle every queued command and the application looper run what is due,
 * without advancing either clock. The wait is Media3's own acknowledgement, not an SDK condition.
 */
internal fun ExoPlayer.handleCommands() {
    TestPlayerRunHelper.advance(this).untilPendingCommandsAreFullyHandled()
    shadowOf(Looper.getMainLooper()).idle()
}

/** A consumer's SDK side on one test scheduler and the player's clock, as the consumer guide shows. */
private class DeterministicLive(private val test: TestScope, recoveryPolicy: PlaybackRecoveryPolicy) {
    private val work = StandardTestDispatcher(test.testScheduler)
    private val clock = FakeClock(/* isAutoAdvancing = */ false)
    val exoPlayer: ExoPlayer = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
        .setClock(clock)
        .build()
    val connection = ScriptedSubscriptionConnection().apply {
        scriptSubscribe(SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, 120)))
    }
    private val manager = createSubscriptionManager(connection, work).apply { startAdmission() }
    private val session = FakeTvheadendSession(currentLiveObservation()).apply { scriptLivePlaybackSuccess(manager) }
    val recoveries = mutableListOf<PlaybackRecoveryReason>()
    val coordinator = createTvheadendPlaybackCoordinator(
        exoPlayer,
        recoveryPolicy = recoveryPolicy,
        onRecoveryRequired = { recoveries += it },
        workDispatcher = work,
    ).also { it.launchIn(test.backgroundScope) }

    suspend fun setTarget(): LivePlaybackTargetResult = coordinator.setLiveTarget(
        session,
        session.captureCurrentSession(),
        ChannelId(1),
        LivePlaybackOptions(timeshiftPeriod = 120.seconds),
    )

    suspend fun startStream() {
        connection.emit(SubscriptionEvent.Started(listOf(audioStream()), null))
        connection.emit(SubscriptionEvent.Timeshift(0, 0, 0, 120_000_000, 100))
    }

    /** Runs the due SDK work and player commands in turn until both are quiet at the current time. */
    fun settle() = repeat(SETTLE_ROUNDS) {
        test.runCurrent()
        exoPlayer.handleCommands()
    }

    /** Advances both clocks together in steps shorter than any SDK deadline, settling after each. */
    fun advanceBy(duration: Duration) {
        var remaining = duration
        while (remaining > Duration.ZERO) {
            val step = minOf(remaining, ADVANCE_STEP)
            clock.advanceTime(step.inWholeMilliseconds)
            test.advanceTimeBy(step)
            settle()
            remaining -= step
        }
    }

    suspend fun close() {
        try {
            coordinator.shutdown(1.seconds)
            manager.closeAndJoin()
        } finally {
            exoPlayer.release()
        }
    }
}

private const val SETTLE_ROUNDS = 8
private val ADVANCE_STEP = 100.milliseconds

private fun audioStream() = SubscriptionStream(
    StreamIndex(0), SubscriptionStreamType.MPEG2_AUDIO, null, null, null, null, null,
    null, null, null, null, null, null, null, null,
)

/** Signals on the playback thread when the player first stops, then holds it until [resume] opens. */
private class StopHoldingLoadControl(
    private val stopped: CountDownLatch,
    private val resume: CountDownLatch,
) : DefaultLoadControl() {
    override fun onStopped(playerId: PlayerId) {
        stopped.countDown()
        resume.await()
        super.onStopped(playerId)
    }
}

/** An application item whose preparation fails before it publishes a timeline. */
private class FailingMediaSource : BaseMediaSource() {
    private val item = MediaItem.fromUri("tvheadend-test:failing")

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) = Unit

    override fun maybeThrowSourceInfoRefreshError() {
        throw IOException("Application source failed")
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = throw UnsupportedOperationException()

    override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

    override fun releaseSourceInternal() = Unit
}
