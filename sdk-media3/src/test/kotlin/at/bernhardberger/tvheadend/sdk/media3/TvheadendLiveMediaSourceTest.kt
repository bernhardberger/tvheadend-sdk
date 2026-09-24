@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.analytics.PlayerId
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionPriority
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionState
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTerminalReason
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.createSubscriptionManager
import at.bernhardberger.tvheadend.sdk.testing.ScriptedSubscriptionConnection
import at.bernhardberger.tvheadend.sdk.testing.SubscriptionBinaryFixture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendLiveMediaSourceTest {
    @Test
    fun `packet arrival after accepted seek cannot restore a mapping before usable queued media`() = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val attachment = bridge.newAttachment()
        val period = TvheadendLiveMediaPeriod(
            DefaultAllocator(false, 1_024), attachment, {},
        )
        val connection = ScriptedSubscriptionConnection()
        connection.scriptSubscribe(SubscriptionOperationResult.Ok(SubscriptionConfirmation(null, null, null, 120)))
        val manager = createSubscriptionManager(connection, StandardTestDispatcher(testScheduler))
        manager.startAdmission()
        val opening = async { manager.open(SubscriptionChannelId(1), period, 120.seconds) }
        runCurrent()
        val stream = SubscriptionStream(
            StreamIndex(0), SubscriptionStreamType.MPEG2_AUDIO, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
        )
        connection.emit(SubscriptionEvent.Started(listOf(stream), null, SubscriptionCondition.NO_DETAIL))
        runCurrent()
        val subscription = (opening.await() as SubscriptionOpenResult.Opened).subscription
        attachment.bind(subscription)
        suspend fun packet(time: Long) {
            connection.emit(
                SubscriptionEvent.Packet(
                    frameType = at.bernhardberger.tvheadend.sdk.playback.MuxFrameType.UNKNOWN,
                    streamIndex = StreamIndex(0), decodingTimeUs = time, presentationTimeUs = time,
                    durationUs = 1_000_000, payload = SubscriptionBinaryFixture(byteArrayOf()),
                ),
            )
            runCurrent()
        }
        packet(10_000_000)
        packet(20_000_000)
        val seeking = async { subscription.seek(SubscriptionSeekTarget.Absolute(5.seconds)) }
        runCurrent()
        connection.emit(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 5_000_000, null))
        runCurrent()
        seeking.await()
        packet(5_000_000)
        packet(14_000_000)
        assertSame(TimeshiftPlaybackPosition.Unavailable, bridge.playbackPosition(attachment, 5.seconds))
        assertSame(TimeshiftPlaybackPosition.Unavailable, bridge.playbackPosition(attachment, 15.seconds))
        assertEquals(androidx.media3.common.C.TIME_UNSET, period.readDiscontinuity())
        period.release()
        subscription.close()
        manager.closeAndJoin()
    }

    @Test
    fun `explicit source options reach the subscription opener owned by the source`() = runTest {
        val target = CapturingLiveTarget()
        val options = SubscriptionOptions(
            streamProfileUuid = "0123456789abcdef0123456789abcdef",
            timeshiftPeriod = 600.seconds,
        )
        val source = TvheadendLiveMediaSource(target, options, null, {}, StandardTestDispatcher(testScheduler), { QueuedCoordinatorLooper() })
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertTrue(target.consumer != null)
        assertSame(options, target.options)
        source.releaseSource(caller)
        runCurrent()
    }

    @Test
    fun `default source options reach the bound target`() = runTest {
        val target = CapturingLiveTarget()
        val source = TvheadendLiveMediaSource(target, SubscriptionOptions(), null, {}, StandardTestDispatcher(testScheduler), { QueuedCoordinatorLooper() })
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertTrue(target.consumer != null)
        assertNull(target.options?.streamProfileUuid)
        assertEquals(Duration.ZERO, target.options?.timeshiftPeriod)
        source.releaseSource(caller)
        runCurrent()
    }

    @Test
    fun `re-preparation re-subscribes with the sticky priority and corrects changes during opening`() = runTest {
        val target = CapturingLiveTarget()
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val options = SubscriptionOptions(
            streamProfileUuid = "0123456789abcdef0123456789abcdef",
            timeshiftPeriod = 600.seconds,
        )
        val source = TvheadendLiveMediaSource(target, options, bridge, {}, StandardTestDispatcher(testScheduler), { QueuedCoordinatorLooper() })
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertSame(options, target.options)
        source.releaseSource(caller)
        runCurrent()

        assertTrue(bridge.setPriority(LiveSubscriptionPriority.YIELD) is SubscriptionOperationResult.Ok)
        val opened = FakeTimeshiftSubscription(null)
        val release = CompletableDeferred<Unit>()
        target.openResult = {
            release.await()
            SubscriptionOpenResult.Opened(opened)
        }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertSame(LiveSubscriptionPriority.YIELD, target.options?.priority)
        assertEquals(options.streamProfileUuid, target.options?.streamProfileUuid)
        assertEquals(600.seconds, target.options?.timeshiftPeriod)

        assertTrue(bridge.setPriority(LiveSubscriptionPriority.NORMAL) is SubscriptionOperationResult.Ok)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(LiveSubscriptionPriority.NORMAL), opened.priorities)
        source.releaseSource(caller)
        runCurrent()
        assertEquals(1, opened.closeCount)
    }

    @Test
    fun `failed opening correction leaves the priority unknown so the same priority is re-sent`() = runTest {
        val target = CapturingLiveTarget()
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val source = TvheadendLiveMediaSource(target, SubscriptionOptions(), bridge, {}, StandardTestDispatcher(testScheduler), { QueuedCoordinatorLooper() })
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        val opened = FakeTimeshiftSubscription(null)
        opened.priorityAction = { SubscriptionOperationResult.Timeout }
        val release = CompletableDeferred<Unit>()
        target.openResult = {
            release.await()
            SubscriptionOpenResult.Opened(opened)
        }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertSame(LiveSubscriptionPriority.NORMAL, target.options?.priority)

        assertTrue(bridge.setPriority(LiveSubscriptionPriority.YIELD) is SubscriptionOperationResult.Ok)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(LiveSubscriptionPriority.YIELD), opened.priorities)

        opened.priorityAction = { SubscriptionOperationResult.Ok(Unit) }
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.YIELD) is SubscriptionOperationResult.Ok)
        assertEquals(List(2) { LiveSubscriptionPriority.YIELD }, opened.priorities)
        source.releaseSource(caller)
        runCurrent()
    }

    @Test
    fun `released source subscription no longer receives recorded priorities`() = runTest {
        val target = CapturingLiveTarget()
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val source = TvheadendLiveMediaSource(target, SubscriptionOptions(), bridge, {}, StandardTestDispatcher(testScheduler), { QueuedCoordinatorLooper() })
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        val opened = FakeTimeshiftSubscription(null)
        target.openResult = { SubscriptionOpenResult.Opened(opened) }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.YIELD) is SubscriptionOperationResult.Ok)
        assertEquals(listOf(LiveSubscriptionPriority.YIELD), opened.priorities)

        source.releaseSource(caller)
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.NORMAL) is SubscriptionOperationResult.Ok)
        runCurrent()
        assertEquals(listOf(LiveSubscriptionPriority.YIELD), opened.priorities)
        assertEquals(1, opened.closeCount)
        val reopened = FakeTimeshiftSubscription(null)
        target.openResult = { SubscriptionOpenResult.Opened(reopened) }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertSame(LiveSubscriptionPriority.NORMAL, target.options?.priority)
        assertTrue(reopened.priorities.isEmpty())
        source.releaseSource(caller)
        runCurrent()
    }

    @Test
    fun `held stop reason survives release but not a new preparation`() = runTest {
        val target = CapturingLiveTarget()
        var issue: SubscriptionIssue? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishObservation = { observation -> issue = observation.subscriptionIssue },
        )
        val source = TvheadendLiveMediaSource(target, SubscriptionOptions(), bridge, {}, StandardTestDispatcher(testScheduler), { QueuedCoordinatorLooper() })
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        target.openResult = { SubscriptionOpenResult.Opened(FakeTimeshiftSubscription(null)) }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        checkNotNull(target.consumer).accept(
            SubscriptionEvent.Stopped(SubscriptionCondition.ERROR_REPORTED, SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN),
        )
        assertSame(SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN, issue)

        source.releaseSource(caller)
        runCurrent()
        assertSame(SubscriptionIssue.SUBSCRIPTION_OVERRIDDEN, issue)

        target.openResult = { SubscriptionOpenResult.NotReady }
        source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
        runCurrent()
        assertNull(issue)
        source.releaseSource(caller)
        runCurrent()
    }

    @Test
    fun `inconclusive priority results never suppress a later change`() = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val subscription = FakeTimeshiftSubscription(null)
        bridge.subscriptionOpened(subscription, LiveSubscriptionPriority.NORMAL)
        assertTrue(subscription.priorities.isEmpty())

        // TVHeadend applied YIELD but its reply was lost.
        subscription.priorityAction = { SubscriptionOperationResult.Timeout }
        assertSame(SubscriptionOperationResult.Timeout, bridge.setPriority(LiveSubscriptionPriority.YIELD))
        subscription.priorityAction = { SubscriptionOperationResult.TransportUnavailable }
        assertSame(SubscriptionOperationResult.TransportUnavailable, bridge.setPriority(LiveSubscriptionPriority.NORMAL))
        subscription.priorityAction = { SubscriptionOperationResult.Ok(Unit) }
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.NORMAL) is SubscriptionOperationResult.Ok)
        assertEquals(
            listOf(LiveSubscriptionPriority.YIELD, LiveSubscriptionPriority.NORMAL, LiveSubscriptionPriority.NORMAL),
            subscription.priorities,
        )

        // Unsupported changes send nothing, so the server keeps the confirmed weight.
        subscription.priorityAction = { SubscriptionOperationResult.NotSupported }
        assertSame(SubscriptionOperationResult.NotSupported, bridge.setPriority(LiveSubscriptionPriority.YIELD))
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.NORMAL) is SubscriptionOperationResult.Ok)
        assertEquals(4, subscription.priorities.size)

        subscription.priorityAction = {
            subscription.mutableState.value = SubscriptionState.Terminal(SubscriptionTerminalReason.ConsumerFailed)
            SubscriptionOperationResult.TransportUnavailable
        }
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.YIELD) is SubscriptionOperationResult.Ok)
        assertSame(LiveSubscriptionPriority.YIELD, bridge.subscriptionOptions(SubscriptionOptions()).priority)
    }

    @Test
    fun `superseded opener cannot replace the registered subscription`() = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val current = FakeTimeshiftSubscription(null)
        bridge.subscriptionOpened(current, LiveSubscriptionPriority.NORMAL)
        val stale = FakeTimeshiftSubscription(null)
        bridge.subscriptionOpened(stale, LiveSubscriptionPriority.NORMAL) { false }
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.YIELD) is SubscriptionOperationResult.Ok)
        assertEquals(listOf(LiveSubscriptionPriority.YIELD), current.priorities)
        assertTrue(stale.priorities.isEmpty())

        // A release between the opener's check and its registration still unregisters it.
        var checks = 0
        val racing = FakeTimeshiftSubscription(null)
        bridge.subscriptionOpened(racing, LiveSubscriptionPriority.NORMAL) { checks++ == 0 }
        assertTrue(bridge.setPriority(LiveSubscriptionPriority.NORMAL) is SubscriptionOperationResult.Ok)
        assertTrue(racing.priorities.isEmpty())
    }

    @Test
    fun `terminal delivery clears diagnostics when media adapters are not initialized`() = runTest {
        var publishedDiagnostics: LiveSubscriptionDiagnostics? = null
        val bridge = LiveTimeshiftControlBridge(
            token = PlaybackTargetToken(),
            publish = {},
            publishIssue = {},
            publishDiagnostics = { publishedDiagnostics = it },
        )
        bridge.newAttachment().apply {
            bind(FakeTimeshiftSubscription(60.seconds))
            accept(SubscriptionEvent.Queue(1L, 10L, 100L, 0L, 0L, 0L))
        }
        assertEquals(1L, publishedDiagnostics?.queue?.packetCount)
        val period = TvheadendLiveMediaPeriod(DefaultAllocator(false, 1_024), bridge.newAttachment(), {})

        val failure = runCatching {
            period.accept(SubscriptionEvent.Terminated(SubscriptionTermination.GENERATION_LOST))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(publishedDiagnostics)
    }
}

private fun createPeriod(source: MediaSource): TvheadendLiveMediaPeriod = source.createPeriod(
    MediaSource.MediaPeriodId(Any()),
    DefaultAllocator(false, 1_024),
    0L,
) as TvheadendLiveMediaPeriod

private fun liveSource(
    target: CoordinatorLiveTarget,
    options: SubscriptionOptions,
): MediaSource {
    val token = PlaybackTargetToken()
    return createTvheadendLiveMediaSource(
        target = target,
        options = options,
        timeshiftControls = LiveTimeshiftControlBridge(token) {},
        onUnsupportedStream = {},
    )
}

private class CapturingLiveTarget : CoordinatorLiveTarget {
    override val isCurrent: Boolean = true
    internal var consumer: SubscriptionEventConsumer? = null
    internal var options: SubscriptionOptions? = null
    internal var openResult: suspend () -> SubscriptionOpenResult = { SubscriptionOpenResult.NotReady }

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        this.consumer = consumer
        this.options = options
        return openResult()
    }
}
