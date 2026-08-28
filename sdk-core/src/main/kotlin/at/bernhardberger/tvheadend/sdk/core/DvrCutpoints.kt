package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val DVR_CUTPOINTS_MINIMUM_PROTOCOL_VERSION: Int = 12

/** Safe action attached to one DVR cutpoint interval. */
public enum class DvrCutpointAction {
    /** Omit the interval from playback. */
    CUT,

    /** Silence the interval without removing it from the timeline. */
    MUTE,

    /** Mark a scene boundary without changing playback automatically. */
    SCENE_MARKER,

    /** Mark a commercial interval without imposing application skip policy. */
    COMMERCIAL_BREAK,

    /** Preserve a cutpoint whose future server action is not understood. */
    UNKNOWN,
}

/** One bounded recording interval and the server action attached to it. */
public data class DvrCutpoint(
    public val start: Duration,
    public val end: Duration,
    public val action: DvrCutpointAction,
) {
    init {
        require(start.isFinite() && !start.isNegative() && start == start.inWholeMilliseconds.milliseconds) {
            "DVR cutpoint start must be a finite non-negative whole-millisecond duration"
        }
        require(end.isFinite() && end == end.inWholeMilliseconds.milliseconds && end > start) {
            "DVR cutpoint end must be a finite whole-millisecond duration after start"
        }
    }

    override fun toString(): String = "DvrCutpoint(action=$action, interval=<redacted>)"
}

/** Typed outcome of retrieving the cutpoints for one DVR entry. */
public sealed interface DvrCutpointsResult {
    /** The server returned this immutable, wire-ordered cutpoint list. */
    @ConsistentCopyVisibility
    public data class Available private constructor(
        public val cutpoints: List<DvrCutpoint>,
    ) : DvrCutpointsResult {
        override fun toString(): String = "DvrCutpointsResult.Available(<redacted>)"

        public companion object {
            /** Creates a result while defensively copying the ordered cutpoints. */
            public fun create(cutpoints: List<DvrCutpoint>): Available =
                Available(Collections.unmodifiableList(ArrayList(cutpoints)))
        }
    }

    /** The session has not admitted DVR queries for a synchronized generation. */
    public data object NotReady : DvrCutpointsResult

    /** The originating observation is no longer current for its owning session. */
    public data object ObservationExpired : DvrCutpointsResult

    /** The server rejected the request or returned an invalid cutpoint interval. */
    public data object ServerRejected : DvrCutpointsResult

    /** The authenticated session cannot access the selected recording. */
    public data object AccessDenied : DvrCutpointsResult

    /** The server refused another concurrent operation. */
    public data object ConnectionLimit : DvrCutpointsResult

    /** The request was not accepted before its protocol deadline. */
    public data object Timeout : DvrCutpointsResult

    /** The bound transport generation is unavailable. */
    public data object TransportUnavailable : DvrCutpointsResult

    /** Cutpoint retrieval is unavailable for the current connection. */
    public data object NotSupported : DvrCutpointsResult
}

internal interface DvrCutpointCommands {
    public suspend fun getCutpoints(
        generation: GatewayGeneration,
        id: DvrEntryId,
    ): DvrCutpointsResult

    data object None : DvrCutpointCommands {
        override suspend fun getCutpoints(
            generation: GatewayGeneration,
            id: DvrEntryId,
        ): DvrCutpointsResult =
            DvrCutpointsResult.NotReady
    }
}
