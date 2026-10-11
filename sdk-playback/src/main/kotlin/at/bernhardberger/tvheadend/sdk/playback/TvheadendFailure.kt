package at.bernhardberger.tvheadend.sdk.playback

/**
 * Safe server or transport failure shared by SDK operations.
 *
 * Values are SDK-owned singletons, not an exhaustive enum. Compare known values or use the
 * predicates, and always retain a fallback for future values. Operation-specific recovery and
 * whether a change may have been applied belong to the operation's result, not this failure.
 * Transient means a later attempt may succeed without a configuration change; transient is not
 * permission to retry automatically.
 */
public class TvheadendFailure private constructor(
    private val label: String,
    /** A later attempt may succeed without a configuration change; not automatic retry permission. */
    public val isTransient: Boolean = false,
    /** A new current-generation observation is needed before another attempt. */
    public val requiresFreshObservation: Boolean = false,
    /** The account lacks the required right. */
    public val isAccessRelated: Boolean = false,
    /** The server, protocol, or current transport state cannot support the operation. */
    public val isUnsupported: Boolean = false,
) {
    override fun toString(): String = label

    /** Known safe failures; consumers must retain a fallback for future values. */
    public companion object {
        /** The session is not ready for this operation. */
        @JvmField
        public val NOT_READY: TvheadendFailure = TvheadendFailure("NOT_READY", isTransient = true)

        /** The captured observation's generation is no longer current. */
        @JvmField
        public val OBSERVATION_EXPIRED: TvheadendFailure = TvheadendFailure(
            "OBSERVATION_EXPIRED", isTransient = true, requiresFreshObservation = true,
        )

        /** The connection generation changed during the operation. */
        @JvmField
        public val CONNECTION_CHANGED: TvheadendFailure = TvheadendFailure(
            "CONNECTION_CHANGED", isTransient = true, requiresFreshObservation = true,
        )

        /** The server refused the request. */
        @JvmField
        public val SERVER_REJECTED: TvheadendFailure = TvheadendFailure("SERVER_REJECTED")

        /** The reply could not be interpreted; this implies neither transport loss nor retry. */
        @JvmField
        public val MALFORMED_RESPONSE: TvheadendFailure = TvheadendFailure("MALFORMED_RESPONSE")

        /** The account lacks the required right. */
        @JvmField
        public val ACCESS_DENIED: TvheadendFailure = TvheadendFailure("ACCESS_DENIED", isAccessRelated = true)

        /** The server's connection or user limit was reached. */
        @JvmField
        public val CONNECTION_LIMIT: TvheadendFailure = TvheadendFailure("CONNECTION_LIMIT", isTransient = true)

        /** No reply arrived in time. */
        @JvmField
        public val TIMEOUT: TvheadendFailure = TvheadendFailure("TIMEOUT", isTransient = true)

        /** The connection failed or closed. */
        @JvmField
        public val TRANSPORT_UNAVAILABLE: TvheadendFailure = TvheadendFailure("TRANSPORT_UNAVAILABLE", isTransient = true)

        /** The server, protocol version, or transport's current state cannot support the operation. */
        @JvmField
        public val NOT_SUPPORTED: TvheadendFailure = TvheadendFailure("NOT_SUPPORTED", isUnsupported = true)

        /** The recording or artwork file is gone, unreadable, or served inconsistently. */
        @JvmField
        public val FILE_UNAVAILABLE: TvheadendFailure = TvheadendFailure("FILE_UNAVAILABLE")
    }
}
