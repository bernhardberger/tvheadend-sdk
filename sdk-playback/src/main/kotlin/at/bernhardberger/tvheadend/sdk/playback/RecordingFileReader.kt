@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Largest byte count a single recording file read may request.
 *
 * The transport rejects anything larger, so a read-ahead window is capped at this value.
 */
@SubscriptionInfrastructureApi
public const val MAX_RECORDING_READ_BYTES: Int = 16 * 1024 * 1024

/** Read-ahead window applied when a caller does not choose one. */
@SubscriptionInfrastructureApi
public const val DEFAULT_RECORDING_READ_AHEAD_BYTES: Int = 256 * 1024

/** Value returned by [RecordingFileReader.read] once the readable range is exhausted. */
@SubscriptionInfrastructureApi
public const val RECORDING_END_OF_INPUT: Int = -1

/**
 * Sequential, bounded read-ahead reader over one recording file at a time.
 *
 * Each [open] performs a fresh transport open followed by a seek, so a repositioned consumer never
 * reuses a stale handle. A reader is not safe for concurrent use: [open], [read], and [close] must
 * be called from one sequential consumer.
 */
@SubscriptionInfrastructureApi
public interface RecordingFileReader {
    /**
     * Opens [recordingId] and positions it at absolute [position].
     *
     * A non-null [length] requests exactly that many readable bytes; null leaves the readable
     * range open ended. The result is the resolved readable byte count, or null when the server
     * did not report a size and the caller did not bound the request. A bounded request that the
     * reported size cannot satisfy fails with [RecordingFileFailure.FILE_UNAVAILABLE], as does a
     * [position] beyond the reported size.
     *
     * A handle left open by a previous [open] is released first, so a consumer that skips [close]
     * cannot leak a server-side handle.
     */
    public suspend fun open(
        recordingId: RecordingId,
        position: Long,
        length: Long?,
    ): RecordingFileResult<Long?>

    /**
     * Reads into [destination] and returns the byte count copied.
     *
     * Returns [RECORDING_END_OF_INPUT] once the resolved readable range is exhausted. A request
     * larger than the read-ahead window is served directly without an intermediate copy; a smaller
     * one is served from the window. A read is never larger than [MAX_RECORDING_READ_BYTES].
     */
    public suspend fun read(
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int>

    /**
     * Releases the open handle and resets this reader for reuse.
     *
     * The transport round trip runs uninterruptibly so a cancelled consumer still releases the
     * server-side handle; the caller's own cancellation is rethrown afterwards.
     */
    public suspend fun close(): RecordingFileResult<Unit>
}

/**
 * Creates a reader that reads ahead at most [readAheadBytes] per transport round trip.
 *
 * [readAheadBytes] must be positive and at most [MAX_RECORDING_READ_BYTES].
 */
@SubscriptionInfrastructureApi
public fun createRecordingFileReader(
    opener: RecordingFileOpener,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
): RecordingFileReader = BufferedRecordingFileReader(opener, readAheadBytes)

private class BufferedRecordingFileReader(
    private val opener: RecordingFileOpener,
    private val readAheadBytes: Int,
) : RecordingFileReader {
    init {
        require(readAheadBytes in 1..MAX_RECORDING_READ_BYTES) {
            "Recording read-ahead must be within 1..$MAX_RECORDING_READ_BYTES bytes"
        }
    }

    private var file: RecordingFile? = null
    private var position: Long = 0L
    private var remaining: Long? = null
    private var buffer: ByteArray? = null
    private var bufferOffset: Int = 0
    private var bufferLength: Int = 0

    override suspend fun open(
        recordingId: RecordingId,
        position: Long,
        length: Long?,
    ): RecordingFileResult<Long?> {
        require(position >= 0L) { "Recording read position must not be negative" }
        require(length == null || length >= 0L) { "Recording read length must not be negative" }

        releaseHandle()

        val opened =
            when (val opening = opener.openRecording(recordingId)) {
                is RecordingFileResult.Failed -> return opening
                is RecordingFileResult.Ok -> opening.value
            }

        // Every exit that does not retain the handle releases it here, including a thrown
        // cancellation during the validating seek. Otherwise the server keeps that handle until
        // the whole connection goes away.
        var retained = false
        try {
            val size = opened.sizeBytes
            val resolved: Long?
            if (size == null) {
                resolved = length
            } else {
                if (size < 0L || position > size) {
                    return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
                }
                val available = size - position
                if (length != null && length > available) {
                    return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
                }
                resolved = length ?: available
            }

            when (val seek = opened.seek(position)) {
                is RecordingFileResult.Failed -> return seek
                is RecordingFileResult.Ok ->
                    if (seek.value != position) {
                        return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
                    }
            }

            file = opened
            this.position = position
            remaining = resolved
            bufferOffset = 0
            bufferLength = 0
            retained = true
            return RecordingFileResult.Ok(resolved)
        } finally {
            if (!retained) opened.closeQuietly()
        }
    }

    override suspend fun read(
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): RecordingFileResult<Int> {
        require(destinationOffset in 0..destination.size) {
            "Destination offset must lie inside the destination array"
        }
        require(length in 0..(destination.size - destinationOffset)) {
            "Read window must lie inside the destination array"
        }
        val open = checkNotNull(file) { "Recording file reader is not open" }
        if (length == 0) return RecordingFileResult.Ok(0)

        val buffered = bufferLength - bufferOffset
        if (buffered > 0) {
            val copied = minOf(buffered, length)
            checkNotNull(buffer)
                .copyInto(destination, destinationOffset, bufferOffset, bufferOffset + copied)
            bufferOffset += copied
            return RecordingFileResult.Ok(copied)
        }
        bufferOffset = 0
        bufferLength = 0

        val left = remaining
        if (left != null && left <= 0L) return RecordingFileResult.Ok(RECORDING_END_OF_INPUT)

        var chunk = readAheadBytes
        if (left != null && left < chunk.toLong()) chunk = left.toInt()

        val direct = length >= chunk
        val target = if (direct) destination else buffer ?: ByteArray(readAheadBytes).also { buffer = it }
        val targetOffset = if (direct) destinationOffset else 0

        val read =
            when (val reading = open.read(position, target, targetOffset, chunk)) {
                is RecordingFileResult.Failed -> return reading
                is RecordingFileResult.Ok -> reading.value
            }
        if (read !in 0..chunk) {
            return RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        if (read == 0) {
            // A known readable range that stops early means the file changed under the reader.
            return if (left == null) RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
            else RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }

        position += read
        if (left != null) remaining = left - read
        if (direct) return RecordingFileResult.Ok(read)

        bufferLength = read
        val copied = minOf(read, length)
        target.copyInto(destination, destinationOffset, 0, copied)
        bufferOffset = copied
        return RecordingFileResult.Ok(copied)
    }

    override suspend fun close(): RecordingFileResult<Unit> {
        val open = file
        resetState()
        val result =
            if (open == null) RecordingFileResult.Ok(Unit)
            else withContext(NonCancellable) { open.close() }
        coroutineContext.ensureActive()
        return result
    }

    private suspend fun releaseHandle() {
        val open = file
        resetState()
        open?.closeQuietly()
    }

    private fun resetState() {
        file = null
        position = 0L
        remaining = null
        bufferOffset = 0
        bufferLength = 0
    }

    private suspend fun RecordingFile.closeQuietly() {
        withContext(NonCancellable) { close() }
    }
}
