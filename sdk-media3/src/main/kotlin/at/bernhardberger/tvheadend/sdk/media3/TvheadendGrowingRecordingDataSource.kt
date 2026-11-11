@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.SeekMap
import at.bernhardberger.tvheadend.sdk.playback.DEFAULT_RECORDING_READ_AHEAD_BYTES
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal fun createTvheadendGrowingRecordingDataSourceFactory(
    lease: GrowingRecordingFileLease,
    identity: RecordingMediaIdentity,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
    container: GrowingRecordingContainer<*> = GrowingTsContainer,
    onFinalEnd: () -> Unit = {},
): DataSource.Factory {
    val deliveryUnitBytes = container.deliveryUnitBytes
    require(readAheadBytes in deliveryUnitBytes..MAX_RECORDING_READ_BYTES) {
        "Growing TS read-ahead must be within $deliveryUnitBytes..$MAX_RECORDING_READ_BYTES bytes"
    }
    val alignedCapacity = readAheadBytes - readAheadBytes % deliveryUnitBytes
    val finalEndSignaled = AtomicBoolean()
    return DataSource.Factory {
        TvheadendGrowingRecordingDataSource(
            lease = lease,
            identity = identity,
            deliveryBufferBytes = alignedCapacity,
            deliveryUnitBytes = deliveryUnitBytes,
            onFinalEnd = {
                if (finalEndSignaled.compareAndSet(false, true)) onFinalEnd()
            },
        )
    }
}

internal fun createTvheadendGrowingRecordingMediaSource(
    lease: GrowingRecordingFileLease,
    identity: RecordingMediaIdentity,
    readAheadBytes: Int = DEFAULT_RECORDING_READ_AHEAD_BYTES,
    onSeekMap: (SeekMap) -> Unit = {},
    onFinalEnd: () -> Unit = {},
    probeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    container: GrowingRecordingContainer<*> = GrowingTsContainer,
): MediaSource = ProgressiveMediaSource.Factory(
    createTvheadendGrowingRecordingDataSourceFactory(lease, identity, readAheadBytes, container, onFinalEnd),
    createGrowingRecordingExtractorsFactory(container, onSeekMap, lease, probeDispatcher),
).createMediaSource(tvheadendRecordingMediaItem(identity))

/**
 * Unknown-length Media3 source over P7-F2's target-scoped growing-file continuity lease.
 *
 * A transport read may stop in the middle of a delivery unit while Tvheadend is appending it.
 * Those bytes remain private until the complete container delivery unit is available, so temporary
 * file boundaries never reach Media3's maintained parser as malformed input. Every reopen uses the
 * same lease, so Media3 retry and seek cannot rebind to a new generation or physical file.
 */
