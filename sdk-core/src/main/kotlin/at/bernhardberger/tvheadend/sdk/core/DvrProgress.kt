package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import kotlin.time.Duration
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
    }
}

/** How playback of one completed-recording target ended. */
public enum class DvrPlaybackExit {
    /** Media3 reached the natural end of the active recording. */
    NATURAL_END,

    /** The active recording was replaced or stopped without a playback error. */
    ORDERLY,

    /** Playback terminated because of an error. */
    ERROR,
}

/** Typed outcome of one generation-bound DVR progress report. */
public sealed interface DvrProgressResult {
    /** The command was accepted; later asynchronous metadata remains authoritative. */
    public data object Accepted : DvrProgressResult

    /** The session has not admitted progress reports for a synchronized generation. */
    public data object NotReady : DvrProgressResult

    /** The originating observation is no longer current for its owning session. */
    public data object ObservationExpired : DvrProgressResult

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

/** Policy for DVR progress cadence, resume offers, and orderly completion. */
public data class DvrProgressPolicy(
    public val checkpointInterval: Duration = 30.seconds,
    public val orderlyCompletionFraction: Double = 0.95,
) {
    init {
        require(checkpointInterval.isFinite() && checkpointInterval.isPositive()) {
            "DVR checkpoint interval must be finite and positive"
        }
        require(orderlyCompletionFraction > 0.0 && orderlyCompletionFraction <= 1.0) {
            "DVR orderly completion fraction must be in (0.0, 1.0]"
        }
    }

    /** Creates a caller-driven tracker that applies this policy. */
    public fun tracker(): DvrProgressTracker = DvrProgressTracker(this)

    /** Offers every positive saved position for a completed recording. */
    public fun resumeOffer(entry: DvrEntry): DvrResumeOffer {
        val position = entry.playPosition
        if (entry.state != DvrEntryState.COMPLETED || position == null || !position.isPositive()) {
            return DvrResumeOffer.StartOver
        }
        return DvrResumeOffer.Resume(position)
    }

    /**
     * Creates one terminal report from actual playback measurements.
     *
     * A natural end marks a completed recording watched. An orderly exit does so only at the
     * configured fraction of a known positive duration. Errors and growing recordings never do.
     */
    public fun terminalProgress(
        position: Duration,
        duration: Duration?,
        state: DvrEntryState?,
        exit: DvrPlaybackExit,
    ): DvrPlaybackProgress {
        val observation = position.toPlaybackProgress(markWatched = false)
        val markWatched = state == DvrEntryState.COMPLETED && when (exit) {
            DvrPlaybackExit.NATURAL_END -> true
            DvrPlaybackExit.ORDERLY ->
                duration != null &&
                    duration.isFinite() &&
                    duration.isPositive() &&
                    position >= duration * orderlyCompletionFraction
            DvrPlaybackExit.ERROR -> false
        }
        return observation.copy(markWatched = markWatched)
    }
}

/**
 * Caller-driven progress state machine; it owns neither a coroutine nor a clock.
 *
 * This mutable tracker is not thread-safe. The caller must serialize all observations.
 */
public class DvrProgressTracker(
    public val policy: DvrProgressPolicy = DvrProgressPolicy(),
) {
    private var cadenceAt: Instant? = null
    private var cadencePosition: Duration? = null

    /** Returns a checkpoint after one elapsed interval when playback moved forward. */
    public fun onElapsed(now: Instant, position: Duration): DvrPlaybackProgress? {
        val observation = position.toPlaybackProgress(markWatched = false)
        val originAt = cadenceAt
        val originPosition = cadencePosition
        if (originAt == null || originPosition == null) {
            setCadence(now, position)
            return null
        }
        if (now - originAt < policy.checkpointInterval) {
            return null
        }
        setCadence(now, position)
        return observation.takeIf { position > originPosition }
    }

    /** Always reports an explicit pause and restarts cadence from that observation. */
    public fun onPause(now: Instant, position: Duration): DvrPlaybackProgress {
        val observation = position.toPlaybackProgress(markWatched = false)
        setCadence(now, position)
        return observation
    }

    /** Always reports a terminal observation and clears cadence for a subsequent target. */
    public fun onTerminal(
        position: Duration,
        duration: Duration?,
        state: DvrEntryState?,
        exit: DvrPlaybackExit,
    ): DvrPlaybackProgress {
        val observation = policy.terminalProgress(position, duration, state, exit)
        reset()
        return observation
    }

    /** Clears the elapsed-time cadence. */
    public fun reset() {
        cadenceAt = null
        cadencePosition = null
    }

    private fun setCadence(now: Instant, position: Duration) {
        cadenceAt = now
        cadencePosition = position
    }
}

private fun Duration.toPlaybackProgress(markWatched: Boolean): DvrPlaybackProgress {
    require(isFinite() && !isNegative()) {
        "DVR playback position must be a finite non-negative duration"
    }
    return DvrPlaybackProgress(
        position = inWholeSeconds.seconds,
        markWatched = markWatched,
    )
}

internal interface DvrProgressCommands {
    public suspend fun reportProgress(
        generation: GatewayGeneration,
        id: DvrEntryId,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult

    @SubscriptionInfrastructureApi
    public suspend fun reportProgress(
        lease: GrowingRecordingFileLease,
        progress: DvrPlaybackProgress,
    ): DvrProgressResult = DvrProgressResult.NotReady

    data object None : DvrProgressCommands {
        override suspend fun reportProgress(
            generation: GatewayGeneration,
            id: DvrEntryId,
            progress: DvrPlaybackProgress,
        ): DvrProgressResult = DvrProgressResult.NotReady
    }
}
