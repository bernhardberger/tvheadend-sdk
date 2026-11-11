@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

// Media3 1.11.1 may skip up to 256 KiB before each timestamp search and at its final landing.
private const val MP4_SEARCH_SKIP_RESERVE_BYTES = 256 * 1024L
private const val MP4_SEARCH_BYTES = 32 * 1024 * 1024L

/** Probes finished fragments without exposing the muxer's stale duration or unsafe MFRA index. */
internal class FinishedFragmentedMp4Extractor(private val delegate: Extractor) : Extractor by delegate {
    private var output: ExtractorOutput? = null
    private var track: RecordingMp4Track? = null
    private var initialized = false
    private var firstMoofPosition: Long? = null
    private var footerPosition: Long? = null
    private var indexPosition: Long? = null
    private var mediaEnd = 0L
    private var tailBytes = ByteArray(0)
    private var tailPosition: Long? = null
    private var returnPosition: Long? = null
    private var seeker: RecordingMp4BinarySearchSeeker? = null

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    override fun init(output: ExtractorOutput) {
        this.output = output
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                if (seeker == null) {
                    output.seekMap(
                        if (!seekMap.isSeekable && track?.hasUnsignedNegativeDuration == true) SeekMap.Unseekable(C.TIME_UNSET)
                        else seekMap,
                    )
                }
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!initialized) {
            initialized = true
            if (input.position == 0L) {
                track = readTrack(input)
                if (track != null && input.length != C.LENGTH_UNSET.toLong()) {
                    returnPosition = input.position
                    footerPosition = input.length - 16
                }
            }
        }
        if (probe(input, seekPosition)) return Extractor.RESULT_SEEK
        returnPosition?.let { position ->
            if (input.position != position) {
                seekPosition.position = position
                return Extractor.RESULT_SEEK
            }
            returnPosition = null
        }
        seeker?.takeIf { it.isSeeking }?.let { return it.handlePendingSeek(input, seekPosition) }
        return delegate.read(input, seekPosition)
    }

    private fun probe(input: ExtractorInput, seekPosition: PositionHolder): Boolean {
        footerPosition?.let { position ->
            if (input.position != position) {
                seekPosition.position = position
                return true
            }
            val bytes = ByteArray(16)
            input.readFully(bytes, 0, bytes.size)
            val footer = ParsableByteArray(bytes)
            if (footer.readUnsignedInt() == 16L && footer.readInt() == Mp4Box.TYPE_mfro && footer.readInt() == 0) {
                val size = footer.readUnsignedInt()
                val start = input.length - size
                if (size >= 24 && firstMoofPosition?.let { start > it } == true) indexPosition = start
            }
            footerPosition = null
            if (indexPosition == null) startTail(input.length)
        }
        indexPosition?.let { position ->
            if (input.position != position) {
                seekPosition.position = position
                return true
            }
            val bytes = ByteArray(16)
            input.readFully(bytes, 0, bytes.size)
            val header = ParsableByteArray(bytes)
            val shortSize = header.readUnsignedInt()
            val type = header.readInt()
            val size = if (shortSize == 1L) header.readLong() else shortSize
            startTail(if (type == Mp4Box.TYPE_mfra && size == input.length - position) position else input.length)
            indexPosition = null
        }
        while (tailPosition != null) {
            val position = checkNotNull(tailPosition)
            if (input.position != position) {
                seekPosition.position = position
                return true
            }
            val bytes = ByteArray((mediaEnd - position).toInt())
            val added = bytes.size - tailBytes.size
            input.readFully(bytes, 0, added)
            tailBytes.copyInto(bytes, added)
            tailBytes = bytes
            val duration = RecordingMp4Fragments.lastEndTime(bytes, position, mediaEnd, checkNotNull(track))
            if (duration != null && duration > 0 && duration < Long.MAX_VALUE) {
                seeker = RecordingMp4BinarySearchSeeker(checkNotNull(track), duration, mediaEnd)
                checkNotNull(output).seekMap(checkNotNull(seeker).seekMap)
                tailPosition = null
            } else {
                val nextSize = minOf(MP4_PROBE_BYTES.toLong(), bytes.size.toLong() * 2, mediaEnd)
                tailPosition = (mediaEnd - nextSize).takeIf { nextSize > bytes.size }
            }
        }
        tailBytes = ByteArray(0)
        return false
    }

    private fun startTail(end: Long) {
        mediaEnd = end
        tailPosition = (end - 1024 * 1024).coerceAtLeast(0L)
    }

    override fun seek(position: Long, timeUs: Long) {
        seeker = seeker?.let {
            RecordingMp4BinarySearchSeeker(checkNotNull(track), it.seekMap.durationUs, mediaEnd)
                .apply { setSeekTargetUs(timeUs) }
        }
        delegate.seek(position, timeUs)
    }

    private fun readTrack(input: ExtractorInput): RecordingMp4Track? {
        input.resetPeekPosition()
        try {
            val size = input.length.takeUnless { it == C.LENGTH_UNSET.toLong() } ?: Long.MAX_VALUE
            var parsedTrack: RecordingMp4Track? = null
            val header = ByteArray(16)
            while (input.peekPosition < minOf(size, MP4_PROBE_BYTES.toLong())) {
                input.peekFully(header, 0, 8)
                var box = RecordingMp4Fragments.header(header, 0, 8)
                if (box == null) {
                    input.peekFully(header, 8, 8)
                    box = RecordingMp4Fragments.header(header, 0, 16) ?: return null
                }
                if (box.type == Mp4Box.TYPE_moof) {
                    if (box.size <= size - input.peekPosition + box.headerSize) {
                        firstMoofPosition = input.peekPosition - box.headerSize
                    }
                    return parsedTrack
                }
                if (box.size > MP4_PROBE_BYTES - input.peekPosition + box.headerSize ||
                    box.size > size - input.peekPosition + box.headerSize
                ) return parsedTrack
                if (box.type == Mp4Box.TYPE_moov) {
                    val bytes = ByteArray(box.size)
                    header.copyInto(bytes, 0, 0, box.headerSize)
                    input.peekFully(bytes, box.headerSize, bytes.size - box.headerSize)
                    parsedTrack = RecordingMp4Fragments.track(bytes)
                } else {
                    input.advancePeekPosition(box.size - box.headerSize)
                }
            }
            return parsedTrack
        } finally {
            input.resetPeekPosition()
        }
    }
}

