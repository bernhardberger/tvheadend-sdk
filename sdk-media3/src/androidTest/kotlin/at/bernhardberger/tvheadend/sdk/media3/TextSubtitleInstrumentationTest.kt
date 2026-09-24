@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.ts.TsPayloadReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class TextSubtitleInstrumentationTest {
    @Test
    fun text_subtitle_pages_decode_as_replacing_coloured_cues() {
        val output = TextSampleOutput()
        val reader = (createElementaryStreamReader(textStream()) as ReaderResult.Supported).reader
        reader.createTracks(output, TsPayloadReader.TrackIdGenerator(1, 1))

        reader.write(
            500_000L,
            "<font color=\"#ffff00\">Grüße</font> & <i>\n<font color=\"#00ffff\">Zweite</font> Zeile\n\u0000"
                .toByteArray(),
        )
        reader.write(900_000L, byteArrayOf(0))

        val first = output.decode(0)
        assertEquals(500_000L, first.startTimeUs)
        assertEquals(C.TIME_UNSET, first.durationUs)
        val cue = first.cues.single()
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(Cue.DIMEN_UNSET, cue.position)
        val text = cue.text as Spanned
        assertEquals("Grüße & <i>\nZweite Zeile", text.toString())
        val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .map { span -> Triple(text.getSpanStart(span), text.getSpanEnd(span), span.foregroundColor) }
            .sortedBy { it.first }
        assertEquals(
            listOf(
                Triple(0, 5, 0xffffff00.toInt()),
                Triple(12, 18, 0xff00ffff.toInt()),
            ),
            spans,
        )

        val blank = output.decode(1)
        assertEquals(900_000L, blank.startTimeUs)
        assertTrue(blank.cues.isEmpty())
    }

    private fun androidx.media3.extractor.ts.ElementaryStreamReader.write(timeUs: Long, page: ByteArray) {
        packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR)
        consume(ParsableByteArray(page))
        packetFinished()
    }
}

private class TextSampleOutput : ExtractorOutput, TrackOutput {
    private val pending = mutableListOf<Byte>()
    private val samples = mutableListOf<Pair<Long, ByteArray>>()

    fun decode(index: Int): CuesWithTiming {
        val (timeUs, bytes) = samples[index]
        return CueDecoder().decode(timeUs, bytes, 0, bytes.size)
    }

    override fun track(id: Int, type: Int): TrackOutput = this
    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
    override fun format(format: Format): Unit = Unit

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        error("Text pages are written from memory")

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        pending += buffer.toList()
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        samples += timeUs to pending.toByteArray()
        pending.clear()
    }
}

private fun textStream(): SubscriptionStream = SubscriptionStream(
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
