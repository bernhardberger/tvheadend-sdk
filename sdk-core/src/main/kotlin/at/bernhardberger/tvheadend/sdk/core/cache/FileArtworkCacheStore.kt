@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Instant

/**
 * Index operations are serialized by the runtime; [read] runs outside its artwork lock.
 * Each mutation publishes its payload before its index shard. Process interruption can lose
 * the interrupted entry, but digest validation never accepts a mismatched payload. Completed
 * writes survive process restart; atomic rename alone makes no power-loss/fsync guarantee.
 */
internal class FileArtworkCacheStore(root: File, private val files: ArtworkCacheFiles = DiskArtworkCacheFiles) {
    private val namespacesRoot = File(root, "tvheadend-sdk")
    private val indexes = mutableMapOf<File, Index>()
    private var namespacesLoaded = false
    var revision: Long = 0
        private set

    internal class Entry(var value: ArtworkEntryDto)
    private class Index(val entries: MutableMap<Int, Entry>, var legacy: Boolean = false) {
        val shards = Array(INDEX_SHARDS) { mutableMapOf<Int, Entry>() }
        val dirtyShards = mutableSetOf<Int>()
        var bytes = entries.values.sumOf { it.value.size }
        // Removing/replacing the oldest entry may leave a conservative lower bound until prune.
        var oldestStoredAt = entries.values.minOfOrNull { it.value.storedAt } ?: Long.MAX_VALUE

        init {
            entries.forEach { (id, entry) -> shards[shard(id)][id] = entry }
            if (legacy) dirtyShards.addAll(shards.indices)
        }

        fun put(id: Int, entry: Entry) {
            val previous = entries.put(id, entry)
            shards[shard(id)][id] = entry
            bytes += entry.value.size - (previous?.value?.size ?: 0)
            oldestStoredAt = minOf(oldestStoredAt, entry.value.storedAt)
            dirtyShards.add(shard(id))
        }

        fun remove(id: Int) {
            bytes -= entries.remove(id)?.value?.size ?: 0
            shards[shard(id)].remove(id)
            dirtyShards.add(shard(id))
        }

        fun refreshOldestStoredAt() {
            oldestStoredAt = entries.values.minOfOrNull { it.value.storedAt } ?: Long.MAX_VALUE
        }
    }
    internal class Read(val file: File, val entry: Entry) {
        // Immutable validation data; touch bookkeeping may change while the file is read.
        val size = entry.value.size
        val digest = entry.value.digest
    }

    fun load(namespace: CacheNamespace, id: ArtworkId, now: Instant, notBefore: Instant): ByteArray? = safely {
        val pending = prepare(namespace, id, notBefore) ?: return@safely null
        val bytes = read(pending)
        complete(pending, bytes, now)
        bytes
    }

    fun prepare(namespace: CacheNamespace, id: ArtworkId, notBefore: Instant): Read? = safely {
        val directory = directory(namespace)
        val index = index(directory, notBefore)
        val entry = index.entries[id.value] ?: return@safely null
        if (entry.value.storedAt < notBefore.toEpochMilliseconds()) {
            files.delete(File(directory, id.value.toString()))
            index.remove(id.value)
            revision++
            return@safely null
        }
        Read(File(directory, id.value.toString()), entry)
    }

    /** Reads and validates one file, without consulting or changing mutable index state. */
    fun read(pending: Read): ByteArray? = safely {
        files.read(pending.file).takeIf {
            it.size.toLong() == pending.size && digest(it).contentEquals(pending.digest)
        }
    }

    fun complete(pending: Read, bytes: ByteArray?, now: Instant) {
        val index = indexes[pending.file.parentFile] ?: return
        val id = pending.file.name.toInt()
        if (index.entries[id] !== pending.entry) return // Replaced, evicted or cleared while reading.
        if (bytes == null) {
            safely { files.delete(pending.file) }
            index.remove(id)
            revision++
        } else {
            pending.entry.value = pending.entry.value.copy(lastAccess = now.toEpochMilliseconds())
        }
        index.dirtyShards.add(shard(id))
    }

