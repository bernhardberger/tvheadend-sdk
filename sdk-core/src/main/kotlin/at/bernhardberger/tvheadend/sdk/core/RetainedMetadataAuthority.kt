package at.bernhardberger.tvheadend.sdk.core

/**
 * Authority of repository metadata exposed through a retained display projection.
 *
 * This describes data provenance and synchronization only. It does not authorize mutations or
 * imply consumer policy such as selectability, action availability, or retry behavior.
 */
public enum class RetainedMetadataAuthority {
    /** No current or retained data is available. */
    ABSENT,

    /** Synchronization is in progress without retained data. */
    SYNCHRONIZING_WITHOUT_RETAINED_DATA,

    /** Synchronization is in progress while prior data remains available. */
    SYNCHRONIZING_WITH_RETAINED_DATA,

    /** Data is current for its repository generation; only `currentSession` proves active authority. */
    CURRENT,

    /** Data belongs to an inactive connection generation. */
    STALE,
}
