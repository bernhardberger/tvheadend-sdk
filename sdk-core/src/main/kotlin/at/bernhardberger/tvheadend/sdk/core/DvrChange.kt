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

/** Server-observed DVR lifecycle changes. Consumers must retain a fallback for future values. */
public class DvrChangeKind private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known lifecycle changes. */
    public companion object {
        /** A recording was scheduled. */
        @JvmField
        public val SCHEDULED: DvrChangeKind = DvrChangeKind("SCHEDULED")
        /** Recording started. */
        @JvmField
        public val RECORDING_STARTED: DvrChangeKind = DvrChangeKind("RECORDING_STARTED")
        /** Recording completed. */
        @JvmField
        public val RECORDING_COMPLETED: DvrChangeKind = DvrChangeKind("RECORDING_COMPLETED")
        /** Recording stopped. */
        @JvmField
        public val RECORDING_STOPPED: DvrChangeKind = DvrChangeKind("RECORDING_STOPPED")
        /** Recording was aborted. */
        @JvmField
        public val RECORDING_ABORTED: DvrChangeKind = DvrChangeKind("RECORDING_ABORTED")
        /** Recording failed. */
        @JvmField
        public val RECORDING_FAILED: DvrChangeKind = DvrChangeKind("RECORDING_FAILED")
        /** Recording was missed. */
        @JvmField
        public val MISSED: DvrChangeKind = DvrChangeKind("MISSED")
        /** The entry was removed. */
        @JvmField
        public val REMOVED: DvrChangeKind = DvrChangeKind("REMOVED")
    }
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
