package at.bernhardberger.tvheadend.sdk.media3

import kotlin.time.Duration
import kotlin.time.Instant

/** A content coordinate scoped to one stream segment. Never reuse after stop or replacement. */
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
    public val wallClockMapping: TimeshiftWallClockMapping = TimeshiftWallClockMapping.Unavailable,
    internal val subscriptionOwner: Any = owner,
) {
    /**
     * True when [other] describes the same subscription, whatever its edge has since done.
     *
     * A consumer that samples a position and reads history separately can be interrupted by
     * subscription replacement between the two reads. Coordinates from different subscriptions
     * are unrelated, so a consumer must be able to reject that pairing without also rejecting
     * ordinary edge advancement, which equality alone cannot distinguish.
     * This does not establish segment validity after a stream restart; use [describesSameSegment]
     * when combining displayed-content evidence with separately sampled history.
     */
    public fun describesSameSubscription(other: TimeshiftTimeline?): Boolean =
        other != null && subscriptionOwner === other.subscriptionOwner

    /** True only for the same playable segment; unlike transport identity, this changes on restart. */
    public fun describesSameSegment(other: TimeshiftTimeline?): Boolean =
        other != null && owner === other.owner

    /** Select once; subsequent edge advancement does not change the selected content coordinate. */
    public fun select(position: Duration): TimeshiftContentTarget? =
        position.takeIf { it.isFinite() && it in start..end }
            ?.let { TimeshiftContentTarget(owner, it) }

    override fun equals(other: Any?): Boolean = other is TimeshiftTimeline &&
        owner === other.owner && start == other.start && end == other.end && wallClockMapping == other.wallClockMapping

    override fun hashCode(): Int = 31 * (31 * System.identityHashCode(owner) + start.hashCode()) + end.hashCode()

    override fun toString(): String = "TimeshiftTimeline(start=$start, end=$end)"
}

/** Schedule-grade approximation only: transport, buffering and clock errors have no guaranteed bound. */
public sealed interface TimeshiftWallClockMapping {
    /** No usable server-time/live-edge association in this observation. */
    public data object Unavailable : TimeshiftWallClockMapping

    /** Immutable live-edge association. Retain this snapshot throughout a preview. */
    public class Estimate internal constructor(
        private val owner: Any,
        public val contentAnchor: Duration,
        public val estimatedWallClockAnchor: Instant,
    ) : TimeshiftWallClockMapping {
        /** Null for another subscription. This does not validate current seekability or retarget content. */
        public fun estimate(target: TimeshiftContentTarget): Instant? =
            if (target.owner === owner) estimatedWallClockAnchor + (target.position - contentAnchor) else null

        override fun toString(): String = "TimeshiftWallClockMapping.Estimate"
    }
}

/** Opaque identity of one accepted content seek. Compare by identity, never persist it. */
public class TimeshiftSeekToken internal constructor(internal val owner: Any) {
    override fun toString(): String = "TimeshiftSeekToken"
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
        /**
         * Seekable history observed in the same sample as [target], null when none was observed.
         *
         * Use [TimeshiftTimeline.describesSameSegment] against separately observed history
         * to confirm both describe one playable segment before combining them.
         */
        public val timeline: TimeshiftTimeline?,
        /**
         * The accepted seek whose discontinuity has been consumed and whose new packet range
         * contains this sampled playback position. Null before a content seek or after an
         * uncorrelated seek. This fences old samples; it does not acknowledge a decoded frame.
         */
        public val seek: TimeshiftSeekToken? = null,
    ) : TimeshiftPlaybackPosition
}

/** Result of seeking a selected content coordinate. */
public sealed interface TimeshiftContentSeekResult {
    /** The owning segment restarted, or its subscription was replaced, detached or retired. */
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
        /** Non-null only for acceptance. Match against [TimeshiftPlaybackPosition.Estimate.seek]. */
        public val seek: TimeshiftSeekToken? = null,
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

    fun clear() {
        segments.clear()
        newSegment = false
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
