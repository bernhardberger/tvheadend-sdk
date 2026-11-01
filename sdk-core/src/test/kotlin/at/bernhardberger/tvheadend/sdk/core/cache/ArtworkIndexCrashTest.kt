@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Instant

internal class ArtworkIndexCrashTest {
    @TempDir lateinit var root: File
    private val namespace = cacheNamespace("fixture", 9982, "user")
    private val now = Instant.fromEpochSeconds(1_700_000_000)
    private val content = byteArrayOf(1, 2, 3)
    private val replacement = byteArrayOf(4, 5, 6)

    @Test
    fun `interrupted insert and replacement never accept a mismatched payload`() {
        for (replace in listOf(false, true)) for (boundary in 1..2) for (after in listOf(false, true)) {
            val directory = File(root, "$replace-$boundary-$after")
            val initial = FileArtworkCacheStore(directory)
            initial.store(namespace, ArtworkId(33), content, now, now, 100)
            if (replace) initial.store(namespace, ArtworkId(1), content, now, now, 100)
            val files = InterruptedFiles(boundary, after)
            val store = FileArtworkCacheStore(directory, files)
            store.prune(now, 100)
            files.armed = true
            assertThrows(ProcessStopped::class.java) {
                store.store(namespace, ArtworkId(1), replacement, now, now, 100)
            }
            val restarted = FileArtworkCacheStore(directory)
            val actual = restarted.load(namespace, ArtworkId(1), now, now)
            when {
                boundary == 2 && after -> assertArrayEquals(replacement, actual)
                boundary == 1 && !after && replace -> assertArrayEquals(content, actual)
                else -> assertNull(actual)
            }
            assertArrayEquals(content, restarted.load(namespace, ArtworkId(33), now, now),
                "An interrupted update must retain the other entry in its shard")
        }
    }

    @Test
    fun `interrupted delete never resurrects a removed payload`() {
        for (boundary in 1..2) for (after in listOf(false, true)) {
            val directory = File(root, "$boundary-$after")
            FileArtworkCacheStore(directory).store(namespace, ArtworkId(1), content, now, now, 100)
            val files = InterruptedFiles(boundary, after)
            val store = FileArtworkCacheStore(directory, files)
            store.prune(now, 100)
            files.armed = true
            assertThrows(ProcessStopped::class.java) { store.remove(namespace, ArtworkId(1), now) }
            val actual = FileArtworkCacheStore(directory).load(namespace, ArtworkId(1), now, now)
            if (boundary == 1 && !after) assertArrayEquals(content, actual) else assertNull(actual)
        }
    }

    @Test
    fun `every legacy migration interruption retains committed entries and removals`() {
        // 32 shard publications followed by legacy-index retirement, on either side of each IO.
        for (boundary in 1..33) for (after in listOf(false, true)) {
            val directory = File(root, "$boundary-$after")
            seedLegacy(directory, listOf(1, 2, 33))
            val files = InterruptedFiles(boundary, after)
            val store = FileArtworkCacheStore(directory, files)
            assertArrayEquals(content, store.load(namespace, ArtworkId(1), now, now))
            files.armed = true
            assertThrows(ProcessStopped::class.java) { store.flush() }
            val restarted = FileArtworkCacheStore(directory)
            for (id in listOf(1, 2, 33)) assertArrayEquals(content, restarted.load(namespace, ArtworkId(id), now, now))
            restarted.remove(namespace, ArtworkId(1), now)
            restarted.store(namespace, ArtworkId(33), replacement, now, now, 100)
            val again = FileArtworkCacheStore(directory)
            assertNull(again.load(namespace, ArtworkId(1), now, now))
            assertArrayEquals(replacement, again.load(namespace, ArtworkId(33), now, now))
            assertFalse(File(artwork(directory), "index.bin").exists())
        }
    }

