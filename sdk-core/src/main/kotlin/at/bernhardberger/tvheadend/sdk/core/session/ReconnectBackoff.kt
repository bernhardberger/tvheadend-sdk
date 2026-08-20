package at.bernhardberger.tvheadend.sdk.core.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun interface ReconnectBackoff {
    public fun delayForRetry(failureIndex: Int): Duration
}

internal class ExponentialReconnectBackoff(
    private val nextJitter: () -> Double,
    private val initial: Duration = 1.seconds,
    private val maximum: Duration = 30.seconds,
) : ReconnectBackoff {
    init {
        require(initial.isPositive()) { "Initial retry delay must be positive" }
        require(maximum >= initial) { "Maximum retry delay must not be smaller than initial" }
    }

    override fun delayForRetry(failureIndex: Int): Duration {
        require(failureIndex >= 0) { "Failure index must not be negative" }
        val sample = nextJitter()
        require(sample.isFinite() && sample in 0.0..1.0) { "Jitter sample must be between zero and one" }

        var nominal = initial
        var remaining = failureIndex
        while (remaining > 0 && nominal < maximum) {
            nominal = if (nominal >= maximum / 2) maximum else nominal * 2
            remaining -= 1
        }
        val jittered = nominal * (0.8 + (0.4 * sample))
        return minOf(jittered, maximum)
    }
}
