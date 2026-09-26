@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.TsExtractor
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * A video keyframe of a committed TS fixture, as listed by FFmpeg in the fixture's
 * `keyframes.csv`: the byte offset of the TS packet that starts its PES and its presentation time
 * relative to the fixture's first PCR, which is the origin of the growing seek map.
 */
internal data class FixtureKeyframe(val bytePosition: Long, val sourceMs: Long)

/** The FFmpeg keyframe table of one fixture, independent of anything Media3 parses. */
internal class FixtureKeyframes private constructor(val keyframes: List<FixtureKeyframe>) {
    /**
     * Returns the source keyframe playback must start from when it reads from [openPosition].
     *
     * A reader that starts at a byte offset can only deliver PES packets that begin at or after
     * it, and decoding restarts at a keyframe, so this is the first frame it can render.
     */
    fun firstReadableFrom(openPosition: Long): FixtureKeyframe? =
        keyframes.firstOrNull { keyframe -> keyframe.bytePosition >= openPosition }

    /**
     * Checks that playback whose TS parser starts at [parseStart] renders source content near
     * [targetMs]: the first keyframe it can decode must lie in the accepted window.
     */
    fun verifyLanding(parseStart: Long, targetMs: Long): FixtureLanding {
        val keyframe = firstReadableFrom(parseStart)
            ?: return FixtureLanding.Rejected("no source keyframe follows byte $parseStart")
        return verifyFirstFrame(keyframe, keyframe.sourceMs..keyframe.sourceMs, targetMs, "parser start $parseStart")
    }

    companion object {
        fun parse(csv: String): FixtureKeyframes {
            val rows = csv.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(rows.firstOrNull() == "byte_position,source_ms") { "Unexpected keyframe table header" }
            val keyframes = rows.drop(1).map { row ->
                val (position, sourceMs) = row.split(',').map(String::toLong)
                FixtureKeyframe(position, sourceMs)
            }
            require(keyframes.isNotEmpty() && keyframes.zipWithNext().all { (a, b) -> a.bytePosition < b.bytePosition }) {
                "Keyframe table must be non-empty and ordered by byte position"
            }
            return FixtureKeyframes(keyframes)
        }
    }
}

/** The size and CRC-32 of one sample's data; identifies a sample independently of its timestamps. */
internal data class SampleIdentity(val sizeBytes: Int, val crc32: Long) {
    override fun toString(): String = "SampleIdentity(size=$sizeBytes, crc32=${"%08x".format(crc32)})"

    companion object {
        fun of(bytes: ByteArray, offset: Int, length: Int): SampleIdentity =
            SampleIdentity(length, CRC32().apply { update(bytes, offset, length) }.value)

        /** Identifies the bytes [buffer] holds from [start] to its position, leaving [buffer] unchanged. */
        fun written(buffer: ByteBuffer, start: Int): SampleIdentity {
            val view = buffer.duplicate()
            view.limit(buffer.position())
            view.position(start)
            return SampleIdentity(view.remaining(), CRC32().apply { update(view) }.value)
        }
    }
}

/** A video keyframe sample as Media3's TS extractor emits it: its sample time and data identity. */
internal data class ExtractedKeyframe(val timeUs: Long, val identity: SampleIdentity)

/**
 * Extracts the video keyframe samples of the TS [fixture] with the TS extractor the growing source
 * delegates to. Sample boundaries are those the player's sample queue receives.
 *
 * With a [seekPosition], the extractor first parses the bytes before it, is then seeked to it as a
 * growing seek does, and only keyframes read after the seek are returned.
 */
internal fun extractVideoKeyframes(fixture: ByteArray, seekPosition: Int? = null): List<ExtractedKeyframe> {
    val output = KeyframeCapturingOutput()
    val extractor = TsExtractor(SubtitleParser.Factory.UNSUPPORTED)
    extractor.init(output)
    try {
        if (seekPosition != null) {
            extractor.readAll(fixture, 0, seekPosition)
            extractor.seek(seekPosition.toLong(), 0L)
            output.forgetKeyframes()
        }
        extractor.readAll(fixture, seekPosition ?: 0, fixture.size)
    } finally {
        extractor.release()
    }
    return output.keyframes()
}

private fun Extractor.readAll(fixture: ByteArray, from: Int, to: Int) {
    // An unknown length keeps the extractor from seeking to read the duration.
    val input = DefaultExtractorInput(ByteArrayDataReader(fixture, from, to), from.toLong(), C.LENGTH_UNSET.toLong())
    val position = PositionHolder()
    while (true) {
        when (val result = read(input, position)) {
            Extractor.RESULT_CONTINUE -> Unit
            Extractor.RESULT_END_OF_INPUT -> return
            else -> error("Unexpected extractor result $result while extracting the fixture")
        }
    }
}

/**
 * The fixture's FFmpeg keyframes, each paired with the data identity of the Media3 keyframe sample
 * at the same ordinal. The two lists must agree in count and relative timing, and no two
 * keyframe samples may share an identity, so an identity names exactly one source keyframe.
 */
