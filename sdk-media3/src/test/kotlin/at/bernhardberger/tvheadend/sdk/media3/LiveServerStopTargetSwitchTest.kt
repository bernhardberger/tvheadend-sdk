@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import at.bernhardberger.tvheadend.sdk.core.DvrProgressPolicy
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Drives the real coordinator, Media3 coordinator player and live sources; only ExoPlayer's
 * playlist handling is replaced by [RealLiveSourceAccess].
 */
internal class LiveServerStopTargetSwitchTest {
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `switching away from a stopped target does not carry the server stop to the next target`() = runTest(
        timeout = 30.seconds,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val access = RealLiveSourceAccess(dispatcher)
        val events = PlaybackPlayerEventAccumulator()
        val coordinator = TvheadendPlaybackCoordinator(
            player = Media3PlaybackCoordinatorPlayer(access, events) { _, _ ->
                RecordingAdmission.Completed(Duration.ZERO)
            },
            playerEvents = events,
            progressPolicy = DvrProgressPolicy(),
            onRecoveryRequired = {},
            timeSource = SystemPlaybackCoordinatorTimeSource,
        )
        val owner = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.run() }
        val channelA = ScriptedLiveChannel(this)
        val channelB = ScriptedLiveChannel(this)
        fun observation() = coordinator.livePlaybackObservation.value as LivePlaybackObservation.Active
        fun flush() = repeat(3) {
            runCurrent()
            access.looperQueue.runAll()
        }
        // Every step is driven explicitly; a step that cannot finish fails instead of parking the test.
        fun settle(step: String, vararg jobs: Job) {
            repeat(SETTLE_ROUNDS) {
                if (jobs.all(Job::isCompleted)) return
                flush()
            }
            fail<Unit>("$step did not complete")
        }
        fun <T> Deferred<T>.settled(step: String): T {
            settle(step, this)
            return getCompleted()
        }

        try {
            val installA = async { coordinator.setLiveTarget(channelA, LivePlaybackOptions(timeshiftPeriod = 120.seconds)) }
            assertEquals(PlaybackTargetResult.STARTED, installA.settled("install A"))
            access.createPeriod()
            settle("A plays", channelA.started())
            assertTrue(observation().timeshiftState is LiveTimeshiftState.Available) { observation().toString() }
            settle("A stops", channelA.emit(SubscriptionEvent.Stopped(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.BAD_SIGNAL)))
            assertTrue(observation().serverStopped)

            val installB = async { coordinator.setLiveTarget(channelB, LivePlaybackOptions(timeshiftPeriod = 120.seconds)) }
            assertEquals(PlaybackTargetResult.STARTED, installB.settled("install B"))
            assertFalse(observation().serverStopped)
            assertEquals(null, observation().subscriptionIssue)

            // The retired channel's status and stop must not reach the new target.
            settle(
                "late A events",
                channelA.emit(SubscriptionEvent.Status(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.NO_FREE_ADAPTER)),
                channelA.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL)),
            )
            assertFalse(observation().serverStopped)

            access.createPeriod()
            settle("B plays", channelB.started())
            assertFalse(observation().serverStopped)
            assertEquals(null, observation().subscriptionIssue)
            assertTrue(observation().timeshiftState is LiveTimeshiftState.Available) { observation().toString() }

            settle("B stops", channelB.emit(SubscriptionEvent.Stopped(SubscriptionCondition.NO_DETAIL)))
            assertTrue(observation().serverStopped)

            val shutdown = async { coordinator.shutdown(1.seconds) }
            settle("shutdown", shutdown, owner)
        } finally {
            owner.cancel()
            access.release()
            val closing = listOf(channelA, channelB).map { channel -> launch { channel.close() } }
            repeat(SETTLE_ROUNDS) { flush() }
            closing.forEach(Job::cancel)
        }
    }

    private companion object {
        const val SETTLE_ROUNDS = 20
    }
}

private class ScriptedLiveChannel(private val scope: TestScope) : CoordinatorLiveTarget {
    private val connection = ScriptedSubscriptionConnection()
    private val manager = createSubscriptionManager(connection, StandardTestDispatcher(scope.testScheduler))

