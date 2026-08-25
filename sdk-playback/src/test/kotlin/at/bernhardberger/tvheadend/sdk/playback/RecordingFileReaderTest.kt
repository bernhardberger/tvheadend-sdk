@file:OptIn(SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingFileReaderTest {
    @Test
    fun `every reopen performs a fresh open followed by a seek`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(64) { index -> index.toByte() })
        val reader = createRecordingFileReader(transport, readAheadBytes = 16)

        assertOk(64L, reader.open(RECORDING, position = 0L, length = null))
        assertOk(Unit, reader.close())
        assertOk(34L, reader.open(RECORDING, position = 30L, length = null))
        assertOk(Unit, reader.close())

        assertEquals(
            listOf("open", "seek@0", "close", "open", "seek@30", "close"),
            transport.calls,
        )
    }

    @Test
    fun `an open resolves the remaining readable length from the reported size`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(100))
        val reader = createRecordingFileReader(transport)

        assertOk(70L, reader.open(RECORDING, position = 30L, length = null))
    }

    @Test
    fun `an open honours a bounded length inside the reported size`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(100))
        val reader = createRecordingFileReader(transport)

        assertOk(20L, reader.open(RECORDING, position = 10L, length = 20L))
    }

    @Test
    fun `an open rejects a bounded length the reported size cannot satisfy`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(100))
        val reader = createRecordingFileReader(transport)

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            reader.open(RECORDING, position = 90L, length = 20L),
        )
        assertEquals(listOf("open", "close"), transport.calls)
    }

    @Test
    fun `an open rejects a position beyond the reported size`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(10))
        val reader = createRecordingFileReader(transport)

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            reader.open(RECORDING, position = 11L, length = null),
        )
        assertEquals(listOf("open", "close"), transport.calls)
    }

    @Test
    fun `an open at the reported size resolves an empty readable range`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(10))
        val reader = createRecordingFileReader(transport)

        assertOk(0L, reader.open(RECORDING, position = 10L, length = null))
        assertOk(RECORDING_END_OF_INPUT, reader.read(ByteArray(4), 0, 4))
    }

    @Test
    fun `an unreported size leaves the readable length unknown`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(10), reportSize = false)
        val reader = createRecordingFileReader(transport)

        assertOk(null, reader.open(RECORDING, position = 0L, length = null))
    }

    @Test
    fun `an open closes the handle when the server seeks elsewhere`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(64), seekOffsetOverride = 5L)
        val reader = createRecordingFileReader(transport)

        assertFailed(
            RecordingFileFailure.FILE_UNAVAILABLE,
            reader.open(RECORDING, position = 8L, length = null),
        )
        assertEquals(listOf("open", "seek@8", "close"), transport.calls)
    }

    @Test
    fun `an open propagates the transport failure without a further round trip`() = runTest {
        val transport = FakeRecordingTransport(
            content = ByteArray(8),
            openFailure = RecordingFileFailure.CONNECTION_CHANGED,
        )
        val reader = createRecordingFileReader(transport)

        assertFailed(
            RecordingFileFailure.CONNECTION_CHANGED,
            reader.open(RECORDING, position = 0L, length = null),
        )
        assertEquals(listOf("open"), transport.calls)
    }

    @Test
    fun `a reopen releases a handle the consumer never closed`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(32))
        val reader = createRecordingFileReader(transport)

        assertOk(32L, reader.open(RECORDING, position = 0L, length = null))
        assertOk(32L, reader.open(RECORDING, position = 0L, length = null))

        assertEquals(listOf("open", "seek@0", "close", "open", "seek@0"), transport.calls)
        assertEquals(1, transport.openHandles)
    }

    @Test
    fun `no read requests more than the read-ahead window`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(1_024))
        val reader = createRecordingFileReader(transport, readAheadBytes = 8)

        assertOk(1_024L, reader.open(RECORDING, position = 0L, length = null))
        assertOk(8, reader.read(ByteArray(512), 0, 512))

        assertEquals(listOf(8), transport.requestedLengths)
    }

    @Test
    fun `small reads are served from one read-ahead window`() = runTest {
        val content = ByteArray(32) { index -> index.toByte() }
        val transport = FakeRecordingTransport(content)
        val reader = createRecordingFileReader(transport, readAheadBytes = 16)
        assertOk(32L, reader.open(RECORDING, position = 0L, length = null))

        val destination = ByteArray(16)
        repeat(4) { round -> assertOk(4, reader.read(destination, round * 4, 4)) }

        assertArrayEquals(content.copyOfRange(0, 16), destination)
        assertEquals(listOf(16), transport.requestedLengths)
    }

    @Test
    fun `a request at least one window wide is served directly`() = runTest {
        val content = ByteArray(64) { index -> (index + 1).toByte() }
        val transport = FakeRecordingTransport(content)
        val reader = createRecordingFileReader(transport, readAheadBytes = 16)
        assertOk(64L, reader.open(RECORDING, position = 0L, length = null))

        val destination = ByteArray(40)
        assertOk(16, reader.read(destination, 8, 24))

        assertArrayEquals(ByteArray(8), destination.copyOfRange(0, 8))
        assertArrayEquals(content.copyOfRange(0, 16), destination.copyOfRange(8, 24))
        assertArrayEquals(ByteArray(16), destination.copyOfRange(24, 40))
        assertEquals(listOf(16), transport.requestedLengths)
    }

    @Test
    fun `the final window is clamped to the remaining readable range`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(10))
        val reader = createRecordingFileReader(transport, readAheadBytes = 8)
        assertOk(10L, reader.open(RECORDING, position = 0L, length = null))

        val destination = ByteArray(8)
        assertOk(8, reader.read(destination, 0, 8))
        assertOk(2, reader.read(destination, 0, 8))
        assertOk(RECORDING_END_OF_INPUT, reader.read(destination, 0, 8))

        assertEquals(listOf(8, 2), transport.requestedLengths)
        assertEquals(listOf(0L, 8L), transport.requestedPositions)
    }

    @Test
    fun `a bounded open never reads past its requested length`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(100))
        val reader = createRecordingFileReader(transport, readAheadBytes = 16)
        assertOk(20L, reader.open(RECORDING, position = 40L, length = 20L))

        val destination = ByteArray(64)
        assertOk(16, reader.read(destination, 0, 64))
        assertOk(4, reader.read(destination, 0, 64))
        assertOk(RECORDING_END_OF_INPUT, reader.read(destination, 0, 64))

        assertEquals(listOf(16, 4), transport.requestedLengths)
        assertEquals(listOf(40L, 56L), transport.requestedPositions)
    }

    @Test
    fun `an unknown length ends at the server's end of file`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(6), reportSize = false)
        val reader = createRecordingFileReader(transport, readAheadBytes = 8)
        assertOk(null, reader.open(RECORDING, position = 0L, length = null))

        val destination = ByteArray(8)
        assertOk(6, reader.read(destination, 0, 8))
        assertOk(RECORDING_END_OF_INPUT, reader.read(destination, 0, 8))
    }

    @Test
    fun `a known range that stops early is an unreadable file`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(6), reportedSizeOverride = 32L)
        val reader = createRecordingFileReader(transport, readAheadBytes = 8)
        assertOk(32L, reader.open(RECORDING, position = 0L, length = null))

        val destination = ByteArray(8)
        assertOk(6, reader.read(destination, 0, 8))
        assertFailed(RecordingFileFailure.FILE_UNAVAILABLE, reader.read(destination, 0, 8))
    }

    @Test
    fun `a payload larger than the requested window is rejected`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(64), overreadBy = 1)
        val reader = createRecordingFileReader(transport, readAheadBytes = 8)
        assertOk(64L, reader.open(RECORDING, position = 0L, length = null))

        assertFailed(RecordingFileFailure.FILE_UNAVAILABLE, reader.read(ByteArray(32), 0, 32))
    }

    @Test
    fun `a read propagates the transport failure classification`() = runTest {
        val transport = FakeRecordingTransport(
            content = ByteArray(64),
            readFailure = RecordingFileFailure.TIMEOUT,
        )
        val reader = createRecordingFileReader(transport)
        assertOk(64L, reader.open(RECORDING, position = 0L, length = null))

        assertFailed(RecordingFileFailure.TIMEOUT, reader.read(ByteArray(32), 0, 32))
    }

    @Test
    fun `an empty window reads nothing`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(8))
        val reader = createRecordingFileReader(transport)
        assertOk(8L, reader.open(RECORDING, position = 0L, length = null))

        assertOk(0, reader.read(ByteArray(4), 0, 0))
        assertEquals(emptyList<Int>(), transport.requestedLengths)
    }

    @Test
    fun `a read requires an open file`() {
        val reader = createRecordingFileReader(FakeRecordingTransport(ByteArray(8)))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { reader.read(ByteArray(4), 0, 4) }
        }
    }

    @Test
    fun `a read window must lie inside the destination array`() {
        val transport = FakeRecordingTransport(content = ByteArray(8))
        val reader = createRecordingFileReader(transport)
        runBlocking { assertOk(8L, reader.open(RECORDING, position = 0L, length = null)) }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reader.read(ByteArray(4), 3, 2) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reader.read(ByteArray(4), -1, 1) }
        }
    }

    @Test
    fun `an open rejects a negative position or length`() {
        val reader = createRecordingFileReader(FakeRecordingTransport(ByteArray(8)))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reader.open(RECORDING, position = -1L, length = null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reader.open(RECORDING, position = 0L, length = -1L) }
        }
    }

    @Test
    fun `a close releases the handle once and stays idempotent`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(8))
        val reader = createRecordingFileReader(transport)
        assertOk(8L, reader.open(RECORDING, position = 0L, length = null))

        assertOk(Unit, reader.close())
        assertOk(Unit, reader.close())

        assertEquals(listOf("open", "seek@0", "close"), transport.calls)
        assertEquals(0, transport.openHandles)
    }

    @Test
    fun `a cancelled consumer still releases the server handle`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(8))
        val reader = createRecordingFileReader(transport)
        assertOk(8L, reader.open(RECORDING, position = 0L, length = null))
        val release = CompletableDeferred<Unit>()
        transport.closeGate = release

        val job = launch { reader.close() }
        transport.closeEntered.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(transport.calls.contains("close"), "The close round trip must still complete")
        assertEquals(0, transport.openHandles)
    }

    @Test
    fun `a cancelled open still releases the handle it just opened`() = runTest {
        val transport = FakeRecordingTransport(content = ByteArray(8))
        val reader = createRecordingFileReader(transport)
        transport.seekGate = CompletableDeferred()

        var cancelled = false
        val job = launch {
            try {
                reader.open(RECORDING, position = 0L, length = null)
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
        transport.seekEntered.await()
        job.cancel()
        job.join()

        assertTrue(cancelled, "Cancellation must propagate out of open")
        assertEquals(listOf("open", "seek@0", "close"), transport.calls)
        assertEquals(
            0,
            transport.openHandles,
            "A cancelled open must not leave its handle open on the server",
        )
    }

    @Test
    fun `reads smaller than the window keep byte order across every refill`() = runTest {
        val content = ByteArray(20) { index -> (index + 1).toByte() }
        val transport = FakeRecordingTransport(content = content)
        val reader = createRecordingFileReader(transport, readAheadBytes = 8)
        assertOk(20L, reader.open(RECORDING, position = 0L, length = null))

        val destination = ByteArray(20)
        var total = 0
        while (total < content.size) {
            val read = reader.read(destination, total, minOf(3, content.size - total))
            assertTrue(read is RecordingFileResult.Ok, "Expected success but was $read")
            val count = (read as RecordingFileResult.Ok).value
            assertTrue(count > 0, "A partial window must still make progress")
            total += count
        }

        assertArrayEquals(content, destination)
        assertEquals(listOf(8, 8, 4), transport.requestedLengths)
        assertEquals(listOf(0L, 8L, 16L), transport.requestedPositions)
        assertOk(RECORDING_END_OF_INPUT, reader.read(destination, 0, 3))
        reader.close()
    }

    @Test
    fun `a read-ahead window outside the transport bound is rejected`() {
        val transport = FakeRecordingTransport(content = ByteArray(8))

        assertThrows(IllegalArgumentException::class.java) {
            createRecordingFileReader(transport, readAheadBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createRecordingFileReader(transport, readAheadBytes = MAX_RECORDING_READ_BYTES + 1)
        }
    }

    @Test
    fun `the transport read bound stays at sixteen mebibytes`() {
        assertEquals(16 * 1024 * 1024, MAX_RECORDING_READ_BYTES)
        assertTrue(DEFAULT_RECORDING_READ_AHEAD_BYTES in 1..MAX_RECORDING_READ_BYTES)
    }

    @Test
    fun `existing opener implementations default growing access to unsupported`() = runTest {
        val opener = RecordingFileOpener {
            RecordingFileResult.Failed(RecordingFileFailure.FILE_UNAVAILABLE)
        }

        assertFailed(
            RecordingFileFailure.NOT_SUPPORTED,
            opener.openGrowingRecording(RECORDING, position = 0L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { opener.openGrowingRecording(RECORDING, position = -1L) }
        }
    }

    @Test
    fun `identifiers and failures never expose their payload`() {
        assertEquals("RecordingId(<redacted>)", RECORDING.toString())
        assertEquals(
            "RecordingFileResult.Ok(<redacted>)",
            RecordingFileResult.Ok(42L).toString(),
        )
        assertEquals(
            "RecordingFileResult.Failed(failure=ACCESS_DENIED)",
            RecordingFileResult.Failed(RecordingFileFailure.ACCESS_DENIED).toString(),
        )
        assertThrows(IllegalArgumentException::class.java) { RecordingId(-1L) }
        assertThrows(IllegalArgumentException::class.java) { RecordingId(0x1_0000_0000L) }
    }
}

private val RECORDING = RecordingId(19L)

private fun <T> assertOk(expected: T, actual: RecordingFileResult<T>) {
    assertTrue(actual is RecordingFileResult.Ok, "Expected success but was $actual")
    assertEquals(expected, (actual as RecordingFileResult.Ok).value)
}

private fun assertFailed(expected: RecordingFileFailure, actual: RecordingFileResult<*>) {
    assertTrue(actual is RecordingFileResult.Failed, "Expected failure but was $actual")
    assertEquals(expected, (actual as RecordingFileResult.Failed).failure)
}

private class FakeRecordingTransport(
    private val content: ByteArray,
    private val reportSize: Boolean = true,
    private val reportedSizeOverride: Long? = null,
    private val seekOffsetOverride: Long? = null,
    private val openFailure: RecordingFileFailure? = null,
    private val readFailure: RecordingFileFailure? = null,
    private val overreadBy: Int = 0,
) : RecordingFileOpener {
    internal val calls = ArrayList<String>()
    internal val requestedLengths = ArrayList<Int>()
    internal val requestedPositions = ArrayList<Long>()
    internal var openHandles: Int = 0
        private set
    internal val closeEntered = CompletableDeferred<Unit>()
    internal var closeGate: CompletableDeferred<Unit>? = null
    internal val seekEntered = CompletableDeferred<Unit>()
    internal var seekGate: CompletableDeferred<Unit>? = null

    override suspend fun openRecording(
        recordingId: RecordingId,
    ): RecordingFileResult<RecordingFile> {
        calls += "open"
        openFailure?.let { failure -> return RecordingFileResult.Failed(failure) }
        openHandles += 1
        return RecordingFileResult.Ok(Handle())
    }

    private inner class Handle : RecordingFile {
        override val sizeBytes: Long? =
            reportedSizeOverride ?: content.size.toLong().takeIf { reportSize }

        override suspend fun seek(position: Long): RecordingFileResult<Long> {
            calls += "seek@$position"
            seekEntered.complete(Unit)
            seekGate?.await()
            return RecordingFileResult.Ok(seekOffsetOverride ?: position)
        }

        override suspend fun read(
            position: Long,
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> {
            requestedPositions += position
            requestedLengths += length
            readFailure?.let { failure -> return RecordingFileResult.Failed(failure) }
            val available = (content.size - position).coerceAtLeast(0L).toInt()
            val served = minOf(available, length) + overreadBy
            content.copyInto(
                destination,
                destinationOffset,
                position.toInt(),
                position.toInt() + minOf(available, served),
            )
            return RecordingFileResult.Ok(served)
        }

        override suspend fun close(): RecordingFileResult<Unit> {
            closeEntered.complete(Unit)
            closeGate?.await()
            calls += "close"
            openHandles -= 1
            return RecordingFileResult.Ok(Unit)
        }
    }
}
