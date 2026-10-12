@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

internal class LiveStartLoadControlTest {
    private val playerId = PlayerId("live-start-test")
    private val live = timeline(MediaItem.Builder().setMediaId(LIVE_MEDIA_ID).build(), false)
    private val recording = timeline(MediaItem.EMPTY, true)

    @Test
    fun `skip restart consumes once across playback and application threads and completion cannot resurrect`() {
        var now = 10_000L
        val control = LiveStartLoadControl(createTvheadendLoadControl(), TvheadendLiveStartBuffer(500.milliseconds)) { now }
        control.onPrepared(playerId)
        val mark = control.beginSkip(PlaybackTargetToken(), live.getUidOfPeriod(0), true)
        assertFalse(control.shouldStartPlayback(parameters(499, rebuffering = true, rebufferAt = 9_000)))
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val playback = Thread { started.set(control.shouldStartPlayback(parameters(500, rebuffering = true, rebufferAt = 9_000))) }
        playback.start()
        playback.join()
        assertTrue(started.get())
        now += 100
        control.finishSkip(mark, TimeshiftCommandDisposition.ACCEPTED)
        assertFalse(control.shouldStartPlayback(parameters(500, rebuffering = true, rebufferAt = now)))
    }

    @Test
    fun `grace bounds restart onset not the final readiness decision and preserves unconfirmed skips`() {
        var now = 10_000L
        val control = LiveStartLoadControl(createTvheadendLoadControl(), TvheadendLiveStartBuffer(500.milliseconds)) { now }
        control.onPrepared(playerId)
        for (disposition in listOf(TimeshiftCommandDisposition.ACCEPTED, TimeshiftCommandDisposition.UNCONFIRMED)) {
            val mark = control.beginSkip(PlaybackTargetToken(), live.getUidOfPeriod(0), false)
            now = 11_000
            control.finishSkip(mark, disposition)
            now = 20_000
            assertTrue(control.shouldStartPlayback(parameters(500, rebuffering = true, rebufferAt = 14_000)))
            val expired = control.beginSkip(PlaybackTargetToken(), live.getUidOfPeriod(0), false)
            control.finishSkip(expired, disposition)
            now += 3_001
            assertFalse(control.shouldStartPlayback(parameters(500, rebuffering = true, rebufferAt = now)))
            now = 10_000
        }
    }

    @Test
    fun `refusal cancellation retirement and mismatched periods clear marks without late result revival`() {
        val control = LiveStartLoadControl(createTvheadendLoadControl(), TvheadendLiveStartBuffer(500.milliseconds)) { 10_000 }
        control.onPrepared(playerId)
        for (mode in listOf("refused", "cancelled", "retired", "period", "recording")) {
            val token = PlaybackTargetToken()
            val mark = control.beginSkip(token, live.getUidOfPeriod(0), true)
            when (mode) {
                "refused" -> control.finishSkip(mark, TimeshiftCommandDisposition.NOT_ACCEPTED)
                "cancelled" -> control.cancelSkip(mark)
                "retired" -> control.retireTarget(token)
                "period" -> {
                    val other = parameters(500, rebuffering = true, periodUid = Any())
                    // Unknown periods delegate; the fake delegate avoids Media3's own unknown-UID rejection.
                    val fake = LiveStartLoadControl(object : LoadControl by createTvheadendLoadControl() {
                        override fun shouldStartPlayback(parameters: LoadControl.Parameters) = false
                    }, TvheadendLiveStartBuffer(500.milliseconds)) { 10_000 }
                    val old = fake.beginSkip(token, live.getUidOfPeriod(0), true)
                    assertFalse(fake.shouldStartPlayback(other))
                    fake.finishSkip(old, TimeshiftCommandDisposition.ACCEPTED)
                    assertFalse(fake.shouldStartPlayback(parameters(500, rebuffering = true)))
                    control.cancelSkip(mark)
                }
                "recording" -> assertFalse(control.shouldStartPlayback(parameters(500, timeline = recording, rebuffering = true)))
            }
            control.finishSkip(mark, TimeshiftCommandDisposition.ACCEPTED)
            assertFalse(control.shouldStartPlayback(parameters(500, rebuffering = true)))
        }
    }

    @Test
    fun `old completion and cancellation cannot change an overlapping newer skip`() {
        val control = LiveStartLoadControl(createTvheadendLoadControl(), TvheadendLiveStartBuffer(500.milliseconds)) { 10_000 }
        control.onPrepared(playerId)
        val old = control.beginSkip(PlaybackTargetToken(), live.getUidOfPeriod(0), false)
        val newer = control.beginSkip(PlaybackTargetToken(), live.getUidOfPeriod(0), true)
        control.finishSkip(old, TimeshiftCommandDisposition.NOT_ACCEPTED)
        control.cancelSkip(old)
        control.finishSkip(newer, TimeshiftCommandDisposition.ACCEPTED)
        assertTrue(control.shouldStartPlayback(parameters(500, rebuffering = true)))
        assertFalse(control.shouldStartPlayback(parameters(500, rebuffering = true)))
    }