    /** Last-access updates are disposable; the runtime batches them and flushes at stop. */
    fun flush() {
        indexes.forEach { (directory, index) ->
            val dirty = index.dirtyShards.iterator()
            while (dirty.hasNext()) {
                val shard = dirty.next()
                safely {
                    writeShard(directory, shard, index.shards[shard].mapValues { it.value.value })
                    dirty.remove()
                }
            }
            if (index.legacy && index.dirtyShards.isEmpty()) safely {
                val legacy = File(directory, "index.bin")
                if (files.delete(legacy) || files.length(legacy) == 0L) index.legacy = false
            }
        }
    }

    /** Must accompany deletion of the policy root, including a partially failed clear. */
    fun invalidate() {
        indexes.clear()
        namespacesLoaded = false
        revision++
    }

    /** Invalidates only the deleted namespace, preserving other pending index writes. */
    fun invalidate(namespace: CacheNamespace) {
        indexes.remove(directory(namespace))
        revision++
    }

    fun store(namespace: CacheNamespace, id: ArtworkId, bytes: ByteArray, now: Instant, notBefore: Instant, maxBytes: Long) {
        safely {
            val directory = directory(namespace)
            val index = index(directory, notBefore)
            if (bytes.isNotEmpty() && bytes.size.toLong() <= maxBytes) {
                files.writeAtomically(File(directory, id.value.toString()), bytes)
                index.put(id.value, Entry(ArtworkEntryDto(
                    bytes.size.toLong(), now.toEpochMilliseconds(), now.toEpochMilliseconds(), digest(bytes),
                )))
            } else {
                files.delete(File(directory, id.value.toString()))
                index.remove(id.value)
            }
            revision++
        }
        prune(notBefore, maxBytes)
    }

    fun remove(namespace: CacheNamespace, id: ArtworkId, notBefore: Instant) {
        safely {
            val directory = directory(namespace)
            val index = index(directory, notBefore)
            files.delete(File(directory, id.value.toString()))
            index.remove(id.value)
            revision++
        }
        flush()
    }

    /**
     * Retention and byte budget apply across every namespace under this policy root. Legacy
     * namespaces are skipped: catalog restore deletes them without holding the artwork lock.
     */
    fun prune(notBefore: Instant, maxBytes: Long) {
        safely {
            discoverNamespaces(notBefore)
            val cutoff = notBefore.toEpochMilliseconds()
            var total = indexes.values.sumOf { it.bytes }
            if (total <= maxBytes && indexes.values.none { it.oldestStoredAt < cutoff }) return@safely
            val entries = indexes.flatMap { (directory, index) ->
                index.entries.map { (id, entry) -> Triple(directory, id, entry.value) }
            }.sortedWith(compareBy<Triple<File, Int, ArtworkEntryDto>> {
                it.third.storedAt >= cutoff
            }.thenBy { it.third.lastAccess }) // Expire first, then evict the least recently used survivors.
            for ((directory, id, entry) in entries) {
                if (entry.storedAt < cutoff || total > maxBytes) {
                    val file = File(directory, id.toString())
                    if (!files.delete(file) && files.length(file) > 0) continue
                    indexes.getValue(directory).remove(id)
                    total -= entry.size
                    revision++
                }
            }
            indexes.values.forEach { it.refreshOldestStoredAt() }
        }
        flush()
    }

    private fun discoverNamespaces(notBefore: Instant) {
        if (namespacesLoaded) return
        // The policy root has one owning runtime. Discover persisted namespaces once; subsequent
        // writes admit their namespace through index(). Clear invalidates this inventory as well.
        files.list(namespacesRoot).forEach { namespace ->
            if (files.isDirectory(namespace) && !LEGACY_NAMESPACE_PATTERN.matches(namespace.name)) {
                index(File(namespace, "artwork"), notBefore)
            }
        }
        namespacesLoaded = true
    }

    private fun index(directory: File, notBefore: Instant): Index = indexes.getOrPut(directory) {
        readIndex(directory, notBefore).also { revision++ }
    }

