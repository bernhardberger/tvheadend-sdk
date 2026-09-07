@file:OptIn(ExperimentalSerializationApi::class, kotlin.io.path.ExperimentalPathApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.io.path.deleteRecursively
import kotlin.time.Instant

private const val CACHE_ROOT_DIR_NAME = "tvheadend-sdk"
private const val CATALOG_FILE_NAME = "catalog.bin"
private const val EPG_FILE_NAME = "epg.bin"
private const val TMP_FILE_SUFFIX = ".tmp"
private val LEGACY_NAMESPACE_PATTERN = Regex("[0-9a-f]{32}")

/**
 * File-backed [MetadataCacheStore] rooted at `<root>/tvheadend-sdk/<namespace>/`.
 *
 * Every namespace stores its catalog as `catalog.bin` and its EPG snapshot as `epg.bin`, both
 * kotlinx-serialization protobuf envelopes written atomically through a same-directory `.tmp`
 * file and [Files.move] with [StandardCopyOption.ATOMIC_MOVE]. Read and write failures never
 * throw: missing, expired, schema-mismatched, or corrupt files are deleted and treated as absent.
 */
internal class FileMetadataCacheStore(root: File) : MetadataCacheStore {

    private val namespacesRoot: File = File(root, CACHE_ROOT_DIR_NAME)

    override suspend fun loadCatalog(namespace: CacheNamespace, notBefore: Instant): ChannelCatalog? {
        // Legacy identities cannot be restored safely and would otherwise never age out.
        namespacesRoot.listFiles().orEmpty()
            .filter { LEGACY_NAMESPACE_PATTERN.matches(it.name) }
            .forEach {
                try {
                    // Path deletion does not traverse symbolic links outside this namespace.
                    it.toPath().deleteRecursively()
                } catch (_: IOException) {
                    // Best effort, like normal cache eviction; retry on a later restore.
                }
            }
        val file = catalogFile(namespace)
        val envelope = readEnvelope(file, CatalogEnvelopeDto.serializer()) ?: return null
        if (!envelope.isFreshEnough(CATALOG_SCHEMA_VERSION, notBefore)) {
            file.delete()
            return null
        }
        return decodeModel(file) { envelope.payload.toModel() }
    }

    override suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): EpgSnapshot? {
        val file = epgFile(namespace)
        val envelope = readEnvelope(file, EpgEnvelopeDto.serializer()) ?: return null
        if (!envelope.isFreshEnough(EPG_SCHEMA_VERSION, notBefore)) {
            file.delete()
            return null
        }
        return decodeModel(file) { envelope.payload.toModel() }
    }

    override suspend fun storeCatalog(namespace: CacheNamespace, catalog: ChannelCatalog, storedAt: Instant) {
        writeEnvelope(
            file = catalogFile(namespace),
            envelope = CatalogEnvelopeDto(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                storedAtEpochMillis = storedAt.toEpochMilliseconds(),
                payload = catalog.toDto(),
            ),
            serializer = CatalogEnvelopeDto.serializer(),
        )
    }

    override suspend fun storeEpg(namespace: CacheNamespace, snapshot: EpgSnapshot, storedAt: Instant) {
        writeEnvelope(
            file = epgFile(namespace),
            envelope = EpgEnvelopeDto(
                schemaVersion = EPG_SCHEMA_VERSION,
                storedAtEpochMillis = storedAt.toEpochMilliseconds(),
                payload = snapshot.toDto(),
            ),
            serializer = EpgEnvelopeDto.serializer(),
        )
    }

    override suspend fun clear() {
        namespacesRoot.deleteRecursively()
    }

    override suspend fun statistics(): CacheStatistics {
        val namespaceDirs = namespacesRoot.listFiles()?.filter { entry -> entry.isDirectory } ?: emptyList()
        val metadataBytes = namespaceDirs.sumOf { dir ->
            File(dir, CATALOG_FILE_NAME).lengthIfFile() + File(dir, EPG_FILE_NAME).lengthIfFile()
        }
        val artwork = namespaceDirs.flatMap { dir ->
            File(dir, "artwork").listFiles().orEmpty().filter { it.isFile && it.name.toIntOrNull()?.let { id -> id > 0 } == true }
        }
        return CacheStatistics(metadataBytes, artwork.sumOf { it.length() }, artwork.size)
    }

    private fun catalogFile(namespace: CacheNamespace): File = File(namespaceDir(namespace), CATALOG_FILE_NAME)

    private fun epgFile(namespace: CacheNamespace): File = File(namespaceDir(namespace), EPG_FILE_NAME)

    private fun namespaceDir(namespace: CacheNamespace): File = File(namespacesRoot, namespace.value)

    private fun <T> readEnvelope(file: File, serializer: KSerializer<T>): T? {
        if (!file.isFile) return null
        return try {
            ProtoBuf.decodeFromByteArray(serializer, file.readBytes())
        } catch (_: IOException) {
            file.delete()
            null
        } catch (_: RuntimeException) {
            // Covers SerializationException and any decoder fault on damaged bytes; a cache file
            // must never be able to fail the connection.
            file.delete()
            null
        }
    }

    /** A payload that decodes but violates a model invariant is treated like a corrupt file. */
    private inline fun <T> decodeModel(file: File, toModel: () -> T): T? = try {
        toModel()
    } catch (_: IllegalArgumentException) {
        file.delete()
        null
    }

    private fun <T> writeEnvelope(file: File, envelope: T, serializer: KSerializer<T>) {
        val parent = file.parentFile
        val tmp = File(parent, "${file.name}$TMP_FILE_SUFFIX")
        try {
            parent.mkdirs()
            tmp.writeBytes(ProtoBuf.encodeToByteArray(serializer, envelope))
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: IOException) {
            tmp.delete()
        } catch (_: RuntimeException) {
            tmp.delete()
        }
    }

    private fun File.lengthIfFile(): Long = if (isFile) length() else 0L
}

private fun CatalogEnvelopeDto.isFreshEnough(expectedSchemaVersion: Int, notBefore: Instant): Boolean =
    schemaVersion == expectedSchemaVersion && Instant.fromEpochMilliseconds(storedAtEpochMillis) >= notBefore

private fun EpgEnvelopeDto.isFreshEnough(expectedSchemaVersion: Int, notBefore: Instant): Boolean =
    schemaVersion == expectedSchemaVersion && Instant.fromEpochMilliseconds(storedAtEpochMillis) >= notBefore
