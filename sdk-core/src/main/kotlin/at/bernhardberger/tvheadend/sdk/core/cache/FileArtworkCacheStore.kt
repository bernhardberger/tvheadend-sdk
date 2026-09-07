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

/** Raw artwork and a disposable protobuf index. The runtime serializes all access on IO. */
internal class FileArtworkCacheStore(root: File) {
    private val namespacesRoot = File(root, "tvheadend-sdk")

    fun load(namespace: CacheNamespace, id: ArtworkId, now: Instant, notBefore: Instant): ByteArray? = safely {
        val directory = directory(namespace)
        val entries = readIndex(directory, notBefore)
        val entry = entries[id.value] ?: return@safely null
        val file = File(directory, id.value.toString())
        val bytes = file.readBytes()
        if (bytes.size.toLong() != entry.size || !digest(bytes).contentEquals(entry.digest)) {
            file.delete()
            entries.remove(id.value)
            writeIndex(directory, entries)
            return@safely null
        }
        entries[id.value] = entry.copy(lastAccess = now.toEpochMilliseconds())
        writeIndex(directory, entries)
        bytes
    }

    fun store(namespace: CacheNamespace, id: ArtworkId, bytes: ByteArray, now: Instant, notBefore: Instant, maxBytes: Long) {
        safely {
            val directory = directory(namespace)
            val entries = readIndex(directory, notBefore)
            if (bytes.isNotEmpty() && bytes.size.toLong() <= maxBytes) {
                writeAtomically(File(directory, id.value.toString()), bytes)
                entries[id.value] = ArtworkEntryDto(
                    bytes.size.toLong(), now.toEpochMilliseconds(), now.toEpochMilliseconds(), digest(bytes),
                )
            } else {
                File(directory, id.value.toString()).delete()
                entries.remove(id.value)
            }
            writeIndex(directory, entries)
        }
        prune(notBefore, maxBytes)
    }

    fun remove(namespace: CacheNamespace, id: ArtworkId, notBefore: Instant) {
        safely {
            val directory = directory(namespace)
            val entries = readIndex(directory, notBefore)
            File(directory, id.value.toString()).delete()
            entries.remove(id.value)
            writeIndex(directory, entries)
        }
    }

    /** Retention and byte budget apply across every namespace under this policy root. */
    fun prune(notBefore: Instant, maxBytes: Long) {
        safely {
            val indexes = namespacesRoot.listFiles().orEmpty().filter { it.isDirectory }.associate { namespace ->
                val directory = File(namespace, "artwork")
                directory to readIndex(directory, notBefore)
            }
            val entries = indexes.flatMap { (directory, index) ->
                index.map { (id, entry) -> Triple(directory, id, entry) }
            }.sortedBy { it.third.lastAccess }
            var total = entries.sumOf { it.third.size }
            val changed = mutableSetOf<File>()
            for ((directory, id, entry) in entries) {
                if (total <= maxBytes) break
                if (File(directory, id.toString()).delete()) {
                    indexes.getValue(directory).remove(id)
                    changed.add(directory)
                    total -= entry.size
                }
            }
            changed.forEach { directory -> safely { writeIndex(directory, indexes.getValue(directory)) } }
        }
    }

    private fun readIndex(directory: File, notBefore: Instant): MutableMap<Int, ArtworkEntryDto> {
        val file = File(directory, "index.bin")
        val index = safely {
            ProtoBuf.decodeFromByteArray(ArtworkIndexDto.serializer(), file.readBytes())
                .takeIf { it.schemaVersion == 1 }
        }?.entries.orEmpty().toMutableMap()
        val changed = index.entries.removeAll { (id, entry) ->
            id <= 0 || entry.size <= 0 || entry.storedAt < notBefore.toEpochMilliseconds() ||
                entry.digest.size != SHA256_BYTES || File(directory, id.toString()).length() != entry.size
        }
        directory.listFiles().orEmpty().forEach { child ->
            if (child.name != "index.bin" && child.name.toIntOrNull() !in index) child.delete()
        }
        if (changed || index.isEmpty()) writeIndex(directory, index)
        return index
    }

    private fun writeIndex(directory: File, entries: Map<Int, ArtworkEntryDto>) {
        if (entries.isEmpty()) {
            File(directory, "index.bin").delete()
        } else {
            writeAtomically(
                File(directory, "index.bin"),
                ProtoBuf.encodeToByteArray(ArtworkIndexDto.serializer(), ArtworkIndexDto(1, entries)),
            )
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
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
