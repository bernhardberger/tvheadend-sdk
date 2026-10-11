package at.bernhardberger.tvheadend.sdk.playback

/** Typed result of a recording file operation. */
@SubscriptionInfrastructureApi
public sealed interface RecordingFileResult<out T> {
    /** Successful operation value. */
    public class Ok<out T>(public val value: T) : RecordingFileResult<T> {
        override fun toString(): String = "RecordingFileResult.Ok(<redacted>)"
    }

    /**
     * Failed operation carrying only its safe classification.
     *
     * [TvheadendFailure.CONNECTION_CHANGED] means the handle's connection generation is gone;
     * the caller may recover by reopening on a new connection. [TvheadendFailure.FILE_UNAVAILABLE]
     * means this connection cannot serve this file, or served it inconsistently. Access denial
     * means recording file access is missing; unsupported means the server lacks file access.
     * A malformed reply implies neither connection loss nor automatic retry.
     */
    public data class Failed(public val failure: TvheadendFailure) : RecordingFileResult<Nothing> {
        override fun toString(): String = "RecordingFileResult.Failed(failure=$failure)"
    }
}

/**
 * Generation-bound handle to one open recording file.
 *
 * A handle stays valid only while the connection generation that opened it is live; afterwards
 * every operation reports [TvheadendFailure.CONNECTION_CHANGED]. Each read carries its own
 * absolute position so a timed out or retried read can never desynchronise a server-side cursor.
 *
 * Implementations are not safe for concurrent use. One handle serves one sequential reader.
 */
@SubscriptionInfrastructureApi
public interface RecordingFile {
    /** Size in bytes reported when the file was opened, or null when the server omitted it. */
    public val sizeBytes: Long?

    /**
     * Repositions the server-side cursor to absolute [position].
     *
     * The returned value is the position the server reports it reached, which a caller must
     * verify because a server may legitimately land elsewhere.
     */
    public suspend fun seek(position: Long): RecordingFileResult<Long>

    /**
     * Reads at most [length] bytes from absolute [position] into [destination].
     *
     * A successful result is the byte count actually copied, never negative and never larger than
     * [length]. Zero means the server reached end of file. A short non-zero read is normal and is
     * not an error.
     */
    public suspend fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int>

    /** Releases the server-side handle. Closing an already closed handle succeeds. */
    public suspend fun close(): RecordingFileResult<Unit>
}

/**
 * Sequential reader that follows one growing recording file until its final end.
 *
 * A temporary empty transport read is kept inside [read]. It suspends until more bytes arrive,
 * fresh recording metadata proves final end, or a typed failure occurs. Implementations are not
 * safe for concurrent use. The caller owns the reader and must [close] it.
 */
@SubscriptionInfrastructureApi
public interface GrowingRecordingFileReader {
    /** Validated file extent when this reader opened; null if the server omitted its size. */
    public val sizeBytes: Long?

    /** True only after a stat made wholly after completion establishes the final size. */
    public val isFinal: Boolean

    /** Refreshes the validated size on this handle without reading media data. */
    public suspend fun refreshSize(): RecordingFileResult<Long?>

    /** Repositions this sequential reader within its validated extent; no new handle is opened. */
    public suspend fun seek(position: Long): RecordingFileResult<Unit>

    /**
     * Reads into [destination], waiting across temporary end of file when necessary.
     *
     * Returns [RECORDING_END_OF_INPUT] only after fresh current-generation metadata and an
     * open-handle stat corroborate finality. A pending thread interrupt is surfaced as
     * [InterruptedException] without being cleared. One call may request at most
     * [MAX_RECORDING_READ_BYTES].
     */
    public suspend fun read(
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int>

    /** Releases the server-side handle. Closing an already closed reader succeeds. */
    public suspend fun close(): RecordingFileResult<Unit>
}

/**
 * Target-scoped continuity lease for one growing recording file.
 *
 * The lease binds one connection generation, DVR entry incarnation, and physical file identity.
 * Every [open] validates that original binding, so a seek or loader retry cannot follow a
 * reconnect, replacement, rollover, or clone. Once [isCurrent] becomes false it stays false.
 */
@SubscriptionInfrastructureApi
public interface GrowingRecordingFileLease {
    /** Whether the originally bound generation, incarnation, and physical file remain current. */
    public val isCurrent: Boolean

    /**
     * Opens a reader at absolute [position] while preserving this lease's original continuity.
     *
     * The caller owns the returned reader and must close it. A stale lease returns the safe typed
     * failure that invalidated its original target rather than establishing a new target.
     */
    public suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader>
}

/** Opens one already-bound recording without exposing its identity or generation controls. */
@SubscriptionInfrastructureApi
public fun interface RecordingFileOpener {
    /**
     * Opens the stored file selected when this opener was created.
     *
     * The caller owns the returned handle and must close it. When no generation is bound the
     * result is [TvheadendFailure.CONNECTION_CHANGED].
     */
    public suspend fun openRecording(): RecordingFileResult<RecordingFile>
}
