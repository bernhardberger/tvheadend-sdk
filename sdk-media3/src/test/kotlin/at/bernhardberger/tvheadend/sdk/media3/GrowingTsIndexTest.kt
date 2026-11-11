@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrowingTsIndexTest {
    companion object {
        @org.junit.jupiter.api.BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    @Test
    fun `confirmed completion publishes final extent and retires probe without extractor release`() = runTest {
        val bytes = File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
        val lease = ProbeLease(bytes, bytes.size).apply { completed = true }
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(
            GrowingTsContainer,
            delegate = WindowedKeyframeExtractor(),
            lease = lease,
            probeDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            extractor.init(output)
            runCurrent()
            assertTrue(output.maps.last().isSeekable)
            assertFalse(output.maps.last().isEstimated)
            assertEquals(1, lease.opens)
            assertEquals(1, lease.closes)
        } finally { extractor.release() }
    }

    @Test
    fun `lease driven map is retracted on continuity loss even with no playback consumption`() = runTest {
        val bytes = File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
        val lease = ProbeLease(bytes, bytes.size)
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(
            GrowingTsContainer,
            delegate = WindowedKeyframeExtractor(),
            lease = lease,
            probeDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            extractor.init(output)
            runCurrent()
            assertTrue(output.maps.last().isSeekable)
            lease.current = false
            // No further media consumption is required: the probe's next sample retracts the map.
            advanceTimeBy(5.seconds)
            runCurrent()
            assertFalse(output.maps.last().isSeekable)
            assertTrue(output.maps.last().durationUs > 23_000_000L)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `extractor release cancels a suspended probe and closes its reader without publishing`() = runTest {
        var reading = false
        val lease = ProbeLease(ByteArray(188 * 600), 188 * 600).apply {
            beforeRead = { reading = true; awaitCancellation() }
        }
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(
            GrowingTsContainer,
            delegate = WindowedKeyframeExtractor(),
            lease = lease,
            probeDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            extractor.init(output)
            runCurrent()
            assertTrue(reading)
            assertEquals(0, lease.closes)
        } finally {
            extractor.release()
        }
        runCurrent()
        assertEquals(1, lease.closes)
        assertTrue(output.maps.none { it.isSeekable })
    }

    @Test
    fun `validated video codecs publish estimated maps from preceding observed keyframes`() {
        listOf(MimeTypes.VIDEO_MPEG2, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265).forEach { mimeType ->
            val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 5_000_000L)
            index.onVideoFormat(VIDEO_TRACK, mimeType)
            addKeyframes(index, count = 4, intervalUs = 2_000_000L)

            val map = index.nextSeekMap() as GrowingRecordingSeekMap

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
        val unsupported = GrowingRecordingIndex(GrowingTsContainer)
        unsupported.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_AV1)
        addKeyframes(unsupported, count = 8)
        assertNull(unsupported.nextSeekMap())

        val ambiguous = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
        ambiguous.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(ambiguous, count = 4)
        assertTrue(ambiguous.nextSeekMap() is GrowingRecordingSeekMap)

        ambiguous.onVideoFormat(VIDEO_TRACK + 1, MimeTypes.VIDEO_H264)

        assertSame(EstimatedUnseekableGrowingSeekMap, ambiguous.nextSeekMap())
        assertNull(ambiguous.nextSeekMap())
    }

    @Test
    fun `validated codec drift retracts a previously seekable estimate`() {
        listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1).forEach { mimeType ->
            val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
            index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
            addKeyframes(index, count = 4)
            assertTrue(index.nextSeekMap() is GrowingRecordingSeekMap)

            index.onVideoFormat(VIDEO_TRACK, mimeType)

            assertSame(EstimatedUnseekableGrowingSeekMap, index.nextSeekMap())
            assertEquals(0, index.pointCount)
        }
    }

    @Test
    fun `map updates require a bounded indexed-horizon advance`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 5_000_000L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_MPEG2)
        addKeyframes(index, count = 4, intervalUs = 2_000_000L)
        val first = index.nextSeekMap() as GrowingRecordingSeekMap

        addKeyframe(index, ordinal = 5, timeUs = first.indexedHorizonUs + 4_999_999L)
        assertNull(index.nextSeekMap())
        addKeyframe(index, ordinal = 6, timeUs = first.indexedHorizonUs + 5_000_000L)

        val second = index.nextSeekMap() as GrowingRecordingSeekMap
        assertEquals(first.indexedHorizonUs + 5_000_000L, second.indexedHorizonUs)
    }

    @Test
    fun `unexplained regressing keyframe evidence retracts a published map`() {
        // Time and bytes regressing together, then time regressing alone.
        for ((sampleOrdinal, sourceOrdinal) in listOf(1 to 4, 5 to 6)) {
            val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
            index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
            addKeyframes(index, count = 4)
            assertTrue(index.nextSeekMap() is GrowingRecordingSeekMap)

            index.onVideoKeyframe(
                trackId = VIDEO_TRACK,
                timeUs = 2_000_000L,
                samplePosition = packetPosition(sampleOrdinal),
                sourceReadPosition = packetPosition(sourceOrdinal),
            )

            assertSame(EstimatedUnseekableGrowingSeekMap, index.nextSeekMap())
            assertEquals(0, index.pointCount)
        }
    }

    @Test
    fun `backward extractor seek replays old evidence without invalidating the index`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(index, count = 4)
        assertTrue(index.nextSeekMap() is GrowingRecordingSeekMap)

        index.onExtractorSeek()
        addKeyframe(index, ordinal = 2, timeUs = 2_000_000L)

        assertNull(index.nextSeekMap())
        assertTrue(index.pointCount > 0)
        addKeyframe(index, ordinal = 5, timeUs = 5_000_000L)
        assertTrue(index.nextSeekMap() is GrowingRecordingSeekMap)
    }

    @Test
    fun `index compaction preserves origin horizon ordering and its hard memory bound`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, maximumPoints = 8, minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)

        repeat(10_000) { zeroBased ->
            addKeyframe(index, ordinal = zeroBased + 1, timeUs = (zeroBased + 1L) * 1_000_000L)
            assertTrue(index.pointCount <= 8)
            if (zeroBased == 8) {
                val compacted = index.nextSeekMap() as GrowingRecordingSeekMap
                val floor = compacted.getSeekPoints(4_000_000L).first
                assertTrue(
                    floor.timeUs in compacted.points.first().timeUs + 1..4_000_000L,
                    "The first compaction keeps an interior preroll point",
                )
            }
        }

        val map = index.nextSeekMap() as GrowingRecordingSeekMap
        assertEquals(0L, map.points.first().timeUs)
        assertEquals(10_000_000_000L, map.indexedHorizonUs)
        assertTrue(map.points.zipWithNext().all { (first, second) -> first.timeUs < second.timeUs })
        assertTrue(map.points.zipWithNext().all { (first, second) -> first.position <= second.position })
    }

    @Test
    fun `zero time keyframe supplies preroll and equal timestamps preserve seekability`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframe(index, ordinal = 1, timeUs = 0L)
        addKeyframe(index, ordinal = 2, timeUs = 1_000_000L)
        addKeyframe(index, ordinal = 3, timeUs = 2_000_000L)
        addKeyframe(index, ordinal = 4, timeUs = 3_000_000L)
        addKeyframe(index, ordinal = 5, timeUs = 4_000_000L)
        val map = index.nextSeekMap() as GrowingRecordingSeekMap
        assertEquals(packetPosition(1), map.getSeekPoints(1_000_000L).first.position)

        addKeyframe(index, ordinal = 6, timeUs = 4_000_000L)
        addKeyframe(index, ordinal = 7, timeUs = 5_000_000L)
        assertTrue(index.nextSeekMap()!!.isSeekable)
    }

    @Test
    fun `samples sharing one read window add no seek horizon`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_H264)
        addKeyframes(index, count = 4)
        index.nextSeekMap()
        index.onVideoKeyframe(VIDEO_TRACK, 5_000_000L, packetPosition(5), packetPosition(5))
        assertNull(index.nextSeekMap())
    }

    @Test
    fun `zero minimum map advance is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 0L) }
    }

    @Test
    fun `seek map returns conservative floor and ceiling points without claiming final duration`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
        index.onVideoFormat(VIDEO_TRACK, MimeTypes.VIDEO_MPEG2)
        addKeyframes(index, count = 4, intervalUs = 2_000_000L)
        val map = index.nextSeekMap() as GrowingRecordingSeekMap

        val seekPoints = map.getSeekPoints(7_000_000L)

        assertEquals(6_000_000L, seekPoints.first.timeUs)
        assertEquals(8_000_000L, seekPoints.second.timeUs)
        assertEquals(map.indexedHorizonUs, map.durationUs)
        assertFalse(EstimatedUnseekableGrowingSeekMap.isSeekable)
        assertEquals(C.TIME_UNSET, EstimatedUnseekableGrowingSeekMap.durationUs)
        assertEquals(map.points.first().position, map.getSeekPoints(0L).first.position)

        val beyondHorizon = map.getSeekPoints(map.indexedHorizonUs + 1_000_000L)
        assertEquals(map.points.last().timeUs, beyondHorizon.first.timeUs)
        assertEquals(map.points.last().position, beyondHorizon.first.position)
        assertEquals(beyondHorizon.first, beyondHorizon.second)
    }

    @Test
    fun `extractor indexes the start of each delegate read window rather than its buffered end`() {
        val index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L)
        val delegate = WindowedKeyframeExtractor()
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(GrowingTsContainer, delegate = delegate, index = index)
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

        val map = output.maps.filterIsInstance<GrowingRecordingSeekMap>().single()
        assertEquals(
            listOf(0L, 0L, 0L, 9_400L, 18_800L, 28_200L),
            map.points.map(GrowingRecordingIndexPoint::position),
        )
    }

    @Test
    fun `extractor retracts seekability when a dormant second video track is declared`() {
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(GrowingTsContainer, delegate = DormantSecondVideoExtractor())
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

        assertTrue(output.maps.any { map -> map is GrowingRecordingSeekMap })
        assertSame(EstimatedUnseekableGrowingSeekMap, output.maps.last())
    }

    @Test
    fun `split samples retain their first read window and non-keyframes add no horizon`() {
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(
            GrowingTsContainer,
            delegate = WindowedKeyframeExtractor(splitSamples = true, trailingNonKeyframe = true),
            index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L),
        )
        extractor.init(output)
        val input = emptyInput()
        repeat(14) { extractor.read(input, PositionHolder()) }
        val map = output.maps.last()
        assertEquals(9_400L, map.getSeekPoints(4_000_000L).first.position)
        assertEquals(6_000_000L, map.durationUs)
        extractor.release()
    }

    @Test
    fun `wrapper seek resets sample origins forwards the exact pair and releases its delegate`() {
        val output = CapturingSeekMapOutput()
        val delegate = WindowedKeyframeExtractor(splitSamples = true)
        val extractor = GrowingRecordingExtractor(GrowingTsContainer, delegate = delegate, index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L))
        extractor.init(output)
        val input = emptyInput()
        repeat(12) { extractor.read(input, PositionHolder()) }
        extractor.seek(188_000L, 20_000_000L)
        val afterSeek = emptyInput(188_000L)
        repeat(6) { extractor.read(afterSeek, PositionHolder()) }
        assertEquals(188_000L, output.maps.last().getSeekPoints(22_000_000L).second.position)
        assertEquals(listOf(188_000L to 20_000_000L), delegate.seeks)
        extractor.release()
        assertEquals(1, delegate.releaseCalls)
    }

    @Test
    fun `wrapper backward seek preserves the published index during replay`() {
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(
            GrowingTsContainer,
            delegate = WindowedKeyframeExtractor(splitSamples = true),
            index = GrowingRecordingIndex(GrowingTsContainer, minimumMapAdvanceUs = 1L),
        )
        extractor.init(output)
        val input = emptyInput()
        repeat(12) { extractor.read(input, PositionHolder()) }
        extractor.seek(9_400L, 2_000_000L)
        val afterSeek = emptyInput(9_400L)
        repeat(2) { extractor.read(afterSeek, PositionHolder()) }
        assertTrue(output.maps.last().isSeekable)
        extractor.release()
    }

    @Test
    fun `factory exposes only the maintained TS wrapper`() {
        val extractors = createGrowingTsExtractorsFactory().createExtractors()

        assertEquals(1, extractors.size)
        assertTrue(extractors.single().underlyingImplementation is GrowingRecordingExtractor<*>)
    }

    @Test
    fun `wrapper preserves sniff rejection track completion and one initial estimated map`() {
        val output = CapturingSeekMapOutput()
        val extractor = GrowingRecordingExtractor(GrowingTsContainer, delegate = WindowedKeyframeExtractor(sniffResult = false, delegateMapCount = 2))
        assertFalse(extractor.sniff(emptyInput()))
        extractor.init(output)
        assertEquals(1, output.trackCompletions)
        assertEquals(listOf(EstimatedUnseekableGrowingSeekMap), output.maps.toList())
    }

    @Test
    fun `final probed extent waits for a supported format and publishes on the first ready read`() = runTest {
        listOf(null, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H264).forEach { mimeType ->
            val bytes = File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
            val lease = ProbeLease(bytes, bytes.size).apply { completed = true }
            val delegate = WindowedKeyframeExtractor(mimeType = mimeType, deferFormat = true)
            val output = CapturingSeekMapOutput()
            val extractor = GrowingRecordingExtractor(GrowingTsContainer, delegate = delegate, lease = lease, probeDispatcher = StandardTestDispatcher(testScheduler))
            try {
                extractor.init(output)
                runCurrent()
                assertFalse(output.maps.last().isSeekable)
                extractor.read(emptyInput(), PositionHolder())
                assertEquals(mimeType == MimeTypes.VIDEO_H264, output.maps.last().isSeekable)
            } finally { extractor.release() }
        }
    }

    private fun emptyInput(position: Long = 0L): ExtractorInput = DefaultExtractorInput(
        DataReader { buffer, offset, length ->
            buffer.fill(0, offset, offset + length)
            length
        },
        position,
        C.LENGTH_UNSET.toLong(),
    )

    private fun addKeyframes(
        index: GrowingRecordingIndex,
        count: Int,
        intervalUs: Long = 1_000_000L,
    ) {
        repeat(count) { zeroBased ->
            val ordinal = zeroBased + 1
            addKeyframe(index, ordinal, ordinal * intervalUs)
        }
    }

    private fun addKeyframe(index: GrowingRecordingIndex, ordinal: Int, timeUs: Long) {
        index.onVideoKeyframe(
            trackId = VIDEO_TRACK,
            timeUs = timeUs,
            samplePosition = packetPosition(ordinal),
            sourceReadPosition = packetPosition(ordinal + 1),
        )
    }

    private fun packetPosition(ordinal: Int): Long = ordinal * 100L * GROWING_TS_PACKET_BYTES
}

private class WindowedKeyframeExtractor(
    private val sniffResult: Boolean = true,
    private val delegateMapCount: Int = 1,
    private val mimeType: String? = MimeTypes.VIDEO_H264,
    private val deferFormat: Boolean = false,
    private val splitSamples: Boolean = false,
    private val trailingNonKeyframe: Boolean = false,
) : Extractor {
    private lateinit var track: TrackOutput
    private var readCount = 0
    private var keyframeCount = 0
    val seeks = mutableListOf<Pair<Long, Long>>()
    var releaseCalls = 0
        private set

    override fun sniff(input: ExtractorInput): Boolean = sniffResult

    override fun init(output: ExtractorOutput) {
        track = output.track(VIDEO_TRACK, C.TRACK_TYPE_VIDEO)
        if (!deferFormat) track.format(Format.Builder().setSampleMimeType(mimeType).build())
        output.endTracks()
        repeat(delegateMapCount) { output.seekMap(SeekMap.Unseekable(C.TIME_UNSET)) }
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        readCount += 1
        if (deferFormat && readCount == 1) track.format(Format.Builder().setSampleMimeType(mimeType).build())
        if (readCount % 2 == 1) {
            if (splitSamples) track.sampleData(ParsableByteArray(ByteArray(1)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN)
            input.readFully(ByteArray(9_400), 0, 9_400)
        } else {
            keyframeCount += 1
            if (!splitSamples) sample(timeUs = keyframeCount * 1_000_000L - 1L, flags = 0)
            val flags = if (trailingNonKeyframe && keyframeCount == 7) 0 else C.BUFFER_FLAG_KEY_FRAME
            sample(timeUs = keyframeCount * 1_000_000L, flags = flags)
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        seeks += position to timeUs
        readCount = 0
        keyframeCount = (timeUs / 1_000_000L).toInt()
    }

    override fun release() { releaseCalls += 1 }

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

private class CapturingSeekMapOutput(private val onSeekMap: (SeekMap) -> Unit = {}) : ExtractorOutput {
    val maps = CopyOnWriteArrayList<SeekMap>()
    var trackCompletions = 0
        private set

    override fun track(id: Int, type: Int): TrackOutput = EmptyTrackOutput

    override fun endTracks() { trackCompletions += 1 }

    override fun seekMap(seekMap: SeekMap) {
        maps += seekMap
        onSeekMap(seekMap)
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
