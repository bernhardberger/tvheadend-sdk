@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import kotlin.coroutines.cancellation.CancellationException

/*
 * Media3 parses these parameter sets but publishes no scan type on Format, so the SDK reads the
 * declaring syntax elements with Media3's emulation-prevention-aware bit reader. Its full H.264
 * parser loops over an unbounded pic_order_cnt_cycle; its H.265 parser also allocates arrays from
 * unrelated syntax. Neither offers a bounded scan-only parser. The small prefixes below stop at
 * the scan declaration and parameter-set ids, with bounded counts and Exp-Golomb values.
 */

/** Carries the detected scan type on [Format.metadata]. */
internal data class VideoScanMetadata(val scanType: VideoScanType) : Metadata.Entry

/** Wraps Media3's extractors so that every extracted video format carries its scan type. */
internal class VideoScanExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().wrap()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).wrap()

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory =
        apply { delegate.setSubtitleParserFactory(subtitleParserFactory) }

    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory =
        apply { delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory =
        apply { delegate.setParseHagcMetadata(parseHagcMetadata) }

    private fun Array<Extractor>.wrap(): Array<Extractor> = Array(size) { index -> VideoScanExtractor(this[index]) }
}

/** Decorates one extractor; seeks restore codec setup before inspecting post-seek pictures. */
internal class VideoScanExtractor(private val delegate: Extractor) : Extractor {
    private var output: VideoScanExtractorOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails() = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        val wrapped = VideoScanExtractorOutput(output)
        this.output = wrapped
        delegate.init(wrapped)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        output?.resetForSeek()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

/** Wraps video tracks only; other tracks reach [delegate] unchanged. */
internal class VideoScanExtractorOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
    private val videoTracks = HashMap<Int, VideoScanTrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        if (type != C.TRACK_TYPE_VIDEO) return track
        return videoTracks.getOrPut(id) { VideoScanTrackOutput(track) }
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)

    fun resetForSeek() {
        videoTracks.values.forEach(VideoScanTrackOutput::resetForSeek)
    }

    /** Keeps the decoder's parameter sets across a discontinuity within the same subscription. */
    fun resetForDiscontinuity() {
        videoTracks.values.forEach(VideoScanTrackOutput::resetForDiscontinuity)
    }
}

/**
 * Adds [VideoScanMetadata] to each video format and issues a new format from the first sample
 * whose picture activates a different scan type.
 *
 * Positions count every byte written to this output. Slice prefixes resolve their parameter sets
 * before sample commit, even when the rest of the slice has not arrived yet.
 */
internal class VideoScanTrackOutput(private val delegate: TrackOutput) : TrackOutput {
    private var baseFormat: Format? = null
    private var emittedScan = VideoScanType.UNKNOWN
    private var parameters: VideoScanParameters? = null
    private var bytesWritten = 0L
    private val changes = ScanChanges()
    private var beforeFormat: PreFormatScanData? = null
    private var readerInput: DataReader? = null
    private var readingMain = false
    private val inspectingReader = DataReader { buffer, offset, length ->
        val read = checkNotNull(readerInput).read(buffer, offset, length)
        if (read > 0) {
            if (readingMain) parameters?.scan(buffer, offset, offset + read, bytesWritten)
            bytesWritten += read
        }
        read
    }

    override fun format(format: Format) {
        val nextSyntax = ScanSyntax.of(format.sampleMimeType)
        val nextParameters = nextSyntax?.let { VideoScanParameters(it, changes::add) }
        parameters = nextParameters
        val base = format.withScan(VideoScanType.UNKNOWN)
        baseFormat = base
        val scan = nextParameters?.seed(format.initializationData) ?: VideoScanType.UNKNOWN
        changes.reset(scan)
        beforeFormat?.replay(nextParameters)
        beforeFormat = null
        emittedScan = scan
        delegate.format(base.withScan(scan))
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        readerInput = input
        readingMain = sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN
        return try {
            delegate.sampleData(inspectingReader, length, allowEndOfInput, sampleDataPart)
        } finally {
            readerInput = null
        }
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && length > 0) {
            if (baseFormat == null) {
                val buffered = beforeFormat ?: PreFormatScanData().also { beforeFormat = it }
                buffered.append(data.data, data.position, length, bytesWritten)
            } else {
                parameters?.scan(data.data, data.position, data.position + length, bytesWritten)
            }
        }
        delegate.sampleData(data, length, sampleDataPart)
        bytesWritten += length
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        val sampleEnd = bytesWritten - offset
        parameters?.commit(sampleEnd)
        val scan = changes.at(sampleEnd)
        val base = baseFormat
        if (scan != emittedScan && base != null) {
            emittedScan = scan
            delegate.format(base.withScan(scan))
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    fun resetForSeek() {
        beforeFormat = null
        val scan = parameters?.seed(baseFormat?.initializationData.orEmpty()) ?: VideoScanType.UNKNOWN
        changes.reset(scan)
    }

    fun resetForDiscontinuity() {
        beforeFormat = null
        parameters?.discardUncommitted()
        changes.clearPending()
    }
}

/** Retains at most the first 64 KiB of MAIN bytes until a track declares its first format. */
private class PreFormatScanData {
    private val bytes = ByteArray(MAX_BYTES)
    private var size = 0
    private val ranges = ArrayList<Range>()

