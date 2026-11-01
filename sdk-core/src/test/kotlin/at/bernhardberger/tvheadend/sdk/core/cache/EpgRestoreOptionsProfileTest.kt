@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgReducer
import com.sun.management.ThreadMXBean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.random.Random

/** Explicit host experiment: same eager work, no timing or allocation acceptance threshold. */
internal class EpgRestoreOptionsProfileTest {
    private val root = Files.createTempDirectory(
        Files.createDirectories(File("build/tmp/restore038").toPath()), "case-",
    ).toFile()
    private val now = Instant.fromEpochSeconds(1_700_000_000)
    private val serializer = EpgEventDto.serializer()

    @AfterEach
    fun removeCache() { root.deleteRecursively() }

    @Test
    @EnabledIfSystemProperty(named = "tvh.restore.profile", matches = "true")
    fun `compare maintained restore decoders with fully consumed records`() = runTest(timeout = 5.minutes) {
        val allocation = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        if (allocation?.isThreadAllocatedMemorySupported == true) allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        fun allocated(): Long = if (allocation?.isThreadAllocatedMemoryEnabled == true)
            allocation.getThreadAllocatedBytes(thread) else -1
        for (size in listOf(5_000, 100_000)) {
            val channels = 143
            val fixture = fixture(size)
            val jsonRecords = fixture.events.map { Json.encodeToString(serializer, it.toDto()).encodeToByteArray() }
            val protobufRecords = fixture.events.map { ProtoBuf.encodeToByteArray(serializer, it.toDto()) }
            val namespace = cacheNamespace("benchmark.invalid", 9982, "")
            val store = FileMetadataCacheStore(root, object : Clock { override fun now(): Instant = now })
            store.storeEpg(namespace, CachedEpg(fixture), now)
            println("RESTORE_OPTIONS size=$size channels=$channels expired=0 warmups=3 samples=7 " +
                "jsonBytes=${jsonRecords.sumOf { it.size.toLong() }} protobufBytes=${protobufRecords.sumOf { it.size.toLong() }} " +
                "allocationSupported=${allocated() >= 0}")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            var chars = CharBuffer.allocate(0)
            fun validate(record: ByteArray): CharBuffer {
                if (chars.capacity() < record.size) chars = CharBuffer.allocate(record.size)
                chars.clear().limit(record.size)
                decoder.reset()
                val bytes = ByteBuffer.wrap(record)
                val decoded = decoder.decode(bytes, chars, true)
                if (!decoded.isUnderflow) decoded.throwException()
                check(!bytes.hasRemaining())
                val flushed = decoder.flush(chars)
                if (!flushed.isUnderflow) flushed.throwException()
                return chars.flip()
            }
            val options: List<Pair<String, (ByteArray) -> EpgEventDto>> = listOf(
                "strict-buffer-json" to { Json.decodeFromString(serializer, validate(it).toString()) },
                "strict-stdlib-json" to { Json.decodeFromString(serializer, it.decodeToString(throwOnInvalidSequence = true)) },
                "jdk-string-fallback-json" to {
                    val text = it.decodeToString()
                    if ('\uFFFD' in text) validate(it)
                    Json.decodeFromString(serializer, text)
                },
                "strict-buffer-stream-json" to {
                    validate(it)
                    Json.decodeFromStream(serializer, ByteArrayInputStream(it))
                },
                "raw-stream-json-INELIGIBLE" to { Json.decodeFromStream(serializer, ByteArrayInputStream(it)) },
                "protobuf-INELIGIBLE" to { ProtoBuf.decodeFromByteArray(serializer, it) },
                "protobuf-canonical-roundtrip" to { record ->
                    ProtoBuf.decodeFromByteArray(serializer, record).also { dto ->
                        require(record.contentEquals(ProtoBuf.encodeToByteArray(serializer, dto)))
                    }
                },
            )
            // Rotate execution order each iteration to reduce order/JIT/GC bias.
            repeat(10) { iteration ->
                repeat(options.size) { slot ->
                    val (name, decode) = options[(slot + iteration) % options.size]
                    val records = if (name.startsWith("protobuf")) protobufRecords else jsonRecords
                    val beforeBytes = allocated()
                    val before = System.nanoTime()
                    val restored = EpgSnapshot.create(records.map { decode(it).toModel() }, fixture.coverages)
                    val nanos = System.nanoTime() - before
                    val bytes = allocated() - beforeBytes
                    assertEquals(fixture, restored)
                    if (iteration >= 3) println("OPTION size=$size name=$name sample=${iteration - 3} ns=$nanos bytes=$bytes")
                }
                val beforeBytes = allocated()
                val before = System.nanoTime()
                val restored = checkNotNull(store.loadEpg(namespace, now))
                val nanos = System.nanoTime() - before
                val bytes = allocated() - beforeBytes
                assertEquals(fixture, restored.snapshot)
                if (iteration >= 3) println("LOAD size=$size sample=${iteration - 3} ns=$nanos bytes=$bytes")
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "tvh.restore.profile", matches = "true")
    fun `profile G10 saved guide load and save`() = runTest(timeout = 5.minutes) {
        val allocation = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        if (allocation?.isThreadAllocatedMemorySupported == true) allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        fun allocated(): Long = if (allocation?.isThreadAllocatedMemoryEnabled == true)
            allocation.getThreadAllocatedBytes(thread) else -1
        for (replacementHeavy in listOf(false, true)) for (size in listOf(5_000, 100_000)) {
            val fixture = fixture(size, replacementHeavy)
            val workload = if (replacementHeavy) "g10-replacement-heavy" else "g10"
            val namespace = cacheNamespace("benchmark.invalid", 9982, "")
            val store = FileMetadataCacheStore(root, object : Clock { override fun now(): Instant = now })
            store.storeEpg(namespace, CachedEpg(fixture), now)
            println("G10_RESTORE workload=$workload size=$size warmups=5 samples=15 " +
                "allocationSupported=${allocated() >= 0} filesystem=${Files.getFileStore(root.toPath()).type()}")
            repeat(20) { iteration ->
                val beforeBytes = allocated()
                val before = System.nanoTime()
                val restored = checkNotNull(store.loadEpg(namespace, now))
                val nanos = System.nanoTime() - before
                val bytes = allocated() - beforeBytes
                assertEquals(fixture, restored.snapshot)
                if (iteration >= 5) println("G10_LOAD workload=$workload size=$size sample=${iteration - 5} ns=$nanos bytes=$bytes")
            }
            repeat(20) { iteration ->
                val beforeBytes = allocated()
                val before = System.nanoTime()
                store.storeEpg(namespace, CachedEpg(fixture), now)
                val nanos = System.nanoTime() - before
                val bytes = allocated() - beforeBytes
                assertEquals(fixture, checkNotNull(store.loadEpg(namespace, now)).snapshot)
                if (iteration >= 5) println("G10_SAVE workload=$workload size=$size sample=${iteration - 5} ns=$nanos bytes=$bytes")
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "tvh.restore.profile", matches = "true")
    fun `compare encode costs and complete saved guide writes`() = runTest(timeout = 5.minutes) {
        val allocation = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        if (allocation?.isThreadAllocatedMemorySupported == true) allocation.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        fun allocated(): Long = if (allocation?.isThreadAllocatedMemoryEnabled == true)
            allocation.getThreadAllocatedBytes(thread) else -1
        for (size in listOf(5_000, 100_000)) {
            val fixture = fixture(size)
            val stream = ByteArrayOutputStream()
            val encoders: List<Pair<String, (EpgEventDto) -> ByteArray>> = listOf(
                "json-string" to { Json.encodeToString(serializer, it).encodeToByteArray() },
                "json-stream" to {
                    stream.reset()
                    Json.encodeToStream(serializer, it, stream)
                    stream.toByteArray()
                },
                "protobuf" to { ProtoBuf.encodeToByteArray(serializer, it) },
            )
            // Check all encoded records before timing, including exact JSON byte equivalence.
            for ((name, encode) in encoders) {
                for (event in fixture.events) {
                    val dto = event.toDto()
                    val bytes = encode(dto)
                    val decoded = if (name == "protobuf") ProtoBuf.decodeFromByteArray(serializer, bytes)
                    else Json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
                    assertEquals(dto, decoded)
                    if (name == "json-stream") assertEquals(Json.encodeToString(serializer, dto), bytes.decodeToString())
                }
            }
            println("SAVE_OPTIONS size=$size warmups=3 samples=7 allocationSupported=${allocated() >= 0}")
            repeat(10) { iteration ->
                repeat(encoders.size) { slot ->
                    val (name, encode) = encoders[(slot + iteration) % encoders.size]
                    val beforeBytes = allocated()
                    val before = System.nanoTime()
                    var payloadBytes = 0L
                    for (event in fixture.events) payloadBytes += encode(event.toDto()).size
                    val nanos = System.nanoTime() - before
                    val bytes = allocated() - beforeBytes
                    if (iteration >= 3) println("ENCODE size=$size name=$name sample=${iteration - 3} " +
                        "ns=$nanos bytes=$bytes payloadBytes=$payloadBytes")
                }
            }
            val namespace = cacheNamespace("benchmark.invalid", 9982, "")
            val store = FileMetadataCacheStore(root, object : Clock { override fun now(): Instant = now })
            val shuffled = EpgSnapshot.create(fixture.events.shuffled(Random(38)), fixture.coverages)
            for ((order, snapshot) in listOf("ordered" to fixture, "shuffled" to shuffled)) {
                repeat(10) { iteration ->
                    val beforeBytes = allocated()
                    val before = System.nanoTime()
                    store.storeEpg(namespace, CachedEpg(snapshot), now)
                    val nanos = System.nanoTime() - before
                    val bytes = allocated() - beforeBytes
                    assertEquals(fixture, checkNotNull(store.loadEpg(namespace, now)).snapshot)
                    if (iteration >= 3) println("SAVE size=$size order=$order sample=${iteration - 3} ns=$nanos bytes=$bytes")
                }
            }
        }
    }

    // Matches the sibling device-benchmark DeviceFixture and diskSnapshot reducer construction.
    private fun fixture(size: Int, replacementHeavy: Boolean = false): EpgSnapshot {
        val channels = 143
        val step = minOf(1800L, 18.hours.inWholeSeconds / ((size + channels - 1) / channels))
        val reducer = EpgReducer(size)
        val generation = GatewayGeneration()
        reducer.reconcileChannels(List(channels) { ChannelId(it + 1L) })
        repeat(size) { index ->
            val start = now + (index / channels * step).seconds
            reducer.accept(MetadataEvent.EventAdded(generation, GatewayEpgEvent(
                EventId(index + 1L), ChannelId(index % channels + 1L), start, start + step.seconds,
                title = "Synthetic programme $index", summary = "Synthetic summary",
                description = (if (replacementHeavy) "Generated description é 日本語. \uFFFD "
                    else "Generated description é 日本語. ").repeat(4),
                categories = listOf("Synthetic category", "Second category"), keywords = listOf("one", "two"),
                credits = listOf(EpgCredit("Synthetic performer", EpgCreditRole.ACTOR)),
                image = "imagecache/${index % 439 + 1}",
            )))
        }
        return reducer.snapshot()
    }

    @Test
    fun `raw maintained stream and protobuf decoders replace malformed UTF8`() {
        val dto = EpgEvent.create(EventId(1), start = now, stop = now, title = "BAD").toDto()
        val malformed = byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())
        fun corrupt(bytes: ByteArray): ByteArray {
            val marker = bytes.indices.first { index -> index + 2 < bytes.size &&
                bytes[index] == 'B'.code.toByte() && bytes[index + 1] == 'A'.code.toByte() &&
                bytes[index + 2] == 'D'.code.toByte() }
            malformed.copyInto(bytes, marker)
            return bytes
        }
        val json = corrupt(Json.encodeToString(serializer, dto).encodeToByteArray())
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            json.decodeToString(throwOnInvalidSequence = true)
        }
        assertEquals("\uFFFD", Json.decodeFromStream(serializer, ByteArrayInputStream(json)).title)
        val protobuf = corrupt(ProtoBuf.encodeToByteArray(serializer, dto))
        assertEquals("\uFFFD", ProtoBuf.decodeFromByteArray(serializer, protobuf).title)
        assertThrows(IllegalArgumentException::class.java) {
            val decoded = ProtoBuf.decodeFromByteArray(serializer, protobuf)
            require(protobuf.contentEquals(ProtoBuf.encodeToByteArray(serializer, decoded)))
        }
    }
}
