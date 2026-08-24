@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import at.bernhardberger.tvheadend.sdk.playback.DEFAULT_RECORDING_READ_AHEAD_BYTES
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.createRecordingFileReader
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking

/** Scheme of the opaque URI that identifies one TVHeadend recording to Media3. */
internal const val RECORDING_URI_SCHEME: String = "tvheadend-recording"

/**
 * Media3 failure raised for one classified recording file failure.
 *
 * The message carries only [failure], never a server path, endpoint, or raw server error.
 */
@SubscriptionInfrastructureApi
public class TvheadendRecordingException(
    public val failure: RecordingFileFailure,
) : IOException("Recording file operation failed: $failure")

/** Builds the Media3 item that addresses the stored file of [recordingId]. */
@SubscriptionInfrastructureApi
@androidx.media3.common.util.UnstableApi
public fun tvheadendRecordingMediaItem(recordingId: RecordingId): MediaItem =
    MediaItem.Builder()
        .setMediaId(recordingUri(recordingId))
        .setUri(recordingUri(recordingId))
        .build()

/**
 * Creates a Media3 data source factory that serves recordings through [recordings].
 *
 * Every created data source owns its own reader, so Media3 may create as many as it needs.
 * [readAheadBytes] bounds each transport round trip and must be at most 16 MiB.
 */
@SubscriptionInfrastructureApi
@androidx.media3.common.util.UnstableApi
public fun createTvheadendRecordingDataSourceFactory(
    recordings: RecordingFileOpener,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
): DataSource.Factory = DataSource.Factory {
    TvheadendRecordingDataSource(createRecordingFileReader(recordings, readAheadBytes))
}

/**
 * Creates a pull-based Media3 source for one completed recording.
 *
 * Playback uses the standard progressive pipeline, so the stored MKV, MP4, or TS container is
 * parsed by Media3's own extractors. Each extractor reopen performs a fresh transport open and
 * seek rather than reusing a stale server handle. Below HTSP v27, TVHeadend increments play count
 * on a plain file close, so this lower-level source cannot provide coordinated watched semantics;
 * callers needing that invariant must require the session's supported recording-progress
 * capability before opening it.
 */
@SubscriptionInfrastructureApi
@androidx.media3.common.util.UnstableApi
public fun createTvheadendRecordingMediaSource(
    recordings: RecordingFileOpener,
    recordingId: RecordingId,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
): MediaSource = ProgressiveMediaSource.Factory(
    createTvheadendRecordingDataSourceFactory(recordings, readAheadBytes),
    DefaultExtractorsFactory(),
).createMediaSource(tvheadendRecordingMediaItem(recordingId))

internal fun recordingUri(recordingId: RecordingId): String =
    "$RECORDING_URI_SCHEME://${recordingId.value}"

/** Parses [uri] back into a recording identifier, or returns null when it addresses something else. */
internal fun parseRecordingId(uri: String): RecordingId? {
    val prefix = "$RECORDING_URI_SCHEME://"
    if (!uri.startsWith(prefix)) return null
    val digits = uri.substring(prefix.length)
    if (digits.isEmpty() || digits.any { character -> character !in '0'..'9' }) return null
    val value = digits.toLongOrNull() ?: return null
    return if (value <= MAX_RECORDING_ID_VALUE) RecordingId(value) else null
}

/**
 * Media3 data source that pulls one recording over the SDK's suspend recording transport.
 *
 * Media3's data source callbacks are synchronous, so each one blocks its own loader thread around
 * exactly one suspend call. That boundary is legitimate only off the main thread, which every
 * entry point asserts. Cancellation interrupts the loader thread, so the interrupt flag is cleared
 * for the duration of the call and restored afterwards; otherwise a cancelled reopen or close
 * would abandon its transport round trip and leak a server-side handle.
 */
private class TvheadendRecordingDataSource(
    private val reader: RecordingFileReader,
) : BaseDataSource(true) {
    private var openUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        checkLoaderThread()
        transferInitializing(dataSpec)
        val recordingId = parseRecordingId(dataSpec.uri.toString())
            ?: throw TvheadendRecordingException(RecordingFileFailure.FILE_UNAVAILABLE)
        val length = dataSpec.length.takeIf { value -> value != C.LENGTH_UNSET.toLong() }
        val resolved = blockingIo { reader.open(recordingId, dataSpec.position, length) }.orThrow()
        openUri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return resolved ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkLoaderThread()
        val read = blockingIo { reader.read(buffer, offset, length) }.orThrow()
        if (read == RECORDING_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openUri

    /**
     * Releases the handle exactly once.
     *
     * A classified close failure is not actionable and must not mask the error that is already
     * unwinding, so it is dropped while the local state is still released. A close that cannot
     * reach the server at all still surfaces as an [IOException], which is the declared contract
     * and which Media3 ignores while unwinding a load.
     */
    override fun close() {
        checkLoaderThread()
        val wasOpen = opened
        opened = false
        openUri = null
        blockingIo { reader.close() }
        if (wasOpen) transferEnded()
    }
}

private fun <T> RecordingFileResult<T>.orThrow(): T = when (this) {
    is RecordingFileResult.Ok -> value
    is RecordingFileResult.Failed -> throw TvheadendRecordingException(failure)
}

/**
 * Runs one suspend transport call to completion on the calling loader thread.
 *
 * Media3 cancels a load by interrupting this thread, so the interrupt flag is cleared for the
 * duration of the round trip and restored afterwards; an interrupt that lands mid-call becomes an
 * [InterruptedIOException] with the flag re-armed.
 *
 * The protocol layer signals a superseded connection generation by cancelling its own coroutine.
 * That cancellation cannot be Media3's, because [runBlocking] roots a fresh job here and Media3
 * never cancels it. Reporting it as a classified changed connection keeps a reconnect
 * distinguishable from an unreadable file and lets Media3's own retry policy reopen the recording
 * on the replacement generation, instead of surfacing an opaque unexpected loader failure.
 */
private fun <T> blockingIo(body: suspend () -> T): T {
    val wasInterrupted = Thread.interrupted()
    try {
        return runBlocking { body() }
    } catch (interrupt: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException().apply { initCause(interrupt) }
    } catch (cancellation: CancellationException) {
        throw TvheadendRecordingException(RecordingFileFailure.CONNECTION_CHANGED)
            .apply { initCause(cancellation) }
    } finally {
        if (wasInterrupted) Thread.currentThread().interrupt()
    }
}

private fun checkLoaderThread() {
    check(Looper.getMainLooper()?.thread !== Thread.currentThread()) {
        "Recording playback must not block the main thread"
    }
}

private const val MAX_RECORDING_ID_VALUE: Long = 0xffff_ffffL