    fun append(data: ByteArray, from: Int, length: Int, position: Long) {
        val count = minOf(length, MAX_BYTES - size)
        if (count == 0) return
        val previous = ranges.lastOrNull()
        if (previous != null && previous.position + previous.length == position) {
            previous.length += count
        } else {
            ranges.add(Range(size, count, position))
        }
        data.copyInto(bytes, size, from, from + count)
        size += count
    }

    fun replay(parameters: VideoScanParameters?) {
        ranges.forEach { range -> parameters?.scan(bytes, range.from, range.from + range.length, range.position) }
    }

    private class Range(val from: Int, var length: Int, val position: Long)

    private companion object {
        const val MAX_BYTES = 64 * 1024
    }
}

/** Keeps byte-ordered changes bounded while retaining the newest evidence on overflow. */
private class ScanChanges {
    private var current = VideoScanType.UNKNOWN
    private val pending = ArrayDeque<PendingScan>()

    fun add(position: Long, scan: VideoScanType) {
        if (scan == (pending.lastOrNull()?.scan ?: current)) return
        if (pending.size == MAX_PENDING_SCANS) pending.removeLast()
        pending.addLast(PendingScan(position, scan))
    }

    fun at(sampleEnd: Long): VideoScanType {
        while (pending.isNotEmpty() && pending.first().position < sampleEnd) current = pending.removeFirst().scan
        return current
    }

    fun reset(scan: VideoScanType) {
        pending.clear()
        current = scan
    }

    fun clearPending() = pending.clear()

    private class PendingScan(val position: Long, val scan: VideoScanType)

    private companion object {
        const val MAX_PENDING_SCANS = 32
    }
}

private sealed interface ScanUnit {
    data class Sequence(val id: Int, val scan: VideoScanType) : ScanUnit
    data class Picture(val id: Int, val sequenceId: Int) : ScanUnit
    data class Slice(val pictureId: Int?) : ScanUnit
}

/** Classifies the bounded prefixes needed to resolve a picture's scan declaration. */
private enum class ScanSyntax {
    H264, H265, MPEG2;

    fun collects(header: Byte): Boolean = when (this) {
        H264 -> header.toInt() and 0x1F in H264_TYPES
        H265 -> (header.toInt() shr 1 and 0x3F).let { it < 32 || it == 33 || it == 34 }
        MPEG2 -> header == 0xB5.toByte()
    }

    fun isSlice(header: Byte): Boolean = when (this) {
        H264 -> header.toInt() and 0x1F in 1..5
        H265 -> header.toInt() shr 1 and 0x3F < 32
        MPEG2 -> false
    }

    fun parseOrNull(unit: ByteArray, limit: Int): ScanUnit? = try {
        when (this) {
            H264 -> H264ScanPrefix.parse(unit, limit)
            H265 -> H265ScanPrefix.parse(unit, limit)
            MPEG2 -> parseMpeg2(unit, limit)
        }
    } catch (failure: RuntimeException) {
        if (failure is CancellationException) throw failure
        null
    }

    private fun parseMpeg2(unit: ByteArray, limit: Int): ScanUnit? {
        // ISO/IEC 13818-2 6.2.2.3: extension id 1 is the sequence extension.
        if (limit < 3 || unit[1].toInt() and 0xF0 != 0x10) return null
        return ScanUnit.Sequence(0, scanFlag(unit[2].toInt() and 0x08 != 0))
    }

