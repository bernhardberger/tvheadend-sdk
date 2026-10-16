@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

internal class FileMetadataCacheStoreTest {
    @TempDir
    lateinit var root: File

    private val namespace = cacheNamespace("tvh.example.org", 9982, "alice")
    private val storedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private var restoreNow = storedAt
    private fun store(): FileMetadataCacheStore = FileMetadataCacheStore(root, object : Clock {
        override fun now(): Instant = restoreNow
    })

    private fun sampleCatalog(): CachedCatalog = CachedCatalog(ChannelCatalog.create(
        channels = listOf(Channel.create(id = ChannelId(1L), name = "Channel One")),
    ))

    private fun sampleSnapshot(): CachedEpg {
        val start = storedAt
        val stop = Instant.fromEpochMilliseconds(storedAt.toEpochMilliseconds() + 3_600_000L)
        return CachedEpg(EpgSnapshot.create(
            events = listOf(
                EpgEvent.create(
                    id = EventId(1L),
                    channelId = ChannelId(1L),
                    start = start,
                    stop = stop,
                    title = "Title",
                    description = "Programme — Beschreibung",
                ),
            ),
            coverages = listOf(
                EpgCoverage.create(ChannelId(1L), start, stop, stop),
                EpgCoverage.empty(ChannelId(2L), stop),
            ),
        ))
    }

    private fun catalogFile(): File = File(File(File(root, "tvheadend-sdk"), namespace.value), "catalog.bin")

    private fun epgFile(): File = File(File(File(root, "tvheadend-sdk"), namespace.value), "epg.bin")

    @Test
    fun `credits round trip with unknown roles order absence and explicit empty preserved`() = runTest {
        val credits = listOf(EpgCredit("Writer", EpgCreditRole.WRITER),
            EpgCredit("Composer", EpgCreditRole.of("composer")))
        val snapshot = EpgSnapshot.create(events = listOf(credits, null, emptyList()).mapIndexed { index, value ->
            EpgEvent.create(EventId(index.toLong() + 1), start = storedAt, stop = storedAt, credits = value)
        })
        val cache = store()
        cache.storeEpg(namespace, CachedEpg(snapshot), storedAt)
        val restored = requireNotNull(cache.loadEpg(namespace, storedAt)).snapshot
        assertEquals(snapshot, restored)
        assertEquals(listOf(credits, null, emptyList()), restored.events.map { it.credits })
        org.junit.jupiter.api.Assertions.assertSame(EpgCreditRole.WRITER, restored.events[0].credits!![0].role)
    }

    @Test
    fun `EPG3 is discarded once without touching the catalog`() = runTest {
        // Independent old schema: field 23 does not exist in this writer.
        val record = ProtoBuf.encodeToByteArray(LegacyEpgEvent.serializer(), LegacyEpgEvent(
            1, storedAt.toEpochMilliseconds(), storedAt.toEpochMilliseconds(), "Legacy title",
        ))
        epgFile().parentFile.mkdirs()
        java.io.DataOutputStream(epgFile().outputStream()).use {
            it.writeInt(0x45504733)
            it.writeLong(storedAt.toEpochMilliseconds())
            it.writeInt(0) // Empty refresh-times protobuf record.
            it.writeInt(1)
            it.writeInt(record.size)
            it.write(record)
            it.writeInt(0) // Coverages.
            it.writeInt(0) // Query times.
        }
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        assertNull(cache.loadEpg(namespace, storedAt))
        assertFalse(epgFile().exists())
        assertEquals(sampleCatalog(), cache.loadCatalog(namespace, storedAt))
    }

    @Test
    fun `legacy EPG3 schema skips credits when decoding a new record`() {
        val event = EpgEvent.create(
            EventId(1), start = storedAt, stop = storedAt, title = "Credited title",
            credits = listOf(EpgCredit("Actor", EpgCreditRole.ACTOR)),
        )
        val record = ProtoBuf.encodeToByteArray(EpgEventDto.serializer(), event.toDto())
        val restored = ProtoBuf.decodeFromByteArray(LegacyEpgEvent.serializer(), record)
        assertEquals(LegacyEpgEvent(
            1, storedAt.toEpochMilliseconds(), storedAt.toEpochMilliseconds(), "Credited title",
        ), restored)
    }

    @kotlinx.serialization.Serializable
    private data class LegacyEpgEvent(
        @kotlinx.serialization.protobuf.ProtoNumber(1) val id: Long,
        @kotlinx.serialization.protobuf.ProtoNumber(3) val start: Long,
        @kotlinx.serialization.protobuf.ProtoNumber(4) val stop: Long,
        @kotlinx.serialization.protobuf.ProtoNumber(5) val title: String,
    )

