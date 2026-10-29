@file:OptIn(ExperimentalSerializationApi::class, kotlin.io.path.ExperimentalPathApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.io.path.deleteRecursively
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private const val CACHE_ROOT_DIR_NAME = "tvheadend-sdk"
private const val CATALOG_FILE_NAME = "catalog.bin"
private const val EPG_FILE_NAME = "epg.bin"
private const val TMP_FILE_SUFFIX = ".tmp"
internal val LEGACY_NAMESPACE_PATTERN = Regex("[0-9a-f]{32}")
// EPG5 uses JSON event records and start/stop framing to skip expired payloads before decoding.
private const val EPG_STREAM_MAGIC = 0x45504735
private const val MAX_EPG_CACHE_RECORDS = 250_000
private const val MAX_EPG_CACHE_RECORD_BYTES = 1_048_576

/**
 * File-backed [MetadataCacheStore] rooted at `<root>/tvheadend-sdk/<namespace>/`.
 *
 * Every namespace stores its catalog as `catalog.bin` and its EPG snapshot as `epg.bin`.
 * EPG records are length-prefixed with standard data streams, keeping decoding bounded to one
 * record. Event DTOs use kotlinx JSON: measured decoding is cheaper than kotlinx ProtoBuf here.
 * The small coverage/receipt records and the catalog retain their protobuf representation.
 * Both files are written atomically through a same-directory `.tmp`
 * file and [Files.move] with [StandardCopyOption.ATOMIC_MOVE]. Read and write failures never
 * throw: missing, expired, schema-mismatched, or corrupt files are deleted and treated as absent.
 */
internal class FileMetadataCacheStore(
    root: File,
    private val clock: Clock = Clock.System,
    private val listFiles: (File) -> Array<File>? = File::listFiles,
) : MetadataCacheStore {

    private val namespacesRoot: File = File(root, CACHE_ROOT_DIR_NAME)

    override suspend fun loadCatalog(namespace: CacheNamespace, notBefore: Instant): CachedCatalog? {
        // Legacy identities cannot be restored safely and would otherwise never age out.
        listFiles(namespacesRoot).orEmpty()
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
        return decodeModel(file) {
            CachedCatalog(envelope.payload.toModel(), MetadataRefreshTimes(
                envelope.lastSynchronizedAtEpochMillis?.let(Instant::fromEpochMilliseconds),
                envelope.lastUpdatedAtEpochMillis?.let(Instant::fromEpochMilliseconds),
            ))
        }
    }

    override suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): CachedEpg? {
        val file = epgFile(namespace)
        if (!file.isFile) return null
        return try {
            val now = Instant.fromEpochSeconds(clock.now().epochSeconds)
            val through = now + 24.hours
            val past = now - 6.hours
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == EPG_STREAM_MAGIC)
                require(Instant.fromEpochMilliseconds(input.readLong()) >= notBefore)
                val times = input.readRecord(MetadataRefreshTimesDto.serializer()).toModel()
                val coverages = List(input.readRecordCount()) {
                    currentCoroutineContext().ensureActive()
                    val coverage = input.readRecord(EpgCoverageDto.serializer()).toModel()
                    val queriedTo = coverage.queriedTo?.coerceAtMost(through)
                    val from = maxOf(coverage.coveredFrom, past)
                    val to = minOf(coverage.coveredTo, through)
                    if (from > to) EpgCoverage.empty(coverage.channelId, queriedTo)
                    else EpgCoverage.create(coverage.channelId, from, to, queriedTo)
                }
                val queries = LinkedHashMap<ChannelId, Instant>()
                repeat(input.readRecordCount()) {
                    currentCoroutineContext().ensureActive()
                    val record = input.readRecord(EpgQueryTimeDto.serializer())
                    require(queries.put(ChannelId(record.channelId),
                        Instant.fromEpochMilliseconds(record.queriedAtEpochMillis)) == null)
                }
                val events = input.readNearEvents(past, through)
                CachedEpg(EpgSnapshot.create(events, coverages), times, queries)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            file.delete()
            null
        } catch (_: RuntimeException) {
            file.delete()
            null
        }
    }

    override suspend fun storeCatalog(namespace: CacheNamespace, catalog: CachedCatalog, storedAt: Instant) {
        writeEnvelope(
            file = catalogFile(namespace),
            envelope = CatalogEnvelopeDto(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                storedAtEpochMillis = storedAt.toEpochMilliseconds(),
                payload = catalog.catalog.toDto(),
                lastSynchronizedAtEpochMillis = catalog.refreshTimes.lastSynchronizedAt?.toEpochMilliseconds(),
                lastUpdatedAtEpochMillis = catalog.refreshTimes.lastUpdatedAt?.toEpochMilliseconds(),
            ),
            serializer = CatalogEnvelopeDto.serializer(),
        )
    }

    override suspend fun storeEpg(namespace: CacheNamespace, snapshot: CachedEpg, storedAt: Instant) {
        val file = epgFile(namespace)
        val tmp = File(file.parentFile, "${file.name}$TMP_FILE_SUFFIX")
        try {
            require(snapshot.snapshot.events.size <= MAX_EPG_CACHE_RECORDS)
            require(snapshot.snapshot.coverages.size <= MAX_EPG_CACHE_RECORDS)
            require(snapshot.lastQueriedAt.size <= MAX_EPG_CACHE_RECORDS)
            file.parentFile.mkdirs()
            DataOutputStream(tmp.outputStream().buffered()).use { output ->
                output.writeInt(EPG_STREAM_MAGIC)
                output.writeLong(storedAt.toEpochMilliseconds())
                output.writeRecord(MetadataRefreshTimesDto.serializer(), snapshot.refreshTimes.toDto())
                output.writeInt(snapshot.snapshot.coverages.size)
                for (coverage in snapshot.snapshot.coverages) {
                    currentCoroutineContext().ensureActive()
                    output.writeRecord(EpgCoverageDto.serializer(), coverage.toDto())
                }
                output.writeInt(snapshot.lastQueriedAt.size)
                for ((channelId, time) in snapshot.lastQueriedAt) {
                    currentCoroutineContext().ensureActive()
                    output.writeRecord(EpgQueryTimeDto.serializer(),
                        EpgQueryTimeDto(channelId.value, time.toEpochMilliseconds()))
                }
                output.writeInt(snapshot.snapshot.events.size)
                for (event in snapshot.snapshot.events.sortedWith(compareBy<EpgEvent> { it.start }.thenBy { it.id.value })) {
                    currentCoroutineContext().ensureActive()
                    // Standard framing keeps expired and far event payloads out of the decoder.
                    output.writeLong(event.start.epochSeconds)
                    output.writeLong(event.stop.epochSeconds)
                    output.writeRecordBytes(Json.encodeToString(EpgEventDto.serializer(), event.toDto()).encodeToByteArray())
                }
            }
            currentCoroutineContext().ensureActive()
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            // Keep the last complete cache if the replacement cannot be written.
        } catch (_: RuntimeException) {
            // Cache persistence is optional, including records beyond the bounded cache format.
        } finally {
            tmp.delete()
        }
    }

    override suspend fun clear() {
        try {
            // Path deletion unlinks symbolic links instead of deleting their targets.
            namespacesRoot.toPath().deleteRecursively()
        } catch (_: IOException) {
            // Best effort, like java.io deletion; what remains is retried by the next clear.
        }
    }

    override suspend fun statistics(): CacheStatistics {
        val namespaceDirs = listFiles(namespacesRoot)?.filter { entry -> entry.isDirectory } ?: emptyList()
        val metadataBytes = namespaceDirs.sumOf { dir ->
            File(dir, CATALOG_FILE_NAME).lengthIfFile() + File(dir, EPG_FILE_NAME).lengthIfFile()
        }
        val artwork = namespaceDirs.flatMap { dir ->
            listFiles(File(dir, "artwork")).orEmpty().filter { it.isFile && it.name.toIntOrNull()?.let { id -> id > 0 } == true }
        }
        return CacheStatistics(metadataBytes, artwork.sumOf { it.length() }, artwork.size)
    }

    override suspend fun metadataBytes(): Long = listFiles(namespacesRoot).orEmpty()
        .filter { it.isDirectory }
        .sumOf { File(it, CATALOG_FILE_NAME).lengthIfFile() + File(it, EPG_FILE_NAME).lengthIfFile() }

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

    private fun DataInputStream.readRecordCount(): Int =
        readInt().also { require(it in 0..MAX_EPG_CACHE_RECORDS) }

    private suspend fun DataInputStream.readNearEvents(past: Instant, through: Instant): List<EpgEvent> {
        val count = readRecordCount()
        val events = ArrayList<EpgEvent>()
        val discardBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        // Decoder and buffers belong to this restore, not to individual event records.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var bytes = ByteBuffer.allocate(0)
        var chars = CharBuffer.allocate(0)
        var previousStart = Long.MIN_VALUE
        repeat(count) {
            currentCoroutineContext().ensureActive()
            val start = readLong()
            require(start >= previousStart)
            if (start > through.epochSeconds) return events
            previousStart = start
            val stop = readLong()
            require(stop >= start)
            val size = readRecordSize()
            if (stop < past.epochSeconds) {
                // FileInputStream.skip can seek past EOF; readFully also detects a truncated tail.
                discardRecordBytes(size, discardBuffer)
            } else {
                if (bytes.capacity() < size) {
                    bytes = ByteBuffer.allocate(size)
                    // UTF-8 produces at most one UTF-16 code unit per byte (including surrogate
                    // pairs). Both capacities stay within the validated record-size bound.
                    chars = CharBuffer.allocate(size)
                }
                readFully(bytes.array(), 0, size)
                bytes.clear().limit(size)
                chars.clear().limit(size)
                decoder.reset()
                val decoded = decoder.decode(bytes, chars, true)
                if (!decoded.isUnderflow) decoded.throwException()
                require(!bytes.hasRemaining())
                val flushed = decoder.flush(chars)
                if (!flushed.isUnderflow) flushed.throwException()
                chars.flip()
                // Only the written characters become an immutable String; no buffer view escapes.
                val dto = Json.decodeFromString(EpgEventDto.serializer(), chars.toString())
                require(Instant.fromEpochMilliseconds(dto.start).epochSeconds == start &&
                    Instant.fromEpochMilliseconds(dto.stop).epochSeconds == stop)
                events.add(dto.toModel())
            }
        }
        require(read() == -1)
        return events
    }

    private fun <T> DataInputStream.readRecord(serializer: KSerializer<T>): T {
        val size = readRecordSize()
        val bytes = ByteArray(size)
        readFully(bytes)
        return ProtoBuf.decodeFromByteArray(serializer, bytes)
    }

    private fun <T> DataOutputStream.writeRecord(serializer: KSerializer<T>, value: T) {
        writeRecordBytes(ProtoBuf.encodeToByteArray(serializer, value))
    }

    private fun DataInputStream.readRecordSize(): Int =
        readInt().also { require(it in 0..MAX_EPG_CACHE_RECORD_BYTES) }

    private fun DataInputStream.discardRecordBytes(size: Int, buffer: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size)
            readFully(buffer, 0, count)
            remaining -= count
        }
    }

    private fun DataOutputStream.writeRecordBytes(bytes: ByteArray) {
        require(bytes.size <= MAX_EPG_CACHE_RECORD_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }
}

private fun CatalogEnvelopeDto.isFreshEnough(expectedSchemaVersion: Int, notBefore: Instant): Boolean =
    schemaVersion == expectedSchemaVersion && Instant.fromEpochMilliseconds(storedAtEpochMillis) >= notBefore