    companion object {
        private val H264_TYPES = setOf(1, 2, 5, NalUnitUtil.H264_NAL_UNIT_TYPE_SPS, 8)

        fun of(mimeType: String?): ScanSyntax? = when (mimeType) {
            MimeTypes.VIDEO_H264 -> H264
            MimeTypes.VIDEO_H265 -> H265
            MimeTypes.VIDEO_MPEG2 -> MPEG2
            else -> null
        }
    }
}

/** Resolves parameter-set ids in byte order; an inactive SPS never changes the reported scan. */
private class VideoScanParameters(private val syntax: ScanSyntax, private val onScan: (Long, VideoScanType) -> Unit) {
    private val working = ScanParameterMaps()
    private val committed = ScanParameterMaps()
    private val staged = ArrayDeque<ParameterUpdate>()
    private val units = ParameterSetScanner(syntax, ::consume)
    private var seeding = false
    private var committedEnd = Long.MIN_VALUE
    private var lostHistoryFrom: Long? = null

    fun seed(initializationData: List<ByteArray>): VideoScanType {
        working.clear()
        committed.clear()
        staged.clear()
        committedEnd = Long.MIN_VALUE
        lostHistoryFrom = null
        units.reset()
        seeding = true
        initializationData.forEach { data ->
            if (data.isNotEmpty() && data[0] != 0.toByte() && syntax.collects(data[0])) {
                if (data.size <= MAX_SCAN_UNIT_BYTES) consume(data, data.size, 0L)
            } else {
                units.scan(data, 0, data.size, 0L)
                units.finish()
            }
        }
        seeding = false
        units.reset()
        committed.copyFrom(working)
        return working.initialScan()
    }

    fun scan(data: ByteArray, from: Int, to: Int, position: Long) = units.scan(data, from, to, position)

    fun commit(sampleEnd: Long) {
        committedEnd = sampleEnd
        if (lostHistoryFrom?.let { it < sampleEnd } == true) {
            committed.clear()
            lostHistoryFrom = null
        }
        while (staged.isNotEmpty() && staged.first().position < sampleEnd) committed.apply(staged.removeFirst().unit)
    }

    fun discardUncommitted() {
        units.reset()
        staged.clear()
        lostHistoryFrom = null
        working.copyFrom(committed)
    }

    private fun consume(data: ByteArray, limit: Int, position: Long): Boolean {
        when (val parsed = syntax.parseOrNull(data, limit) ?: return false) {
            is ScanUnit.Sequence -> {
                update(parsed, position)
                if (!seeding && syntax == ScanSyntax.MPEG2) onScan(position, parsed.scan)
            }
            is ScanUnit.Picture -> update(parsed, position)
            is ScanUnit.Slice -> if (!seeding && parsed.pictureId != null) {
                onScan(position, working.resolve(parsed.pictureId))
            }
        }
        return true
    }

    private fun update(unit: ScanUnit, position: Long) {
        if (!seeding && staged.size == MAX_STAGED_UPDATES) {
            // If a commit crosses discarded history, forget confidence rather than guess its maps.
            lostHistoryFrom = lostHistoryFrom ?: staged.first().position
            staged.clear()
        }
        working.apply(unit)
        when {
            seeding -> Unit
            position < committedEnd -> committed.apply(unit)
            else -> staged.addLast(ParameterUpdate(position, unit))
        }
    }

    private class ParameterUpdate(val position: Long, val unit: ScanUnit)

    private companion object {
        const val MAX_STAGED_UPDATES = 1024
    }
}

/** Bounded by the codec's SPS/PPS id ranges, separately for working and committed declarations. */
private class ScanParameterMaps {
    private val sequences = HashMap<Int, VideoScanType>()
    private val pictures = HashMap<Int, Int>()

    fun apply(unit: ScanUnit) {
        when (unit) {
            is ScanUnit.Sequence -> sequences[unit.id] = unit.scan
            is ScanUnit.Picture -> pictures[unit.id] = unit.sequenceId
            is ScanUnit.Slice -> Unit
        }
    }

    fun resolve(pictureId: Int): VideoScanType = pictures[pictureId]?.let(sequences::get) ?: VideoScanType.UNKNOWN

    fun initialScan(): VideoScanType = sequences.values.distinct().singleOrNull() ?: VideoScanType.UNKNOWN

    fun clear() {
        sequences.clear()
        pictures.clear()
    }

    fun copyFrom(other: ScanParameterMaps) {
        clear()
        sequences.putAll(other.sequences)
        pictures.putAll(other.pictures)
    }
}

/** Uses Media3's escaped bit reader; bounds unary prefixes before they can overflow Int shifts. */
private class ScanBits(data: ByteArray, offset: Int, limit: Int) {
    private val bits = ParsableNalUnitBitArray(data, offset, limit)
    var complete: Boolean = true
        private set

    fun read(count: Int): Int {
        if (!available(count)) return 0
        return bits.readBits(count)
    }

    fun flag(): Boolean = read(1) != 0

