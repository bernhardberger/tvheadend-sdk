package at.bernhardberger.tvheadend.sdk.core.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

class ServerTimeEstimateTest {
    @Test
    fun `only monotonic elapsed and matching connection contribute to estimate`() {
        val time = TestTimeSource()
        val estimate = ServerTimeEstimate(time)
        val first = GatewayGeneration()
        val next = GatewayGeneration()
        val epoch = Instant.fromEpochSeconds(1_000)
        assertNull(estimate.atStatus(first))
        estimate.observe(first, epoch)
        time += 5.seconds
        assertEquals(epoch + 5.seconds, estimate.atStatus(first))
        assertNull(estimate.atStatus(next))
        estimate.observe(next, epoch - 100.seconds)
        assertNull(estimate.atStatus(first))
        repeat(100_000) { assertEquals(epoch - 100.seconds, estimate.atStatus(next)) }
        time += 7.seconds
        assertEquals(epoch - 93.seconds, estimate.atStatus(next))
    }
}
