@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-controlled file of a recording that is still being written, served to playback through
 * [FakeTvheadendSession.scriptGrowingRecordingPlayback].
 *
 * The test grows the file with [append], ends it with [finish], stops delivery to the readers
 * that are open with [interrupt], and ends the file's continuity with [retire]. Each call takes
 * effect before it returns: a read waiting at the end of the file resumes at once with the new
 * bytes, the end of input or the failure. Nothing depends on time, so a test needs no clock to
 * drive this fake. A read that reaches the end of the file waits until the next call;
 * [waitingReads] counts those reads, so a test can tell when playback has consumed everything.
 *
 * The DVR entry's state is separate: the test publishes the recording's ended entry through
 * the session observation itself, as the server would after [finish].
 *
 * Readers follow [GrowingRecordingFileReader]: they read sequentially, seek within the current
 * file, report [RECORDING_END_OF_INPUT] only at the end of a finished file, become final when a
 * size refresh or such a read happens after [finish], and surface a pending thread interrupt as
 * [InterruptedException] without clearing it. The fake is safe to use from playback's loader
 * threads and the test thread at the same time.
 */
@FakePlaybackApi
@ExperimentalTvheadendApi
public class FakeGrowingRecording(initialBytes: ByteArray = ByteArray(0)) {
    private val lock = Any()
    private var content = initialBytes.copyOf()
    private var size = initialBytes.size
    private var finished = false
    private var retiredFailure: TvheadendFailure? = null
    private val readers = mutableSetOf<Reader>()
    private var wakeUp = CompletableDeferred<Unit>()
    private val waiting = MutableStateFlow(0)

    /** Size of the file written so far, in bytes. */
    public val sizeBytes: Long
        get() = synchronized(lock) { size.toLong() }

    /** Number of readers playback holds open. */
    public val openReaders: Int
        get() = synchronized(lock) { readers.size }

    /** Number of reads waiting at the end of the file for [append], [finish], [interrupt] or [retire]. */
    public val waitingReads: StateFlow<Int> = waiting.asStateFlow()

    /** Whether the file's continuity still holds, which is false after [retire]. */
    public val isCurrent: Boolean
        get() = synchronized(lock) { retiredFailure == null }

    /** Appends [bytes] to the file and resumes the reads waiting at its end. */
    public fun append(bytes: ByteArray) {
        synchronized(lock) {
            check(!finished) { "A finished recording cannot grow" }
            check(retiredFailure == null) { "A retired recording cannot grow" }
            if (size + bytes.size > content.size) {
                content = content.copyOf(maxOf(size + bytes.size, content.size * 2))
            }
            bytes.copyInto(content, size)
            size += bytes.size
            wakeLocked()
        }
    }

    /** Ends the file: reads past its last byte now report [RECORDING_END_OF_INPUT]. */
    public fun finish() {
        synchronized(lock) {
            finished = true
            wakeLocked()
        }
    }

    /**
     * Stops delivery to the readers open now: each of their pending and later calls fails with
     * [failure]. Readers opened afterwards read normally, as after a server recovers.
     */
    public fun interrupt(failure: TvheadendFailure = TvheadendFailure.TIMEOUT) {
        require(failure in DELIVERY_FAILURES) { "A growing reader fails only with $DELIVERY_FAILURES" }
        synchronized(lock) {
            readers.forEach { it.failure = failure }
            wakeLocked()
        }
    }

    /**
     * Ends the file's continuity for good, as a replaced file or a lost connection does: the
     * lease stops being current, opens fail and every open reader fails with [failure].
     */
    public fun retire(failure: TvheadendFailure = TvheadendFailure.CONNECTION_CHANGED) {
        require(failure in RETIREMENT_FAILURES) { "A lease retires only with $RETIREMENT_FAILURES" }
        synchronized(lock) {
            if (retiredFailure == null) retiredFailure = failure
            readers.forEach { it.failure = it.failure ?: failure }
            wakeLocked()
        }
    }

