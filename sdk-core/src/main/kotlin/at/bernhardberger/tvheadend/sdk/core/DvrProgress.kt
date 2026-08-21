package at.bernhardberger.tvheadend.sdk.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal const val DVR_PROGRESS_KEEP_PLAY_COUNT: Long = (Int.MAX_VALUE - 1).toLong()
internal const val DVR_PROGRESS_INCR_PLAY_COUNT: Long = Int.MAX_VALUE.toLong()
internal const val DVR_PROGRESS_MINIMUM_PROTOCOL_VERSION: Int = 27
private const val DVR_PROGRESS_POSITION_MAX_SECONDS: Long = Int.MAX_VALUE.toLong()

/** One playback-progress observation to persist for a DVR entry. */
public data class DvrPlaybackProgress(
    public val position: Duration,
    public val markWatched: Boolean = false,
) {
    init {
        require(position.isFinite() && !position.isNegative()) {
            "DvrPlaybackProgress position must be a finite non-negative duration"
        }
        require(position == position.inWholeSeconds.seconds) {
            "DvrPlaybackProgress position must use whole seconds"
        }
        require(position.inWholeSeconds in 0L..DVR_PROGRESS_POSITION_MAX_SECONDS) {
            "DvrPlaybackProgress position must fit a signed 32-bit second count"
        }
    }

    override fun toString(): String = "DvrPlaybackProgress(<redacted>)"

    public companion object {
        /** Creates a position-only checkpoint that does not mark the recording watched. */
        public fun checkpoint(position: Duration): DvrPlaybackProgress =
            DvrPlaybackProgress(position, markWatched = false)

        /** Creates the close report for [position] using [policy] finished detection. */
        public fun close(
            position: Duration,
            duration: Duration?,
            policy: DvrProgressPolicy = DvrProgressPolicy(),
        ): DvrPlaybackProgress = policy.closeProgress(position, duration)
    }
}

/** Typed outcome of one generation-bound DVR progress report. */
public sealed interface DvrProgressResult {
    /** The command was accepted; later asynchronous metadata remains authoritative. */
    public data object Accepted : DvrProgressResult

    /** The session has not admitted progress reports for a synchronized generation. */
    public data object NotReady : DvrProgressResult

    /** The server rejected the command without a safe detailed reason. */
    public data object ServerRejected : DvrProgressResult

    /** The authenticated session lacks recorder permission. */
    public data object AccessDenied : DvrProgressResult

    /** The server refused another concurrent operation. */
    public data object ConnectionLimit : DvrProgressResult

    /** The command was not accepted before its protocol deadline. */
    public data object Timeout : DvrProgressResult

    /** The bound transport generation is unavailable. */
    public data object TransportUnavailable : DvrProgressResult

    /** Progress reporting is unavailable for the current connection. */
    public data object NotSupported : DvrProgressResult
}

/** Resume decision derived from a DVR entry and progress policy. */
public sealed interface DvrResumeOffer {
    /** Resume playback at [position]. */
    public data class Resume(
        public val position: Duration,
    ) : DvrResumeOffer {
        override fun toString(): String = "DvrResumeOffer.Resume(<redacted>)"
    }

    /** Start the recording from the beginning. */
    public data object StartOver : DvrResumeOffer
}

