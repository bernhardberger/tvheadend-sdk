@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrowingTsIndexTest {
    @Test
    fun `validated video codecs publish estimated maps from preceding observed keyframes`() {
        listOf(MimeTypes.VIDEO_MPEG2, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265).forEach { mimeType ->
            val index = GrowingTsIndex(minimumMapAdvanceUs = 5_000_000L)
            index.onVideoFormat(VIDEO_TRACK, mimeType)
            addKeyframes(index, count = 4, intervalUs = 2_000_000L)

            val map = index.nextSeekMap() as GrowingTsSeekMap

            assertTrue(map.isSeekable)
            assertTrue(map.isEstimated)
            assertEquals(8_000_000L, map.indexedHorizonUs)
            assertEquals(0L, map.points.first().position)
            assertEquals(
                packetPosition(3),
                map.points.last().position,
                "The target must pre-roll from the preceding observed keyframe",
            )
            assertTrue(map.points.all { point -> point.position % GROWING_TS_PACKET_BYTES == 0L })
        }
    }

    @Test
    fun `unvalidated codec and ambiguous video layout remain non-seekable`() {
        val unsupported = GrowingTsIndex()
        unsupported.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_AV1)
        addKeyframes(unsupported, count = 8)
        assertNull(unsupported.nextSeekMap())

        val ambiguous = GrowingTsIndex(minimumMapAdvanceUs = 1L)
        ambiguous.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(ambiguous, count = 4)
        assertTrue(ambiguous.nextSeekMap() is GrowingTsSeekMap)

        ambiguous.onVideoFormat(VIDEO_TRACK + 1, MimeTypes.VIDEO_H264)

        assertSame(EstimatedUnseekableGrowingTsSeekMap, ambiguous.nextSeekMap())
        assertNull(ambiguous.nextSeekMap())
    }

    @Test
    fun `validated codec drift retracts a previously seekable estimate`() {
        val index = GrowingTsIndex(minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(index, count = 4)
        assertTrue(index.nextSeekMap() is GrowingTsSeekMap)

        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H265)

        assertSame(EstimatedUnseekableGrowingTsSeekMap, index.nextSeekMap())
        assertEquals(0, index.pointCount)
    }

    @Test
    fun `map updates require a bounded indexed-horizon advance`() {
        val index = GrowingTsIndex(minimumMapAdvanceUs = 5_000_000L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_MPEG2)
        addKeyframes(index, count = 4, intervalUs = 2_000_000L)
        val first = index.nextSeekMap() as GrowingTsSeekMap

        addKeyframe(index, ordinal = 5, timeUs = first.indexedHorizonUs + 4_999_999L)
        assertNull(index.nextSeekMap())
        addKeyframe(index, ordinal = 6, timeUs = first.indexedHorizonUs + 5_000_000L)

        val second = index.nextSeekMap() as GrowingTsSeekMap
        assertEquals(first.indexedHorizonUs + 5_000_000L, second.indexedHorizonUs)
    }

    @Test
    fun `unexplained regressing keyframe evidence retracts a published map`() {
        val index = GrowingTsIndex(minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(index, count = 4)
        assertTrue(index.nextSeekMap() is GrowingTsSeekMap)

        index.onVideoKeyframe(
            trackId = VIDEO_TRACK,
            timeUs = 2_000_000L,
            samplePosition = packetPosition(1),
            sourceReadPosition = packetPosition(4),
        )

        assertSame(EstimatedUnseekableGrowingTsSeekMap, index.nextSeekMap())
        assertEquals(0, index.pointCount)
    }

    @Test
    fun `backward extractor seek replays old evidence without invalidating the index`() {
        val index = GrowingTsIndex(minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(index, count = 4)
        assertTrue(index.nextSeekMap() is GrowingTsSeekMap)

        index.onExtractorSeek()
        addKeyframe(index, ordinal = 2, timeUs = 2_000_000L)

        assertNull(index.nextSeekMap())
        assertTrue(index.pointCount > 0)
        addKeyframe(index, ordinal = 5, timeUs = 5_000_000L)
        assertTrue(index.nextSeekMap() is GrowingTsSeekMap)
    }

    @Test
    fun `index compaction preserves origin horizon ordering and its hard memory bound`() {
        val index = GrowingTsIndex(maximumPoints = 8, minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)

        repeat(10_000) { zeroBased ->
            addKeyframe(index, ordinal = zeroBased + 1, timeUs = (zeroBased + 1L) * 1_000_000L)
            assertTrue(index.pointCount <= 8)
        }

        val map = index.nextSeekMap() as GrowingTsSeekMap
        assertEquals(0L, map.points.first().timeUs)
        assertEquals(10_000_000_000L, map.indexedHorizonUs)
        assertTrue(map.points.zipWithNext().all { (first, second) -> first.timeUs < second.timeUs })
        assertTrue(map.points.zipWithNext().all { (first, second) -> first.position <= second.position })
    }

    @Test
    fun `seek map returns conservative floor and ceiling points without claiming final duration`() {
        val index = GrowingTsIndex(minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_MPEG2)
        addKeyframes(index, count = 4, intervalUs = 2_000_000L)
        val map = index.nextSeekMap() as GrowingTsSeekMap

        val seekPoints = map.getSeekPoints(7_000_000L)

        assertEquals(6_000_000L, seekPoints.first.timeUs)
        assertEquals(8_000_000L, seekPoints.second.timeUs)
        assertEquals(map.indexedHorizonUs, map.durationUs)
        assertFalse(EstimatedUnseekableGrowingTsSeekMap.isSeekable)

        val beyondHorizon = map.getSeekPoints(map.indexedHorizonUs + 1_000_000L)
        assertEquals(map.points.last().timeUs, beyondHorizon.first.timeUs)
        assertEquals(map.points.last().position, beyondHorizon.first.position)
        assertEquals(beyondHorizon.first, beyondHorizon.second)
    }

    @Test
    fun `extractor indexes the start of each delegate read window rather than its buffered end`() {
        val index = GrowingTsIndex(minimumMapAdvanceUs = 1L)
        val delegate = WindowedKeyframeExtractor()
        val output = CapturingSeekMapOutput()
        val extractor = GrowingTsExtractor(delegate = delegate, index = index)
        val input = DefaultExtractorInput(
            DataReader { buffer, offset, length ->
                buffer.fill(0, offset, offset + length)
                length
            },
            0L,
            C.LENGTH_UNSET.toLong(),
        )
        extractor.init(output)

        repeat(10) { extractor.read(input, PositionHolder()) }

        val map = output.maps.filterIsInstance<GrowingTsSeekMap>().single()
        assertEquals(
            listOf(0L, 0L, 0L, 9_400L, 18_800L, 28_200L),
            map.points.map(GrowingTsIndexPoint::position),
        )
    }

    @Test
    fun `extractor retracts seekability when a dormant second video track is declared`() {
        val output = CapturingSeekMapOutput()
        val extractor = GrowingTsExtractor(delegate = DormantSecondVideoExtractor())
        val input = DefaultExtractorInput(
            DataReader { buffer, offset, length ->
                buffer.fill(0, offset, offset + length)
                length
            },
            0L,
            C.LENGTH_UNSET.toLong(),
        )
        extractor.init(output)

        repeat(8) { extractor.read(input, PositionHolder()) }

        assertTrue(output.maps.any { map -> map is GrowingTsSeekMap })
        assertSame(EstimatedUnseekableGrowingTsSeekMap, output.maps.last())
    }

    @Test
    fun `factory exposes only the maintained TS wrapper`() {
        val extractors = createGrowingTsExtractorsFactory().createExtractors()

        assertEquals(1, extractors.size)
        assertTrue(extractors.single() is GrowingTsExtractor)
    }

    private fun addKeyframes(
        index: GrowingTsIndex,
        count: Int,
        intervalUs: Long = 1_000_000L,
    ) {
        repeat(count) { zeroBased ->
            val ordinal = zeroBased + 1
            addKeyframe(index, ordinal, ordinal * intervalUs)
        }
    }

    private fun addKeyframe(index: GrowingTsIndex, ordinal: Int, timeUs: Long) {
        index.onVideoKeyframe(
            trackId = VIDEO_TRACK,
            timeUs = timeUs,
            samplePosition = packetPosition(ordinal),
            sourceReadPosition = packetPosition(ordinal + 1),
        )
    }

    private fun packetPosition(ordinal: Int): Long = ordinal * 100L * GROWING_TS_PACKET_BYTES
}

private class WindowedKeyframeExtractor : Extractor {
    private lateinit var track: TrackOutput
    private var readCount = 0
    private var keyframeCount = 0

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        track = output.track(VIDEO_TRACK, C.TRACK_TYPE_VIDEO)
        track.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        output.endTracks()
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        readCount += 1
        if (readCount % 2 == 1) {
            input.readFully(ByteArray(9_400), 0, 9_400)
        } else {
            keyframeCount += 1
            sample(timeUs = keyframeCount * 1_000_000L - 1L, flags = 0)
            sample(timeUs = keyframeCount * 1_000_000L, flags = C.BUFFER_FLAG_KEY_FRAME)
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long): Unit = Unit

    override fun release(): Unit = Unit

    private fun sample(timeUs: Long, flags: Int) {
        track.sampleData(ParsableByteArray(ByteArray(1)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(timeUs, flags, 1, 0, null)
    }
}

private class DormantSecondVideoExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private lateinit var primaryTrack: TrackOutput
    private var readCount = 0
    private val window = ByteArray(9_400)

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        this.output = output
        primaryTrack = output.track(VIDEO_TRACK, C.TRACK_TYPE_VIDEO)
        primaryTrack.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        output.endTracks()
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        readCount += 1
        if (readCount == 8) {
            output.track(VIDEO_TRACK + 1, C.TRACK_TYPE_VIDEO)
        } else {
            input.readFully(window, 0, window.size)
            primaryTrack.sampleData(ParsableByteArray(ByteArray(1)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN)
            primaryTrack.sampleMetadata(
                readCount * 1_000_000L,
                C.BUFFER_FLAG_KEY_FRAME,
                1,
                0,
                null,
            )
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long): Unit = Unit

    override fun release(): Unit = Unit
}

private class CapturingSeekMapOutput : ExtractorOutput {
    val maps = ArrayList<SeekMap>()

    override fun track(id: Int, type: Int): TrackOutput = EmptyTrackOutput

    override fun endTracks(): Unit = Unit

    override fun seekMap(seekMap: SeekMap) {
        maps += seekMap
    }
}

private object EmptyTrackOutput : TrackOutput {
    override fun format(format: Format): Unit = Unit

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int = input.read(ByteArray(length), 0, length)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.skipBytes(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ): Unit = Unit
}

private const val VIDEO_TRACK: Int = 7
