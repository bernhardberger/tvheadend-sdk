package at.bernhardberger.tvheadend.sdk.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

internal class DvrCutpointsTest {
    @Test
    fun `cutpoints validate millisecond intervals and redact coordinates`() {
        val cutpoint = DvrCutpoint(
            start = 1_000.milliseconds,
            end = 2_000.milliseconds,
            action = DvrCutpointAction.COMMERCIAL_BREAK,
        )

        assertEquals(1_000.milliseconds, cutpoint.start)
        assertEquals(2_000.milliseconds, cutpoint.end)
        assertEquals(DvrCutpointAction.COMMERCIAL_BREAK, cutpoint.action)
        assertEquals(
            "DvrCutpoint(action=COMMERCIAL_BREAK, interval=<redacted>)",
            cutpoint.toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DvrCutpoint((-1).milliseconds, 1.milliseconds, DvrCutpointAction.CUT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrCutpoint(500.microseconds, 1.milliseconds, DvrCutpointAction.CUT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DvrCutpoint(1.milliseconds, 1.milliseconds, DvrCutpointAction.CUT)
        }
    }

    @Test
    fun `available result snapshots ordered overlapping cutpoints`() {
        val first = DvrCutpoint(5_000.milliseconds, 10_000.milliseconds, DvrCutpointAction.CUT)
        val second = DvrCutpoint(1_000.milliseconds, 8_000.milliseconds, DvrCutpointAction.MUTE)
        val source = arrayListOf(first, second)

        val available = DvrCutpointsResult.Available.create(source)
        source.clear()

        assertEquals(listOf(first, second), available.cutpoints)
        assertEquals("DvrCutpointsResult.Available(<redacted>)", available.toString())
        @Suppress("UNCHECKED_CAST")
        val mutable = available.cutpoints as MutableList<DvrCutpoint>
        assertThrows(UnsupportedOperationException::class.java) {
            mutable += DvrCutpoint(
                12_000.milliseconds,
                13_000.milliseconds,
                DvrCutpointAction.UNKNOWN,
            )
        }
        assertTrue(available.cutpoints[0].start > available.cutpoints[1].start)
    }
}