    init {
        connection.scriptSubscribe(SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, 120)))
        manager.startAdmission()
    }

    override val isCurrent: Boolean = true

    override suspend fun open(consumer: SubscriptionEventConsumer, options: SubscriptionOptions): SubscriptionOpenResult =
        manager.open(SubscriptionChannelId(1), consumer, options)

    fun started(): Job = emit(
        SubscriptionEvent.Started(
            listOf(
                SubscriptionStream(StreamIndex(0), SubscriptionStreamType.MPEG2_AUDIO, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null),
            ),
            null,
            SubscriptionCondition.NO_DETAIL,
        ),
    )

    /** Emits in order on the test scheduler; the caller settles the returned job. */
    fun emit(event: SubscriptionEvent): Job = scope.launch { connection.emit(event) }

    suspend fun close() = manager.closeAndJoin()
}

/** Plays the installed live source like ExoPlayer's playlist: one prepared source and period. */
private class RealLiveSourceAccess(private val dispatcher: CoroutineDispatcher) : CoordinatorPlaybackAccess {
    val looperQueue = QueuedCoordinatorLooper()
    override val looper: CoordinatorLooper = looperQueue
    private val allocator = DefaultAllocator(false, 1_024)
    private var installed: LiveSource? = null
    private var prepared: LiveSource? = null
    private var period: MediaPeriod? = null
    private var timeline: Timeline? = null
    private val caller = MediaSource.MediaSourceCaller { _, refreshed -> timeline = refreshed }

    override fun requireApplicationLooper() {
        check(looperQueue.isCurrent())
    }

    override fun snapshot(): PlaybackPlayerSnapshot =
        PlaybackPlayerSnapshot(Duration.ZERO, null, Player.STATE_READY, failed = false)

    override fun addListener(listener: Player.Listener) = Unit

    override fun removeListener(listener: Player.Listener) = Unit

    override fun createLiveSource(
        target: CoordinatorLiveTarget,
        options: SubscriptionOptions,
        timeshiftControls: LiveTimeshiftControlBridge,
    ): CoordinatorMediaSource = LiveSource(
        TvheadendLiveMediaSource(target, options, timeshiftControls, {}, dispatcher, { looperQueue }),
    )

    override fun createRecordingSource(
        target: CoordinatorRecordingTarget,
        identity: RecordingMediaIdentity,
    ): CoordinatorMediaSource = error("recordings are not played here")

    override fun createGrowingRecordingSource(
        lease: GrowingRecordingFileLease,
        identity: RecordingMediaIdentity,
        onFinalEnd: () -> Unit,
    ): CoordinatorMediaSource = error("recordings are not played here")

    override fun createRecovery(
        onRecoveryRequired: (PlaybackRecoveryReason) -> Unit,
    ): CoordinatorPlaybackRecovery = object : CoordinatorPlaybackRecovery {
        override fun beginPlaybackTarget() = Unit

        override fun close() = Unit
    }

    override fun createResume(identity: RecordingMediaIdentity): CoordinatorRecordingResume =
        error("recordings are not played here")

    override fun setMediaSource(source: CoordinatorMediaSource, startPosition: Duration?) {
        release()
        installed = source as LiveSource
    }

    override fun prepare() {
        val source = checkNotNull(installed)
        source.media.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        prepared = source
    }

    override fun stop() = release()

    override fun clearMediaItems() {
        release()
        installed = null
    }

    fun createPeriod() {
        val source = checkNotNull(prepared).media
        val created = source.createPeriod(
            MediaSource.MediaPeriodId(checkNotNull(timeline).getUidOfPeriod(0)),
            allocator,
            0,
        )
        created.prepare(object : MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: MediaPeriod) = Unit

            override fun onContinueLoadingRequested(source: MediaPeriod) = Unit
        }, 0)
        period = created
    }

    fun release() {
        val source = prepared ?: return
        period?.let(source.media::releasePeriod)
        period = null
        source.media.releaseSource(caller)
        prepared = null
        timeline = null
    }

    private class LiveSource(val media: TvheadendLiveMediaSource) : CoordinatorMediaSource
}
