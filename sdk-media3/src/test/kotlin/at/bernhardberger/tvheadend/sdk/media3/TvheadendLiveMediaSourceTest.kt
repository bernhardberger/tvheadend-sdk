@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class TvheadendLiveMediaSourceTest {
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
