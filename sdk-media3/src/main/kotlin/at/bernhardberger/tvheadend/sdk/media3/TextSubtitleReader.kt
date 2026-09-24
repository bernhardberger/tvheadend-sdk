@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.ByteArrayOutputStream

/**
 * Reads TVHeadend text subtitle pages, which its teletext parser publishes as whole
 * NUL-terminated UTF-8 pages with `<font color>` runs. Each page replaces the previous one.
 */
internal class TextSubtitleReader(
    private val language: String?,
) : ElementaryStreamReader {
    private val encoder = CueEncoder()
    private val page = ByteArrayOutputStream()
    private var output: TrackOutput? = null
    private var packetPending = false
    private var packetTimeUs = C.TIME_UNSET

    override fun seek() {
        resetPacket()
    }

    override fun createTracks(output: ExtractorOutput, idGenerator: TsPayloadReader.TrackIdGenerator) {
        idGenerator.generateNewId()
        val trackOutput = output.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        trackOutput.format(
            Format.Builder()
                .setId(idGenerator.formatId)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                .setLanguage(language)
                .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
                .build(),
        )
        this.output = trackOutput
    }

    override fun packetStarted(pesTimeUs: Long, flags: Int) {
        resetPacket()
        packetPending = true
        packetTimeUs = pesTimeUs
    }

    override fun consume(data: ParsableByteArray) {
        check(packetPending) { "Text subtitle data arrived outside a packet" }
        val length = data.bytesLeft()
        page.write(data.data, data.position, length)
        data.skipBytes(length)
    }

    override fun packetFinished() {
        if (!packetPending) return
        val timeUs = packetTimeUs
        val payload = page.toByteArray()
        resetPacket()
        // Media3 cannot place an untimed replacement page on the playback timeline.
        if (timeUs == C.TIME_UNSET) return
        val sample = encoder.encode(parseTextSubtitlePage(payload).toCues(), C.TIME_UNSET)
        val trackOutput = checkNotNull(output) { "Text subtitle tracks were not created" }
        trackOutput.sampleData(ParsableByteArray(sample), sample.size)
        trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)
    }

    override fun endOfInputReached(): Unit = Unit

    private fun resetPacket() {
        packetPending = false
        packetTimeUs = C.TIME_UNSET
        page.reset()
    }
}

internal data class TextSubtitlePage(
    val text: String,
    val colourRuns: List<TextSubtitleColourRun>,
)

internal data class TextSubtitleColourRun(
    val start: Int,
    val end: Int,
    val colour: Int,
)

/**
 * Returns `null` for a blank page. Only TVHeadend's exact colour tags are markup; any other
 * text, including `<`, `&` and unknown tags, is shown literally because TVHeadend does not escape.
 */
internal fun parseTextSubtitlePage(payload: ByteArray): TextSubtitlePage? {
    val terminator = payload.indexOf(0.toByte()).takeIf { it >= 0 } ?: payload.size
    val source = String(payload, 0, terminator, Charsets.UTF_8)
    val text = StringBuilder(source.length)
    val runs = mutableListOf<TextSubtitleColourRun>()
    var runStart = -1
    var runColour = 0
    fun closeRun() {
        if (runStart >= 0 && text.length > runStart) {
            runs += TextSubtitleColourRun(runStart, text.length, runColour)
        }
        runStart = -1
    }
    var index = 0
    while (index < source.length) {
        if (source[index] == '<') {
            val open = FONT_COLOUR_TAGS.entries.firstOrNull { source.startsWith(it.key, index) }
            if (open != null) {
                closeRun()
                runStart = text.length
                runColour = open.value
                index += open.key.length
                continue
            }
            if (runStart >= 0 && source.startsWith(FONT_CLOSE_TAG, index)) {
                closeRun()
                index += FONT_CLOSE_TAG.length
                continue
            }
        }
        text.append(source[index])
        index++
    }
    closeRun()
    val visible = text.trimEnd('\n').toString()
    if (visible.isBlank()) return null
    return TextSubtitlePage(
        visible,
        runs.mapNotNull { run ->
            run.copy(end = minOf(run.end, visible.length)).takeIf { it.start < it.end }
        },
    )
}

private fun TextSubtitlePage?.toCues(): List<Cue> {
    val page = this ?: return emptyList()
    val text = SpannableString(page.text)
    page.colourRuns.forEach { run ->
        text.setSpan(ForegroundColorSpan(run.colour), run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return listOf(Cue.Builder().setText(text).build())
}

private const val FONT_CLOSE_TAG = "</font>"

// The complete palette emitted by TVHeadend's parser_teletext.c; teletext black is sent as grey.
private val FONT_COLOUR_TAGS: Map<String, Int> = mapOf(
    "<font color=\"#888888\">" to 0xff888888.toInt(),
    "<font color=\"#ff0000\">" to 0xffff0000.toInt(),
    "<font color=\"#00ff00\">" to 0xff00ff00.toInt(),
    "<font color=\"#ffff00\">" to 0xffffff00.toInt(),
    "<font color=\"#0000ff\">" to 0xff0000ff.toInt(),
    "<font color=\"#ff00ff\">" to 0xffff00ff.toInt(),
    "<font color=\"#00ffff\">" to 0xff00ffff.toInt(),
    "<font color=\"#ffffff\">" to 0xffffffff.toInt(),
)