    fun skip(count: Int) {
        if (available(count)) bits.skipBits(count)
    }

    fun require(condition: Boolean) {
        if (complete) check(condition)
    }

    private fun available(count: Int): Boolean {
        complete = complete && bits.canReadBits(count)
        return complete
    }

    // Media3's readUnsignedExpGolombCodedInt has no unary-prefix or value bound.
    fun ue(maximum: Int): Int {
        var zeros = 0
        while (!flag()) {
            if (!complete) return 0
            check(++zeros <= 30)
        }
        val suffix = if (zeros == 0) 0 else read(zeros)
        if (!complete) return 0
        val value = (1L shl zeros) - 1L + suffix
        require(value <= maximum)
        return value.toInt()
    }

    fun se(): Int {
        val value = ue(Int.MAX_VALUE - 1)
        if (!complete) return 0
        return if (value and 1 == 0) -(value / 2) else (value + 1) / 2
    }
}

/** Reads H.264 7.3.2.1.1 only through frame_mbs_only_flag; all loops have syntax bounds. */
private object H264ScanPrefix {
    private val EXTENDED_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    private val SUPPORTED_PROFILES = EXTENDED_PROFILES + setOf(66, 77, 88)

    fun parse(unit: ByteArray, limit: Int): ScanUnit? {
        check(limit > 0 && unit[0].toInt() and 0x80 == 0)
        val bits = ScanBits(unit, 1, limit)
        return when (unit[0].toInt() and 0x1F) {
            7 -> sequence(bits)
            8 -> ScanUnit.Picture(bits.ue(255), bits.ue(31))
            1, 2, 5 -> slice(bits)
            else -> null
        }.takeIf { bits.complete }
    }

    private fun sequence(bits: ScanBits): ScanUnit.Sequence {
        val profile = bits.read(8)
        bits.require(profile in SUPPORTED_PROFILES)
        bits.skip(16)
        val id = bits.ue(31)
        if (profile in EXTENDED_PROFILES) extendedProfile(bits)
        bits.ue(12) // log2_max_frame_num_minus4
        pictureOrder(bits)
        bits.ue(16) // max_num_ref_frames
        bits.skip(1)
        bits.ue(65535)
        bits.ue(65535)
        return ScanUnit.Sequence(id, scanFlag(bits.flag()))
    }

    private fun extendedProfile(bits: ScanBits) {
        val chroma = bits.ue(3)
        if (!bits.complete) return
        if (chroma == 3) bits.skip(1)
        bits.ue(6)
        bits.ue(6)
        bits.skip(1)
        if (bits.flag()) {
            repeat(if (chroma == 3) 12 else 8) { index ->
                if (bits.flag()) scalingList(bits, if (index < 6) 16 else 64)
                if (!bits.complete) return
            }
        }
    }

    private fun scalingList(bits: ScanBits, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) {
                val delta = bits.se()
                if (!bits.complete) return
                next = (last + delta) and 0xFF
            }
            if (next != 0) last = next
        }
    }

    private fun pictureOrder(bits: ScanBits) {
        when (bits.ue(2)) {
            0 -> bits.ue(12)
            1 -> {
                bits.skip(1)
                bits.se()
                bits.se()
                val count = bits.ue(255)
                if (!bits.complete) return
                repeat(count) {
                    bits.se()
                    if (!bits.complete) return
                }
            }
        }
    }

    private fun slice(bits: ScanBits): ScanUnit.Slice {
        if (bits.ue(65535) != 0) return ScanUnit.Slice(null)
        bits.ue(9)
        return ScanUnit.Slice(bits.ue(255))
    }
}

/** Reads H.265 7.3.2.2 / 7.3.3, without parsing reference sets or allocating data-sized arrays. */
private object H265ScanPrefix {
    fun parse(unit: ByteArray, limit: Int): ScanUnit? {
        val bits = ScanBits(unit, 0, limit)
        bits.require(!bits.flag())
        val type = bits.read(6)
        if (bits.read(6) != 0) return null // Only base-layer declarations describe these pictures.
        bits.require(bits.read(3) != 0)
        return when (type) {
            33 -> sequence(bits)
            34 -> ScanUnit.Picture(bits.ue(63), bits.ue(15))
            in 0..31 -> slice(bits, type)
            else -> null
        }.takeIf { bits.complete }
    }