private class TvheadendGrowingRecordingDataSource(
    private val lease: GrowingRecordingFileLease,
    private val identity: RecordingMediaIdentity,
    deliveryBufferBytes: Int,
    private val deliveryUnitBytes: Int,
    private val onFinalEnd: () -> Unit,
) : BaseDataSource(true) {
    private val deliveryBuffer = ByteArray(deliveryBufferBytes)
    private var reader: GrowingRecordingFileReader? = null
    private var openUri: Uri? = null
    private var opened = false
    private var deliveryOffset = 0
    private var deliveryLimit = 0
    private var bufferedLimit = 0
    private var openingSkipBytes = 0

    override fun open(dataSpec: DataSpec): Long {
        checkGrowingLoaderThread()
        check(reader == null) { "Growing recording data source is already open" }
        transferInitializing(dataSpec)
        if (dataSpec.uri.toString() != identity.uri) {
            throw TvheadendRecordingException(TvheadendFailure.FILE_UNAVAILABLE)
        }
        if (
            dataSpec.position < 0L ||
            dataSpec.length != C.LENGTH_UNSET.toLong()
        ) {
            throw TvheadendRecordingException(TvheadendFailure.FILE_UNAVAILABLE)
        }
        val openedReader = growingBlockingIo {
            lease.open(dataSpec.position - dataSpec.position % deliveryUnitBytes)
        }.orThrowGrowing()
        reader = openedReader
        openUri = dataSpec.uri
        opened = true
        resetBuffer()
        openingSkipBytes = (dataSpec.position % deliveryUnitBytes).toInt()
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkGrowingLoaderThread()
        if (length == 0) return 0
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Growing recording operation interrupted")
        }
        val activeReader = reader
            ?: throw TvheadendRecordingException(TvheadendFailure.FILE_UNAVAILABLE)
        if (deliveryOffset == deliveryLimit && !fillCompleteUnits(activeReader)) {
            return C.RESULT_END_OF_INPUT
        }
        if (openingSkipBytes != 0) {
            deliveryOffset += openingSkipBytes
            openingSkipBytes = 0
        }
        val copied = minOf(length, deliveryLimit - deliveryOffset)
        deliveryBuffer.copyInto(buffer, offset, deliveryOffset, deliveryOffset + copied)
        deliveryOffset += copied
        bytesTransferred(copied)
        return copied
    }

    override fun getUri(): Uri? = openUri

    override fun close() {
        checkGrowingLoaderThread()
        val activeReader = reader
        val wasOpen = opened
        reader = null
        openUri = null
        opened = false
        resetBuffer()
        try {
            if (activeReader != null) growingCleanupIo { activeReader.close() }
        } finally {
            if (wasOpen) transferEnded()
        }
    }

    private fun fillCompleteUnits(activeReader: GrowingRecordingFileReader): Boolean {
        compactPartialUnit()
        while (deliveryLimit == 0) {
            val writableBytes = deliveryBuffer.size - bufferedLimit
            val read = growingBlockingIo {
                activeReader.read(deliveryBuffer, bufferedLimit, writableBytes)
            }.orThrowGrowing()
            if (read == RECORDING_END_OF_INPUT) {
                if (bufferedLimit != 0) {
                    throw TvheadendRecordingException(TvheadendFailure.FILE_UNAVAILABLE)
                }
                onFinalEnd()
                return false
            }
            if (read !in 1..writableBytes) {
                throw TvheadendRecordingException(TvheadendFailure.FILE_UNAVAILABLE)
            }
            bufferedLimit += read
            deliveryLimit = bufferedLimit - bufferedLimit % deliveryUnitBytes
        }
        return true
    }

    private fun compactPartialUnit() {
        val partialBytes = bufferedLimit - deliveryLimit
        if (partialBytes > 0) {
            deliveryBuffer.copyInto(deliveryBuffer, 0, deliveryLimit, bufferedLimit)
        }
        deliveryOffset = 0
        deliveryLimit = 0
        bufferedLimit = partialBytes
    }

    private fun resetBuffer() {
        deliveryOffset = 0
        deliveryLimit = 0
        bufferedLimit = 0
    }
}

private fun <T> RecordingFileResult<T>.orThrowGrowing(): T = when (this) {
    is RecordingFileResult.Ok -> value
    is RecordingFileResult.Failed -> throw TvheadendRecordingException(failure)
}

/** Media3's synchronous loader callback must preserve a pending cancellation interrupt. */
private fun <T> growingBlockingIo(body: suspend () -> T): T {
    if (Thread.currentThread().isInterrupted) {
        throw InterruptedIOException("Growing recording operation interrupted")
    }
    try {
        return runBlocking { body() }
    } catch (interrupt: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException().apply { initCause(interrupt) }
    } catch (cancellation: CancellationException) {
        throw TvheadendRecordingException(TvheadendFailure.CONNECTION_CHANGED)
            .apply { initCause(cancellation) }
    }
}

/** Cleanup must complete even when Media3 reaches close with its loader interrupt still armed. */
private fun <T> growingCleanupIo(body: suspend () -> T): T {
    val wasInterrupted = Thread.interrupted()
    try {
        return runBlocking { body() }
    } catch (interrupt: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException().apply { initCause(interrupt) }
    } catch (cancellation: CancellationException) {
        throw TvheadendRecordingException(TvheadendFailure.CONNECTION_CHANGED)
            .apply { initCause(cancellation) }
    } finally {
        if (wasInterrupted) Thread.currentThread().interrupt()
    }
}

private fun checkGrowingLoaderThread() {
    check(Looper.getMainLooper()?.thread !== Thread.currentThread()) {
        "Growing recording playback must not block the main thread"
    }
}
