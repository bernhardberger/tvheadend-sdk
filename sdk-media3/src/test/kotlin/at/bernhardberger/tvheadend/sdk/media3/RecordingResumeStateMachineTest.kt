@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class RecordingResumeStateMachineTest {
    private val seeks = mutableListOf<Long>()
    private val timeouts = FakeResumeTimeouts()
    private val resume = RecordingResumeStateMachine(seeks::add, timeouts::schedule)

    @Test
    fun `MP4 and MKV resume waits for a known seekable timeline and seeks exactly once`() {
        resume.beginPlaybackTarget(positionMillis = 180_000, RecordingResumeMode.COMPLETED)
        resume.onMediaState(targetMatches = true, durationMillis = null, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)
        assertEquals(180_000L, resume.pendingPositionMillis)

        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(listOf(180_000L), seeks)
        assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
        assertNull(resume.pendingPositionMillis)
        assertEquals(listOf(COMPLETED_RESUME_PROGRESS_FLOOR_TIMEOUT_MILLIS), timeouts.scheduledDelays)
        assertEquals(0, timeouts.active)
    }

    @Test
    fun `completed resume that turns seekable after the backstop still seeks to the saved position`() {
        resume.beginPlaybackTarget(positionMillis = 180_000, RecordingResumeMode.COMPLETED)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = false)
        assertEquals(180_000L, resume.progressFloorMillis)

        timeouts.fireAll()

        assertNull(resume.outcome)
        assertEquals(180_000L, resume.pendingPositionMillis)
        assertNull(resume.progressFloorMillis)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        assertEquals(listOf(180_000L), seeks)
        assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
        assertNull(resume.pendingPositionMillis)
    }

    @Test
    fun `a different installed target cannot consume the pending resume`() {
        resume.beginPlaybackTarget(positionMillis = 180_000, RecordingResumeMode.COMPLETED)
        resume.onMediaState(targetMatches = false, durationMillis = 3_600_000, isSeekable = true)
        assertEquals(emptyList<Long>(), seeks)

        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        assertEquals(listOf(180_000L), seeks)
    }

    @Test
    fun `a new target replaces stale resume work`() {
        resume.beginPlaybackTarget(positionMillis = 180_000, RecordingResumeMode.COMPLETED)
        resume.beginPlaybackTarget(positionMillis = 240_000, RecordingResumeMode.COMPLETED)
        assertEquals(1, timeouts.active)
        resume.onMediaState(targetMatches = false, durationMillis = 3_600_000, isSeekable = true)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(listOf(240_000L), seeks)
    }

    @Test
    fun `start over and a position outside known completed media do not seek`() {
        resume.beginPlaybackTarget(positionMillis = null, RecordingResumeMode.COMPLETED)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        assertNull(resume.outcome)
        resume.beginPlaybackTarget(positionMillis = 0, RecordingResumeMode.COMPLETED)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)
        resume.beginPlaybackTarget(positionMillis = 3_600_000, RecordingResumeMode.COMPLETED)
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
        assertEquals(RecordingResumeOutcome.ABANDONED, resume.outcome)
        assertEquals(0, timeouts.active)
    }

    @Test
    fun `close clears pending resume and rejects reuse`() {
        resume.beginPlaybackTarget(positionMillis = 180_000, RecordingResumeMode.COMPLETED)
        resume.close()
        resume.onMediaState(targetMatches = true, durationMillis = 3_600_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
        assertEquals(RecordingResumeOutcome.CANCELLED, resume.outcome)
        assertEquals(0, timeouts.active)
        assertThrows(IllegalStateException::class.java) {
            resume.beginPlaybackTarget(positionMillis = 180_000, RecordingResumeMode.COMPLETED)
        }
    }

    @Test
    fun `growing resume ignores the unseekable estimate and seeks once the extent is seekable`() {
        resume.beginPlaybackTarget(positionMillis = 8_000, RecordingResumeMode.GROWING)
        // The initial growing map and a probed extent before codec validation are both unseekable.
        resume.onMediaState(targetMatches = true, durationMillis = null, isSeekable = false)
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = false)
        assertEquals(emptyList<Long>(), seeks)
        assertEquals(8_000L, resume.pendingPositionMillis)

        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = true)
        resume.onMediaState(targetMatches = true, durationMillis = 17_000, isSeekable = true)

        assertEquals(listOf(8_000L), seeks)
        assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
        assertEquals(listOf(GROWING_RESUME_SETTLE_TIMEOUT_MILLIS), timeouts.scheduledDelays)
        assertEquals(0, timeouts.active)
    }

    @Test
    fun `growing resume at or past the probed extent seeks before the edge instead of starting over`() {
        resume.beginPlaybackTarget(positionMillis = 12_000, RecordingResumeMode.GROWING)
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = true)
        assertEquals(listOf(12_000L - GROWING_RESUME_EDGE_MARGIN_MILLIS), seeks)

        resume.beginPlaybackTarget(positionMillis = 600_000, RecordingResumeMode.GROWING)
        resume.onMediaState(targetMatches = true, durationMillis = 12_400, isSeekable = true)
        // Saved positions are whole seconds; the extent is not, so the clamp keeps its milliseconds.
        resume.beginPlaybackTarget(positionMillis = 11_000, RecordingResumeMode.GROWING)
        resume.onMediaState(targetMatches = true, durationMillis = 12_999, isSeekable = true)

        assertEquals(listOf(9_000L, 9_400L, 9_999L), seeks)
        assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
    }

    @Test
    fun `growing resume below the edge margin waits for the next extent refresh`() {
        resume.beginPlaybackTarget(positionMillis = 5_000, RecordingResumeMode.GROWING)
        resume.onMediaState(targetMatches = true, durationMillis = 0, isSeekable = true)
        resume.onMediaState(targetMatches = true, durationMillis = GROWING_RESUME_EDGE_MARGIN_MILLIS, isSeekable = true)
        assertEquals(emptyList<Long>(), seeks)
        assertEquals(5_000L, resume.pendingPositionMillis)

        resume.onMediaState(targetMatches = true, durationMillis = 7_000, isSeekable = true)

        assertEquals(listOf(4_000L), seeks)
    }

    @Test
    fun `growing resume that never becomes seekable is abandoned once`() {
        resume.beginPlaybackTarget(positionMillis = 8_000, RecordingResumeMode.GROWING)
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = false)

        timeouts.fireAll()
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
        assertEquals(RecordingResumeOutcome.ABANDONED, resume.outcome)
        assertNull(resume.pendingPositionMillis)
    }

    @Test
    fun `a viewer seek cancels the pending resume and a stale timeout cannot settle it again`() {
        resume.beginPlaybackTarget(positionMillis = 8_000, RecordingResumeMode.GROWING)
        resume.onViewerSeek()
        timeouts.fireAll()
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = true)

        assertEquals(emptyList<Long>(), seeks)
        assertEquals(RecordingResumeOutcome.CANCELLED, resume.outcome)
    }

    @Test
    fun `a stale timeout from a replaced target cannot abandon its successor`() {
        resume.beginPlaybackTarget(positionMillis = 8_000, RecordingResumeMode.GROWING)
        val stale = timeouts.pending.single()
        resume.beginPlaybackTarget(positionMillis = 9_000, RecordingResumeMode.GROWING)
        assertNull(resume.outcome)

        stale.action()
        assertEquals(9_000L, resume.pendingPositionMillis)
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = true)

        assertEquals(listOf(9_000L), seeks)
    }

    @Test
    fun `an applied resume ignores the seek it issued and later timeline changes`() {
        resume.beginPlaybackTarget(positionMillis = 8_000, RecordingResumeMode.GROWING)
        resume.onMediaState(targetMatches = true, durationMillis = 12_000, isSeekable = true)
        resume.onViewerSeek()
        resume.onMediaState(targetMatches = true, durationMillis = 20_000, isSeekable = true)

        assertEquals(listOf(8_000L), seeks)
        assertEquals(RecordingResumeOutcome.APPLIED, resume.outcome)
    }
}

internal class FakeResumeTimeouts {
    val pending = mutableListOf<Scheduled>()
    val scheduledDelays = mutableListOf<Long>()
    val active: Int
        get() = pending.count { !it.cancelled }

    fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        scheduledDelays += delayMillis
        val scheduled = Scheduled(action)
        pending += scheduled
        return AutoCloseable { scheduled.cancelled = true }
    }

    fun fireAll() {
        pending.filterNot { it.cancelled }.forEach { scheduled ->
            scheduled.cancelled = true
            scheduled.action()
        }
    }

    class Scheduled(val action: () -> Unit) {
        var cancelled = false
    }
}