/** Keeps timestamp scanning independent of the finished extractor's tail-probe lifecycle. */
internal class RecordingMp4BinarySearchSeeker(track: RecordingMp4Track, durationUs: Long, sizeBytes: Long) : BinarySearchSeeker(
    DefaultSeekTimestampConverter(), Mp4TimestampSeeker(track, durationUs, sizeBytes), durationUs,
    0, durationUs + 1, 0, sizeBytes, 1, 1,
) {
    private class Mp4TimestampSeeker(
        private val track: RecordingMp4Track,
        private val durationUs: Long,
        private val sizeBytes: Long,
    ) : TimestampSeeker {
        // Byte zero replays moov and its initial mdat, matching the parser's reset sample tables.
        // An arbitrary first moof is not safe when it omits a track with initial samples.
        private var floorPosition = 0L
        private var floorEndPosition = 0L
        private var ceilingTimeUs = durationUs + 1
        private var steps = 0
        private var ceilingPosition = sizeBytes
        private var remainingBytes = MP4_SEARCH_BYTES - 2 * MP4_SEARCH_SKIP_RESERVE_BYTES

        override fun onSeekFinished() {
            floorPosition = 0
            floorEndPosition = 0
            ceilingTimeUs = durationUs + 1
            steps = 0
            ceilingPosition = sizeBytes
            remainingBytes = MP4_SEARCH_BYTES - 2 * MP4_SEARCH_SKIP_RESERVE_BYTES
        }

        override fun searchForTimestamp(input: ExtractorInput, targetTimestamp: Long): TimestampSearchResult {
            // Charge even external repositionings conservatively; the initial reserve also covers
            // a last partial charge and the framework's final skip back to the validated floor.
            remainingBytes = (remainingBytes - MP4_SEARCH_SKIP_RESERVE_BYTES).coerceAtLeast(0)
            if (++steps > 64 || remainingBytes == 0L) return TimestampSearchResult.targetFoundResult(floorPosition)
            val start = input.position
            val scanStart = maxOf(start, floorEndPosition)
            val candidate = scan(input, scanStart)
            if (candidate == null) {
                if (start <= floorEndPosition || remainingBytes == 0L) return TimestampSearchResult.targetFoundResult(floorPosition)
                ceilingPosition = start
                return TimestampSearchResult.overestimatedResult(ceilingTimeUs, start)
            }
            if (candidate.timeUs <= targetTimestamp) {
                floorPosition = candidate.position
                floorEndPosition = candidate.endPosition
                return TimestampSearchResult.underestimatedResult(candidate.timeUs, candidate.position)
            }
            if (start <= floorEndPosition) return TimestampSearchResult.targetFoundResult(floorPosition)
            ceilingTimeUs = candidate.timeUs
            ceilingPosition = start
            return TimestampSearchResult.overestimatedResult(candidate.timeUs, start)
        }

        private fun scan(input: ExtractorInput, scanStart: Long): RecordingMp4Fragment? {
            val skip = scanStart - input.position
            if (scanStart >= sizeBytes || skip > MP4_PROBE_BYTES) return null
            if (skip > remainingBytes) {
                remainingBytes = 0
                return null
            }
            input.skipFully(skip.toInt())
            remainingBytes -= skip
            var base = scanStart
            var retained = 0
            val overlap = MP4_FRAGMENT_BYTES + 16 // Include the following mdat's largest header.
            val bytes = ByteArray(MP4_FRAGMENT_BYTES + overlap)
            val upperBound = ceilingPosition + minOf(sizeBytes - ceilingPosition, overlap.toLong())
            val end = scanStart + minOf((upperBound - scanStart).coerceAtLeast(0), MP4_PROBE_BYTES.toLong(), remainingBytes)
            while (base + retained < end) {
                val count = minOf(MP4_FRAGMENT_BYTES.toLong(), end - base - retained).toInt()
                input.readFully(bytes, retained, count)
                remainingBytes -= count
                val valid = bytes.copyOf(retained + count)
                RecordingMp4Fragments.find(valid, base, sizeBytes, track, true)?.takeIf {
                    it.timeUs in 0..durationUs
                }?.let { return it }
                retained = minOf(overlap, valid.size)
                valid.copyInto(bytes, 0, valid.size - retained, valid.size)
                base += valid.size - retained
            }
            return null
        }
    }
}
