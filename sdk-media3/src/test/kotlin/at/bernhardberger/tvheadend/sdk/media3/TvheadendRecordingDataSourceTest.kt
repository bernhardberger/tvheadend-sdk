@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import at.bernhardberger.tvheadend.sdk.playback.RecordingFile
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileFailure
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileOpener
import at.bernhardberger.tvheadend.sdk.playback.RecordingFileResult
import at.bernhardberger.tvheadend.sdk.playback.RecordingId
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvheadendRecordingDataSourceTest {
    @Test
    fun `recording identifiers round trip through an opaque uri without a server path`() {
        val uri = recordingUri(RecordingId(4_294_967_295L))

        assertEquals("tvheadend-recording://4294967295", uri)
        assertEquals(RecordingId(4_294_967_295L), parseRecordingId(uri))
        assertEquals(RecordingId(0L), parseRecordingId("tvheadend-recording://0"))
        assertEquals(
            "tvheadend-recording://7",
            tvheadendRecordingMediaItem(RecordingId(7L)).localConfiguration?.uri.toString(),
        )
    }

    @Test
    fun `foreign or malformed uris are not recording identifiers`() {
        listOf(
            "http://host/dvrfile/7",
            "tvheadend-recording:/7",
            "tvheadend-recording://",
            "tvheadend-recording://7/extra",
            "tvheadend-recording://-1",
            "tvheadend-recording:// 7",
            "tvheadend-recording://0x7",
            "tvheadend-recording://4294967296",
            "tvheadend-recording://99999999999999999999",
        ).forEach { candidate ->
            assertNull(parseRecordingId(candidate), "Accepted a non-recording uri")
        }
    }

    @Test
    fun `an unbounded open reports the recording length and reads to end of input`() {
        val transport = FakeRecordingTransport(ByteArray(10) { it.toByte() })
        val source = dataSource(transport, readAheadBytes = 4)
        val listener = RecordingTransferListener()
        source.addTransferListener(listener)

        assertNull(source.uri, "A data source must not present a uri before it opens")
        assertEquals(10L, source.open(recordingSpec(RecordingId(3L))))
        assertEquals("tvheadend-recording://3", source.uri.toString())
        assertEquals(1, listener.starts)

        val buffer = ByteArray(16)
        var total = 0
        while (true) {
            val read = source.read(buffer, total, buffer.size - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }

        assertEquals(10, total)
        assertArrayEquals(ByteArray(10) { it.toByte() }, buffer.copyOf(10))
        assertEquals(10, listener.bytes, "Every delivered byte must be reported once")
        assertTrue(
            transport.requestedLengths.all { it <= 4 },
            "No transport read may exceed the read-ahead bound",
        )

        source.close()
        assertNull(source.uri)
        assertEquals(1, listener.ends)
        assertEquals(1, transport.closes, "Closing the data source must release the server handle")
    }

    @Test
    fun `each reopen performs a fresh transport open and seek at the new position`() {
        val transport = FakeRecordingTransport(ByteArray(64) { it.toByte() })
        val source = dataSource(transport, readAheadBytes = 8)

        assertEquals(64L, source.open(recordingSpec(RecordingId(3L))))
        source.close()
        assertEquals(24L, source.open(recordingSpec(RecordingId(3L), position = 40L)))

        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        assertArrayEquals(byteArrayOf(40, 41, 42, 43), buffer)
        assertEquals(listOf("open", "seek@0", "close", "open", "seek@40"), transport.calls)

        source.close()
        assertEquals(listOf("open", "seek@0", "close", "open", "seek@40", "close"), transport.calls)
    }

    @Test
    fun `a bounded request resolves exactly the requested length`() {
        val transport = FakeRecordingTransport(ByteArray(64) { it.toByte() })
        val source = dataSource(transport, readAheadBytes = 8)

        assertEquals(6L, source.open(recordingSpec(RecordingId(3L), position = 8L, length = 6L)))

        val buffer = ByteArray(16)
        var total = 0
        while (true) {
            val read = source.read(buffer, total, buffer.size - total)
            if (read == C.RESULT_END_OF_INPUT) break
            total += read
        }

        assertEquals(6, total)
        assertArrayEquals(byteArrayOf(8, 9, 10, 11, 12, 13), buffer.copyOf(6))
        source.close()
    }

    @Test
    fun `a changed connection and an unreadable file stay distinguishable`() {
        val changed = FakeRecordingTransport(
            ByteArray(4),
            openFailure = RecordingFileFailure.CONNECTION_CHANGED,
        )
        val connectionFailure = assertThrows(TvheadendRecordingException::class.java) {
            dataSource(changed, readAheadBytes = 4).open(recordingSpec(RecordingId(3L)))
        }
        assertSame(RecordingFileFailure.CONNECTION_CHANGED, connectionFailure.failure)

        val denied = FakeRecordingTransport(
            ByteArray(4),
            openFailure = RecordingFileFailure.ACCESS_DENIED,
        )
        val deniedFailure = assertThrows(TvheadendRecordingException::class.java) {
            dataSource(denied, readAheadBytes = 4).open(recordingSpec(RecordingId(3L)))
        }
        assertSame(RecordingFileFailure.ACCESS_DENIED, deniedFailure.failure)

        val truncated = FakeRecordingTransport(
            ByteArray(8),
            readFailure = RecordingFileFailure.FILE_UNAVAILABLE,
        )
        val readSource = dataSource(truncated, readAheadBytes = 4)
        readSource.open(recordingSpec(RecordingId(3L)))
        val readingFailure = assertThrows(TvheadendRecordingException::class.java) {
            readSource.read(ByteArray(4), 0, 4)
        }
        assertSame(RecordingFileFailure.FILE_UNAVAILABLE, readingFailure.failure)
        readSource.close()
        assertEquals(1, truncated.closes, "A failed read must still release its handle on close")
    }

    @Test
    fun `a foreign uri fails before any transport round trip and reports no transfer`() {
        val transport = FakeRecordingTransport(ByteArray(4))
        val source = dataSource(transport, readAheadBytes = 4)
        val listener = RecordingTransferListener()
        source.addTransferListener(listener)

        val failure = assertThrows(TvheadendRecordingException::class.java) {
            source.open(DataSpec.Builder().setUri("http://host/dvrfile/7").build())
        }

        assertSame(RecordingFileFailure.FILE_UNAVAILABLE, failure.failure)
        assertTrue(transport.calls.isEmpty(), "A foreign uri must not reach the transport")
        assertEquals(0, listener.starts)

        source.close()
        assertEquals(0, listener.ends, "A source that never opened must not report a transfer end")
    }

    @Test
    fun `a superseded connection generation is reported as a changed connection`() {
        val transport = FakeRecordingTransport(
            ByteArray(8),
            openCancellation = CancellationException("Stale HTSP connection generation"),
        )
        val source = dataSource(transport, readAheadBytes = 4)

        val failure = assertThrows(TvheadendRecordingException::class.java) {
            source.open(recordingSpec(RecordingId(3L)))
        }

        assertSame(
            RecordingFileFailure.CONNECTION_CHANGED,
            failure.failure,
            "A superseded generation must stay distinguishable from an unreadable file",
        )
        assertFalse(
            Thread.currentThread().isInterrupted,
            "Classifying a transport cancellation must not leave the loader thread interrupted",
        )
    }

    @Test
    fun `the failure message carries only the classification`() {
        val message = TvheadendRecordingException(RecordingFileFailure.FILE_UNAVAILABLE).message
        assertEquals("Recording file operation failed: FILE_UNAVAILABLE", message)
    }

    private fun dataSource(opener: RecordingFileOpener, readAheadBytes: Int): DataSource =
        createTvheadendRecordingDataSourceFactory(opener, readAheadBytes).createDataSource()

    private fun recordingSpec(
        recordingId: RecordingId,
        position: Long = 0L,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(recordingUri(recordingId))
        .setPosition(position)
        .setLength(length)
        .build()
}

private class RecordingTransferListener : TransferListener {
    var starts: Int = 0
        private set
    var bytes: Int = 0
        private set
    var ends: Int = 0
        private set

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        starts += 1
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        bytes += bytesTransferred
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        ends += 1
    }
}

private class FakeRecordingTransport(
    private val content: ByteArray,
    private val openFailure: RecordingFileFailure? = null,
    private val readFailure: RecordingFileFailure? = null,
    private val openCancellation: CancellationException? = null,
) : RecordingFileOpener {
    val calls = ArrayList<String>()
    val requestedLengths = ArrayList<Int>()
    var closes: Int = 0
        private set

    override suspend fun openRecording(
        recordingId: RecordingId,
    ): RecordingFileResult<RecordingFile> {
        openCancellation?.let { throw it }
        openFailure?.let { return RecordingFileResult.Failed(it) }
        calls += "open"
        return RecordingFileResult.Ok(Handle())
    }

    private inner class Handle : RecordingFile {
        override val sizeBytes: Long = content.size.toLong()

        override suspend fun seek(position: Long): RecordingFileResult<Long> {
            calls += "seek@$position"
            return RecordingFileResult.Ok(position)
        }

        override suspend fun read(
            position: Long,
            destination: ByteArray,
            destinationOffset: Int,
            length: Int,
        ): RecordingFileResult<Int> {
            readFailure?.let { return RecordingFileResult.Failed(it) }
            requestedLengths += length
            val available = (content.size - position).coerceAtLeast(0L).toInt()
            val copied = minOf(available, length)
            content.copyInto(
                destination,
                destinationOffset,
                position.toInt(),
                position.toInt() + copied,
            )
            return RecordingFileResult.Ok(copied)
        }

        override suspend fun close(): RecordingFileResult<Unit> {
            calls += "close"
            closes += 1
            return RecordingFileResult.Ok(Unit)
        }
    }
}
