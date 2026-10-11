package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.SubscriptionIssue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class PlaybackStopResultTest {
    @Test
    fun `retirement equality includes outcome and final issue`() {
        val first = PlaybackStopResult.stopped(SubscriptionIssue.USER_ACCESS)
        val equal = PlaybackStopResult.stopped(SubscriptionIssue.USER_ACCESS)
        assertNotSame(first, equal)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertEquals(1, setOf(first, equal).size)
        assertNotEquals(first, PlaybackStopResult.stopped(null))
        assertNotEquals(first, PlaybackStopResult.stopped(SubscriptionIssue.USER_LIMIT))
        assertNotEquals(first, PlaybackStopResult.retiredAfterDeparture(SubscriptionIssue.USER_ACCESS))
        val state = MutableStateFlow(first)
        state.value = equal
        assertSame(first, state.value)
        assertEquals("PlaybackStopResult(outcome=STOPPED, finalSubscriptionIssue=USER_ACCESS)", first.toString())
        assertEquals(
            "PlaybackStopResult(outcome=PLAYER_UNAVAILABLE, finalSubscriptionIssue=null)",
            PlaybackStopResult.playerUnavailable(null).toString(),
        )
    }

    @Test
    fun `only retirement factories can carry a final issue`() {
        assertTrue(PlaybackStopResult::class.java.constructors.all { it.isSynthetic })
        listOf(PlaybackStopResult.ALREADY_STOPPED, PlaybackStopResult.NOT_RUNNING, PlaybackStopResult.SHUT_DOWN)
            .forEach { assertNull(it.finalSubscriptionIssue) }
        val cases = listOf(
            PlaybackStopResult.stopped(SubscriptionIssue.USER_ACCESS) to PlaybackStopOutcome.STOPPED,
            PlaybackStopResult.retiredAfterDeparture(SubscriptionIssue.USER_ACCESS) to PlaybackStopOutcome.RETIRED_AFTER_DEPARTURE,
            PlaybackStopResult.playerUnavailable(SubscriptionIssue.USER_ACCESS) to PlaybackStopOutcome.PLAYER_UNAVAILABLE,
        )
        cases.forEach { (result, outcome) ->
            assertSame(outcome, result.outcome)
            assertSame(SubscriptionIssue.USER_ACCESS, result.finalSubscriptionIssue)
        }
    }
}