internal class FixtureKeyframeIdentities private constructor(
    private val byIdentity: Map<SampleIdentity, FixtureKeyframe>,
) {
    val size: Int get() = byIdentity.size

    fun identify(identity: SampleIdentity): FixtureKeyframe? = byIdentity[identity]

    /**
     * Checks the first frame rendered after a resume seek.
     *
     * The first sample the video renderer read after the seek was the keyframe K with data
     * [keyframe], or null when that read carried no sample data. K's data names exactly one
     * source keyframe. The first rendered frame presents [keyframeToFirstFrameUs] after K in the
     * same media clock, so its source time is K's source time plus that difference, rounded
     * outward to whole milliseconds; it must lie in the accepted window around [targetMs].
     */
    fun verifyRenderedLanding(keyframe: SampleIdentity?, keyframeToFirstFrameUs: Long, targetMs: Long): FixtureLanding {
        keyframe ?: return FixtureLanding.Rejected("the landing keyframe read carried no sample data")
        val source = identify(keyframe)
            ?: return FixtureLanding.Rejected("landing keyframe $keyframe matches no source keyframe")
        val firstFrame = source.sourceMs + Math.floorDiv(keyframeToFirstFrameUs, MICROS_PER_MS)..
            source.sourceMs - Math.floorDiv(-keyframeToFirstFrameUs, MICROS_PER_MS)
        return verifyFirstFrame(
            source,
            firstFrame,
            targetMs,
            "landing keyframe $keyframe with first frame $keyframeToFirstFrameUs us after it",
        )
    }

    companion object {
        fun align(table: FixtureKeyframes, extracted: List<ExtractedKeyframe>): FixtureKeyframeIdentities {
            val source = table.keyframes
            require(extracted.size == source.size) {
                "Media3 extracted ${extracted.size} video keyframes but the FFmpeg table lists ${source.size}"
            }
            extracted.indices.forEach { index ->
                val extractedUs = extracted[index].timeUs - extracted.first().timeUs
                val sourceUs = (source[index].sourceMs - source.first().sourceMs) * MICROS_PER_MS
                require(kotlin.math.abs(extractedUs - sourceUs) <= KEYFRAME_ALIGNMENT_TOLERANCE_US) {
                    "Keyframe $index is ${extractedUs} us into the Media3 samples but ${sourceUs} us into the FFmpeg table"
                }
            }
            val shared = extracted.groupBy(ExtractedKeyframe::identity).filterValues { it.size > 1 }.keys
            require(shared.isEmpty()) { "Keyframe samples share data identities $shared" }
            return FixtureKeyframeIdentities(
                extracted.map(ExtractedKeyframe::identity).zip(source).toMap(),
            )
        }
    }
}

internal sealed interface FixtureLanding {
    /** [keyframe] is the source keyframe decoding restarted from; [firstFrameSourceMs] bounds the first frame. */
    data class Verified(val keyframe: FixtureKeyframe, val firstFrameSourceMs: LongRange) : FixtureLanding

    data class Rejected(val reason: String) : FixtureLanding
}

private fun verifyFirstFrame(
    keyframe: FixtureKeyframe,
    firstFrameSourceMs: LongRange,
    targetMs: Long,
    origin: String,
): FixtureLanding {
    val window = targetMs - LANDING_EARLIER_TOLERANCE_MS..targetMs + LANDING_LATER_TOLERANCE_MS
    return if (firstFrameSourceMs.first in window && firstFrameSourceMs.last in window) {
        FixtureLanding.Verified(keyframe, firstFrameSourceMs)
    } else {
        FixtureLanding.Rejected(
            "$origin from source keyframe $keyframe renders first source $firstFrameSourceMs ms, " +
                "outside $window ms for target $targetMs ms",
        )
    }
}

private class ByteArrayDataReader(private val bytes: ByteArray, from: Int, private val to: Int) : DataReader {
    private var position = from

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position == to) return C.RESULT_END_OF_INPUT
        val copied = minOf(length, to - position)
        bytes.copyInto(buffer, offset, position, position + copied)
        position += copied
        return copied
    }
}

private class KeyframeCapturingOutput : ExtractorOutput {
    private val video = mutableMapOf<Int, KeyframeCapturingTrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput =
        if (type == C.TRACK_TYPE_VIDEO) video.getOrPut(id, ::KeyframeCapturingTrackOutput) else DiscardingTrackOutput()

    override fun endTracks(): Unit = Unit

    override fun seekMap(seekMap: SeekMap): Unit = Unit

    fun forgetKeyframes() {
        video.values.forEach { track -> track.keyframes.clear() }
    }

    fun keyframes(): List<ExtractedKeyframe> =
        checkNotNull(video.values.singleOrNull()) { "Fixture must have exactly one video track" }.keyframes.toList()
}

/** Keeps every sample byte so each keyframe sample can be identified when its metadata arrives. */
private class KeyframeCapturingTrackOutput : TrackOutput {
    private var bytes = ByteArray(INITIAL_CAPTURE_BYTES)
    private var written = 0
    val keyframes = mutableListOf<ExtractedKeyframe>()

    override fun format(format: Format): Unit = Unit

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        ensureCapacity(length)
        val read = input.read(bytes, written, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        written += read
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        ensureCapacity(length)
        data.readBytes(bytes, written, length)
        written += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (flags and C.BUFFER_FLAG_KEY_FRAME == 0) return
        val end = written - offset
        keyframes += ExtractedKeyframe(timeUs, SampleIdentity.of(bytes, end - size, size))
    }

    private fun ensureCapacity(additional: Int) {
        if (written + additional > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, written + additional))
    }
}

/*
 * The growing seek lands on a PCR at or just before the target, so the next keyframe follows
 * within one GOP (about 2 s in both fixtures) plus the lead of video PTS over PCR (under 1 s).
 * One second of earlier content is tolerated for PCR search granularity.
 */
private const val LANDING_EARLIER_TOLERANCE_MS: Long = 1_000L
private const val LANDING_LATER_TOLERANCE_MS: Long = 3_000L

/** FFmpeg's table rounds 90 kHz presentation times to milliseconds. */
private const val KEYFRAME_ALIGNMENT_TOLERANCE_US: Long = 1_000L
private const val INITIAL_CAPTURE_BYTES: Int = 1 shl 20
private const val MICROS_PER_MS: Long = 1_000L
