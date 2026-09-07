@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.random.Random
import kotlin.time.Instant

internal class FileMetadataCacheStoreTest {
    @TempDir
    lateinit var root: File

    private val namespace = cacheNamespace("tvh.example.org", 9982, "alice")
    private val storedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun store(): FileMetadataCacheStore = FileMetadataCacheStore(root)

    private fun sampleCatalog(): ChannelCatalog = ChannelCatalog.create(
        channels = listOf(Channel.create(id = ChannelId(1L), name = "Channel One")),
    )

    private fun sampleSnapshot(): EpgSnapshot {
        val start = storedAt
        val stop = Instant.fromEpochMilliseconds(storedAt.toEpochMilliseconds() + 3_600_000L)
        return EpgSnapshot.create(
            events = listOf(
                EpgEvent.create(
                    id = EventId(1L),
                    start = start,
                    stop = stop,
                    title = "Title",
                ),
            ),
        )
    }

    private fun catalogFile(): File = File(File(File(root, "tvheadend-sdk"), namespace.value), "catalog.bin")

    private fun epgFile(): File = File(File(File(root, "tvheadend-sdk"), namespace.value), "epg.bin")

    @Test
    fun `stored catalog and EPG snapshot load back equal`() = runTest {
        val cache = store()
        val catalog = sampleCatalog()
        val snapshot = sampleSnapshot()

        cache.storeCatalog(namespace, catalog, storedAt)
        cache.storeEpg(namespace, snapshot, storedAt)

        assertEquals(catalog, cache.loadCatalog(namespace, storedAt))
        assertEquals(snapshot, cache.loadEpg(namespace, storedAt))
    }

    @Test
    fun `a notBefore boundary after storedAt discards and deletes the file`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)

        val notBefore = Instant.fromEpochMilliseconds(storedAt.toEpochMilliseconds() + 1_000L)
        assertNull(cache.loadCatalog(namespace, notBefore))
        assertFalse(catalogFile().isFile)
    }

    @Test
    fun `random corrupt bytes are discarded and the file is deleted`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        catalogFile().writeBytes(Random.nextBytes(256))

        assertNull(cache.loadCatalog(namespace, storedAt))
        assertFalse(catalogFile().isFile)
    }

    @Test
    fun `a truncated file is discarded and deleted`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        val originalBytes = catalogFile().readBytes()
        catalogFile().writeBytes(originalBytes.copyOf(originalBytes.size / 2))

        assertNull(cache.loadCatalog(namespace, storedAt))
        assertFalse(catalogFile().isFile)
    }

    @Test
    fun `a mismatched schema version is discarded and deleted`() = runTest {
        val file = catalogFile()
        file.parentFile.mkdirs()
        val mismatched = CatalogEnvelopeDto(
            schemaVersion = CATALOG_SCHEMA_VERSION + 1,
            storedAtEpochMillis = storedAt.toEpochMilliseconds(),
            payload = sampleCatalog().toDto(),
        )
        file.writeBytes(ProtoBuf.encodeToByteArray(CatalogEnvelopeDto.serializer(), mismatched))

        val cache = store()
        assertNull(cache.loadCatalog(namespace, storedAt))
        assertFalse(file.isFile)
    }

    @Test
    fun `two namespaces are stored and loaded independently`() = runTest {
        val cache = store()
        val other = cacheNamespace("tvh.example.org", 9982, "bob")
        val catalogA = sampleCatalog()
        val catalogB = ChannelCatalog.create(
            channels = listOf(Channel.create(id = ChannelId(2L), name = "Channel Two")),
        )

        cache.storeCatalog(namespace, catalogA, storedAt)
        cache.storeCatalog(other, catalogB, storedAt)

        assertEquals(catalogA, cache.loadCatalog(namespace, storedAt))
        assertEquals(catalogB, cache.loadCatalog(other, storedAt))
    }

    @Test
    fun `clear empties the root and statistics report empty`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)

        cache.clear()

        assertEquals(CacheStatistics.EMPTY, cache.statistics())
        assertFalse(File(root, "tvheadend-sdk").exists())
    }

    @Test
    fun `statistics metadataBytes equals on-disk file sizes after two stores`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)

        val expectedBytes = catalogFile().length() + epgFile().length()
        val statistics = cache.statistics()

        assertEquals(expectedBytes, statistics.metadataBytes)
        assertEquals(0L, statistics.artworkBytes)
        assertEquals(0, statistics.artworkEntryCount)
    }

    @Test
    fun `storing into a non-writable namespace directory does not throw`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        val namespaceDir = catalogFile().parentFile
        namespaceDir.setWritable(false)
        try {
            // Must complete without throwing regardless of whether the write actually failed;
            // an uncaught exception here fails the test.
            cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        } finally {
            namespaceDir.setWritable(true)
        }
    }
}
