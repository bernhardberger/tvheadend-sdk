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

/** Index operations are serialized by the runtime; [read] runs outside its artwork lock. */
internal class FileArtworkCacheStore(root: File, private val files: ArtworkCacheFiles = DiskArtworkCacheFiles) {
    private val namespacesRoot = File(root, "tvheadend-sdk")
    private val indexes = mutableMapOf<File, Index>()
    private var namespacesLoaded = false
    var revision: Long = 0
        private set

    internal class Entry(var value: ArtworkEntryDto)
    private class Index(val entries: MutableMap<Int, Entry>, var dirty: Boolean = false) {
        var bytes = entries.values.sumOf { it.value.size }
        // Removing/replacing the oldest entry may leave a conservative lower bound until prune.
        var oldestStoredAt = entries.values.minOfOrNull { it.value.storedAt } ?: Long.MAX_VALUE

        fun put(id: Int, entry: Entry) {
            val previous = entries.put(id, entry)
            bytes += entry.value.size - (previous?.value?.size ?: 0)
            oldestStoredAt = minOf(oldestStoredAt, entry.value.storedAt)
            dirty = true
        }

        fun remove(id: Int) {
            bytes -= entries.remove(id)?.value?.size ?: 0
            dirty = true
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
        index.dirty = true
    }

    /** Last-access updates are disposable; the runtime batches them and flushes at stop. */
    fun flush() {
        indexes.forEach { (directory, index) ->
            if (index.dirty) safely {
                writeIndex(directory, index.entries.mapValues { it.value.value })
                index.dirty = false
            }
        }
    }

    /** Must accompany deletion of the policy root, including a partially failed clear. */
    fun invalidate() {
        indexes.clear()
        namespacesLoaded = false
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
            index.dirty = true
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
        Index(readIndex(directory, notBefore).mapValues { Entry(it.value) }.toMutableMap()).also { revision++ }
    }

    private fun readIndex(directory: File, notBefore: Instant): MutableMap<Int, ArtworkEntryDto> {
        val file = File(directory, "index.bin")
        val index = safely {
            ProtoBuf.decodeFromByteArray(ArtworkIndexDto.serializer(), files.read(file))
                .takeIf { it.schemaVersion == 1 }
        }?.entries.orEmpty().toMutableMap()
        val changed = index.entries.removeAll { (id, entry) ->
            id <= 0 || entry.size <= 0 || entry.storedAt < notBefore.toEpochMilliseconds() ||
                entry.digest.size != SHA256_BYTES || files.length(File(directory, id.toString())) != entry.size
        }
        files.list(directory).forEach { child ->
            if (child.name != "index.bin" && child.name.toIntOrNull() !in index) files.delete(child)
        }
        if (changed || index.isEmpty()) writeIndex(directory, index)
        return index
    }

    private fun writeIndex(directory: File, entries: Map<Int, ArtworkEntryDto>) {
        if (entries.isEmpty()) {
            files.delete(File(directory, "index.bin"))
        } else {
            files.writeAtomically(
                File(directory, "index.bin"),
                ProtoBuf.encodeToByteArray(ArtworkIndexDto.serializer(), ArtworkIndexDto(1, entries)),
            )
        }
    }

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
