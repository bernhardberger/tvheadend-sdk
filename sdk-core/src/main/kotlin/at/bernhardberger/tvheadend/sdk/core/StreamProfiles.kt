package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.Collections

/** Canonical lowercase 128-bit Tvheadend stream-profile UUID. */
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

    /**
     * Profile discovery failed. Access denial means the session cannot discover profiles;
     * unsupported means discovery is unavailable on this connection. A malformed reply implies
     * neither transport loss nor automatic retry. [TvheadendFailure.CONNECTION_CHANGED] means
     * the generation changed during discovery; [TvheadendFailure.OBSERVATION_EXPIRED] means the
     * originating observation was already retired before dispatch.
     */
    public data class Failed(public val failure: TvheadendFailure) : StreamProfilesResult {
        override fun toString(): String = "StreamProfilesResult.Failed(failure=$failure)"
    }
}

private const val STREAM_PROFILE_UUID_LENGTH = 32

private fun isLowercaseHexDigit(character: Char): Boolean =
    character in '0'..'9' || character in 'a'..'f'
