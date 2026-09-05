@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import at.bernhardberger.tvheadend.sdk.playback.LiveSubscriptionDiagnostics
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionTermination
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionConfirmation
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOperationResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
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
import kotlinx.coroutines.async
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TvheadendLiveMediaSourceTest {
    @Test
    fun `real subscription period path preserves mapping across an audio seek rebase`() = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val attachment = bridge.newAttachment()
        val period = TvheadendLiveMediaPeriod(
            CapturingLiveTarget(), SubscriptionOptions(), DefaultAllocator(false, 1_024), attachment, {},
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
        assertEquals(15.seconds, (bridge.playbackPosition(attachment, 15.seconds) as TimeshiftPlaybackPosition.Estimate).target.position)
        assertEquals(9.seconds, (bridge.playbackPosition(attachment, 25.seconds) as TimeshiftPlaybackPosition.Estimate).target.position)
        period.release()
        subscription.close()
        manager.closeAndJoin()
    }

    @Test
    fun `explicit source options reach the subscription opener through its media period`() = runTest {
        val target = CapturingLiveTarget()
        val options = SubscriptionOptions(
            streamProfileUuid = "0123456789abcdef0123456789abcdef",
            timeshiftPeriod = 600.seconds,
        )
        val period = createPeriod(liveSource(target, options))

        assertSame(SubscriptionOpenResult.NotReady, period.openSubscription())
        assertSame(period, target.consumer)
        assertSame(options, target.options)
    }

    @Test
    fun `default source options reach the bound target`() = runTest {
        val target = CapturingLiveTarget()
        val period = createPeriod(liveSource(target, SubscriptionOptions()))

        assertSame(SubscriptionOpenResult.NotReady, period.openSubscription())
        assertSame(period, target.consumer)
        assertNull(target.options?.streamProfileUuid)
        assertEquals(Duration.ZERO, target.options?.timeshiftPeriod)
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
        val period = createPeriod(
            createTvheadendLiveMediaSource(
                target = CapturingLiveTarget(),
                options = SubscriptionOptions(),
                timeshiftControls = bridge,
            ),
        )

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

    override suspend fun open(
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        this.consumer = consumer
        this.options = options
        return SubscriptionOpenResult.NotReady
    }
}
