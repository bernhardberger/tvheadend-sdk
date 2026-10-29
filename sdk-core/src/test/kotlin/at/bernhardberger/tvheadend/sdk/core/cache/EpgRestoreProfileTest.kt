@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCredit
import at.bernhardberger.tvheadend.sdk.core.EpgCreditRole
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Synthetic warm-file phase measurements, with correctness assertions but no timing thresholds. */
internal class EpgRestoreProfileTest {
    @TempDir lateinit var root: File
    private val now = Instant.fromEpochSeconds(1_700_000_000)
    private val namespace = cacheNamespace("benchmark.invalid", 9982, "")

    @Test
    fun `profile realistic saved guide restoration without a numeric performance gate`() = runTest {
        val store = FileMetadataCacheStore(root, object : Clock { override fun now(): Instant = now })
        for (ageHours in listOf(6, 12)) {
            val fixture = EpgSnapshot.create(List(4_920) { index ->
                val start = now - ageHours.hours + (index / 143 * 2_700L).seconds
                EpgEvent.create(EventId(index.toLong()), ChannelId((index % 143).toLong()), start,
                    start + 2_700.seconds,
                    title = "Synthetic programme $index", subtitle = "Programme subtitle $index",
                    summary = "Synthetic guide summary for event $index.",
                    description = "Synthetic programme description with ordinary metadata. ".repeat(7),
                    categories = listOf("Drama", "Entertainment"), keywords = listOf("Synthetic", "Television"),
                    credits = listOf(EpgCredit("Synthetic Actor $index", EpgCreditRole.ACTOR),
                        EpgCredit("Synthetic Director $index", EpgCreditRole.DIRECTOR)))
            })
            store.storeEpg(namespace, CachedEpg(fixture), now)
            val file = File(root, "tvheadend-sdk/${namespace.value}/epg.bin")
            val expected = fixture.events.filter { it.stop >= now - 6.hours }
            val samples = mutableListOf<List<Double>>()
            val protobufReference = expected.map { ProtoBuf.encodeToByteArray(EpgEventDto.serializer(), it.toDto()) }
            repeat(17) { iteration ->
                val times = mutableListOf<Double>()
                fun <T> timed(block: () -> T): T {
                    val before = System.nanoTime()
                    return block().also { times += (System.nanoTime() - before) / 1e6 }
                }
                val records = timed { readEventRecords(file) }
                val strings = timed {
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    var chars = CharBuffer.allocate(0)
                    records.map { record ->
                        if (chars.capacity() < record.size) chars = CharBuffer.allocate(record.size)
                        chars.clear().limit(record.size)
                        decoder.reset()
                        // This phase already owns framed byte arrays; actual loadEpg below also
                        // reuses its input buffer instead of allocating an array for every record.
                        val bytes = ByteBuffer.wrap(record)
                        val decoded = decoder.decode(bytes, chars, true)
                        if (!decoded.isUnderflow) decoded.throwException()
                        check(!bytes.hasRemaining())
                        val flushed = decoder.flush(chars)
                        if (!flushed.isUnderflow) flushed.throwException()
                        chars.flip().toString()
                    }
                }
                val dtos = timed { strings.map { Json.decodeFromString(EpgEventDto.serializer(), it) } }
                val events = timed { dtos.map { it.toModel() } }
                val snapshot = timed { EpgSnapshot.create(events) }
                timed { snapshot.prepareForObservation() }
                timed { assertTrue(PhaseOneSessionMetadata().seedRetainedSnapshots(null, CachedEpg(snapshot))) }
                val before = System.nanoTime()
                val restored = checkNotNull(store.loadEpg(namespace, now))
                times += (System.nanoTime() - before) / 1e6
                val reference = timed { protobufReference.map { ProtoBuf.decodeFromByteArray(EpgEventDto.serializer(), it) } }
                assertEquals(dtos, reference)
                assertEquals(expected, restored.snapshot.events)
                assertEquals(restored.snapshot.events, snapshot.events)
                if (iteration >= 5) samples += times
            }
            val names = listOf("framingIO", "utf8", "json", "modelAndEagerHash", "snapshot", "prepareIndexes", "seed", "loadEpg", "protobufReference")
            println("EPG restore: bytes=${file.length()}, records=4920, expired=${4920 - expected.size}, warmups=5, samples=12")
            names.forEachIndexed { index, name ->
                val values = samples.map { it[index] }.sorted()
                println("$name ms min=${values.first()} median=${(values[5] + values[6]) / 2} max=${values.last()}")
            }
        }
    }

    private fun readEventRecords(file: File): List<ByteArray> = DataInputStream(file.inputStream().buffered()).use { input ->
        check(input.readInt() == 0x45504735)
        input.readLong()
        fun record(): ByteArray = ByteArray(input.readInt()).also(input::readFully)
        record() // Receipt times.
        repeat(input.readInt()) { record() } // Coverage.
        repeat(input.readInt()) { record() } // Query times.
        List(input.readInt()) {
            input.readLong() // Start-ordered framing.
            val stop = input.readLong()
            record().takeIf { stop >= (now - 6.hours).epochSeconds }
        }.filterNotNull()
    }
}