    @Test
    fun `defaults preserve two second startup and one and a half second ordinary rebuffer`() {
        val control = prepared(TvheadendLiveStartBuffer())
        assertFalse(control.shouldStartPlayback(parameters(1_999)))
        assertTrue(control.shouldStartPlayback(parameters(2_000)))
        assertFalse(control.shouldStartPlayback(parameters(1_499, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(1_500, rebuffering = true)))
    }

    @Test
    fun `initial 500 milliseconds and subsequent learned values apply on next decision`() {
        val buffer = TvheadendLiveStartBuffer(500.milliseconds)
        val control = prepared(buffer)
        assertFalse(control.shouldStartPlayback(parameters(499)))
        assertTrue(control.shouldStartPlayback(parameters(500)))
        val writer = Thread { buffer.duration = 750.milliseconds }
        writer.start()
        writer.join()
        assertFalse(control.shouldStartPlayback(parameters(749)))
        assertTrue(control.shouldStartPlayback(parameters(750)))
        buffer.duration = Duration.ZERO
        assertTrue(control.shouldStartPlayback(parameters(0)))
    }

    @Test
    fun `speed and target live offset constrain the live startup threshold`() {
        val control = prepared(TvheadendLiveStartBuffer())
        assertFalse(control.shouldStartPlayback(parameters(3_999, speed = 2f)))
        assertTrue(control.shouldStartPlayback(parameters(4_000, speed = 2f)))
        assertFalse(control.shouldStartPlayback(parameters(499, liveOffset = 1_000_000)))
        assertTrue(control.shouldStartPlayback(parameters(500, liveOffset = 1_000_000)))
    }

    @Test
    fun `dynamic recordings keep the original startup and rebuffer thresholds`() {
        val control = prepared(TvheadendLiveStartBuffer(500.milliseconds))
        assertFalse(control.shouldStartPlayback(parameters(1_999, timeline = recording)))
        assertTrue(control.shouldStartPlayback(parameters(2_000, timeline = recording)))
        assertFalse(control.shouldStartPlayback(parameters(1_499, timeline = recording, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(parameters(1_500, timeline = recording, rebuffering = true)))
    }

    @Test
    fun `invalid durations cannot replace the current value and labels reveal no state`() {
        val buffer = TvheadendLiveStartBuffer()
        for (invalid in listOf((-1).milliseconds, Duration.INFINITE, 1.nanoseconds, (Int.MAX_VALUE.toLong() + 1).milliseconds)) {
            assertThrows(IllegalArgumentException::class.java) { TvheadendLiveStartBuffer(invalid) }
            assertThrows(IllegalArgumentException::class.java) { buffer.duration = invalid }
            assertEquals(2.seconds, buffer.duration)
        }
        buffer.duration = Int.MAX_VALUE.milliseconds
        assertEquals(Int.MAX_VALUE.milliseconds, buffer.duration)
        assertEquals("TvheadendLiveStartBuffer", buffer.toString())
        assertEquals("TvheadendLoadControl", createTvheadendLoadControl(buffer).toString())
    }

    @Test
    fun `every current callback explicitly forwards including unknown periods`() {
        val calls = mutableListOf<String>()
        val allocator = createTvheadendLoadControl().getAllocator(playerId)
        val parameters = parameters(100, timeline = Timeline.EMPTY)
        val delegate = object : LoadControl {
            override fun onPrepared(playerId: PlayerId) { calls += "prepared" }
            override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) { calls += "tracks" }
            override fun onStopped(playerId: PlayerId) { calls += "stopped" }
            override fun onReleased(playerId: PlayerId) { calls += "released" }
            override fun getAllocator(playerId: PlayerId) = allocator.also { calls += "allocator" }
            override fun getBackBufferDurationUs(playerId: PlayerId) = 12L.also { calls += "backbuffer" }
            override fun retainBackBufferFromKeyframe(playerId: PlayerId) = true.also { calls += "keyframe" }
            override fun shouldContinueLoading(parameters: LoadControl.Parameters) = true.also { calls += "loading" }
            override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long) = true.also { calls += "preload" }
            override fun shouldStartPlayback(parameters: LoadControl.Parameters) = true.also { calls += "start" }
        }
        val control = LiveStartLoadControl(delegate, TvheadendLiveStartBuffer())
        control.onPrepared(playerId)
        control.onTracksSelected(parameters, TrackGroupArray.EMPTY, emptyArray())
        assertSame(allocator, control.getAllocator(playerId))
        assertEquals(12L, control.getBackBufferDurationUs(playerId))
        assertTrue(control.retainBackBufferFromKeyframe(playerId))
        assertTrue(control.shouldContinueLoading(parameters))
        assertTrue(control.shouldContinuePreloading(playerId, parameters.timeline, parameters.mediaPeriodId, 0))
        assertTrue(control.shouldStartPlayback(parameters))
        control.onStopped(playerId)
        control.onReleased(playerId)
        assertEquals(listOf("prepared", "tracks", "allocator", "backbuffer", "keyframe", "loading", "preload", "start", "stopped", "released"), calls)
    }

    private fun prepared(buffer: TvheadendLiveStartBuffer): LoadControl = createTvheadendLoadControl(buffer).delegate.apply {
        onPrepared(playerId)
        onTracksSelected(parameters(0), TrackGroupArray.EMPTY, emptyArray())
    }

    private fun timeline(item: MediaItem, dynamic: Boolean): Timeline =
        SinglePeriodTimeline(C.TIME_UNSET, false, dynamic, false, null, item)

    private fun parameters(
        bufferedMs: Long,
        timeline: Timeline = live,
        rebuffering: Boolean = false,
        speed: Float = 1f,
        liveOffset: Long = C.TIME_UNSET,
        rebufferAt: Long = C.TIME_UNSET,
        periodUid: Any = if (timeline.isEmpty) Any() else timeline.getUidOfPeriod(0),
    ): LoadControl.Parameters = LoadControl.Parameters(
        playerId, timeline, MediaPeriodId(periodUid),
        0, bufferedMs * 1_000, speed, true, rebuffering, liveOffset, rebufferAt,
    )
}
