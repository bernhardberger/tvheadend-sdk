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
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi
import at.bernhardberger.tvheadend.sdk.playback.createRecordingFileReader
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
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

internal class RecordingMediaIdentity {
    internal val uri: String = "$RECORDING_URI_SCHEME://bound/${nextValue.getAndIncrement()}"

    override fun toString(): String = "RecordingMediaIdentity(<redacted>)"

    private companion object {
        val nextValue = AtomicLong()
    }
}

/** Builds the opaque Media3 item for an already-bound recording target. */
internal fun tvheadendRecordingMediaItem(identity: RecordingMediaIdentity): MediaItem =
    MediaItem.Builder()
        .setMediaId(identity.uri)
        .setUri(identity.uri)
        .build()

/**
 * Creates a Media3 data source factory that serves recordings through [recordings].
 *
 * Every created data source owns its own reader, so Media3 may create as many as it needs.
 * [readAheadBytes] bounds each transport round trip and must be at most 16 MiB.
 */
internal fun createTvheadendRecordingDataSourceFactory(
    recordings: RecordingFileOpener,
    identity: RecordingMediaIdentity,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
): DataSource.Factory = DataSource.Factory {
    TvheadendRecordingDataSource(
        createRecordingFileReader(recordings, readAheadBytes),
        identity,
    )
}

/**
 * Creates a pull-based Media3 source for one completed recording.
 *
 * Playback uses the standard progressive pipeline, so the stored MKV, MP4, or TS container is
 * parsed by Media3's own extractors. Each extractor reopen performs a fresh transport open and
 * seek rather than reusing a stale server handle. Below HTSP v27, TVHeadend increments play count
 * on a plain file close. The coordinator still permits completed playback there, but starts over
 * and disables explicit resume and progress reporting.
 */
internal fun createTvheadendRecordingMediaSource(
    recordings: RecordingFileOpener,
    identity: RecordingMediaIdentity,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
): MediaSource = ProgressiveMediaSource.Factory(
    createTvheadendRecordingDataSourceFactory(recordings, identity, readAheadBytes),
    DefaultExtractorsFactory(),
).createMediaSource(tvheadendRecordingMediaItem(identity))

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
    private val identity: RecordingMediaIdentity,
) : BaseDataSource(true) {
    private var openUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        checkLoaderThread()
        transferInitializing(dataSpec)
        if (dataSpec.uri.toString() != identity.uri) {
            throw TvheadendRecordingException(RecordingFileFailure.FILE_UNAVAILABLE)
        }
        val length = dataSpec.length.takeIf { value -> value != C.LENGTH_UNSET.toLong() }
        val resolved = blockingIo { reader.open(dataSpec.position, length) }.orThrow()
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
 * distinguishable from an unreadable file. A Media3 retry reuses the same target-bound opener and
 * therefore fails closed rather than opening the replacement generation.
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