    @Test
    fun `restore uses current device seconds caps coverage and retains only recent ended events`() = runTest {
        val cache = store()
        val channel = ChannelId(1)
        val cut = storedAt + 24.hours
        fun event(id: Long, start: Instant, stop: Instant = start) =
            EpgEvent.create(EventId(id), channel, start, stop)
        val events = listOf(
            event(6, cut + 1.hours), event(5, cut), event(4, storedAt),
            event(3, storedAt - 7.hours, storedAt - 6.hours),
            event(2, storedAt - 8.hours, storedAt - 7.hours),
            event(1, storedAt - 9.hours, storedAt + 1.hours),
        )
        val snapshot = CachedEpg(EpgSnapshot.create(events, listOf(
            EpgCoverage.create(channel, storedAt - 9.hours, storedAt + 168.hours, storedAt + 168.hours),
            EpgCoverage.empty(ChannelId(2), storedAt + 168.hours),
            EpgCoverage.create(ChannelId(3), storedAt + 48.hours, storedAt + 168.hours),
        )), lastQueriedAt = mapOf(channel to storedAt))
        cache.storeEpg(namespace, snapshot, storedAt)
        val bytes = epgFile().readBytes()
        restoreNow = storedAt + 999.milliseconds
        val restored = requireNotNull(cache.loadEpg(namespace, storedAt))
        assertEquals(listOf(1L, 3L, 4L, 5L), restored.snapshot.events.map { it.id.value })
        assertTrue(restored.snapshot.coverages.all { it.knownTo == null || it.knownTo!! <= cut })
        assertEquals(cut, restored.snapshot.coverages[0].coveredTo)
        assertEquals(storedAt - 6.hours, restored.snapshot.coverages[0].coveredFrom)
        assertTrue(restored.snapshot.coverages[2].isEmpty)
        assertEquals(snapshot.lastQueriedAt, restored.lastQueriedAt)
        // The restore cut is taken at load time, not the write timestamp; the full file is retained.
        restoreNow = storedAt + 2.hours
        assertTrue(requireNotNull(cache.loadEpg(namespace, storedAt)).snapshot.events.any { it.id == EventId(6) })
        assertTrue(bytes.contentEquals(epgFile().readBytes()))
    }

    @Test
    fun `intersected coverage preserves proven gaps even when no events remain in the window`() = runTest {
        val cache = store()
        val cut = storedAt + 24.hours
        for (expired in listOf(false, true)) {
            val firstStart = if (expired) storedAt - 8.hours else storedAt
            val snapshot = EpgSnapshot.create(events = listOf(
                EpgEvent.create(EventId(1), ChannelId(1), firstStart, firstStart + 1.hours),
                EpgEvent.create(EventId(2), ChannelId(1), storedAt + 48.hours, storedAt + 49.hours),
            ), coverages = listOf(EpgCoverage.create(ChannelId(1), firstStart, storedAt + 49.hours)))
            cache.storeEpg(namespace, CachedEpg(snapshot), storedAt)
            val restored = requireNotNull(cache.loadEpg(namespace, storedAt)).snapshot
            assertEquals(if (expired) 0 else 1, restored.events.size)
            val coverage = restored.coverages.single()
            assertFalse(coverage.isEmpty)
            assertEquals(if (expired) storedAt - 6.hours else storedAt, coverage.coveredFrom)
            assertEquals(cut, coverage.coveredTo)
            assertEquals(cut, coverage.knownTo)
        }
    }

    @Test
    fun `prefix mismatch and out of order decoded prefixes delete the file`() = runTest {
        val cache = store()
        val snapshot = EpgSnapshot.create(events = listOf(1L, 2L).map { id ->
            val start = storedAt + id.toInt().hours
            EpgEvent.create(EventId(id), start = start, stop = start)
        })
        for (reverse in listOf(false, true)) {
            cache.storeEpg(namespace, CachedEpg(snapshot), storedAt)
            RandomAccessFile(epgFile(), "rw").use { file ->
                file.seek(28) // Magic, timestamp, empty times record, coverage/query/event counts.
                if (reverse) {
                    val records = List(2) {
                        val offset = file.filePointer
                        file.readLong()
                        val length = file.readInt()
                        file.seek(offset)
                        ByteArray(12 + length).also(file::readFully)
                    }
                    file.seek(28)
                    file.write(records[1])
                    file.write(records[0])
                } else {
                    file.writeLong(storedAt.epochSeconds) // Prefix disagrees with the valid DTO.
                }
            }
            assertNull(cache.loadEpg(namespace, storedAt))
            assertFalse(epgFile().exists())
        }
    }

