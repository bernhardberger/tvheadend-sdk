@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal class FileMetadataCacheTimestampValidationTest {
    @TempDir lateinit var root: File

    @Test
    fun `later timestamp mismatch invalidates the whole restore after a valid event`() = runTest {
        val now = Instant.fromEpochSeconds(1_700_000_000)
        val namespace = cacheNamespace("benchmark.invalid", 9982, "")
        val store = FileMetadataCacheStore(root, object : Clock { override fun now(): Instant = now })
        val events = listOf(0L, 1L).map { index ->
            val start = now + index.toInt().hours + 123.milliseconds
            EpgEvent.create(EventId(index + 1), start = start, stop = start + 1.hours)
        }
        val file = File(root, "tvheadend-sdk/${namespace.value}/epg.bin")
        for (prefixOffset in listOf(0L, 8L)) {
            store.storeEpg(namespace, CachedEpg(EpgSnapshot.create(events)), now)
            assertEquals(events, requireNotNull(store.loadEpg(namespace, now)).snapshot.events)
            RandomAccessFile(file, "rw").use { data ->
                data.seek(28 + 16) // Empty receipt/coverage/query header and first start/stop.
                val firstSize = data.readInt()
                data.seek(data.filePointer + firstSize + prefixOffset)
                val offset = data.filePointer
                val timestamp = data.readLong()
                data.seek(offset)
                // Both variants remain ordered, in-window, and stop >= start. Only the
                // second payload's timestamp disagreement can invalidate this record.
                data.writeLong(timestamp + 1)
            }
            assertNull(store.loadEpg(namespace, now))
            assertFalse(file.exists())
        }
    }
}
