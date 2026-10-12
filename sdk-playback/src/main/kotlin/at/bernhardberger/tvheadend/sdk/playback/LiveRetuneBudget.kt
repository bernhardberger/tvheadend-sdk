package at.bernhardberger.tvheadend.sdk.playback

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Optional, thread-safe admission budget for one application-managed logical live target.
 *
 * Keep the budget across retunes of that target; [reset] on channel change, viewer Retry or teardown.
 * Refill measures time since the previous admission, including its delay, not healthy playback.
 * Deferring an unadmitted request spends nothing; cancellation after admission does not refund it.
 * This helper neither sleeps nor launches work. The application owns scheduling, focus/pause checks,
 * target installation and the failure/stop policy on exhaustion.
 *
 * [delays] is defensively copied and must contain only finite, non-negative durations; an empty list
 * admits nothing. [resetAfter] must be finite and positive. [timeSource] must be monotonic.
 * The default [TimeSource.Monotonic] does not advance while an Android device is in deep sleep, so
 * refill can arrive later than wall time; supply a source based on `SystemClock.elapsedRealtimeNanos`
 * if sleep should count toward [resetAfter].
 * Claims and resets are serialized, including reads of the supplied time source.
 */
@ExperimentalTvheadendApi
public class LiveRetuneBudget(
    delays: List<Duration> = listOf(0.seconds, 2.seconds, 5.seconds, 10.seconds, 20.seconds),
    private val resetAfter: Duration = 60.seconds,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val delays = delays.toList()
    private val lock = Any()
    private var attempts = 0
    private var lastAdmission: ComparableTimeMark? = null

    init {
        require(this.delays.all { it.isFinite() && it >= Duration.ZERO }) {
            "Retune delays must be finite and non-negative"
        }
        require(resetAfter.isFinite() && resetAfter > Duration.ZERO) {
            "Retune reset interval must be finite and positive"
        }
    }

    /**
     * Atomically claims an attempt, refilling at or beyond [resetAfter] since the last admission.
     * Exhausted calls do not move the previous admission mark.
     */
    public fun nextAttempt(): LiveRetuneDecision = synchronized(lock) {
        val now = timeSource.markNow()
        lastAdmission?.let { last ->
            if (now - last >= resetAfter) attempts = 0
        }
        if (attempts >= delays.size) {
            LiveRetuneDecision.Exhausted
        } else {
            val delay = delays[attempts]
            attempts += 1
            lastAdmission = now
            LiveRetuneDecision.Admitted(attempt = attempts, delay = delay)
        }
    }

    /** Clears the admission count and time mark; the next admitted attempt is numbered 1. */
    public fun reset(): Unit = synchronized(lock) {
        attempts = 0
        lastAdmission = null
    }
}

/** Closed local admission result; consumers may use an exhaustive `when`. */
@ExperimentalTvheadendApi
public sealed interface LiveRetuneDecision {
    /** An attempt already claimed from the budget; cancellation does not refund it. */
    @ExperimentalTvheadendApi
    public data class Admitted(
        /** One-based admission count, restarting at 1 after refill or explicit reset. */
        public val attempt: Int,
        /** Delay the application should apply before installing the target. */
        public val delay: Duration,
    ) : LiveRetuneDecision

    /** No attempts remain; the application decides how to stop or report failure. */
    @ExperimentalTvheadendApi
    public data object Exhausted : LiveRetuneDecision
}
