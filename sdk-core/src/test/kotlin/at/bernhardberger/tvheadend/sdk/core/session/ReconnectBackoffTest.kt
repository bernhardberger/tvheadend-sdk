package at.bernhardberger.tvheadend.sdk.core.session

import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class ReconnectBackoffTest {
    @Test
    fun `neutral jitter follows exponential delays and saturates safely`() {
        val backoff = ExponentialReconnectBackoff(nextJitter = { 0.5 })

        assertEquals(
            listOf(1, 2, 4, 8, 16, 30, 30).map { it.seconds },
            (0..6).map(backoff::delayForRetry),
        )
        assertEquals(30.seconds, backoff.delayForRetry(Int.MAX_VALUE))
    }

    @Test
    fun `jitter samples are bounded and validated`() {
        assertEquals(
            800.seconds,
            ExponentialReconnectBackoff(
                nextJitter = { 0.0 },
                initial = 1_000.seconds,
                maximum = 2_000.seconds,
            ).delayForRetry(0),
        )
        assertEquals(
            1_200.seconds,
            ExponentialReconnectBackoff(
                nextJitter = { 1.0 },
                initial = 1_000.seconds,
                maximum = 2_000.seconds,
            ).delayForRetry(0),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExponentialReconnectBackoff(nextJitter = { Double.NaN }).delayForRetry(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExponentialReconnectBackoff(nextJitter = { -0.1 }).delayForRetry(0)
        }
    }
}