    private fun readIndex(directory: File, notBefore: Instant): Index {
        val children = files.list(directory).toSet()
        val shardFiles = (0 until INDEX_SHARDS).map { shardFile(directory, it) }.toSet()
        val legacy = children.any { it.name == "index.bin" }
        val previous = if (legacy) safely {
            ProtoBuf.decodeFromByteArray(ArtworkIndexDto.serializer(), files.read(File(directory, "index.bin")))
                .takeIf { it.schemaVersion == 1 }
        } else null
        val entries = previous?.entries.orEmpty().toMutableMap()

        // A shard, including an empty/corrupt shard, supersedes its entire legacy bucket.
        // Publish all buckets before deleting the legacy index: interruption is replay-safe.
        for (bucket in 0 until INDEX_SHARDS) {
            val file = shardFile(directory, bucket)
            if (file !in children) continue
            if (legacy) entries.keys.removeAll { shard(it) == bucket }
            val restored = safely {
                ProtoBuf.decodeFromByteArray(ArtworkIndexDto.serializer(), files.read(file))
                    .takeIf { it.schemaVersion == SHARD_SCHEMA && it.entries.keys.all { id -> id > 0 && shard(id) == bucket } }
            }
            entries.putAll(restored?.entries.orEmpty())
        }
        val invalid = entries.filter { (id, entry) ->
            id <= 0 || entry.size <= 0 || entry.storedAt < notBefore.toEpochMilliseconds() ||
                entry.digest.size != SHA256_BYTES || files.length(File(directory, id.toString())) != entry.size
        }
        invalid.keys.forEach { entries.remove(it) }
        children.forEach { child ->
            if (child.name != "index.bin" && child !in shardFiles &&
                child.name.toIntOrNull() !in entries) files.delete(child)
        }
        return Index(entries.mapValues { Entry(it.value) }.toMutableMap(), legacy).also { index ->
            invalid.keys.filter { it > 0 }.forEach { index.dirtyShards.add(shard(it)) }
        }
    }

    private fun writeShard(directory: File, shard: Int, entries: Map<Int, ArtworkEntryDto>) {
        // Empty shards are tombstones during interrupted legacy migration. Keep them so a
        // failed legacy-index deletion cannot resurrect removed entries on the next restart.
        files.writeAtomically(
            shardFile(directory, shard),
            ProtoBuf.encodeToByteArray(ArtworkIndexDto.serializer(), ArtworkIndexDto(SHARD_SCHEMA, entries)),
        )
    }

    private fun shardFile(directory: File, shard: Int): File = File(directory, "index-$shard.bin")

    private fun directory(namespace: CacheNamespace): File = File(File(namespacesRoot, namespace.value), "artwork")

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // Damaged protobuf may fail inside the decoder rather than with SerializationException.
        null
    }

    private companion object {
        const val SHA256_BYTES = 32
        const val INDEX_SHARDS = 32
        const val SHARD_SCHEMA = 2
        fun shard(id: Int): Int = Math.floorMod(id, INDEX_SHARDS)
    }
}

@Serializable
internal data class ArtworkIndexDto(val schemaVersion: Int, val entries: Map<Int, ArtworkEntryDto>)

@Serializable
internal data class ArtworkEntryDto(val size: Long, val storedAt: Long, val lastAccess: Long, val digest: ByteArray)

/** Narrow filesystem seam for deterministic operation-count and overlapping-read tests. */
internal interface ArtworkCacheFiles {
    fun read(file: File): ByteArray
    fun length(file: File): Long
    fun list(directory: File): List<File>
    fun isDirectory(file: File): Boolean
    fun delete(file: File): Boolean
    fun writeAtomically(file: File, bytes: ByteArray)
}

internal object DiskArtworkCacheFiles : ArtworkCacheFiles {
    override fun read(file: File): ByteArray = file.readBytes()
    override fun length(file: File): Long = file.length()
    override fun list(directory: File): List<File> = directory.listFiles()?.toList() ?: run {
        // A failed enumeration must not be remembered as a successfully discovered empty root.
        if (directory.exists()) throw IOException("Artwork directory enumeration failed")
        emptyList()
    }
    override fun isDirectory(file: File): Boolean = file.isDirectory
    override fun delete(file: File): Boolean = file.delete()
    override fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
