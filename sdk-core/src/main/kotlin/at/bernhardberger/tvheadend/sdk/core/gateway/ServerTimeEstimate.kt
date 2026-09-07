package at.bernhardberger.tvheadend.sdk.core.gateway

import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One connection-scoped observation, never a device wall-clock offset. */
internal class ServerTimeEstimate(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Observation(val generation: GatewayGeneration, val time: Instant, val mark: TimeMark)
    private var observation: Observation? = null

    @Synchronized
    fun observe(generation: GatewayGeneration, time: Instant) {
        observation = Observation(generation, time, timeSource.markNow())
    }

    @Synchronized
    fun atStatus(generation: GatewayGeneration): Instant? {
        val current = observation?.takeIf { it.generation === generation } ?: return null
        val elapsed = current.mark.elapsedNow()
        if (!elapsed.isFinite() || elapsed.isNegative()) return null
        return current.time + elapsed
    }
}