/** Defaults for DVR progress checkpoints, seek debounce, resume, and finished detection. */
public data class DvrProgressPolicy(
    public val resumeFloor: Duration = 180.seconds,
    public val checkpointInterval: Duration = 30.seconds,
    public val checkpointMinimumDelta: Duration = 10.seconds,
    public val seekDebounce: Duration = 2.seconds,
    public val finishedWatchedFraction: Double = 0.95,
    public val finishedRemaining: Duration = 5.minutes,
) {
    init {
        require(resumeFloor.isFinite() && !resumeFloor.isNegative()) {
            "DVR resume floor must be a finite non-negative duration"
        }
        require(checkpointInterval.isFinite() && checkpointInterval.isPositive()) {
            "DVR checkpoint interval must be finite and positive"
        }
        require(checkpointMinimumDelta.isFinite() && !checkpointMinimumDelta.isNegative()) {
            "DVR checkpoint minimum delta must be a finite non-negative duration"
        }
        require(seekDebounce.isFinite() && seekDebounce.isPositive()) {
            "DVR seek debounce must be finite and positive"
        }
        require(finishedWatchedFraction > 0.0 && finishedWatchedFraction <= 1.0) {
            "DVR finished watched fraction must be in (0.0, 1.0]"
        }
        require(finishedRemaining.isFinite() && !finishedRemaining.isNegative()) {
            "DVR finished remaining duration must be a finite non-negative duration"
        }
    }

    /** Creates a caller-driven tracker that applies this policy. */
    public fun tracker(): DvrProgressTracker = DvrProgressTracker(this)

    /** Offers resume only for completed recordings that are past the floor and not finished. */
    public fun resumeOffer(entry: DvrEntry): DvrResumeOffer {
        val position = entry.playPosition
        if (entry.state != DvrEntryState.COMPLETED || position == null || position < resumeFloor) {
            return DvrResumeOffer.StartOver
        }
        if (isFinished(position, entry.programmeDuration())) {
            return DvrResumeOffer.StartOver
        }
        return DvrResumeOffer.Resume(position)
    }

    /** True when [position] has reached 95 percent or has at most five minutes remaining. */
    public fun isFinished(position: Duration, duration: Duration?): Boolean {
        if (duration == null || !duration.isPositive()) {
            return false
        }
        if (position >= duration * finishedWatchedFraction) {
            return true
        }
        return duration - position <= finishedRemaining
    }

    /** Close report that marks watched only when [isFinished] is true. */
    public fun closeProgress(position: Duration, duration: Duration?): DvrPlaybackProgress =
        DvrPlaybackProgress(
            position = position.wholeProgressSeconds(),
            markWatched = isFinished(position.wholeProgressSeconds(), duration),
        )
}

/** Caller-driven checkpoint and seek-debounce helper; it does not own a coroutine or clock. */
public class DvrProgressTracker(
    public val policy: DvrProgressPolicy = DvrProgressPolicy(),
) {
    private var lastCheckpointAt: Instant? = null
    private var lastCheckpointPosition: Duration? = null
    private var pendingSeekAt: Instant? = null

    /** Records a seek and waits for [DvrProgressPolicy.seekDebounce] before reporting. */
    public fun onSeek(now: Instant) {
        pendingSeekAt = now
    }

    /** Returns a checkpoint when interval, minimum delta, or a settled seek requires one. */
    public fun onElapsed(now: Instant, position: Duration): DvrPlaybackProgress? {
        val snapped = position.wholeProgressSeconds()
        val seekStartedAt = pendingSeekAt
        if (seekStartedAt != null) {
            return if (now - seekStartedAt >= policy.seekDebounce) {
                emitCheckpoint(now, snapped)
            } else {
                null
            }
        }
        val originAt = lastCheckpointAt
        val originPosition = lastCheckpointPosition
        if (originAt == null || originPosition == null) {
            lastCheckpointAt = now
            lastCheckpointPosition = snapped
            return null
        }
        if (now - originAt < policy.checkpointInterval) {
            return null
        }
        val delta = (snapped - originPosition).absoluteValue
        if (delta < policy.checkpointMinimumDelta) {
            return null
        }
        return emitCheckpoint(now, snapped)
    }

    /** Always reports [position], marking watched when the policy considers playback finished. */
    public fun close(position: Duration, duration: Duration?): DvrPlaybackProgress {
        reset()
        return policy.closeProgress(position, duration)
    }

    /** Clears checkpoint and seek-debounce state. */
    public fun reset() {
        lastCheckpointAt = null
        lastCheckpointPosition = null
        pendingSeekAt = null
    }

    private fun emitCheckpoint(now: Instant, position: Duration): DvrPlaybackProgress {
        pendingSeekAt = null
        lastCheckpointAt = now
        lastCheckpointPosition = position
        return DvrPlaybackProgress.checkpoint(position)
    }
}

internal interface DvrProgressCommands {
    public suspend fun reportProgress(
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult

    data object None : DvrProgressCommands {
        override suspend fun reportProgress(
            id: DvrEntryId,
            progress: DvrPlaybackProgress,
        ): DvrProgressResult = DvrProgressResult.NotReady
    }
}

internal fun DvrEntry.programmeDuration(): Duration? {
    val start = start ?: return null
    val stop = stop ?: return null
    return (stop - start).takeIf { duration -> duration.isPositive() }
}

private fun Duration.wholeProgressSeconds(): Duration = inWholeSeconds.seconds