    @Test
    fun `EPG4 writes all events ordered by start then id and does not decode the far tail`() = runTest {
        val cache = store()
        val events = listOf(3L, 2L, 1L).map { id ->
            val start = if (id == 3L) storedAt + 25.hours else storedAt
            EpgEvent.create(EventId(id), start = start, stop = start)
        }
        cache.storeEpg(namespace, CachedEpg(EpgSnapshot.create(events)), storedAt)
        RandomAccessFile(epgFile(), "rw").use { file ->
            assertEquals(0x45504734, file.readInt())
            file.readLong()
            file.seek(file.filePointer + 4 + file.readInt()) // Empty refresh-times record.
            assertEquals(0, file.readInt()) // Coverage count.
            assertEquals(0, file.readInt()) // Query count.
            assertEquals(3, file.readInt()) // Full snapshot persisted, not just the near window.
            val ids = mutableListOf<Long>()
            repeat(2) {
                assertEquals(storedAt.epochSeconds, file.readLong())
                val record = ByteArray(file.readInt()).also(file::readFully)
                ids += ProtoBuf.decodeFromByteArray(EpgEventDto.serializer(), record).id
            }
            assertEquals(listOf(1L, 2L), ids)
            assertEquals((storedAt + 25.hours).epochSeconds, file.readLong())
            val tail = file.filePointer
            val record = ByteArray(file.readInt()).also(file::readFully)
            assertEquals(3L, ProtoBuf.decodeFromByteArray(EpgEventDto.serializer(), record).id)
            // No readable payload remains after the first out-of-window start. A decoder/read
            // crossing this boundary would reject the file instead of returning the near guide.
            file.setLength(tail)
        }
        assertEquals(listOf(1L, 2L), requireNotNull(cache.loadEpg(namespace, storedAt)).snapshot.events.map { it.id.value })
        assertTrue(epgFile().exists())
        restoreNow = storedAt + 2.hours
        assertNull(cache.loadEpg(namespace, storedAt))
        assertFalse(epgFile().exists())
    }

    @Test
    fun `catalog v1 absent optional times remain unknown with usable content`() = runTest {
        val catalog = sampleCatalog().catalog
        val legacy = LegacyCatalogEnvelope(1, storedAt.toEpochMilliseconds(), catalog.toDto())
        catalogFile().parentFile.mkdirs()
        catalogFile().writeBytes(ProtoBuf.encodeToByteArray(LegacyCatalogEnvelope.serializer(), legacy))
        val restored = requireNotNull(store().loadCatalog(namespace, storedAt))
        assertEquals(catalog, restored.catalog)
        assertEquals(MetadataRefreshTimes(), restored.refreshTimes)
        assertTrue(catalogFile().isFile)
    }

