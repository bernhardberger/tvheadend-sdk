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
    fun `truncated clock intersects only observed unambiguous packet bounds`() {
        val mapping = TimeshiftPacketMapping()
        mapping.accept(10_000_633, 20_000_633)
        mapping.accept(10_100_633, 20_100_633)
        assertNull(mapping.map(10_000_000))
        assertEquals(20_000_633, mapping.map(10_000_000, 1_000))
        assertNull(mapping.map(9_999_000, 1_000))
        assertNull(mapping.map(10_101_000, 1_000))
        assertEquals(20_001_000, mapping.map(10_001_000, 1_000))
        mapping.discontinuity()
        mapping.accept(10_000_999, 30_000_999)
        assertNull(mapping.map(10_000_000, 1_000))
        mapping.clear()
        assertNull(mapping.map(10_000_000, 1_000))
        mapping.accept(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, mapping.map(Long.MAX_VALUE - 100, 1_000))
        assertNull(mapping.map(-1, 1_000))
    }

    @Test
    fun `ordered delayed skip delivery correlates each accepted command without reader times`() = runTest {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val attachment = bridge.newAttachment()
        val subscription = FakeTimeshiftSubscription(120.seconds)
        attachment.bind(subscription)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 120_000_000, 0))
        val target = attachment.timeline()!!.select(0.seconds)!!
        subscription.seekAction = { SubscriptionSeekResult.Accepted }
        val first = bridge.seekContent(target) as TimeshiftContentSeekResult.Completed
        val second = bridge.seekContent(target) as TimeshiftContentSeekResult.Completed
        org.junit.jupiter.api.Assertions.assertNotNull(first.seek)
        org.junit.jupiter.api.Assertions.assertNotSame(first.seek, second.seek)
        for (expected in listOf(first, second)) {
            attachment.accept(SubscriptionEvent.Skipped(true, SkipOutcome.ACCEPTED, null, null))
            attachment.packetMapping.accept(1_000_000, 0)
            assertSame(TimeshiftPlaybackPosition.Unavailable, bridge.playbackPosition(attachment, 1.seconds))
            attachment.playbackDiscontinuity()
            assertSame(expected.seek, (bridge.playbackPosition(attachment, 1.seconds) as TimeshiftPlaybackPosition.Estimate).seek)
        }
        subscription.seekAction = { SubscriptionSeekResult.Rejected }
        assertNull((bridge.seekContent(target) as TimeshiftContentSeekResult.Completed).seek)
        attachment.accept(SubscriptionEvent.Skipped(true, SkipOutcome.REJECTED, null, null))
        assertSame(second.seek, (bridge.playbackPosition(attachment, 1.seconds) as TimeshiftPlaybackPosition.Estimate).seek)
        subscription.seekAction = { SubscriptionSeekResult.NotSeekable }
        bridge.seekContent(target)
        assertEquals(0, attachment.pendingSeeks.size)
    }

    @Test
    fun `estimate snapshots follow status not packets or reader pause and reject replacement`() {
        var state: LiveTimeshiftState = LiveTimeshiftState.Unavailable
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) { state = it }
        val attachment = bridge.newAttachment()
        attachment.bind(FakeTimeshiftSubscription(120.seconds))
        val now = kotlin.time.Instant.fromEpochSeconds(1_000)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100_000_000, 100, now))
        val history = (state as LiveTimeshiftState.Available).timeline!!
        val mapping = history.wallClockMapping as TimeshiftWallClockMapping.Estimate
        val selected = history.select(40.seconds)!!
        assertEquals(now - 60.seconds, mapping.estimate(selected))
        attachment.accept(SubscriptionEvent.Speed(0))
        assertSame(mapping, (state as LiveTimeshiftState.Available).timeline!!.wallClockMapping)
        val packet = SubscriptionEvent.Packet(
            at.bernhardberger.tvheadend.sdk.playback.MuxFrameType.UNKNOWN,
            at.bernhardberger.tvheadend.sdk.playback.StreamIndex(0), 0, 0, 1,
            at.bernhardberger.tvheadend.sdk.testing.SubscriptionBinaryFixture(byteArrayOf()),
        )
        repeat(100_000) { attachment.accept(packet) }
        assertSame(mapping, attachment.timeline()!!.wallClockMapping)
        attachment.accept(SubscriptionEvent.Timeshift(0, 10_000_000, 0, 110_000_000, 0, now + 12.seconds))
        val sample = bridge.playbackPosition(attachment, 0.seconds) as TimeshiftPlaybackPosition.Estimate
        assertSame(attachment.timeline()!!.wallClockMapping, sample.timeline!!.wallClockMapping)
        assertEquals(now - 60.seconds, mapping.estimate(selected))
        assertEquals(40.seconds, selected.position)
        val replacement = bridge.newAttachment()
        replacement.bind(FakeTimeshiftSubscription(120.seconds))
        replacement.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100_000_000, 100, now))
        assertNull(mapping.estimate(replacement.timeline()!!.select(40.seconds)!!))
    }

    @Test
    fun `missing bounds preserve continuity baseline but advancing recovery is allowed`() {
        for (nextEnd in listOf(90L, 110L)) {
            val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
            val attachment = bridge.newAttachment()
            attachment.bind(FakeTimeshiftSubscription(120.seconds))
            val now = kotlin.time.Instant.fromEpochSeconds(1_000)
            attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100, 100, now))
            attachment.accept(SubscriptionEvent.Timeshift(0, 0, null, null, 100, now))
            assertNull(attachment.timeline())
            attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, nextEnd, 100, now))
            attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 120, 100, now))
            assertEquals(nextEnd > 100, attachment.timeline()!!.wallClockMapping is TimeshiftWallClockMapping.Estimate)
        }
    }

    @Test
    fun `dropped data and repeated stream starts disable estimates until replacement`() {
        val started = SubscriptionEvent.Started(
            null, null, at.bernhardberger.tvheadend.sdk.playback.SubscriptionCondition.NO_DETAIL,
        )
        for (event in listOf(SubscriptionEvent.Dropped(1), started)) {
            val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
            val attachment = bridge.newAttachment()
            attachment.bind(FakeTimeshiftSubscription(120.seconds))
            attachment.accept(started)
            val now = kotlin.time.Instant.fromEpochSeconds(1_000)
            attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100, 100, now))
            attachment.accept(event)
            assertSame(TimeshiftWallClockMapping.Unavailable, attachment.timeline()!!.wallClockMapping)
            attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 110, 100, now))
            assertSame(TimeshiftWallClockMapping.Unavailable, attachment.timeline()!!.wallClockMapping)
        }
    }

    @Test
    fun `stalled missing and regressing live edges do not fabricate new anchors`() {
        val bridge = LiveTimeshiftControlBridge(PlaybackTargetToken()) {}
        val attachment = bridge.newAttachment()
        attachment.bind(FakeTimeshiftSubscription(120.seconds))
        val now = kotlin.time.Instant.fromEpochSeconds(1_000)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100, 100, now))
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 100, 100, now + 10.seconds))
        assertSame(TimeshiftWallClockMapping.Unavailable, attachment.timeline()!!.wallClockMapping)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 110, 100))
        assertSame(TimeshiftWallClockMapping.Unavailable, attachment.timeline()!!.wallClockMapping)
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 90, 100, now))
        attachment.accept(SubscriptionEvent.Timeshift(0, 0, 0, 120, 100, now))
        assertSame(TimeshiftWallClockMapping.Unavailable, attachment.timeline()!!.wallClockMapping)
    }

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
        assertSame(TimeshiftWallClockMapping.Unavailable, timeline.wallClockMapping)
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
