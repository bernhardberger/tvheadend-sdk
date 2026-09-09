package at.bernhardberger.tvheadend.sdk.media3

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TimeshiftSeekSelectionTest {
    private val owner = Any()
    private fun timeline(start: Int = 0, end: Int = 100) = TimeshiftTimeline(owner, start.seconds, end.seconds)
    private fun anchor(position: Int) = TimeshiftContentTarget(owner, position.seconds)

    @Test
    fun `clamped feedback follows latest and reversal discards overshoot`() {
        val origin = anchor(95)
        val first = resolveTimeshiftSelection(timeline(), origin, null, 30.seconds)!!
        assertEquals(5.seconds, first.displacement)
        assertEquals(TimeshiftSeekSelection.Boundary.LATEST, first.boundary)
        val refreshed = resolveTimeshiftSelection(timeline(end = 102), origin, first, Duration.ZERO)!!
        assertEquals(7.seconds, refreshed.displacement)
        val repeated = resolveTimeshiftSelection(timeline(end = 105), origin, refreshed, 300.seconds)!!
        assertEquals(105.seconds, repeated.target.position)
        val reversed = resolveTimeshiftSelection(timeline(end = 110), origin, repeated, (-30).seconds)!!
        assertEquals(80.seconds, reversed.target.position)
        assertEquals((-15).seconds, reversed.displacement)
        assertEquals(TimeshiftSeekSelection.Boundary.NONE, reversed.boundary)
    }

    @Test
    fun `interior selection remains fixed while bounds move and clamps on eviction`() {
        val origin = anchor(50)
        val first = resolveTimeshiftSelection(timeline(), origin, null, (-20).seconds)!!
        val refreshed = resolveTimeshiftSelection(timeline(end = 130), origin, first, Duration.ZERO)!!
        assertEquals(30.seconds, refreshed.target.position)
        val evicted = resolveTimeshiftSelection(timeline(start = 40), origin, refreshed, Duration.ZERO)!!
        assertEquals(40.seconds, evicted.target.position)
        assertEquals(TimeshiftSeekSelection.Boundary.START, evicted.boundary)
        val forward = resolveTimeshiftSelection(timeline(start = 45), origin, evicted, 30.seconds)!!
        assertEquals(75.seconds, forward.target.position)
    }

    @Test
    fun `replacement and invalid input never retarget a selection`() {
        val origin = anchor(50)
        val first = resolveTimeshiftSelection(timeline(), origin, null, 10.seconds)!!
        assertNull(resolveTimeshiftSelection(TimeshiftTimeline(Any(), Duration.ZERO, 100.seconds), origin, first, Duration.ZERO))
        assertNull(resolveTimeshiftSelection(timeline(), origin, first, Duration.INFINITE))
        assertNull(resolveTimeshiftSelection(timeline(start = 101), origin, first, Duration.ZERO))
    }
}
