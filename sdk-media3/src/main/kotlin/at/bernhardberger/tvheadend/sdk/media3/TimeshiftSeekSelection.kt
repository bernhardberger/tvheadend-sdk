package at.bernhardberger.tvheadend.sdk.media3

import kotlin.time.Duration

/** Immutable preview, not evidence that playback has moved. Never persist across playback targets. */
public class TimeshiftSeekSelection internal constructor(
    internal val anchor: TimeshiftContentTarget,
    public val target: TimeshiftContentTarget,
    public val boundary: Boundary,
) {
    /** START and LATEST follow their observed boundary; NONE retains a fixed content coordinate. */
    public enum class Boundary { START, NONE, LATEST }

    /** Actual selected movement, excluding discarded overshoot at a boundary. */
    public val displacement: Duration get() = target.position - anchor.position

    override fun toString(): String = "TimeshiftSeekSelection(boundary=$boundary)"
}

/** Called with freshly observed bounds; ZERO refreshes a preview without adding a key step. */
internal fun resolveTimeshiftSelection(
    timeline: TimeshiftTimeline,
    anchor: TimeshiftContentTarget,
    previous: TimeshiftSeekSelection?,
    delta: Duration,
): TimeshiftSeekSelection? {
    if (!delta.isFinite() || !timeline.start.isFinite() || !timeline.end.isFinite() ||
        timeline.start > timeline.end || anchor.owner !== timeline.owner ||
        previous?.let { it.anchor.owner !== timeline.owner || it.target.owner !== timeline.owner } == true
    ) return null
    val origin = previous?.anchor ?: anchor
    val base = when (previous?.boundary) {
        TimeshiftSeekSelection.Boundary.START -> timeline.start
        TimeshiftSeekSelection.Boundary.LATEST -> timeline.end
        TimeshiftSeekSelection.Boundary.NONE -> previous.target.position
        null -> anchor.position
    }
    val requested = base + delta
    val position = requested.coerceIn(timeline.start, timeline.end)
    val boundary = when {
        position == timeline.end && (delta > Duration.ZERO ||
            previous?.boundary == TimeshiftSeekSelection.Boundary.LATEST) -> TimeshiftSeekSelection.Boundary.LATEST
        position == timeline.start -> TimeshiftSeekSelection.Boundary.START
        position == timeline.end -> TimeshiftSeekSelection.Boundary.LATEST
        else -> TimeshiftSeekSelection.Boundary.NONE
    }
    return TimeshiftSeekSelection(origin, TimeshiftContentTarget(timeline.owner, position), boundary)
}
