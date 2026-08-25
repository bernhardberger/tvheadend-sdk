@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvheadendGrowingRecordingDataSourceTest {
    @Test
    fun `open is unknown length and partial transport bytes wait for a complete TS packet`() {
        val opener = ScriptedGrowingOpener(
            ReadStep.Bytes(ByteArray(100) { 1 }),
            ReadStep.Bytes(ByteArray(88) { 2 }),
            ReadStep.End,
        )
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES * 2)

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(recordingSpec(position = 0L)))
        assertEquals(listOf(0L), opener.openPositions)
        val destination = ByteArray(GROWING_TS_PACKET_BYTES)
        assertEquals(50, source.read(destination, 0, 50))
        assertEquals(2, opener.readCalls, "No partial packet may be delivered after the first read")
        assertEquals(GROWING_TS_PACKET_BYTES - 50, source.read(destination, 50, destination.size - 50))
        assertArrayEquals(ByteArray(100) { 1 } + ByteArray(88) { 2 }, destination)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))

        source.close()
        assertEquals(1, opener.closeCalls)
    }

    @Test
    fun `final end with an incomplete TS packet fails closed`() {
        val opener = ScriptedGrowingOpener(
            ReadStep.Bytes(ByteArray(GROWING_TS_PACKET_BYTES - 1)),
            ReadStep.End,
        )
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES)
        source.open(recordingSpec())

        val failure = assertThrows(TvheadendRecordingException::class.java) {
            source.read(ByteArray(GROWING_TS_PACKET_BYTES), 0, GROWING_TS_PACKET_BYTES)
        }

        assertSame(RecordingFileFailure.FILE_UNAVAILABLE, failure.failure)
        source.close()
    }

    @Test
    fun `every packet-aligned reopen gets a fresh reader at the requested position`() {
        val opener = ScriptedGrowingOpener(ReadStep.End)
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES)

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(recordingSpec(position = 376L)))
        source.close()
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(recordingSpec(position = 752L)))
        source.close()

        assertEquals(listOf(376L, 752L), opener.openPositions)
        assertEquals(2, opener.closeCalls)
    }

    @Test
    fun `non-packet offsets and bounded requests fail before opening transport`() {
        val opener = ScriptedGrowingOpener(ReadStep.End)

        listOf(
            recordingSpec(position = 1L),
            recordingSpec(length = GROWING_TS_PACKET_BYTES.toLong()),
        ).forEach { spec ->
            val failure = assertThrows(TvheadendRecordingException::class.java) {
                dataSource(opener, GROWING_TS_PACKET_BYTES).open(spec)
            }
            assertSame(RecordingFileFailure.FILE_UNAVAILABLE, failure.failure)
        }

        assertTrue(opener.openPositions.isEmpty())
    }

    @Test
    fun `typed open and read failures retain their safe classification`() {
        val denied = ScriptedGrowingOpener(
            ReadStep.End,
            openFailure = RecordingFileFailure.ACCESS_DENIED,
        )
        val openFailure = assertThrows(TvheadendRecordingException::class.java) {
            dataSource(denied, GROWING_TS_PACKET_BYTES).open(recordingSpec())
        }
        assertSame(RecordingFileFailure.ACCESS_DENIED, openFailure.failure)

        val unavailable = ScriptedGrowingOpener(
            ReadStep.Failed(RecordingFileFailure.FILE_UNAVAILABLE),
        )
        val source = dataSource(unavailable, GROWING_TS_PACKET_BYTES)
        source.open(recordingSpec())
        val readFailure = assertThrows(TvheadendRecordingException::class.java) {
            source.read(ByteArray(1), 0, 1)
        }
        assertSame(RecordingFileFailure.FILE_UNAVAILABLE, readFailure.failure)
        source.close()
    }

    @Test
    fun `transport cancellation remains a changed connection`() {
        val opener = ScriptedGrowingOpener(ReadStep.Cancelled)
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES)
        source.open(recordingSpec())

        val failure = assertThrows(TvheadendRecordingException::class.java) {
            source.read(ByteArray(1), 0, 1)
        }

        assertSame(RecordingFileFailure.CONNECTION_CHANGED, failure.failure)
        source.close()
    }

    @Test
    fun `pending loader interrupt prevents read but cleanup consumes and restores it`() {
        val opener = ScriptedGrowingOpener(ReadStep.Bytes(ByteArray(GROWING_TS_PACKET_BYTES)))
        val failure = AtomicReference<Throwable?>()
        val interruptAfterRead = AtomicBoolean(false)
        val interruptAfterClose = AtomicBoolean(false)
        val thread = Thread {
            val source = dataSource(opener, GROWING_TS_PACKET_BYTES)
            try {
                source.open(recordingSpec())
                Thread.currentThread().interrupt()
                try {
                    source.read(ByteArray(1), 0, 1)
                } catch (caught: Throwable) {
                    failure.set(caught)
                    interruptAfterRead.set(Thread.currentThread().isInterrupted)
                }
                source.close()
                interruptAfterClose.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }

        thread.start()
        thread.join(5_000L)

        assertFalse(thread.isAlive, "Interrupted loader did not terminate")
        assertTrue(failure.get() is InterruptedIOException)
        assertTrue(interruptAfterRead.get(), "Read cleared Media3's pending interrupt")
        assertTrue(interruptAfterClose.get(), "Cleanup did not restore the pending interrupt")
        assertEquals(0, opener.readCalls)
        assertEquals(1, opener.closeCalls)
    }

    @Test
    fun `pending interrupt prevents buffered delivery without another transport read`() {
        val opener = ScriptedGrowingOpener(
            ReadStep.Bytes(ByteArray(GROWING_TS_PACKET_BYTES * 2) { 7 }),
        )
        val firstByte = ByteArray(1)
        val bufferedDestination = byteArrayOf(99)
        val failure = AtomicReference<Throwable?>()
        val interruptAfterRead = AtomicBoolean(false)
        val interruptAfterClose = AtomicBoolean(false)
        val thread = Thread {
            val source = dataSource(opener, GROWING_TS_PACKET_BYTES * 2)
            try {
                source.open(recordingSpec())
                source.read(firstByte, 0, firstByte.size)
                Thread.currentThread().interrupt()
                try {
                    source.read(bufferedDestination, 0, bufferedDestination.size)
                } catch (caught: Throwable) {
                    failure.set(caught)
                    interruptAfterRead.set(Thread.currentThread().isInterrupted)
                }
                source.close()
                interruptAfterClose.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }

        thread.start()
        thread.join(5_000L)

        assertFalse(thread.isAlive, "Interrupted loader did not terminate")
        assertArrayEquals(byteArrayOf(7), firstByte)
        assertArrayEquals(byteArrayOf(99), bufferedDestination)
        assertTrue(failure.get() is InterruptedIOException)
        assertTrue(interruptAfterRead.get(), "Buffered read cleared the loader interrupt")
        assertTrue(interruptAfterClose.get(), "Cleanup did not restore the loader interrupt")
        assertEquals(1, opener.readCalls)
        assertEquals(1, opener.closeCalls)
    }

    @Test
    fun `interrupt during a suspended transport read terminates and preserves cleanup`() {
        val opener = BlockingGrowingOpener()
        val failure = AtomicReference<Throwable?>()
        val interruptAfterRead = AtomicBoolean(false)
        val interruptAfterClose = AtomicBoolean(false)
        val thread = Thread {
            val source = dataSource(opener, GROWING_TS_PACKET_BYTES)
            try {
                source.open(recordingSpec())
                try {
                    source.read(ByteArray(1), 0, 1)
                } catch (caught: Throwable) {
                    failure.set(caught)
                    interruptAfterRead.set(Thread.currentThread().isInterrupted)
                }
                source.close()
                interruptAfterClose.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }

        thread.start()
        try {
            assertTrue(opener.readStarted.await(5, TimeUnit.SECONDS), "Transport read did not suspend")
            thread.interrupt()
            thread.join(5_000L)

            assertFalse(thread.isAlive, "Interrupted loader did not terminate")
            assertTrue(failure.get() is InterruptedIOException)
            assertTrue(failure.get()?.cause is InterruptedException)
            assertTrue(interruptAfterRead.get(), "Interrupted wait cleared the loader interrupt")
            assertTrue(interruptAfterClose.get(), "Cleanup did not restore the loader interrupt")
            assertEquals(1, opener.closeCalls)
        } finally {
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(5_000L)
            }
        }
    }

    private fun dataSource(opener: RecordingFileOpener, readAheadBytes: Int): DataSource =
        createTvheadendGrowingRecordingDataSourceFactory(opener, readAheadBytes).createDataSource()

    private fun recordingSpec(
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(recordingUri(RecordingId(7L)))
        .setPosition(position)
        .setLength(length)
        .build()
}

private sealed interface ReadStep {
    class Bytes(val value: ByteArray) : ReadStep

    class Failed(val failure: RecordingFileFailure) : ReadStep

    data object End : ReadStep

    data object Cancelled : ReadStep
}

private class ScriptedGrowingOpener(
    vararg steps: ReadStep,
    private val openFailure: RecordingFileFailure? = null,
) : RecordingFileOpener {
    private val template = steps.toList()
    val openPositions = ArrayList<Long>()
    var readCalls = 0
        private set
    var closeCalls = 0
        private set

    override suspend fun openRecording(recordingId: RecordingId): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)

    override suspend fun openGrowingRecording(
        recordingId: RecordingId,
        position: Long,
    ): RecordingFileResult<GrowingRecordingFileReader> {
        openFailure?.let { failure -> return RecordingFileResult.Failed(failure) }
        openPositions += position
        return RecordingFileResult.Ok(Reader(template.toMutableList()))
    }

    private inner class Reader(
        private val steps: MutableList<ReadStep>,
    ) : GrowingRecordingFileReader {
        override suspend fun read(
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> {
            readCalls += 1
            return when (val step = if (steps.isEmpty()) ReadStep.End else steps.removeAt(0)) {
                is ReadStep.Bytes -> {
                    check(step.value.size <= length)
                    step.value.copyInto(destination, destinationOffset)
                    RecordingFileResult.Ok(step.value.size)
                }
                is ReadStep.Failed -> RecordingFileResult.Failed(step.failure)
                ReadStep.End -> RecordingFileResult.Ok(-1)
                ReadStep.Cancelled -> throw CancellationException("generation replaced")
            }
        }

        override suspend fun close(): RecordingFileResult<Unit> {
            closeCalls += 1
            return RecordingFileResult.Ok(Unit)
        }
    }
}

private class BlockingGrowingOpener : RecordingFileOpener {
    val readStarted = CountDownLatch(1)
    var closeCalls = 0
        private set

    override suspend fun openRecording(recordingId: RecordingId): RecordingFileResult<RecordingFile> =
        RecordingFileResult.Failed(RecordingFileFailure.NOT_SUPPORTED)

    override suspend fun openGrowingRecording(
        recordingId: RecordingId,
        position: Long,
    ): RecordingFileResult<GrowingRecordingFileReader> = RecordingFileResult.Ok(
        object : GrowingRecordingFileReader {
            override suspend fun read(
                destination: ByteArray,
                destinationOffset: Int,
                length: Int,
            ): RecordingFileResult<Int> {
                readStarted.countDown()
                awaitCancellation()
            }

            override suspend fun close(): RecordingFileResult<Unit> {
                closeCalls += 1
                return RecordingFileResult.Ok(Unit)
            }
        },
    )
}