    internal val lease: GrowingRecordingFileLease = object : GrowingRecordingFileLease {
        override val isCurrent: Boolean
            get() = this@FakeGrowingRecording.isCurrent

        override suspend fun open(position: Long): RecordingFileResult<GrowingRecordingFileReader> =
            synchronized(lock) {
                retiredFailure?.let { return@synchronized RecordingFileResult.Failed(it) }
                if (position !in 0..size) {
                    return@synchronized RecordingFileResult.Failed(TvheadendFailure.FILE_UNAVAILABLE)
                }
                RecordingFileResult.Ok(Reader(position.toInt(), size.toLong()).also(readers::add))
            }

        override fun toString(): String = "FakeGrowingRecording.lease"
    }

    private fun wakeLocked() {
        // Every waiting read is released here, so the count drops before the call returns.
        wakeUp.complete(Unit)
        wakeUp = CompletableDeferred()
        waiting.value = 0
    }

    private inner class Reader(private var position: Int, override val sizeBytes: Long?) :
        GrowingRecordingFileReader {
        var failure: TvheadendFailure? = null
        private var closed = false
        private var final = false

        override val isFinal: Boolean
            get() = synchronized(lock) { final }

        override suspend fun refreshSize(): RecordingFileResult<Long?> = synchronized(lock) {
            unusableLocked()?.let { return@synchronized RecordingFileResult.Failed(it) }
            if (finished) final = true
            RecordingFileResult.Ok(size.toLong())
        }

        override suspend fun seek(position: Long): RecordingFileResult<Unit> = synchronized(lock) {
            unusableLocked()?.let { return@synchronized RecordingFileResult.Failed(it) }
            if (position !in 0..size) {
                return@synchronized RecordingFileResult.Failed(TvheadendFailure.FILE_UNAVAILABLE)
            }
            this.position = position.toInt()
            RecordingFileResult.Ok(Unit)
        }

        override suspend fun read(destination: ByteArray, destinationOffset: Int, length: Int): RecordingFileResult<Int> {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (length !in 1..MAX_RECORDING_READ_BYTES || destinationOffset < 0 ||
                destinationOffset > destination.size - length
            ) {
                return RecordingFileResult.Failed(TvheadendFailure.FILE_UNAVAILABLE)
            }
            while (true) {
                val wait = synchronized(lock) {
                    unusableLocked()?.let { return RecordingFileResult.Failed(it) }
                    val available = size - position
                    if (available > 0) {
                        val count = minOf(length, available)
                        content.copyInto(destination, destinationOffset, position, position + count)
                        position += count
                        return RecordingFileResult.Ok(count)
                    }
                    if (finished) {
                        final = true
                        return RecordingFileResult.Ok(RECORDING_END_OF_INPUT)
                    }
                    waiting.value += 1
                    wakeUp
                }
                try {
                    wait.await()
                } finally {
                    // A read cancelled before its wake-up still counts until it leaves.
                    synchronized(lock) { if (wait === wakeUp) waiting.value -= 1 }
                }
            }
        }

        override suspend fun close(): RecordingFileResult<Unit> = synchronized(lock) {
            closed = true
            readers.remove(this)
            RecordingFileResult.Ok(Unit)
        }

        private fun unusableLocked(): TvheadendFailure? =
            if (closed) TvheadendFailure.FILE_UNAVAILABLE else failure

        override fun toString(): String = "FakeGrowingRecording.Reader"
    }

    override fun toString(): String = "FakeGrowingRecording"

    private companion object {
        val DELIVERY_FAILURES = setOf(
            TvheadendFailure.TIMEOUT,
            TvheadendFailure.FILE_UNAVAILABLE,
            TvheadendFailure.CONNECTION_CHANGED,
        )
        val RETIREMENT_FAILURES = setOf(TvheadendFailure.CONNECTION_CHANGED, TvheadendFailure.FILE_UNAVAILABLE)
    }
}
