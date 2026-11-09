@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.testing.FakePlaybackApi::class,
)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RECORDING_END_OF_INPUT
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FakeGrowingRecordingTest {
    @Test
    fun `a read at the end of the file waits for growth and ends only after finish`() = runTest {
        val recording = FakeGrowingRecording(byteArrayOf(1, 2))
        val reader = recording.open()
        val buffer = ByteArray(4)
        assertEquals(2, reader.readOk(buffer))

        val pending = async { reader.readOk(buffer) }
        runCurrent()
        assertEquals(1, recording.waitingReads.value)
        recording.append(byteArrayOf(3))
        // Growth releases the read before append returns, not when the reader next runs.
        assertEquals(0, recording.waitingReads.value)
        assertEquals(1, pending.await())
        assertEquals(3, buffer[0].toInt())

        val ending = async { reader.readOk(buffer) }
        runCurrent()
        assertFalse(reader.isFinal)
        recording.finish()
        assertEquals(RECORDING_END_OF_INPUT, ending.await())
        assertTrue(reader.isFinal)
        assertEquals(3L, recording.sizeBytes)
    }

    @Test
    fun `interrupt fails only open readers and retire ends the lease`() = runTest {
        val recording = FakeGrowingRecording(byteArrayOf(1))
        val stopped = recording.open(position = 1)
        val pending = async { stopped.read(ByteArray(4), 0, 4) }
        runCurrent()
        recording.interrupt()
        assertEquals(RecordingFileResult.Failed(TvheadendFailure.TIMEOUT), pending.await())
        assertEquals(RecordingFileResult.Failed(TvheadendFailure.TIMEOUT), stopped.refreshSize())

        val reopened = recording.open(position = 1)
        recording.append(byteArrayOf(2))
        assertEquals(1, reopened.readOk(ByteArray(4)))

        recording.retire()
        assertFalse(recording.lease.isCurrent)
        assertEquals(RecordingFileResult.Failed(TvheadendFailure.CONNECTION_CHANGED), reopened.seek(0))
        assertEquals(RecordingFileResult.Failed(TvheadendFailure.CONNECTION_CHANGED), recording.lease.open(0))
        assertEquals(2, recording.openReaders)
        assertTrue(reopened.close() is RecordingFileResult.Ok)
        assertTrue(stopped.close() is RecordingFileResult.Ok)
        assertEquals(0, recording.openReaders)
    }

    @Test
    fun `a pending thread interrupt surfaces without being cleared`() {
        val reader = startNow { FakeGrowingRecording(byteArrayOf(1)).open() }.getOrThrow()
        Thread.currentThread().interrupt()
        try {
            val read = startNow { reader.read(ByteArray(1), 0, 1) }
            assertTrue(read.exceptionOrNull() is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}

/** Runs [block] on the calling thread without an event loop that would observe the interrupt itself. */
private fun <T> startNow(block: suspend () -> T): Result<T> {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "The block suspended" }
}

private suspend fun FakeGrowingRecording.open(position: Long = 0): GrowingRecordingFileReader =
    (lease.open(position) as RecordingFileResult.Ok).value

private suspend fun GrowingRecordingFileReader.readOk(buffer: ByteArray): Int =
    (read(buffer, 0, buffer.size) as RecordingFileResult.Ok).value
