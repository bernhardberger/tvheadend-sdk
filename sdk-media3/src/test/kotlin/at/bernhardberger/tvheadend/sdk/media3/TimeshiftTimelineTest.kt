@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.SkipOutcome
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekResult
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionSeekTarget
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class TimeshiftTimelineTest {
    @Test
    fun `duplicate status retains stateflow value instead of publishing identity changes`() {
        val state = kotlinx.coroutines.flow.MutableStateFlow<LiveTimeshiftState>(LiveTimeshiftState.Unavailable)
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) { state.value = it }
        val attachment = bridge.newAttachment()
        attachment.bind(FakeTimeshiftSubscription(120.seconds))
        val status = SubscriptionEvent.Timeshift(0, 0, 10_000_000, 100_000_000, 100)
        attachment.accept(status)
        val first = state.value
        attachment.accept(status)
        assertSame(first, state.value)
        attachment.accept(SubscriptionEvent.Queue(1, 1, 1, 0, 0, 0))
        assertSame(first, state.value)
    }

    @Test
    fun `selection survives edge advance but expires and cannot cross replacement`() = runTest {
        var state: LiveTimeshiftState = LiveTimeshiftState.Unavailable
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) { state = it }
        val first = bridge.newAttachment()
        val subscription = FakeTimeshiftSubscription(120.seconds)
        first.bind(subscription)
        first.accept(SubscriptionEvent.Timeshift(0, 0, 10_000_000, 100_000_000, 100))
        val timeline = (state as LiveTimeshiftState.Available).timeline!!
        val selected = timeline.select(40.seconds)!!
        assertNull(timeline.select(9.seconds))
        assertSame(TimeshiftWallClockMapping.UNAVAILABLE, timeline.wallClockMapping)
        first.accept(SubscriptionEvent.Timeshift(0, 0, 20_000_000, 110_000_000, 100))
        subscription.seekAction = {
            first.accept(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 39_000_000, null))
            SubscriptionSeekResult.AcceptedAt(39.seconds)
        }
        val result = bridge.seekContent(selected) as TimeshiftContentSeekResult.Completed
        assertSame(TimeshiftCommandResult.ACCEPTED, result.command)
        assertEquals(39.seconds, result.readerReached!!.position)
        assertEquals(40.seconds, (subscription.seekTargets.single() as SubscriptionSeekTarget.Absolute).position)
        first.accept(SubscriptionEvent.Timeshift(0, 0, 50_000_000, 140_000_000, 100))
        assertSame(TimeshiftContentSeekResult.Expired, bridge.seekContent(selected))
        assertEquals(1, subscription.seekTargets.size)
        val second = bridge.newAttachment()
        val replacement = FakeTimeshiftSubscription(120.seconds)
        second.bind(replacement)
        second.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100_000_000, 100))
        assertSame(TimeshiftContentSeekResult.Replaced, bridge.seekContent(selected))
        assertEquals(0, replacement.seekTargets.size)
    }

    @Test
    fun `delayed earlier acknowledgement cannot supply the current seek result`() = runTest {
        var state: LiveTimeshiftState = LiveTimeshiftState.Unavailable
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) { state = it }
        val attachment = bridge.newAttachment()
        val subscription = FakeTimeshiftSubscription(120.seconds)
        attachment.bind(subscription)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100_000_000, 100))
        val selected = (state as LiveTimeshiftState.Available).timeline!!.select(40.seconds)!!
        subscription.seekAction = {
            attachment.accept(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 99_000_000, null))
            SubscriptionSeekResult.AcceptedAt(39.seconds)
        }
        assertEquals(39.seconds, (bridge.seekContent(selected) as TimeshiftContentSeekResult.Completed).readerReached!!.position)
        subscription.seekAction = {
            attachment.accept(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 99_000_000, null))
            SubscriptionSeekResult.Accepted
        }
        assertNull((bridge.seekContent(selected) as TimeshiftContentSeekResult.Completed).readerReached)
    }

    @Test
    fun `overlapping periods make mapping unavailable until old period detaches`() {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val first = bridge.newAttachment()
        first.bind(FakeTimeshiftSubscription(120.seconds))
        val second = bridge.newAttachment()
        second.bind(FakeTimeshiftSubscription(120.seconds))
        second.packetMapping.accept(10_000_000, 20_000_000)
        second.packetMapping.accept(20_000_000, 30_000_000)
        assertNull(bridge.mappingAttachment())
        assertSame(TimeshiftPlaybackPosition.Unavailable, bridge.playbackPosition(second, 15.seconds))
        first.detach()
        assertSame(second, bridge.mappingAttachment())
        assertEquals(25.seconds, (bridge.playbackPosition(second, 15.seconds) as TimeshiftPlaybackPosition.Estimate).target.position)
    }

    @Test
    fun `interleaved tracks with identical offset do not create ambiguity`() {
        val mapping = TimeshiftPacketMapping()
        mapping.accept(10, 10)
        mapping.discontinuity()
        mapping.accept(20, 100)
        mapping.accept(18, 98)
        mapping.accept(30, 110)
        assertEquals(100L, mapping.map(20))
        mapping.discontinuity()
        mapping.accept(19, 199)
        mapping.accept(31, 211)
        assertNull(mapping.map(20))
    }

    @Test
    fun `replacement while seek awaits acknowledgement cannot publish reached content for successor`() = runTest {
        var state: LiveTimeshiftState = LiveTimeshiftState.Unavailable
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) { state = it }
        val first = bridge.newAttachment()
        val subscription = FakeTimeshiftSubscription(120.seconds)
        first.bind(subscription)
        first.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100_000_000, 100))
        val selected = (state as LiveTimeshiftState.Available).timeline!!.select(40.seconds)!!
        subscription.seekAction = {
            bridge.newAttachment().bind(FakeTimeshiftSubscription(120.seconds))
            first.accept(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, 40_000_000, null))
            SubscriptionSeekResult.Accepted
        }
        assertSame(TimeshiftContentSeekResult.Replaced, bridge.seekContent(selected))
    }

    @Test
    fun `missing history and accepted acknowledgement without absolute time remain unknown`() = runTest {
        var state: LiveTimeshiftState = LiveTimeshiftState.Unavailable
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) { state = it }
        val attachment = bridge.newAttachment()
        attachment.bind(FakeTimeshiftSubscription(120.seconds))
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100_000_000, 100))
        val selected = (state as LiveTimeshiftState.Available).timeline!!.select(40.seconds)!!
        assertNull((bridge.seekContent(selected) as TimeshiftContentSeekResult.Completed).readerReached)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, null, null, 100))
        assertSame(TimeshiftContentSeekResult.Unavailable, bridge.seekContent(selected))
        assertNull((state as LiveTimeshiftState.Available).timeline)
    }

    @Test
    fun `queued old media keeps its mapping through pause seek and rebasing`() {
        val mapping = TimeshiftPacketMapping()
        mapping.accept(10_000_000, 10_000_000)
        mapping.accept(20_000_000, 20_000_000)
        assertEquals(15_000_000, mapping.map(15_000_000))
        mapping.discontinuity()
        mapping.accept(21_000_000, 5_000_000)
        mapping.accept(30_000_000, 14_000_000)
        // Paused Media3 position remains in the old queued segment, not at the server reader.
        assertEquals(15_000_000, mapping.map(15_000_000))
        assertEquals(9_000_000, mapping.map(25_000_000))
        assertNull(mapping.map(20_500_000))
        assertNull(mapping.map(31_000_000))
        mapping.discontinuity()
        mapping.accept(31_000_000, 70_000_000)
        mapping.accept(40_000_000, 79_000_000)
        assertEquals(74_000_000, mapping.map(35_000_000))
        assertEquals(9_000_000, mapping.map(25_000_000))
    }
}
