package at.bernhardberger.tvheadend.sdk.playback

/**
 * Relative server-side priority of one live subscription when tuners are contended.
 *
 * The SDK maps each value to a server subscription weight and never decides when to change it;
 * that policy belongs to the application. A server stream profile configured to force its own
 * priority ignores the requested weight, so [YIELD] has no effect for such profiles even though
 * the request is accepted.
 */
public enum class LiveSubscriptionPriority {
    /** Uses the stream profile's default weight, identical to subscribing without a priority. */
    NORMAL,

    /**
     * Uses the lowest normal server weight so other subscriptions may take this tuner.
     *
     * The server may stop this subscription when a higher-weight subscription needs its tuner.
     */
    YIELD,
}
