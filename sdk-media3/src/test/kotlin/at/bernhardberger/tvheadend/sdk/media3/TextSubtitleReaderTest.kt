@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextSubtitleReaderTest {
    @Test
    fun `tracks publish a replacing Media3 cue format before any page`() {
        val output = TextCapturingExtractorOutput()
        textReader().createTracks(output, TsPayloadReader.TrackIdGenerator(7, 1))

        assertEquals(7, output.trackId)
        assertEquals(C.TRACK_TYPE_TEXT, output.trackType)
        val format = output.trackOutput.formats.single()
        assertEquals(MimeTypes.VIDEO_MP2T, format.containerMimeType)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, format.sampleMimeType)
        assertEquals("de", format.language)
        assertEquals(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE, format.cueReplacementBehavior)
        assertEquals(0, format.roleFlags)
        assertNull(format.codecs)
        assertTrue(output.trackOutput.samples.isEmpty())
    }

    @Test
    fun `every page is one key-frame sample at its presentation time without a new format`() {
        val output = TextCapturingExtractorOutput()
        val adapter = SubscriptionElementaryStreamAdapter(textReader(), output, 7)

        adapter.accept(page("Erste Zeile\n\u0000".toByteArray(), 500_000L))
        adapter.accept(page(byteArrayOf(0), 900_000L))
        adapter.accept(page(byteArrayOf(), 1_300_000L))
        adapter.end()

        assertEquals(1, output.trackOutput.formats.size)
        assertEquals(listOf(500_000L, 900_000L, 1_300_000L), output.trackOutput.samples.map { it.timeUs })
        output.trackOutput.samples.forEach { sample ->
            assertEquals(C.BUFFER_FLAG_KEY_FRAME, sample.flags)
            assertEquals(0, sample.offset)
            assertTrue(sample.size > 0)
            assertEquals(sample.size, sample.bytes.size)
        }
    }

    @Test
    fun `untimed pages are not queued`() {
        val output = TextCapturingExtractorOutput()
        val adapter = SubscriptionElementaryStreamAdapter(textReader(), output, 7)

        adapter.accept(page("Ohne Zeit\n\u0000".toByteArray(), null))

        assertTrue(output.trackOutput.samples.isEmpty())
    }

    @Test
    fun `seek discards a partial page`() {
        val output = TextCapturingExtractorOutput()
        val reader = textReader()
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(7, 1))

        reader.packetStarted(500_000L, 0)
        reader.consume(ParsableByteArray("Halb".toByteArray()))
        reader.seek()
        assertThrows(IllegalStateException::class.java) {
            reader.consume(ParsableByteArray("fertig\n\u0000".toByteArray()))
        }
        reader.packetFinished()
        assertTrue(output.trackOutput.samples.isEmpty())

        reader.packetStarted(900_000L, 0)
        reader.consume(ParsableByteArray("Neu\n\u0000".toByteArray()))
        reader.packetFinished()
        assertEquals(listOf(900_000L), output.trackOutput.samples.map { it.timeUs })
    }

    @Test
    fun `plain text page drops the terminator and trailing row break`() {
        assertEquals(TextSubtitlePage("Guten Abend", emptyList()), parse("Guten Abend\n\u0000"))
    }

    @Test
    fun `bytes after the terminator are ignored`() {
        assertEquals(TextSubtitlePage("Hallo", emptyList()), parse("Hallo\n\u0000Rest"))
    }

    @Test
    fun `umlauts sharp s and euro survive UTF-8 decoding`() {
        assertEquals(TextSubtitlePage("Grüße für 5 € – ÄÖÜ äöü ß", emptyList()), parse("Grüße für 5 € – ÄÖÜ äöü ß\n\u0000"))
    }

    @Test
    fun `rows keep their line breaks`() {
        assertEquals(
            TextSubtitlePage("Erste Zeile\nZweite Zeile", emptyList()),
            parse("Erste Zeile\nZweite Zeile\n\u0000"),
        )
    }

    @Test
    fun `colour runs become colour ranges and white stays unstyled`() {
        assertEquals(
            TextSubtitlePage(
                "Gelb weiß\nCyan",
                listOf(
                    TextSubtitleColourRun(0, 4, 0xffffff00.toInt()),
                    TextSubtitleColourRun(10, 14, 0xff00ffff.toInt()),
                ),
            ),
            parse("<font color=\"#ffff00\">Gelb</font> weiß\n<font color=\"#00ffff\"> Cyan</font>\n\u0000"),
        )
    }

    @Test
    fun `every TVHeadend palette tag maps to its colour`() {
        mapOf(
            "#888888" to 0xff888888.toInt(),
            "#ff0000" to 0xffff0000.toInt(),
            "#00ff00" to 0xff00ff00.toInt(),
            "#ffff00" to 0xffffff00.toInt(),
            "#0000ff" to 0xff0000ff.toInt(),
            "#ff00ff" to 0xffff00ff.toInt(),
            "#00ffff" to 0xff00ffff.toInt(),
            "#ffffff" to 0xffffffff.toInt(),
        ).forEach { (tag, colour) ->
            assertEquals(
                TextSubtitlePage("x", listOf(TextSubtitleColourRun(0, 1, colour))),
                parse("<font color=\"$tag\">x</font>\n\u0000"),
                tag,
            )
        }
    }

    @Test
    fun `empty runs are dropped and an unclosed run stops at the visible text`() {
        assertEquals(
            TextSubtitlePage(
                "Hi\nrot",
                listOf(
                    TextSubtitleColourRun(0, 2, 0xff00ff00.toInt()),
                    TextSubtitleColourRun(3, 6, 0xffff0000.toInt()),
                ),
            ),
            parse("<font color=\"#ff0000\"></font><font color=\"#00ff00\">  Hi</font>\n<font color=\"#ff0000\">rot\n\u0000"),
        )
    }

    @Test
    fun `row edge spaces are trimmed and colour ranges follow the remaining text`() {
        assertEquals(
            TextSubtitlePage(
                "Gelb\nCyan weiß grün",
                listOf(
                    TextSubtitleColourRun(0, 4, 0xffffff00.toInt()),
                    TextSubtitleColourRun(5, 9, 0xff00ffff.toInt()),
                    TextSubtitleColourRun(15, 19, 0xff00ff00.toInt()),
                ),
            ),
            parse(
                "<font color=\"#ffff00\">  Gelb  </font>\n" +
                    "   <font color=\"#00ffff\">Cyan</font> weiß <font color=\"#00ff00\">grün </font>" +
                    "<font color=\"#ff0000\">  </font> \n\u0000",
            ),
        )
    }

    @Test
    fun `page descriptions never contain subtitle text`() {
        val page = checkNotNull(parse("<font color=\"#ffff00\">SENTINEL-4711 geheim</font>\n\u0000"))

        assertEquals("TextSubtitlePage(length=20, colourRuns=1)", page.toString())
        assertFalse(page.toString().contains("SENTINEL"))
        assertFalse(page.colourRuns.toString().contains("SENTINEL"))
    }

    @Test
    fun `unknown and malformed tags stay literal`() {
        val source = "<i>kursiv</i> <font color=\"#123456\">a</font> <FONT color=\"#ff0000\">b <font color=#ff0000>c"
        assertEquals(TextSubtitlePage(source, emptyList()), parse("$source\n\u0000"))
    }

    @Test
    fun `ampersands and angle brackets stay literal`() {
        assertEquals(
            TextSubtitlePage("Tom & Jerry <3 a<b &amp; >", emptyList()),
            parse("Tom & Jerry <3 a<b &amp; >\n\u0000"),
        )
    }

    @Test
    fun `blank and zero-length pages clear the subtitle`() {
        assertNull(parseTextSubtitlePage(byteArrayOf(0)))
        assertNull(parseTextSubtitlePage(byteArrayOf()))
        assertNull(parse("<font color=\"#ff0000\">   </font>\n\u0000"))
    }

    @Test
    fun `invalid UTF-8 is replaced instead of failing`() {
        val payload = byteArrayOf(0x41, 0xc3.toByte(), 0x28, 0x42, 0xff.toByte(), 0x0a, 0x00)
        assertEquals(TextSubtitlePage("A\uFFFD(B\uFFFD", emptyList()), parseTextSubtitlePage(payload))
    }

    private fun parse(payload: String): TextSubtitlePage? = parseTextSubtitlePage(payload.toByteArray())

    private fun textReader() = (createElementaryStreamReader(textStream()) as ReaderResult.Supported).reader

    private fun textStream() = SubscriptionStream(
        index = StreamIndex(0L),
        type = SubscriptionStreamType.TEXT_SUBTITLE,
        language = "ger",
        compositionId = null,
        ancillaryId = null,
        width = null,
        height = null,
        frameDuration = null,
        aspectNumerator = null,
        aspectDenominator = null,
        audioType = null,
        audioVersion = null,
        channelCount = null,
        rate = null,
        rdsUecp = null,
        codecMetadata = null,
    )

    private fun page(bytes: ByteArray, presentationTimeUs: Long?) = SubscriptionEvent.Packet(
        frameType = MuxFrameType.UNKNOWN,
        streamIndex = StreamIndex(0L),
        decodingTimeUs = presentationTimeUs,
        presentationTimeUs = presentationTimeUs,
        durationUs = 0L,
        payload = TextPageBinary(bytes),
    )
}

private class TextCapturingExtractorOutput : ExtractorOutput {
    val trackOutput = TextCapturingTrackOutput()
    var trackId: Int? = null
    var trackType: Int? = null

    override fun track(id: Int, type: Int): TrackOutput {
        trackId = id
        trackType = type
        return trackOutput
    }

    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
}

private data class TextCapturedSample(
    val timeUs: Long,
    val flags: Int,
    val size: Int,
    val offset: Int,
    val bytes: List<Byte>,
)

private class TextCapturingTrackOutput : TrackOutput {
    val formats = mutableListOf<Format>()
    val samples = mutableListOf<TextCapturedSample>()
    private val pending = mutableListOf<Byte>()

    override fun format(format: Format) {
        formats += format
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        error("Text pages are written from memory")

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        pending += buffer.toList()
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        samples += TextCapturedSample(timeUs, flags, size, offset, pending.toList())
        pending.clear()
    }
}

private class TextPageBinary(private val bytes: ByteArray) : SubscriptionBinary {
    override val size: Int = bytes.size

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}
