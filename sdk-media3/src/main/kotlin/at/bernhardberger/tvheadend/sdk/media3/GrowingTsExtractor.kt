@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.GrowingTsBinarySearch
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun createGrowingTsExtractorsFactory(
    onSeekMap: (SeekMap) -> Unit = {},
    lease: GrowingRecordingFileLease? = null,
): ExtractorsFactory = ExtractorsFactory {
    arrayOf(GrowingTsExtractor(onSeekMap = onSeekMap, lease = lease))
}

/** Thin indexing decorator; Media3's maintained [TsExtractor] still owns all TS parsing. */
internal class GrowingTsExtractor(
    private val delegate: Extractor = TsExtractor(SubtitleParser.Factory.UNSUPPORTED),
    private val index: GrowingTsIndex = GrowingTsIndex(),
    private val onSeekMap: (SeekMap) -> Unit = {},
    private val lease: GrowingRecordingFileLease? = null,
) : Extractor {
    private val publicationLock = Any()
    private var probeJob: Job? = null
    private var released = false
    @Volatile private var extent: GrowingTsExtent? = null
    private var publishedExtent: GrowingTsExtent? = null
    private var publishedSeekable = false
    private var binarySeeker: BinarySearchSeeker? = null
    private val trackOutputs = ArrayList<GrowingTsTrackOutput>()
    private var downstream: ExtractorOutput? = null
    private var conservativeReadPosition = 0L
    private var sentInitialMap = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        downstream = output
        delegate.init(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val delegateTrack = output.track(id, type)
                    if (type == C.TRACK_TYPE_VIDEO) index.onVideoTrack(id)
                    val indexed = GrowingTsTrackOutput(
                        delegate = delegateTrack,
                        trackId = id,
                        trackType = type,
                        readPosition = { conservativeReadPosition },
                        index = index,
                    )
                    trackOutputs += indexed
                    return indexed
                }

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) {
                    synchronized(publicationLock) {
                        if (!sentInitialMap && publishedExtent == null) {
                            sentInitialMap = true
                            // ProgressiveMediaSource rejects estimated updates after a definitive map.
                            onSeekMap(EstimatedUnseekableGrowingTsSeekMap)
                            output.seekMap(EstimatedUnseekableGrowingTsSeekMap)
                        }
                    }
                }
            },
        )
        if (lease != null) {
            // Extractor init/release is the synchronous Media3 lifecycle boundary. The probe has
            // its own readers and continues while playback loading is stopped (including pause).
            probeJob = CoroutineScope(Dispatchers.IO).launch {
                val probe = GrowingTsExtentProbe(lease)
                try {
                while (isActive && lease.isCurrent) {
                    val next = probe.sample()
                    synchronized(publicationLock) {
                        val previous = extent
                        if (!released && next != null &&
                            (previous == null || next.firstPcr == previous.firstPcr &&
                                next.pcrPid == previous.pcrPid && next.sizeBytes >= previous.sizeBytes &&
                                next.durationUs >= previous.durationUs)
                        ) {
                            extent = next
                            publishExtent()
                        }
                    }
                    if (extent?.isFinal == true) break
                    delay(5_000L)
                }
                synchronized(publicationLock) { publishExtent() }
                } finally {
                    probe.close()
                }
            }
        }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val seeker = binarySeeker
        if (seeker != null && seeker.isSeeking) {
            val limit = checkNotNull(extent).sizeBytes
            val boundedInput = object : ExtractorInput by input {
                override fun getLength(): Long = limit
            }
            return seeker.handlePendingSeek(boundedInput, seekPosition)
        }
        val readStartPosition = input.position
        val result = delegate.read(input, seekPosition)
        if (input.position > readStartPosition) {
            // TsExtractor reads ahead, so only the start of that input window is a safe lower bound.
            conservativeReadPosition = readStartPosition
        }
        if (lease == null) {
            index.nextSeekMap()?.let { map ->
                onSeekMap(map)
                checkNotNull(downstream).seekMap(map)
            }
        } else {
            synchronized(publicationLock) { publishExtent() }
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        binarySeeker = extent?.takeIf { index.seekingAllowed && timeUs > 0L }?.let {
            GrowingTsBinarySearch.create(it.firstPcr, it.durationUs, it.sizeBytes, it.pcrPid, GROWING_TS_SEARCH_BYTES)
                .apply { setSeekTargetUs(timeUs) }
        }
        conservativeReadPosition = position
        trackOutputs.forEach(GrowingTsTrackOutput::resetForSeek)
        index.onExtractorSeek()
        delegate.seek(position, timeUs)
    }

    override fun release() {
        synchronized(publicationLock) { released = true }
        probeJob?.cancel()
        delegate.release()
    }

    private fun publishExtent() {
        val current = extent ?: return
        val seekable = index.seekingAllowed && lease?.isCurrent == true
        if (released || current == publishedExtent && seekable == publishedSeekable) return
        publishedExtent = current
        publishedSeekable = seekable
        val nativeMap = GrowingTsBinarySearch.create(
            current.firstPcr, current.durationUs, current.sizeBytes, current.pcrPid, GROWING_TS_SEARCH_BYTES,
        ).seekMap
        val map = object : SeekMap by nativeMap {
            override fun isEstimated(): Boolean = !current.isFinal
            override fun isSeekable(): Boolean = seekable
        }
        // ProgressiveMediaPeriod marshals seekMap updates onto its playback-thread handler.
        onSeekMap(map)
        checkNotNull(downstream).seekMap(map)
    }
}