    @Test
    fun `EPG2 is invalidated while catalog remains usable`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        java.io.DataOutputStream(epgFile().outputStream()).use {
            it.writeInt(0x45504732)
            it.writeLong(storedAt.toEpochMilliseconds())
            it.writeInt(0) // EPG2 events
            it.writeInt(0) // EPG2 coverages
        }
        assertNull(cache.loadEpg(namespace, storedAt))
        assertFalse(epgFile().exists())
        assertEquals(sampleCatalog(), cache.loadCatalog(namespace, storedAt))
    }

    @Test
    fun `EPG4 and catalog preserve receipt times independently of storedAt`() = runTest {
        val times = MetadataRefreshTimes(Instant.fromEpochMilliseconds(100), Instant.fromEpochMilliseconds(234))
        val queries = mapOf(ChannelId(1) to Instant.fromEpochMilliseconds(234), ChannelId(2) to storedAt)
        val catalog = sampleCatalog().copy(refreshTimes = times)
        val epg = sampleSnapshot().copy(refreshTimes = times, lastQueriedAt = queries)
        val cache = store()
        cache.storeCatalog(namespace, catalog, storedAt)
        cache.storeEpg(namespace, epg, storedAt)
        assertEquals(catalog, cache.loadCatalog(namespace, storedAt))
        assertEquals(epg, cache.loadEpg(namespace, storedAt))
        RandomAccessFile(epgFile(), "r").use { assertEquals(0x45504734, it.readInt()) }
    }

    @Test
    fun `query time count and record length are bounded before allocation`() = runTest {
        val payload = CachedEpg(EpgSnapshot.create(), lastQueriedAt = mapOf(ChannelId(1) to storedAt))
        for (offset in listOf(20L, 24L)) {
            for (invalid in listOf(-1, Int.MAX_VALUE)) {
                val cache = store()
                cache.storeEpg(namespace, payload, storedAt)
                RandomAccessFile(epgFile(), "rw").use { it.seek(offset); it.writeInt(invalid) }
                assertNull(cache.loadEpg(namespace, storedAt))
                assertFalse(epgFile().exists())
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class LegacyCatalogEnvelope(
        @kotlinx.serialization.protobuf.ProtoNumber(1) val schemaVersion: Int,
        @kotlinx.serialization.protobuf.ProtoNumber(2) val storedAtEpochMillis: Long,
        @kotlinx.serialization.protobuf.ProtoNumber(3) val payload: ChannelCatalogDto,
    )

    @Test
    fun `large guide round trips through the record stream`() = runTest {
        val snapshot = EpgSnapshot.create(events = List(10_000) { index ->
            EpgEvent.create(
                id = EventId(index.toLong() + 1),
                start = storedAt,
                stop = storedAt,
                description = "$index:" + "programme description ".repeat(100),
            )
        })
        val cache = store()
        cache.storeEpg(namespace, CachedEpg(snapshot), storedAt)
        val restored = requireNotNull(cache.loadEpg(namespace, storedAt)).snapshot
        restored.prepareForObservation()
        assertEquals(snapshot, restored)
        assertEquals(10_000, restored.eventsById.size)
        assertEquals(10_000, restored.eventsByChannel[null]?.size)
        assertFalse(File(epgFile().parentFile, "epg.bin.tmp").exists())
    }

    @Test
    fun `unrecognized EPG format is discarded and can be replaced`() = runTest {
        val cache = store()
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        RandomAccessFile(epgFile(), "rw").use { it.writeInt(0) }

        assertNull(cache.loadEpg(namespace, storedAt))
        assertFalse(epgFile().exists())

        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        assertEquals(sampleSnapshot(), cache.loadEpg(namespace, storedAt))
    }

    @Test
    fun `invalid EPG record counts and lengths are rejected before allocation`() = runTest {
        // Empty times record occupies a four-byte zero length after the stream header.
        for (offset in listOf(12L, 16L, 20L)) {
            for (invalid in listOf(-1, Int.MAX_VALUE)) {
                val cache = store()
                cache.storeEpg(namespace, sampleSnapshot(), storedAt)
                RandomAccessFile(epgFile(), "rw").use { file ->
                    file.seek(offset)
                    file.writeInt(invalid)
                }
                assertNull(cache.loadEpg(namespace, storedAt))
                assertFalse(epgFile().exists())
            }
        }
    }

    @Test
    fun `EPG truncation trailing bytes and expired timestamps invalidate the cache`() = runTest {
        val cache = store()
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        RandomAccessFile(epgFile(), "rw").use { it.setLength(it.length() - 1) }
        assertNull(cache.loadEpg(namespace, storedAt))
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        epgFile().appendBytes(byteArrayOf(1))
        assertNull(cache.loadEpg(namespace, storedAt))
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        assertNull(cache.loadEpg(namespace, Instant.fromEpochMilliseconds(
            storedAt.toEpochMilliseconds() + 1)))
        assertFalse(epgFile().exists())
    }

    @Test
    fun `oversized EPG replacement preserves the last complete cache`() = runTest {
        val cache = store()
        val previous = sampleSnapshot()
        cache.storeEpg(namespace, previous, storedAt)
        val oversized = EpgSnapshot.create(events = listOf(EpgEvent.create(
            id = EventId(2), start = storedAt, stop = storedAt,
            description = "x".repeat(1_048_577),
        )))
        cache.storeEpg(namespace, CachedEpg(oversized), storedAt)
        assertEquals(previous, cache.loadEpg(namespace, storedAt))
        assertFalse(File(epgFile().parentFile, "epg.bin.tmp").exists())
    }

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
    fun `restore reclaims legacy namespaces without touching current namespaces or unrelated app data`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        val legacyDirectory = File(root, "tvheadend-sdk/${"a".repeat(32)}")
        assertTrue(catalogFile().parentFile.renameTo(legacyDirectory))
        File(legacyDirectory, "artwork").mkdirs()
        File(legacyDirectory, "artwork/1").writeBytes(byteArrayOf(1, 2))
        val unrelated = File(root, "application-data").apply { writeText("keep") }
        val unrecognized = File(root, "tvheadend-sdk/unrecognized").apply { mkdirs() }
        File(unrecognized, "keep").writeText("keep")
        val other = cacheNamespace("other.example", 9982, "alice")
        cache.storeCatalog(other, sampleCatalog(), storedAt)
        cache.storeEpg(other, sampleSnapshot(), storedAt)

        assertNull(cache.loadCatalog(namespace, storedAt))
        assertNull(cache.loadEpg(namespace, storedAt))
        assertFalse(legacyDirectory.exists())
        assertEquals(sampleCatalog(), cache.loadCatalog(other, storedAt))
        assertEquals(sampleSnapshot(), cache.loadEpg(other, storedAt))
        assertEquals("keep", File(unrecognized, "keep").readText())
        assertEquals(0L, cache.statistics().artworkBytes)
        val otherDirectory = File(root, "tvheadend-sdk/${other.value}")
        assertEquals(
            File(otherDirectory, "catalog.bin").length() + File(otherDirectory, "epg.bin").length(),
            cache.statistics().metadataBytes,
        )
        cache.clear()
        assertFalse(legacyDirectory.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun `legacy cleanup unlinks namespace and nested symlinks without traversing their targets`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        cache.storeEpg(namespace, sampleSnapshot(), storedAt)
        val sdkRoot = File(root, "tvheadend-sdk")
        val legacyLink = File(sdkRoot, "a".repeat(32)).toPath()
        Files.createSymbolicLink(legacyLink, catalogFile().parentFile.toPath())
        val unrelated = File(root, "application-data").apply { mkdirs() }
        val sentinel = File(unrelated, "keep").apply { writeText("keep") }
        val legacyDirectory = File(sdkRoot, "b".repeat(32)).apply { mkdirs() }
        Files.createSymbolicLink(File(legacyDirectory, "artwork").toPath(), unrelated.toPath())

        assertEquals(sampleCatalog(), cache.loadCatalog(namespace, storedAt))
        assertEquals(sampleSnapshot(), cache.loadEpg(namespace, storedAt))
        assertFalse(Files.isSymbolicLink(legacyLink))
        assertFalse(legacyDirectory.exists())
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `formerly ambiguous profiles retain independent catalog and EPG files`() = runTest {
        val cache = store()
        val first = cacheNamespace("::1", 2, "3:4:u")
        val second = cacheNamespace("::1:2:3", 4, "u")
        val catalog = sampleCatalog()
        val epg = sampleSnapshot()
        cache.storeCatalog(first, catalog, storedAt)
        cache.storeEpg(first, epg, storedAt)
        assertNull(cache.loadCatalog(second, storedAt))
        assertNull(cache.loadEpg(second, storedAt))
        cache.storeCatalog(second, CachedCatalog(ChannelCatalog.create()), storedAt)
        cache.storeEpg(second, CachedEpg(EpgSnapshot.create()), storedAt)
        assertEquals(catalog, cache.loadCatalog(first, storedAt))
        assertEquals(epg, cache.loadEpg(first, storedAt))
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
            payload = sampleCatalog().catalog.toDto(),
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
        val catalogB = CachedCatalog(ChannelCatalog.create(
            channels = listOf(Channel.create(id = ChannelId(2L), name = "Channel Two")),
        ))

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
    fun `clear unlinks a symlinked cache root without traversing its target`() = runTest {
        val outside = File(root, "application-data").apply { mkdirs() }
        val sentinel = File(outside, "keep").apply { writeText("keep") }
        val cacheRoot = File(root, "tvheadend-sdk").toPath()
        Files.createSymbolicLink(cacheRoot, outside.toPath())

        store().clear()

        assertFalse(Files.exists(cacheRoot, LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `clear unlinks symlinks inside the cache without traversing their targets`() = runTest {
        val cache = store()
        cache.storeCatalog(namespace, sampleCatalog(), storedAt)
        val unrelated = File(root, "application-data").apply { mkdirs() }
        val sentinel = File(unrelated, "keep").apply { writeText("keep") }
        Files.createSymbolicLink(File(catalogFile().parentFile, "artwork").toPath(), unrelated.toPath())

        cache.clear()

        assertFalse(File(root, "tvheadend-sdk").exists())
        assertEquals("keep", sentinel.readText())
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