    private fun sequence(bits: ScanBits): ScanUnit.Sequence {
        bits.skip(4)
        val subLayers = bits.read(3)
        bits.require(subLayers <= 6)
        bits.skip(1)
        bits.skip(8 + 32) // general_profile_space, tier, profile_idc, compatibility flags
        val progressive = bits.flag()
        val interlaced = bits.flag()
        bits.skip(46 + 8) // Remaining constraint flags and general_level_idc.
        subLayerProfiles(bits, subLayers)
        val scan = when {
            progressive && !interlaced -> VideoScanType.PROGRESSIVE
            interlaced && !progressive -> VideoScanType.INTERLACED
            else -> VideoScanType.UNKNOWN
        }
        return ScanUnit.Sequence(bits.ue(15), scan)
    }

    private fun subLayerProfiles(bits: ScanBits, count: Int) {
        if (!bits.complete) return
        val profiles = BooleanArray(count)
        val levels = BooleanArray(count)
        repeat(count) { index ->
            profiles[index] = bits.flag()
            levels[index] = bits.flag()
            if (!bits.complete) return
        }
        if (count > 0) bits.skip((8 - count) * 2)
        if (!bits.complete) return
        repeat(count) { index ->
            if (profiles[index]) bits.skip(88)
            if (levels[index]) bits.skip(8)
            if (!bits.complete) return
        }
    }

    private fun slice(bits: ScanBits, type: Int): ScanUnit.Slice {
        if (!bits.flag()) return ScanUnit.Slice(null)
        if (type in 16..23) bits.skip(1)
        return ScanUnit.Slice(bits.ue(63))
    }
}

private fun scanFlag(progressive: Boolean): VideoScanType =
    if (progressive) VideoScanType.PROGRESSIVE else VideoScanType.INTERLACED

private const val MAX_SCAN_UNIT_BYTES = 8 * 1024

/** Finds bounded Annex-B units; slice prefixes resolve as soon as their ids arrive. */
private class ParameterSetScanner(
    private val syntax: ScanSyntax,
    private val onUnit: (ByteArray, Int, Long) -> Boolean,
) {
    private var zeros = 0
    private var awaitingHeader = false
    private var collecting = false
    private var slice = false
    private var unit = ByteArray(256)
    private var unitLength = 0
    private var unitPosition = 0L

    fun scan(data: ByteArray, from: Int, to: Int, firstPosition: Long) {
        for (index in from until to) {
            val byte = data[index]
            if (awaitingHeader) start(byte, firstPosition + index - from)
            when {
                byte == 0.toByte() -> zeros = minOf(zeros + 1, MAX_SCAN_UNIT_BYTES + 1)
                zeros >= 2 && byte == 1.toByte() -> {
                    complete()
                    awaitingHeader = true
                    zeros = 0
                }
                else -> {
                    repeat(zeros) { append(0) }
                    zeros = 0
                    append(byte)
                }
            }
        }
        if (slice && collecting && onUnit(unit, unitLength, unitPosition)) collecting = false
    }

    fun finish() {
        repeat(zeros) { append(0) }
        complete()
        reset()
    }

    fun reset() {
        zeros = 0
        awaitingHeader = false
        collecting = false
        unitLength = 0
    }

    private fun start(header: Byte, position: Long) {
        awaitingHeader = false
        collecting = syntax.collects(header)
        slice = syntax.isSlice(header)
        unitLength = 0
        unitPosition = position
    }

    private fun append(byte: Byte) {
        if (!collecting) return
        if (unitLength == if (slice) MAX_SLICE_PREFIX_BYTES else MAX_SCAN_UNIT_BYTES) {
            collecting = false
            return
        }
        if (unitLength == unit.size) unit = unit.copyOf(minOf(unit.size * 2, MAX_SCAN_UNIT_BYTES))
        unit[unitLength++] = byte
        if (slice && unitLength == MAX_SLICE_PREFIX_BYTES) {
            onUnit(unit, unitLength, unitPosition)
            collecting = false
        }
    }

    private fun complete() {
        if (collecting) onUnit(unit, unitLength, unitPosition)
        collecting = false
    }

    private companion object {
        const val MAX_SLICE_PREFIX_BYTES = 32
    }
}

/** Returns this format with [scan] as its only [VideoScanMetadata] entry; UNKNOWN adds none. */
private fun Format.withScan(scan: VideoScanType): Format {
    val others = metadata?.let { existing ->
        (0 until existing.length()).map(existing::get).filterNot { it is VideoScanMetadata }
    }.orEmpty()
    val entries = if (scan == VideoScanType.UNKNOWN) others else others + VideoScanMetadata(scan)
    val next = if (entries.isEmpty()) null else Metadata(metadata?.presentationTimeUs ?: C.TIME_UNSET, entries)
    return if (next == metadata) this else buildUpon().setMetadata(next).build()
}
