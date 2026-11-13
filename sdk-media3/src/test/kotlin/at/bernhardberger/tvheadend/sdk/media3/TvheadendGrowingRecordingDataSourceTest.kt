@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import at.bernhardberger.tvheadend.sdk.playback.MAX_RECORDING_READ_BYTES
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileReader
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvheadendGrowingRecordingDataSourceTest {
    private val identity = RecordingMediaIdentity()

    @Test
    fun `Matroska opens and delivers bytes without TS packet alignment`() {
        val position = 193L
        val bytes = ByteArray(197) { it.toByte() }
        val lease = ScriptedGrowingLease(ReadStep.Bytes(bytes), ReadStep.End)
        val source = createTvheadendGrowingRecordingDataSourceFactory(
            lease = lease,
            identity = identity,
            readAheadBytes = GROWING_TS_PACKET_BYTES * 2,
            container = GrowingMatroskaContainer,
        ).createDataSource()
        try {
            source.open(recordingSpec(position = position))
            val destination = ByteArray(bytes.size)
            val read = source.read(destination, 0, destination.size)
            assertAll(
                {
                    assertEquals(
                        listOf(position), lease.openPositions, "Matroska must open at the exact requested byte",
                    )
                },
                {
                    assertEquals(
                        bytes.size, read,
                        "Matroska must deliver a non-packet-sized read unchanged",
                    )
                },
                { assertArrayEquals(bytes, destination, "Matroska must not skip opening bytes") },
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `open is unknown length and partial transport bytes wait for a complete TS packet`() {
        val opener = ScriptedGrowingLease(
            ReadStep.Bytes(ByteArray(288) { it.toByte() }),
            ReadStep.Bytes(ByteArray(88) { (it + 288).toByte() }),
            ReadStep.End,
        )
        var finalEnds = 0
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES * 2) { finalEnds += 1 }

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(recordingSpec(position = 0L)))
        assertEquals(listOf(0L), opener.openPositions)
        val destination = ByteArray(GROWING_TS_PACKET_BYTES)
        assertEquals(50, source.read(destination, 0, 50))
        val remainder = ByteArray(376)
        assertEquals(GROWING_TS_PACKET_BYTES - 50, source.read(remainder, 0, remainder.size))
        assertArrayEquals(ByteArray(188) { it.toByte() }, destination.take(50).toByteArray() + remainder.take(138))
        val nextPacket = ByteArray(GROWING_TS_PACKET_BYTES)
        assertEquals(GROWING_TS_PACKET_BYTES, source.read(nextPacket, 0, nextPacket.size))
        assertArrayEquals(ByteArray(188) { (it + 188).toByte() }, nextPacket)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        assertEquals(1, finalEnds)

        source.close()
        assertEquals(1, opener.closeCalls)
    }

    @Test
    fun `final end with an incomplete TS packet fails closed`() {
        val opener = ScriptedGrowingLease(
            ReadStep.Bytes(ByteArray(GROWING_TS_PACKET_BYTES - 1)),
            ReadStep.End,
        )
        var finalEnds = 0
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES) { finalEnds += 1 }
        source.open(recordingSpec())

        val failure = assertThrows(TvheadendRecordingException::class.java) {
            source.read(ByteArray(GROWING_TS_PACKET_BYTES), 0, GROWING_TS_PACKET_BYTES)
        }

        assertSame(TvheadendFailure.FILE_UNAVAILABLE, failure.failure)
        assertEquals(0, finalEnds)
        source.close()
    }

    @Test
    fun `final end callback is shared across every data source from one factory`() {
        val lease = ScriptedGrowingLease(ReadStep.End)
        var finalEnds = 0
        val factory = createTvheadendGrowingRecordingDataSourceFactory(
            lease = lease,
            identity = identity,
            readAheadBytes = GROWING_TS_PACKET_BYTES,
            onFinalEnd = { finalEnds += 1 },
        )

        repeat(2) {
            val source = factory.createDataSource()
            source.open(recordingSpec())
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
            source.close()
        }

        assertEquals(1, finalEnds)
        assertEquals(2, lease.closeCalls)
    }

    @Test
    fun `every packet-aligned reopen gets a fresh reader at the requested position`() {
        val opener = ScriptedGrowingLease(ReadStep.End)
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES)

        assertEquals(C.LENGTH_UNSET.toLong(), source.open(recordingSpec(position = 376L)))
        source.close()
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(recordingSpec(position = 752L)))
        source.close()

        assertEquals(listOf(376L, 752L), opener.openPositions)
        assertEquals(2, opener.closeCalls)
    }

    @Test
    fun `bounded requests fail before opening transport`() {
        val opener = ScriptedGrowingLease(ReadStep.End)

        listOf(
            recordingSpec(length = GROWING_TS_PACKET_BYTES.toLong()),
        ).forEach { spec ->
            val failure = assertThrows(TvheadendRecordingException::class.java) {
                dataSource(opener, GROWING_TS_PACKET_BYTES).open(spec)
            }
            assertSame(TvheadendFailure.FILE_UNAVAILABLE, failure.failure)
        }

        assertTrue(opener.openPositions.isEmpty())
    }

    @Test
    fun `binary seek offsets reopen at packet boundary and omit only the requested prefix`() {
        val packet = ByteArray(GROWING_TS_PACKET_BYTES) { it.toByte() }
        val opener = ScriptedGrowingLease(ReadStep.Bytes(packet), ReadStep.End)
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES)
        source.open(recordingSpec(position = 189L))
        val bytes = ByteArray(187)
        assertEquals(187, source.read(bytes, 0, bytes.size))
        assertArrayEquals(packet.copyOfRange(1, packet.size), bytes)
        assertEquals(listOf(188L), opener.openPositions)
        source.close()
    }

    @Test
    fun `typed open and read failures retain their safe classification`() {
        val denied = ScriptedGrowingLease(
            ReadStep.End,
            openFailure = TvheadendFailure.ACCESS_DENIED,
        )
        val openFailure = assertThrows(TvheadendRecordingException::class.java) {
            dataSource(denied, GROWING_TS_PACKET_BYTES).open(recordingSpec())
        }
        assertSame(TvheadendFailure.ACCESS_DENIED, openFailure.failure)

        val unavailable = ScriptedGrowingLease(
            ReadStep.Failed(TvheadendFailure.FILE_UNAVAILABLE),
        )
        val source = dataSource(unavailable, GROWING_TS_PACKET_BYTES)
        source.open(recordingSpec())
        val readFailure = assertThrows(TvheadendRecordingException::class.java) {
            source.read(ByteArray(1), 0, 1)
        }
        assertSame(TvheadendFailure.FILE_UNAVAILABLE, readFailure.failure)
        source.close()
    }

    @Test
    fun `transport cancellation remains a changed connection`() {
        val opener = ScriptedGrowingLease(ReadStep.Cancelled)
        val source = dataSource(opener, GROWING_TS_PACKET_BYTES)
        source.open(recordingSpec())

        val failure = assertThrows(TvheadendRecordingException::class.java) {
            source.read(ByteArray(1), 0, 1)
        }

        assertSame(TvheadendFailure.CONNECTION_CHANGED, failure.failure)
        source.close()
    }

    @Test
    fun `pending loader interrupt prevents read but cleanup consumes and restores it`() {
        val opener = ScriptedGrowingLease(ReadStep.Bytes(ByteArray(GROWING_TS_PACKET_BYTES)))
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
        val opener = ScriptedGrowingLease(
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
        val opener = BlockingGrowingLease()
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

    @Test
    fun `read ahead accepts endpoints and never requests more than the transport cap`() {
        listOf(GROWING_TS_PACKET_BYTES, MAX_RECORDING_READ_BYTES).forEach { capacity ->
            val lease = ScriptedGrowingLease(
                ReadStep.Bytes(byteArrayOf(1)),
                ReadStep.Bytes(ByteArray(187) { 2 }),
            )
            val source = dataSource(lease, capacity)
            source.open(recordingSpec())
            val packet = ByteArray(188)
            assertEquals(188, source.read(packet, 0, packet.size))
            assertArrayEquals(byteArrayOf(1) + ByteArray(187) { 2 }, packet)
            source.close()
        }
    }

    @Test
    fun `transfer listener observes initialization start delivered bytes and one end`() {
        val source = dataSource(
            ScriptedGrowingLease(ReadStep.Bytes(ByteArray(188))),
            GROWING_TS_PACKET_BYTES,
        )
        val events = mutableListOf<String>()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                events += "initializing"
            }
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                events += "start"
            }
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                events += "bytes:$bytesTransferred"
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                events += "end"
            }
        })
        source.open(recordingSpec())
        source.read(ByteArray(50), 0, 50)
        source.close()
        assertEquals(listOf("initializing", "start", "bytes:50", "end"), events)
    }

    private fun dataSource(
        lease: GrowingRecordingFileLease,
        readAheadBytes: Int,
        onFinalEnd: () -> Unit = {},
    ): DataSource = createTvheadendGrowingRecordingDataSourceFactory(
        lease = lease,
        identity = identity,
        readAheadBytes = readAheadBytes,
        onFinalEnd = onFinalEnd,
    ).createDataSource()

    private fun recordingSpec(
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(identity.uri)
        .setPosition(position)
        .setLength(length)
        .build()
}