    @Test
    fun `interrupted temporary writes leave previous committed bytes and are cleaned on restart`() {
        for (target in listOf("1", "index-1.bin")) {
            val directory = File(root, target)
            FileArtworkCacheStore(directory).store(namespace, ArtworkId(1), content, now, now, 100)
            val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
                override fun writeAtomically(file: File, bytes: ByteArray) {
                    if (file.name == target) {
                        File(file.parentFile, "${file.name}.tmp").writeBytes(bytes.copyOf(1))
                        throw ProcessStopped()
                    }
                    DiskArtworkCacheFiles.writeAtomically(file, bytes)
                }
            }
            val store = FileArtworkCacheStore(directory, files)
            assertThrows(ProcessStopped::class.java) {
                store.store(namespace, ArtworkId(1), replacement, now, now, 100)
            }
            val actual = FileArtworkCacheStore(directory).load(namespace, ArtworkId(1), now, now)
            if (target == "1") assertArrayEquals(content, actual) else assertNull(actual)
            assertFalse(File(artwork(directory), "$target.tmp").exists())
        }
    }

    @Test
    fun `empty shard masks legacy entry even when legacy retirement repeatedly fails`() {
        seedLegacy(root, listOf(1, 2))
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun delete(file: File): Boolean =
                if (file.name == "index.bin") false else DiskArtworkCacheFiles.delete(file)
        }
        FileArtworkCacheStore(root, files).prune(now, 100)
        assertTrue(File(artwork(root), "index.bin").exists())
        FileArtworkCacheStore(root, files).remove(namespace, ArtworkId(1), now)
        // A stale payload left by external recovery must not bypass the shard tombstone.
        File(artwork(root), "1").writeBytes(content)
        assertTrue(File(artwork(root), "index.bin").exists())
        val restarted = FileArtworkCacheStore(root, files)
        assertNull(restarted.load(namespace, ArtworkId(1), now, now))
        assertArrayEquals(content, restarted.load(namespace, ArtworkId(2), now, now))
        restarted.store(namespace, ArtworkId(33), replacement, now, now, 100)
        assertTrue(File(artwork(root), "index.bin").exists())
        File(artwork(root), "1").writeBytes(content)
        val afterMutation = FileArtworkCacheStore(root, files)
        assertNull(afterMutation.load(namespace, ArtworkId(1), now, now))
        assertArrayEquals(replacement, afterMutation.load(namespace, ArtworkId(33), now, now))
        assertArrayEquals(content, afterMutation.load(namespace, ArtworkId(2), now, now))
        // Successful retirement on a later restart must preserve the same deletion override.
        FileArtworkCacheStore(root).prune(now, 100)
        assertFalse(File(artwork(root), "index.bin").exists())
        val retired = FileArtworkCacheStore(root)
        assertNull(retired.load(namespace, ArtworkId(1), now, now))
        assertArrayEquals(replacement, retired.load(namespace, ArtworkId(33), now, now))
    }

    @Test
    fun `failed shard publication retries and corrupt shard remains isolated`() {
        var fail = true
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun writeAtomically(file: File, bytes: ByteArray) {
                if (fail && file.name == "index-1.bin") throw IOException("Synthetic index failure")
                DiskArtworkCacheFiles.writeAtomically(file, bytes)
            }
        }
        val store = FileArtworkCacheStore(root, files)
        store.store(namespace, ArtworkId(1), content, now, now, 100)
        fail = false
        store.flush()
        assertArrayEquals(content, FileArtworkCacheStore(root).load(namespace, ArtworkId(1), now, now))
        store.store(namespace, ArtworkId(2), content, now, now, 100)
        File(artwork(root), "index-1.bin").writeBytes(byteArrayOf(0))
        val restarted = FileArtworkCacheStore(root)
        assertNull(restarted.load(namespace, ArtworkId(1), now, now))
        assertArrayEquals(content, restarted.load(namespace, ArtworkId(2), now, now))
    }

    @Test
    fun `invalid shard never falls back to legacy or overrides another bucket`() {
        for (schema in listOf(2, 99)) {
            val directory = File(root, schema.toString())
            seedLegacy(directory, listOf(1, 2, 33))
            val wrongBucket = ArtworkEntryDto(content.size.toLong(), now.toEpochMilliseconds(), now.toEpochMilliseconds(),
                MessageDigest.getInstance("SHA-256").digest(content))
            File(artwork(directory), "index-1.bin").writeBytes(ProtoBuf.encodeToByteArray(
                ArtworkIndexDto.serializer(), ArtworkIndexDto(schema, mapOf(2 to wrongBucket)),
            ))
            val restarted = FileArtworkCacheStore(directory)
            assertNull(restarted.load(namespace, ArtworkId(1), now, now))
            assertNull(restarted.load(namespace, ArtworkId(33), now, now))
            assertArrayEquals(content, restarted.load(namespace, ArtworkId(2), now, now))
            restarted.flush()
            assertArrayEquals(content, FileArtworkCacheStore(directory).load(namespace, ArtworkId(2), now, now))
        }
    }

    @Test
    fun `dirty shards coalesce touches and encode only their bucket`() {
        val writes = mutableListOf<Pair<String, ByteArray>>()
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun writeAtomically(file: File, bytes: ByteArray) {
                writes += file.name to bytes
                DiskArtworkCacheFiles.writeAtomically(file, bytes)
            }
        }
        seedLegacy(root, (1..439).toList())
        val store = FileArtworkCacheStore(root, files)
        store.prune(now, 100_000)
        writes.clear()
        store.store(namespace, ArtworkId(440), content, now, now, 100_000)
        assertEquals(listOf("440", "index-24.bin"), writes.map { it.first })
        val shard = ProtoBuf.decodeFromByteArray(ArtworkIndexDto.serializer(), writes.last().second)
        assertEquals(14, shard.entries.size)
        writes.clear()
        repeat(8) {
            for (id in listOf(1, 33, 2)) assertArrayEquals(content, store.load(namespace, ArtworkId(id), now, now))
        }
        assertTrue(writes.isEmpty())
        store.flush()
        assertEquals(setOf("index-1.bin", "index-2.bin"), writes.map { it.first }.toSet())
        assertEquals(2, writes.size)
    }

    private fun artwork(directory: File): File = File(directory, "tvheadend-sdk/${namespace.value}/artwork")

    private fun seedLegacy(directory: File, ids: List<Int>) {
        val artwork = artwork(directory).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val entries = ids.associateWith { id ->
            File(artwork, id.toString()).writeBytes(content)
            ArtworkEntryDto(content.size.toLong(), now.toEpochMilliseconds(), now.toEpochMilliseconds(), digest)
        }
        File(artwork, "index.bin").writeBytes(ProtoBuf.encodeToByteArray(ArtworkIndexDto.serializer(), ArtworkIndexDto(1, entries)))
    }

    // Error intentionally bypasses the store's recoverable-IO handling, modelling abrupt
    // process termination without allowing later flush/finalization to repair the cache.
    private class ProcessStopped : Error()

    private class InterruptedFiles(private val boundary: Int, private val after: Boolean) : ArtworkCacheFiles by DiskArtworkCacheFiles {
        var armed = false
        private var operations = 0

        private fun <T> interrupt(block: () -> T): T {
            val stop = armed && ++operations == boundary
            if (stop && !after) throw ProcessStopped()
            return block().also { if (stop && after) throw ProcessStopped() }
        }

        override fun writeAtomically(file: File, bytes: ByteArray): Unit = interrupt { DiskArtworkCacheFiles.writeAtomically(file, bytes) }
        override fun delete(file: File): Boolean = interrupt { DiskArtworkCacheFiles.delete(file) }
    }
}