private class GrowingTsTrackOutput(
    private val delegate: TrackOutput,
    private val trackId: Int,
    private val trackType: Int,
    private val readPosition: () -> Long,
    private val index: GrowingTsIndex,
) : TrackOutput {
    private var sampleStartPosition: Long? = null

    override fun format(format: Format) {
        delegate.format(format)
        if (trackType == C.TRACK_TYPE_VIDEO) {
            index.onVideoFormat(trackId, format.sampleMimeType)
        }
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val observedPosition = readPosition()
        val read = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        if (read > 0 && sampleStartPosition == null) sampleStartPosition = observedPosition
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleStartPosition == null) sampleStartPosition = readPosition()
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        if (trackType == C.TRACK_TYPE_VIDEO && flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
            index.onVideoKeyframe(
                trackId = trackId,
                timeUs = timeUs,
                samplePosition = sampleStartPosition ?: readPosition(),
                sourceReadPosition = readPosition(),
            )
        }
        sampleStartPosition = readPosition()
    }

    fun resetForSeek() {
        sampleStartPosition = null
    }
}

/**
 * Bounded evidence index for one validated video track.
 *
 * A seek point starts at the preceding observed keyframe rather than subtracting a fixture-sized
 * byte constant. This retains one complete observed GOP of parser and decoder pre-roll for
 * validated codecs and naturally scales with stream bitrate and GOP size.
 */
internal class GrowingTsIndex(
    private val maximumPoints: Int = MAXIMUM_GROWING_TS_INDEX_POINTS,
    private val minimumMapAdvanceUs: Long = MINIMUM_GROWING_TS_MAP_ADVANCE_US,
) {
    private val points = ArrayList<GrowingTsIndexPoint>()
    private var selectedVideoTrackId: Int? = null
    private var selectedVideoMimeType: String? = null
    @Volatile private var codecState = GrowingTsCodecState.UNKNOWN
    val seekingAllowed: Boolean get() = codecState == GrowingTsCodecState.SUPPORTED
    private var previousKeyframe: GrowingTsKeyframe? = null
    private var seekablePointCount = 0
    private var publishedSeekableMap = false
    private var invalidationPending = false
    private var lastPublishedHorizonUs = C.TIME_UNSET
    private var replayingAfterSeek = false

    init {
        require(maximumPoints >= MINIMUM_GROWING_TS_INDEX_CAPACITY)
        require(minimumMapAdvanceUs > 0L)
    }

    internal val pointCount: Int
        get() = points.size

    fun onVideoTrack(trackId: Int) {
        if (codecState == GrowingTsCodecState.DENIED) return
        val selectedTrack = selectedVideoTrackId
        if (selectedTrack == null) {
            selectedVideoTrackId = trackId
        } else if (selectedTrack != trackId) {
            denySeeking()
        }
    }

    fun onVideoFormat(trackId: Int, sampleMimeType: String?) {
        onVideoTrack(trackId)
        if (codecState == GrowingTsCodecState.DENIED || trackId != selectedVideoTrackId) return
        if (sampleMimeType == null) return
        if (sampleMimeType !in SEEK_VALIDATED_TS_VIDEO_MIME_TYPES) {
            denySeeking()
            return
        }
        val selectedMime = selectedVideoMimeType
        if (selectedMime != null && selectedMime != sampleMimeType) {
            denySeeking()
            return
        }
        selectedVideoMimeType = sampleMimeType
        codecState = GrowingTsCodecState.SUPPORTED
    }

    fun onExtractorSeek() {
        if (codecState != GrowingTsCodecState.DENIED && previousKeyframe != null) {
            replayingAfterSeek = true
        }
    }

    fun onVideoKeyframe(
        trackId: Int,
        timeUs: Long,
        samplePosition: Long,
        sourceReadPosition: Long,
    ) {
        if (
            codecState != GrowingTsCodecState.SUPPORTED ||
            trackId != selectedVideoTrackId ||
            timeUs < 0L ||
            samplePosition < 0L ||
            sourceReadPosition < samplePosition
        ) {
            return
        }
        val alignedSamplePosition = packetAlignDown(samplePosition)
        val previous = previousKeyframe
        if (
            previous != null &&
            (
                timeUs < previous.timeUs ||
                    alignedSamplePosition < previous.samplePosition ||
                    sourceReadPosition < previous.sourceReadPosition
            )
        ) {
            if (!replayingAfterSeek) denySeeking()
            return
        }
        if (
            previous != null &&
            (
                timeUs == previous.timeUs ||
                    alignedSamplePosition == previous.samplePosition ||
                    sourceReadPosition == previous.sourceReadPosition
            )
        ) {
            return
        }
        replayingAfterSeek = false
        if (points.isEmpty()) points += GrowingTsIndexPoint(0L, 0L)
        if (timeUs > points.last().timeUs) {
            if (points.size == maximumPoints) compactIndex()
            val point = GrowingTsIndexPoint(
                timeUs = timeUs,
                position = previous?.samplePosition ?: 0L,
            )
            points += point
            if (point.position > 0L) seekablePointCount += 1
        }
        previousKeyframe = GrowingTsKeyframe(timeUs, alignedSamplePosition, sourceReadPosition)
    }

    fun nextSeekMap(): SeekMap? {
        if (invalidationPending) {
            invalidationPending = false
            publishedSeekableMap = false
            return EstimatedUnseekableGrowingTsSeekMap
        }
        if (codecState != GrowingTsCodecState.SUPPORTED) return null
        if (seekablePointCount < MINIMUM_GROWING_TS_SEEKABLE_POINTS) return null
        val horizonUs = points.last().timeUs
        if (
            publishedSeekableMap &&
            horizonUs - lastPublishedHorizonUs < minimumMapAdvanceUs
        ) {
            return null
        }
        publishedSeekableMap = true
        lastPublishedHorizonUs = horizonUs
        return GrowingTsSeekMap(points.toList())
    }

    private fun compactIndex() {
        val lastIndex = points.lastIndex
        val compacted = ArrayList<GrowingTsIndexPoint>(maximumPoints / 2 + 2)
        compacted += points.first()
        var index = 1
        while (index < lastIndex) {
            compacted += points[index]
            index += 2
        }
        if (compacted.last() != points.last()) compacted += points.last()
        points.clear()
        points += compacted
        seekablePointCount = points.count { point -> point.timeUs > 0L && point.position > 0L }
    }

    private fun denySeeking() {
        if (codecState == GrowingTsCodecState.DENIED) return
        codecState = GrowingTsCodecState.DENIED
        if (publishedSeekableMap) invalidationPending = true
        points.clear()
        seekablePointCount = 0
        previousKeyframe = null
        replayingAfterSeek = false
    }
}