private sealed interface ReadStep {
    class Bytes(val value: ByteArray) : ReadStep

    class Failed(val failure: TvheadendFailure) : ReadStep

    data object End : ReadStep

    data object Cancelled : ReadStep
}

private class ScriptedGrowingLease(
    vararg steps: ReadStep,
    private val openFailure: TvheadendFailure? = null,
) : GrowingRecordingFileLease {
    private val template = steps.toList()
    val openPositions = ArrayList<Long>()
    var readCalls = 0
        private set
    var closeCalls = 0
        private set

    override val isCurrent: Boolean = true

    override suspend fun open(
        position: Long,
    ): RecordingFileResult<GrowingRecordingFileReader> {
        openFailure?.let { failure -> return RecordingFileResult.Failed(failure) }
        openPositions += position
        return RecordingFileResult.Ok(Reader(template.toMutableList()))
    }

    private inner class Reader(
        private val steps: MutableList<ReadStep>,
    ) : GrowingRecordingFileReader {
        override val sizeBytes: Long? = null
        override val isFinal: Boolean = false
        override suspend fun refreshSize(): RecordingFileResult<Long?> = RecordingFileResult.Ok(null)
        override suspend fun seek(position: Long): RecordingFileResult<Unit> = error("Playback fixture does not reposition")
        override suspend fun read(
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> {
            readCalls += 1
            check(length <= MAX_RECORDING_READ_BYTES)
            check(destinationOffset >= 0 && length <= destination.size - destinationOffset)
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

private class BlockingGrowingLease : GrowingRecordingFileLease {
    val readStarted = CountDownLatch(1)
    var closeCalls = 0
        private set

    override val isCurrent: Boolean = true

    override suspend fun open(
        position: Long,
    ): RecordingFileResult<GrowingRecordingFileReader> = RecordingFileResult.Ok(
        object : GrowingRecordingFileReader {
            override val sizeBytes: Long? = null
            override val isFinal: Boolean = false
            override suspend fun refreshSize(): RecordingFileResult<Long?> = RecordingFileResult.Ok(null)
            override suspend fun seek(position: Long): RecordingFileResult<Unit> = error("Playback fixture does not reposition")
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
