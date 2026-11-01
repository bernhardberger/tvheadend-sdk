@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Identical warm-store workload for baseline and candidate; no host/Android timing gate. */
internal class ArtworkStoreProfileTest {
    // Keep filesystem timings on the checkout's disk, rather than a host /tmp tmpfs.
    private val root = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "artwork-profile-").toFile()
    private val namespace = cacheNamespace("fixture", 9982, "user")
    private val now = Instant.fromEpochSeconds(1_700_000_000)
    private val content = byteArrayOf(1, 2, 3)

    @AfterEach
    fun cleanup() { root.deleteRecursively() }

    @Test
    fun `profile cold first lookup and reconciliation`() {
        val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocations.isThreadAllocatedMemorySupported)
        allocations.isThreadAllocatedMemoryEnabled = true
        println("COLD_PROFILE warmups=5 samples=12 payloadBytes=3 nativeLegacyAndSkew=true")
        println("COLD,count,format,iteration,phase,nanos,allocatedBytes,reads,readBytes,stats,lists,directoryChecks,writes,indexBytes,payloadBytes,deletes")
        for (count in listOf(439, 4_390)) for (format in listOf("native", "legacy", "skewNative")) {
            val directory = File(root, "$count-$format")
            val stride = if (format == "skewNative") 32 else 1
            seed(directory, count, stride)
            val artwork = File(directory, "tvheadend-sdk/${namespace.value}/artwork")
            val legacy = File(artwork, "index.bin").readBytes()
            if (format != "legacy") FileArtworkCacheStore(directory).prune(now, 100_000)
            repeat(17) { iteration ->
                if (format == "legacy") {
                    artwork.listFiles().orEmpty().filter { it.name.startsWith("index") }.forEach { check(it.delete()) }
                    File(artwork, "index.bin").writeBytes(legacy)
                }
                val files = MeasuredFiles()
                val store = FileArtworkCacheStore(directory, files)
                recordCold(count, format, iteration, "firstLookup", files, allocations) {
                    assertArrayEquals(content, store.load(namespace, ArtworkId(1), now, now))
                }
                recordCold(count, format, iteration, "reconcile", files, allocations) {
                    store.prune(now, 100_000)
                }
                val newId = ArtworkId(count * stride + 1)
                recordCold(count, format, iteration, "newStore", files, allocations) {
                    store.store(namespace, newId, content, now, now, 100_000)
                }
                // A second instance verifies both untouched and touched entries after maintenance.
                val restarted = FileArtworkCacheStore(directory)
                assertArrayEquals(content, restarted.load(namespace, ArtworkId((count - 1) * stride + 1), now, now))
                assertArrayEquals(content, restarted.load(namespace, ArtworkId(1), now, now))
                assertArrayEquals(content, restarted.load(namespace, newId, now, now))
                store.remove(namespace, newId, now)
            }
        }
    }

    private fun recordCold(
        count: Int,
        format: String,
        iteration: Int,
        phase: String,
        files: MeasuredFiles,
        allocations: ThreadMXBean,
        block: () -> Unit,
    ) {
        files.reset()
        val thread = Thread.currentThread().id
        val allocated = allocations.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        block()
        val elapsed = System.nanoTime() - started
        val bytes = allocations.getThreadAllocatedBytes(thread) - allocated
        if (iteration >= 5) println("COLD,$count,$format,${iteration - 5},$phase,$elapsed,$bytes,${files.reads},${files.readBytes},${files.stats},${files.lists},${files.directoryChecks},${files.writes},${files.indexBytes},${files.payloadBytes},${files.deletes}")
    }

    @Test
    fun `profile populated cache with thirty two durable stores`() {
        val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(allocations.isThreadAllocatedMemorySupported)
        allocations.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        println("ARTWORK_PROFILE hostJVM=${System.getProperty("java.version")} warmups=5 samples=12 batch=32 payloadBytes=3")
        println("count,iteration,operation,nanos,allocatedBytes,indexBytes,payloadBytes,writes,reads,stats,lists,deletes,indexWriteNanos,payloadWriteNanos")
        for (count in listOf(439, 4_390)) {
            val directory = File(root, count.toString())
            seed(directory, count)
            val files = MeasuredFiles()
            val store = FileArtworkCacheStore(directory, files)
            store.prune(now, 100_000)
            repeat(17) { iteration ->
                val samples = ArrayList<String>(32)
                repeat(32) { operation ->
                    files.reset()
                    val allocated = allocations.getThreadAllocatedBytes(thread)
                    val started = System.nanoTime()
                    store.store(namespace, ArtworkId(count + operation + 1), content, now, now, 100_000)
                    val elapsed = System.nanoTime() - started
                    val bytes = allocations.getThreadAllocatedBytes(thread) - allocated
                    assertEquals(2, files.writes)
                    assertEquals(0, files.reads + files.stats + files.lists + files.deletes)
                    if (iteration >= 5) samples += "$count,${iteration - 5},$operation,$elapsed,$bytes,${files.indexBytes},${files.payloadBytes},${files.writes},${files.reads},${files.stats},${files.lists},${files.deletes},${files.indexWriteNanos},${files.payloadWriteNanos}"
                }
                // Restart before flushing to prove the measured stores already committed.
                val restarted = FileArtworkCacheStore(directory)
                repeat(32) { operation ->
                    assertArrayEquals(content, restarted.load(namespace, ArtworkId(count + operation + 1), now, now))
                    store.remove(namespace, ArtworkId(count + operation + 1), now)
                }
                samples.forEach(::println)
            }
        }
    }

    private fun seed(directory: File, count: Int, stride: Int = 1) {
        val artwork = File(directory, "tvheadend-sdk/${namespace.value}/artwork").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val entries = (0 until count).map { it * stride + 1 }.associateWith { id ->
            File(artwork, id.toString()).writeBytes(content)
            ArtworkEntryDto(content.size.toLong(), now.toEpochMilliseconds(), now.toEpochMilliseconds(), digest)
        }
        File(artwork, "index.bin").writeBytes(ProtoBuf.encodeToByteArray(ArtworkIndexDto.serializer(), ArtworkIndexDto(1, entries)))
    }

    private class MeasuredFiles : ArtworkCacheFiles by DiskArtworkCacheFiles {
        var writes = 0
        var reads = 0
        var readBytes = 0L
        var stats = 0
        var lists = 0
        var directoryChecks = 0
        var deletes = 0
        var indexBytes = 0L
        var payloadBytes = 0L
        var indexWriteNanos = 0L
        var payloadWriteNanos = 0L
        fun reset() {
            writes = 0; reads = 0; readBytes = 0; stats = 0; lists = 0; directoryChecks = 0; deletes = 0
            indexBytes = 0; payloadBytes = 0; indexWriteNanos = 0; payloadWriteNanos = 0
        }
        override fun read(file: File): ByteArray {
            reads++
            return DiskArtworkCacheFiles.read(file).also { readBytes += it.size }
        }
        override fun length(file: File): Long { stats++; return DiskArtworkCacheFiles.length(file) }
        override fun list(directory: File): List<File> { lists++; return DiskArtworkCacheFiles.list(directory) }
        override fun isDirectory(file: File): Boolean { directoryChecks++; return DiskArtworkCacheFiles.isDirectory(file) }
        override fun delete(file: File): Boolean { deletes++; return DiskArtworkCacheFiles.delete(file) }
        override fun writeAtomically(file: File, bytes: ByteArray) {
            writes++
            val index = file.name.startsWith("index")
            if (index) indexBytes += bytes.size else payloadBytes += bytes.size
            val started = System.nanoTime()
            DiskArtworkCacheFiles.writeAtomically(file, bytes)
            val elapsed = System.nanoTime() - started
            if (index) indexWriteNanos += elapsed else payloadWriteNanos += elapsed
        }
    }
}
