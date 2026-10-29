package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ArtworkCacheReadTest {
    @TempDir lateinit var root: File
    private val namespace = cacheNamespace("fixture", 9982, "user")
    private val now = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock { override fun now(): Instant = now }
    private val content = byteArrayOf(1, 2, 3)

    @Test
    fun `warm cached hit reads one file at both small and large cache sizes`() = runTest {
        for (count in listOf(1, 439)) {
            val directory = File(root, count.toString())
            seed(directory, count)
            val files = CountingFiles()
            val disk = FileMetadataCacheStore(directory)
            var statisticsCalls = 0
            val metadata = object : MetadataCacheStore by disk {
                override suspend fun statistics() = disk.statistics().also { statisticsCalls++ }
            }
            val runtime = MetadataCacheRuntime(metadata, MetadataCachePolicy.create(directory),
                StandardTestDispatcher(testScheduler), clock, artworkStore = FileArtworkCacheStore(directory, files))
            assertTrue(runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true } is ArtworkLoadResult.Available)
            files.calls.clear()
            statisticsCalls = 0
            repeat(8) {
                assertTrue(runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true } is ArtworkLoadResult.Available)
            }
            assertEquals(mapOf("read" to 8), files.calls)
            assertEquals(0, statisticsCalls, "A cache hit must not scan the policy root for statistics")
            runtime.shutdown()
            assertEquals(1, files.calls["write"], "Touches flush together when the runtime stops")
        }
    }

    @Test
    fun `a blocked file read does not block another key or clear`() = runBlocking {
        seed(root, 2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun read(file: File): ByteArray {
                val bytes = DiskArtworkCacheFiles.read(file)
                if (file.name == "1") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Synthetic read was not released" }
                }
                return bytes
            }
        }
        val runtime = MetadataCacheRuntime(FileMetadataCacheStore(root), MetadataCachePolicy.create(root),
            Dispatchers.IO, clock, artworkStore = FileArtworkCacheStore(root, files))
        val blocked = async { runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5.seconds) {
                assertTrue(runtime.loadCachedArtwork(namespace, ArtworkId(2)) { true } is ArtworkLoadResult.Available)
                runtime.clear()
            }
        } finally {
            release.countDown()
            blocked.await()
            runtime.shutdown()
        }
        assertEquals(0, root.walkTopDown().count { it.isFile }, "Late read completion must not recreate cleared files")
    }

    @Test
    fun `touches flush on the timer and subsequent reads schedule another batch`() = runTest {
        seed(root, 1)
        val files = CountingFiles()
        val runtime = MetadataCacheRuntime(FileMetadataCacheStore(root), MetadataCachePolicy.create(root),
            StandardTestDispatcher(testScheduler), clock, artworkStore = FileArtworkCacheStore(root, files))
        runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true }
        files.calls.clear()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(emptyMap<String, Int>(), files.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(mapOf("write" to 1), files.calls)
        repeat(3) { runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true } }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(mapOf("write" to 2, "read" to 3), files.calls)
        runtime.shutdown()
        assertEquals(2, files.calls["write"])
    }

    @Test
    fun `cancellation during a file read propagates and leaves later reads usable`() = runBlocking {
        seed(root, 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun read(file: File): ByteArray {
                if (file.name == "1") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Synthetic read was not released" }
                }
                return DiskArtworkCacheFiles.read(file)
            }
        }
        val runtime = MetadataCacheRuntime(FileMetadataCacheStore(root), MetadataCachePolicy.create(root),
            Dispatchers.IO, clock, artworkStore = FileArtworkCacheStore(root, files))
        val reading = async { runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            reading.cancel()
        } finally {
            release.countDown()
            reading.join()
        }
        try {
            assertTrue(reading.isCancelled)
            withTimeout(5.seconds) {
                assertTrue(runtime.loadCachedArtwork(namespace, ArtworkId(1)) { true } is ArtworkLoadResult.Available)
            }
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun `late corrupt read cannot delete a replacement or a new post-clear entry`() {
        val store = FileArtworkCacheStore(root)
        store.store(namespace, ArtworkId(1), content, now, now, 100)
        val pending = checkNotNull(store.prepare(namespace, ArtworkId(1), now))
        val replacement = byteArrayOf(4, 5, 6)
        store.store(namespace, ArtworkId(1), replacement, now, now, 100)
        store.complete(pending, null, now)
        assertArrayEquals(replacement, store.load(namespace, ArtworkId(1), now, now))
        val preClear = checkNotNull(store.prepare(namespace, ArtworkId(1), now))
        root.deleteRecursively()
        store.invalidate()
        store.store(namespace, ArtworkId(1), content, now, now, 100)
        store.complete(preClear, null, now)
        assertArrayEquals(content, store.load(namespace, ArtworkId(1), now, now))
    }

    @Test
    fun `touches are durable after a batched flush and expiry precedes budget eviction`() {
        val store = FileArtworkCacheStore(root)
        store.store(namespace, ArtworkId(1), content, now, now, 100)
        store.store(namespace, ArtworkId(2), content, now + 1.seconds, now, 100)
        assertNotNull(store.load(namespace, ArtworkId(1), now + 2.seconds, now))
        store.flush()
        val restarted = FileArtworkCacheStore(root)
        restarted.store(namespace, ArtworkId(3), content, now + 3.seconds, now, 6)
        assertNull(restarted.load(namespace, ArtworkId(2), now + 3.seconds, now))
        assertNotNull(restarted.load(namespace, ArtworkId(1), now + 4.seconds, now))
        restarted.prune(now + 1.seconds, 3) // Old entry was touched last but is still expired.
        assertNotNull(restarted.load(namespace, ArtworkId(3), now + 4.seconds, now))
        assertNull(restarted.load(namespace, ArtworkId(1), now + 4.seconds, now))
    }

    @Test
    fun `warm stores only write payload and durable index at both cache sizes`() = runTest {
        for (count in listOf(1, 439)) {
            val directory = File(root, count.toString())
            seed(directory, count)
            val files = CountingFiles()
            val store = FileArtworkCacheStore(directory, files)
            store.prune(now, 100_000) // Reconcile persisted namespaces before the warm batch.
            files.calls.clear()
            repeat(32) {
                store.store(namespace, ArtworkId(count + it + 1), content, now, now, 100_000)
            }
            assertEquals(mapOf("write" to 64), files.calls,
                "Warm stores must not enumerate namespaces or re-read/stat existing payloads")
            assertEquals(CacheStatistics(0, (count + 32) * 3L, count + 32),
                FileMetadataCacheStore(directory).statistics())
            // No flush/stop: each successful store already published a restart-readable index.
            val restarted = FileArtworkCacheStore(directory)
            repeat(32) {
                assertArrayEquals(content, restarted.load(namespace, ArtworkId(count + it + 1), now, now))
            }
        }
    }

    @Test
    fun `inventory includes cold and newly admitted namespaces and resets after partial clear`() = runTest {
        val other = cacheNamespace("other", 9982, "user")
        val third = cacheNamespace("third", 9982, "user")
        FileArtworkCacheStore(root).store(other, ArtworkId(1), content, now, now, 6)
        val store = FileArtworkCacheStore(root)
        store.store(namespace, ArtworkId(1), content, now + 1.seconds, now, 6)
        store.store(third, ArtworkId(1), content, now + 2.seconds, now, 6)
        assertNull(store.load(other, ArtworkId(1), now + 2.seconds, now))
        assertNotNull(store.load(namespace, ArtworkId(1), now + 2.seconds, now))
        assertEquals(CacheStatistics(0, 6, 2), FileMetadataCacheStore(root).statistics())

        // A partial clear removes one namespace but leaves another namespace's durable files.
        File(root, "tvheadend-sdk/${namespace.value}").deleteRecursively()
        store.invalidate()
        store.store(other, ArtworkId(2), content, now + 3.seconds, now, 3)
        assertNull(store.load(third, ArtworkId(1), now + 3.seconds, now))
        assertArrayEquals(content, store.load(other, ArtworkId(2), now + 3.seconds, now))
        assertEquals(CacheStatistics(0, 3, 1), FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `replacement removal and corrupt read keep budget accounting exact`() = runTest {
        val store = FileArtworkCacheStore(root)
        store.store(namespace, ArtworkId(1), content, now, now, 6)
        store.store(namespace, ArtworkId(2), content, now + 1.seconds, now, 6)
        store.store(namespace, ArtworkId(1), byteArrayOf(4), now + 2.seconds, now, 6)
        store.store(namespace, ArtworkId(3), byteArrayOf(5, 6), now + 3.seconds, now, 6)
        assertNotNull(store.load(namespace, ArtworkId(2), now + 3.seconds, now))
        assertEquals(CacheStatistics(0, 6, 3), FileMetadataCacheStore(root).statistics())
        store.remove(namespace, ArtworkId(3), now)
        File(root, "tvheadend-sdk/${namespace.value}/artwork/2").writeBytes(byteArrayOf(9, 9, 9))
        assertNull(store.load(namespace, ArtworkId(2), now + 4.seconds, now))
        store.store(namespace, ArtworkId(4), content, now + 4.seconds, now, 6)
        assertArrayEquals(byteArrayOf(4), store.load(namespace, ArtworkId(1), now + 4.seconds, now))
        assertEquals(CacheStatistics(0, 4, 2), FileMetadataCacheStore(root).statistics())
        // A larger replacement evicts the oldest survivor, rather than retaining its old size.
        store.store(namespace, ArtworkId(1), ByteArray(4), now + 5.seconds, now, 6)
        assertNull(store.load(namespace, ArtworkId(4), now + 5.seconds, now))
        store.prune(now + 6.seconds, 6)
        assertEquals(CacheStatistics.EMPTY, FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `failed deletion stays in budget and failed replacement leaves old accounting`() = runTest {
        var refuseDelete = true
        var refuseWrite = false
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun delete(file: File): Boolean =
                if (refuseDelete && file.name == "1") false else DiskArtworkCacheFiles.delete(file)

            override fun writeAtomically(file: File, bytes: ByteArray) {
                if (refuseWrite && file.name == "1") throw IOException("Synthetic write failure")
                DiskArtworkCacheFiles.writeAtomically(file, bytes)
            }
        }
        val store = FileArtworkCacheStore(root, files)
        store.store(namespace, ArtworkId(1), content, now, now, 6)
        store.store(namespace, ArtworkId(2), content, now + 1.seconds, now, 6)
        store.store(namespace, ArtworkId(3), content, now + 2.seconds, now, 6)
        assertNull(store.load(namespace, ArtworkId(2), now + 2.seconds, now))
        assertEquals(CacheStatistics(0, 6, 2), FileMetadataCacheStore(root).statistics())
        refuseWrite = true
        store.store(namespace, ArtworkId(1), byteArrayOf(4), now + 3.seconds, now, 6)
        refuseDelete = false
        store.store(namespace, ArtworkId(4), content, now + 4.seconds, now, 6)
        assertNull(store.load(namespace, ArtworkId(1), now + 4.seconds, now))
        assertNotNull(store.load(namespace, ArtworkId(3), now + 4.seconds, now))
        assertEquals(CacheStatistics(0, 6, 2), FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `failed discovery is retried and failed index write stays dirty`() = runTest {
        val other = cacheNamespace("other", 9982, "user")
        FileArtworkCacheStore(root).store(other, ArtworkId(1), content, now, now, 6)
        var refuseDiscovery = true
        var refuseIndex = false
        val files = object : ArtworkCacheFiles by DiskArtworkCacheFiles {
            override fun list(directory: File): List<File> {
                if (refuseDiscovery && directory.name == "tvheadend-sdk") throw IOException("Synthetic list failure")
                return DiskArtworkCacheFiles.list(directory)
            }

            override fun writeAtomically(file: File, bytes: ByteArray) {
                if (refuseIndex && file.name == "index.bin") throw IOException("Synthetic index failure")
                DiskArtworkCacheFiles.writeAtomically(file, bytes)
            }
        }
        val store = FileArtworkCacheStore(root, files)
        store.store(namespace, ArtworkId(1), content, now + 1.seconds, now, 6)
        refuseDiscovery = false
        refuseIndex = true
        store.store(namespace, ArtworkId(2), content, now + 2.seconds, now, 6)
        assertNull(store.load(other, ArtworkId(1), now + 2.seconds, now))
        refuseIndex = false
        store.flush()
        val restarted = FileArtworkCacheStore(root)
        assertArrayEquals(content, restarted.load(namespace, ArtworkId(1), now + 2.seconds, now))
        assertArrayEquals(content, restarted.load(namespace, ArtworkId(2), now + 2.seconds, now))
        assertEquals(CacheStatistics(0, 6, 2), FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `unlistable root is not remembered as an empty inventory`() = runTest {
        val blockedRoot = File(root, "tvheadend-sdk")
        blockedRoot.writeText("not a directory")
        val store = FileArtworkCacheStore(root)
        store.prune(now, 3)
        assertTrue(blockedRoot.delete())
        // Repair the root with an existing persisted namespace before retrying maintenance.
        val other = cacheNamespace("other", 9982, "user")
        FileArtworkCacheStore(root).store(other, ArtworkId(1), content, now, now, 3)
        store.store(namespace, ArtworkId(1), content, now + 1.seconds, now, 3)
        assertNull(store.load(other, ArtworkId(1), now + 1.seconds, now))
        assertEquals(CacheStatistics(0, 3, 1), FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `backward clock replacement lowers expiry bound and cutoff remains inclusive`() = runTest {
        val store = FileArtworkCacheStore(root)
        store.store(namespace, ArtworkId(1), content, now + 10.seconds, now, 6)
        store.store(namespace, ArtworkId(2), content, now + 20.seconds, now, 6)
        store.store(namespace, ArtworkId(2), content, now + 4.seconds, now, 6)
        store.prune(now + 4.seconds, 6)
        assertEquals(CacheStatistics(0, 6, 2), FileMetadataCacheStore(root).statistics())
        store.prune(now + 5.seconds, 6)
        assertNull(store.load(namespace, ArtworkId(2), now + 5.seconds, now + 5.seconds))
        assertArrayEquals(content, store.load(namespace, ArtworkId(1), now + 5.seconds, now + 5.seconds))
        assertEquals(CacheStatistics(0, 3, 1), FileMetadataCacheStore(root).statistics())
    }

    private fun seed(directory: File, count: Int) {
        val store = FileArtworkCacheStore(directory)
        repeat(count) { store.store(namespace, ArtworkId(it + 1), content, now, now, 100_000) }
    }

    private class CountingFiles : ArtworkCacheFiles {
        val calls = mutableMapOf<String, Int>()
        private fun count(name: String) { calls[name] = (calls[name] ?: 0) + 1 }
        override fun read(file: File): ByteArray { count("read"); return DiskArtworkCacheFiles.read(file) }
        override fun length(file: File): Long { count("length"); return DiskArtworkCacheFiles.length(file) }
        override fun list(directory: File): List<File> { count("list"); return DiskArtworkCacheFiles.list(directory) }
        override fun isDirectory(file: File): Boolean { count("isDirectory"); return DiskArtworkCacheFiles.isDirectory(file) }
        override fun delete(file: File): Boolean { count("delete"); return DiskArtworkCacheFiles.delete(file) }
        override fun writeAtomically(file: File, bytes: ByteArray) {
            count("write")
            DiskArtworkCacheFiles.writeAtomically(file, bytes)
        }
    }
}