internal class GrowingTsSeekMap(
    internal val points: List<GrowingTsIndexPoint>,
) : SeekMap {
    internal val indexedHorizonUs: Long = points.last().timeUs

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = indexedHorizonUs

    override fun isEstimated(): Boolean = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val result = points.binarySearchBy(timeUs) { point -> point.timeUs }
        val insertionIndex = if (result >= 0) result else -result - 1
        val ceilingIndex = insertionIndex.coerceAtMost(points.lastIndex)
        val floorIndex = if (result >= 0) {
            result
        } else {
            (insertionIndex - 1).coerceIn(0, points.lastIndex)
        }
        val floor = points[floorIndex].toSeekPoint()
        val ceiling = points[ceilingIndex].toSeekPoint()
        return if (floor == ceiling) SeekMap.SeekPoints(floor) else SeekMap.SeekPoints(floor, ceiling)
    }
}

internal data class GrowingTsIndexPoint(
    val timeUs: Long,
    val position: Long,
)

private data class GrowingTsKeyframe(
    val timeUs: Long,
    val samplePosition: Long,
    val sourceReadPosition: Long,
)

private enum class GrowingTsCodecState { UNKNOWN, SUPPORTED, DENIED }

internal object EstimatedUnseekableGrowingTsSeekMap : SeekMap {
    override fun isSeekable(): Boolean = false

    override fun getDurationUs(): Long = C.TIME_UNSET

    override fun isEstimated(): Boolean = true

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
        SeekMap.SeekPoints(SeekPoint(0L, 0L))
}

private fun GrowingTsIndexPoint.toSeekPoint(): SeekPoint = SeekPoint(timeUs, position)

private fun packetAlignDown(position: Long): Long =
    position / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES

internal const val GROWING_TS_PACKET_BYTES: Int = 188
internal const val MAXIMUM_GROWING_TS_INDEX_POINTS: Int = 4_096
internal const val MINIMUM_GROWING_TS_MAP_ADVANCE_US: Long = 5_000_000L
private const val MINIMUM_GROWING_TS_INDEX_CAPACITY: Int = 8
private const val MINIMUM_GROWING_TS_SEEKABLE_POINTS: Int = 3
private val SEEK_VALIDATED_TS_VIDEO_MIME_TYPES = setOf(
    MimeTypes.VIDEO_MPEG2,
    MimeTypes.VIDEO_H264,
    MimeTypes.VIDEO_H265,
)
