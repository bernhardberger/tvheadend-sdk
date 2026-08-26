@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionChannelId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEventConsumer
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpenResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionOpener
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
        val opener = CapturingSubscriptionOpener()
        val channelId = SubscriptionChannelId(7L)
        val options = SubscriptionOptions(
            streamProfileUuid = "0123456789abcdef0123456789abcdef",
            timeshiftPeriod = 600.seconds,
        )
        val period = createPeriod(createTvheadendLiveMediaSource(opener, channelId, options))

        assertSame(SubscriptionOpenResult.NotReady, period.openSubscription())
        assertEquals(channelId, opener.channelId)
        assertSame(period, opener.consumer)
        assertSame(options, opener.options)
    }

    @Test
    fun `legacy source overload reaches the subscription opener with server defaults`() = runTest {
        val opener = CapturingSubscriptionOpener()
        val channelId = SubscriptionChannelId(8L)
        val period = createPeriod(createTvheadendLiveMediaSource(opener, channelId))

        assertSame(SubscriptionOpenResult.NotReady, period.openSubscription())
        assertEquals(channelId, opener.channelId)
        assertSame(period, opener.consumer)
        assertNull(opener.options?.streamProfileUuid)
        assertEquals(Duration.ZERO, opener.options?.timeshiftPeriod)
    }
}

private fun createPeriod(source: MediaSource): TvheadendLiveMediaPeriod = source.createPeriod(
    MediaSource.MediaPeriodId(Any()),
    DefaultAllocator(false, 1_024),
    0L,
) as TvheadendLiveMediaPeriod

private class CapturingSubscriptionOpener : SubscriptionOpener {
    internal var channelId: SubscriptionChannelId? = null
    internal var consumer: SubscriptionEventConsumer? = null
    internal var options: SubscriptionOptions? = null

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        timeshiftPeriod: Duration,
    ): SubscriptionOpenResult = error("Legacy period opening must use explicit subscription options")

    override suspend fun open(
        channelId: SubscriptionChannelId,
        consumer: SubscriptionEventConsumer,
        options: SubscriptionOptions,
    ): SubscriptionOpenResult {
        this.channelId = channelId
        this.consumer = consumer
        this.options = options
        return SubscriptionOpenResult.NotReady
    }
}
