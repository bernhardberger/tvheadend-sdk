package at.bernhardberger.tvheadend.sdk.media3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class OpenClassificationTest {
    @Test
    fun `stop outcomes have distinct safe labels`() {
        assertLabels(listOf(
            PlaybackStopOutcome.STOPPED to "STOPPED",
            PlaybackStopOutcome.RETIRED_AFTER_DEPARTURE to "RETIRED_AFTER_DEPARTURE",
            PlaybackStopOutcome.ALREADY_STOPPED to "ALREADY_STOPPED",
            PlaybackStopOutcome.NOT_RUNNING to "NOT_RUNNING",
            PlaybackStopOutcome.SHUT_DOWN to "SHUT_DOWN",
            PlaybackStopOutcome.PLAYER_UNAVAILABLE to "PLAYER_UNAVAILABLE",
        ))
    }

    @Test
    fun `shutdown results have distinct safe labels`() {
        assertLabels(listOf(
            PlaybackShutdownResult.DRAINED to "DRAINED",
            PlaybackShutdownResult.TIMED_OUT to "TIMED_OUT",
            PlaybackShutdownResult.NOT_RUNNING to "NOT_RUNNING",
            PlaybackShutdownResult.ALREADY_SHUT_DOWN to "ALREADY_SHUT_DOWN",
            PlaybackShutdownResult.PLAYER_UNAVAILABLE to "PLAYER_UNAVAILABLE",
        ))
    }

    @Test
    fun `recovery reasons have distinct safe labels`() {
        assertLabels(listOf(
            PlaybackRecoveryReason.AUDIO_RECOVERY_EXHAUSTED to "AUDIO_RECOVERY_EXHAUSTED",
            PlaybackRecoveryReason.LIVE_ENDED to "LIVE_ENDED",
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED to "LIVE_BUFFER_EXHAUSTED",
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED to
                "LIVE_BUFFER_EXHAUSTED_RETIREMENT_FAILED",
            PlaybackRecoveryReason.LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED to
                "LIVE_BUFFER_EXHAUSTED_PLAYER_CLEANUP_FAILED",
        ))
    }

    private fun <T : Any> assertLabels(cases: List<Pair<T, String>>) {
        assertEquals(cases.size, cases.map { it.first }.toSet().size)
        assertEquals(cases.size, cases.map { it.second }.toSet().size)
        cases.forEach { (value, label) -> assertEquals(label, value.toString()) }
    }
}
