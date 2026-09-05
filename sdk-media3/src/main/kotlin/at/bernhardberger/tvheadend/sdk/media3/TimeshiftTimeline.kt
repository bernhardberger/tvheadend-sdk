package at.bernhardberger.tvheadend.sdk.media3

import kotlin.time.Duration

/** A content coordinate scoped to one subscription. Never persist or reuse after replacement. */
public class TimeshiftContentTarget internal constructor(
    internal val owner: Any,
    public val position: Duration,
) {
    override fun toString(): String = "TimeshiftContentTarget"
}

/** Latest observed seekable history, not the grant, a capacity promise, or an extrapolated edge. */
public class TimeshiftTimeline internal constructor(
    internal val owner: Any,
    public val start: Duration,
    public val end: Duration,
) {
    /** Select once; subsequent edge advancement does not change the selected content coordinate. */
    public fun select(position: Duration): TimeshiftContentTarget? =
        position.takeIf { it.isFinite() && it in start..end }
            ?.let { TimeshiftContentTarget(owner, it) }

    /** No UTC anchor is supplied by the supported timeshift status / mux-packet path. */
    public val wallClockMapping: TimeshiftWallClockMapping
        get() = TimeshiftWallClockMapping.UNAVAILABLE

    override fun equals(other: Any?): Boolean = other is TimeshiftTimeline &&
        owner === other.owner && start == other.start && end == other.end

    override fun hashCode(): Int = 31 * (31 * System.identityHashCode(owner) + start.hashCode()) + end.hashCode()

    override fun toString(): String = "TimeshiftTimeline(start=$start, end=$end)"
}

/** Programme-time support is separate from stream coordinates. No local-receipt-time guess is made. */
public enum class TimeshiftWallClockMapping {
    UNAVAILABLE,
}

/** Mapping of the sampled Media3 position, never the server reader or newest queued packet. */
public sealed interface TimeshiftPlaybackPosition {
    /** No usable packet evidence, a gap, replacement, or an unavailable player snapshot. */
    public data object Unavailable : TimeshiftPlaybackPosition

    /**
     * Packet-PTS interpolation in server coordinates. Not a rendered-frame acknowledgement.
     * Packet ordering, decoder latency and server-version clock semantics preclude exactness.
     * The coordinate can remain meaningful even when it is no longer server-seekable.
     */
    public class Estimate internal constructor(
        public val target: TimeshiftContentTarget,
    ) : TimeshiftPlaybackPosition
}

/** Result of seeking a selected content coordinate. */
public sealed interface TimeshiftContentSeekResult {
    /** The owning subscription was replaced, detached or retired. Nothing was sent to its successor. */
    public data object Replaced : TimeshiftContentSeekResult

    /** Latest observed history no longer contains the target. The SDK does not clamp or retarget it. */
    public data object Expired : TimeshiftContentSeekResult

    /** Current history cannot validate the target. */
    public data object Unavailable : TimeshiftContentSeekResult

    /**
     * Command outcome and optional absolute reader acknowledgement in the selection coordinate.
     * Null [readerReached] explicitly means unknown, including accepted acknowledgements without time.
     * This is not displayed content; sample [TvheadendPlaybackCoordinator.timeshiftPlaybackPosition].
     */
    public class Completed internal constructor(
        public val command: TimeshiftCommandResult,
        public val readerReached: TimeshiftContentTarget?,
    ) : TimeshiftContentSeekResult
}

internal fun validateTimeshiftTarget(
    target: TimeshiftContentTarget,
    owner: Any?,
    timeline: TimeshiftTimeline?,
): TimeshiftContentSeekResult? = when {
    owner == null || target.owner !== owner -> TimeshiftContentSeekResult.Replaced
    timeline == null -> TimeshiftContentSeekResult.Unavailable
    target.position !in timeline.start..timeline.end -> TimeshiftContentSeekResult.Expired
    else -> null
}

/** Bounded output-coordinate segments; queued old content retains its own offset after a seek. */
internal class TimeshiftPacketMapping {
    private class Segment(val start: Long, var end: Long, val offset: Long)
    private val segments = ArrayDeque<Segment>()
    private var newSegment = false

    fun discontinuity() {
        newSegment = true
    }

    fun accept(output: Long?, server: Long?) {
        if (output == null || server == null || output < 0L || server < 0L) return
        val offset = try {
            Math.subtractExact(server, output)
        } catch (_: ArithmeticException) {
            return
        }
        val last = segments.lastOrNull()
        if (!newSegment && last != null && last.offset == offset && output >= last.start) {
            last.end = maxOf(last.end, output)
        } else {
            segments.addLast(Segment(output, output, offset))
            if (segments.size > MAX_SEGMENTS) segments.removeFirst()
        }
        newSegment = false
    }

    fun map(output: Long): Long? {
        val matches = segments.filter { output in it.start..it.end }
        val offset = matches.firstOrNull()?.offset ?: return null
        if (matches.any { it.offset != offset }) return null
        return try {
            Math.addExact(output, offset).takeIf { it >= 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private companion object {
        const val MAX_SEGMENTS = 64
    }
}
