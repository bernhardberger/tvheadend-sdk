package at.bernhardberger.tvheadend.sdk.core

import java.util.Collections

/** Canonical lowercase 128-bit TVHeadend stream-profile UUID. */
@JvmInline
public value class StreamProfileId(public val value: String) {
    init {
        require(value.length == STREAM_PROFILE_UUID_LENGTH && value.all(::isLowercaseHexDigit)) {
            "Stream profile ID must be a canonical lowercase 128-bit UUID"
        }
    }

    override fun toString(): String = "StreamProfileId(<redacted>)"
}

/** Immutable presentation metadata for one selectable server stream profile. */
public data class StreamProfile(
    public val id: StreamProfileId,
    public val name: String,
    public val comment: String,
) {
    override fun toString(): String = "StreamProfile(<redacted>)"
}

/** Typed result of discovering stream profiles on the current connection generation. */
public sealed interface StreamProfilesResult {
    /** The server returned this immutable, wire-ordered profile list. */
    @ConsistentCopyVisibility
    public data class Available private constructor(
        public val profiles: List<StreamProfile>,
        /**
         * Exact proof that authorized this operation.
         *
         * Provenance does not guarantee that the proof is still current when the result is read.
         */
        public val originatingSession: CurrentSessionObservation,
    ) : StreamProfilesResult {
        override fun toString(): String = "StreamProfilesResult.Available(<redacted>)"

        public companion object {
            /** Creates a result while defensively copying the ordered profiles. */
            public fun create(
                profiles: List<StreamProfile>,
                originatingSession: CurrentSessionObservation,
            ): Available = Available(
                profiles = Collections.unmodifiableList(ArrayList(profiles)),
                originatingSession = originatingSession,
            )
        }
    }

    /** No connection generation is currently available for discovery. */
    public data object NotReady : StreamProfilesResult

    /** The originating observation is no longer current for its owning session. */
    public data object ObservationExpired : StreamProfilesResult

    /** The server rejected the request or returned an invalid profile UUID. */
    public data object ServerRejected : StreamProfilesResult

    /** The authenticated session cannot discover stream profiles. */
    public data object AccessDenied : StreamProfilesResult

    /** The server refused another concurrent operation. */
    public data object ConnectionLimit : StreamProfilesResult

    /** The request was not accepted before its protocol deadline. */
    public data object Timeout : StreamProfilesResult

    /** The bound transport generation is unavailable or changed during discovery. */
    public data object TransportUnavailable : StreamProfilesResult

    /** Profile discovery is unavailable for the current connection. */
    public data object NotSupported : StreamProfilesResult
}

private const val STREAM_PROFILE_UUID_LENGTH = 32

private fun isLowercaseHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f'
