package at.bernhardberger.tvheadend.sdk.core

/** A meaningful change to one authoritative DVR entry, not a replacement for snapshots. */
public class DvrChange private constructor(
    public val kind: DvrChangeKind,
    public val previous: DvrEntry?,
    public val current: DvrEntry?,
    public val origin: DvrChangeOrigin,
    public val generationIdentity: SessionGenerationIdentity,
) {
    override fun toString(): String = "DvrChange(<redacted>)"

    public companion object {
        /** Creates a change for application fakes. */
        public fun create(
            kind: DvrChangeKind,
            previous: DvrEntry?,
            current: DvrEntry?,
            origin: DvrChangeOrigin,
            generationIdentity: SessionGenerationIdentity,
        ): DvrChange = DvrChange(kind, previous, current, origin, generationIdentity)
    }
}

/** Server-observed DVR lifecycle changes. */
public enum class DvrChangeKind {
    SCHEDULED,
    RECORDING_STARTED,
    RECORDING_COMPLETED,
    RECORDING_STOPPED,
    RECORDING_ABORTED,
    RECORDING_FAILED,
    MISSED,
    REMOVED,
}

/** Entry commands eligible for best-effort change attribution. */
public enum class DvrMutationKind {
    SCHEDULE,
    UPDATE,
    STOP,
    CANCEL,
    DELETE,
}

/** Best-effort correlation, not proof of which client caused a server event. */
public sealed interface DvrChangeOrigin {
    /** No matching command from this SDK session. */
    public data object External : DvrChangeOrigin

    /** A matching command was sent by this SDK session. */
    public data class ThisClient(public val mutation: DvrMutationKind) : DvrChangeOrigin
}
