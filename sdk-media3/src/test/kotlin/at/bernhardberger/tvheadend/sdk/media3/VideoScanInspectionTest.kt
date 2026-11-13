@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mkv.GrowingMatroskaVarint
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.PsExtractor
import androidx.media3.extractor.ts.TsExtractor
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import java.io.File
import java.util.HexFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class VideoScanInspectionTest {
    @ParameterizedTest
    @ValueSource(strings = ["ts", "mkv"])
    fun `a growing container's extractor emits subtitle tracks as Media3 cues`(containerName: String) {
        val container = if (containerName == "ts") GrowingTsContainer else GrowingMatroskaContainer
        val original = if (containerName == "ts") {
            File("src/androidTest/assets/p7-f3/h264-synthetic.ts").readBytes()
        } else growingMatroskaFixture()
        val withSubtitles = if (containerName == "ts") withDvbSubtitlePmt(original) else withSubripTrack(original)
        val baseline = ScanCapturingOutput()
        extract(original, createGrowingRecordingExtractorsFactory(container).createExtractors().single(), baseline)
        val output = ScanCapturingOutput()
        extract(withSubtitles, createGrowingRecordingExtractorsFactory(container).createExtractors().single(), output)
        assertEquals(setOf(MimeTypes.APPLICATION_MEDIA3_CUES), output.formatsByType[C.TRACK_TYPE_TEXT]?.map { it.sampleMimeType }?.toSet())
        for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
            val expected = requireNotNull(baseline.formatsByType[type]).map { it.sampleMimeType }.toSet()
            assertEquals(expected, output.formatsByType[type]?.map { it.sampleMimeType }?.toSet(), "Audio and video formats must be unchanged")
        }
    }

    private fun withDvbSubtitlePmt(original: ByteArray): ByteArray = original.copyOf().apply {
        // The committed fixture's PMT fits in one payload-only TS packet. Keep all existing ES entries.
        val packet = (indices step 188).first { at ->
            this[at + 1].toInt() and 0x40 != 0 && this[at + 3].toInt() and 0x30 == 0x10 &&
                this[at + 4] == 0.toByte() && this[at + 5] == 2.toByte()
        }
        val start = packet + 5
        val size = ((this[start + 1].toInt() and 0x0f) shl 8) or (this[start + 2].toInt() and 0xff)
        // Private PES PID 0x1ffe; descriptor 0x59: English DVB subtitles, page ids 1/1.
        val entry = HexFormat.of().parseHex("06fffef00a5908656e671000010001")
        val crcStart = start + 3 + size - 4
        check(crcStart + entry.size + 4 <= packet + 188)
        entry.copyInto(this, crcStart)
        val newSize = size + entry.size
        this[start + 1] = (0xb0 or (newSize shr 8)).toByte()
        this[start + 2] = newSize.toByte()
        val crc = Util.crc32(this, start, crcStart + entry.size, -1)
        repeat(4) { this[crcStart + entry.size + it] = (crc ushr (24 - 8 * it)).toByte() }
    }

    private fun withSubripTrack(original: ByteArray): ByteArray {
        // Append only a TrackEntry to existing Tracks; no subtitle samples or new binary fixture.
        val tracks = original.indexOf(HexFormat.of().parseHex("1654ae6b"))
        check(tracks >= 0)
        val sizeAt = tracks + 4
        val width = GrowingMatroskaVarint.length(original[sizeAt].toInt() and 0xff)
        val oldSize = GrowingMatroskaVarint.value(original.copyOfRange(sizeAt, sizeAt + width), true).toInt()
        val end = sizeAt + width + oldSize
        val entry = HexFormat.of().parseHex("ae9ad7810373c58103838111888101868b535f544558542f55544638")
        val result = original.copyOfRange(0, end) + entry + original.copyOfRange(end, original.size)
        // Reuse the maintained varint decoder; only this bounded fixture's size needs encoding.
        val newSize = oldSize + entry.size
        check(newSize < (1 shl (7 * width)) - 1)
        repeat(width) { result[sizeAt + it] = (newSize shr (8 * (width - 1 - it))).toByte() }
        result[sizeAt] = (result[sizeAt].toInt() or (1 shl (8 - width))).toByte()
        return result
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `growing Matroska prefixes preserve all video samples and finalize the estimated map`(probeFirst: Boolean) = runTest {
        val bytes = growingMatroskaFixture()
        val expected = extract(bytes, GrowingMatroskaContainer.createExtractor()).sampleScans.size
        val lease = ProbeLease(bytes, bytes.size / 3)
        val output = ScanCapturingOutput()
        val callbacks = mutableListOf<SeekMap>()
        val extractor = GrowingRecordingExtractor(
            GrowingMatroskaContainer,
            lease = lease,
            onSeekMap = callbacks::add,
            probeDispatcher = StandardTestDispatcher(testScheduler),
        )
        var cursor = 0
        val reader = DataReader { destination, offset, length ->
            if (cursor == lease.available && lease.available < bytes.size) {
                lease.available = minOf(bytes.size, lease.available + bytes.size / 3)
            }
            if (cursor == bytes.size) C.RESULT_END_OF_INPUT else {
                val count = minOf(length, lease.available - cursor)
                bytes.copyInto(destination, offset, cursor, cursor + count)
                cursor += count
                count
            }
        }
        val input = DefaultExtractorInput(reader, 0L, C.LENGTH_UNSET.toLong())
        try {
            extractor.init(output)
            if (probeFirst) testScheduler.runCurrent()
            extractor.seek(0L, 0L)
            val holder = PositionHolder()
            while (extractor.read(input, holder) == Extractor.RESULT_CONTINUE) {
                // Read the next complete sample from the growing source.
            }
            testScheduler.runCurrent()
            assertTrue(output.maps.first().durationUs > 0, "The first map must contain the probed duration")
            assertEquals(output.maps, callbacks)
            assertTrue(output.video.formats.isNotEmpty(), "The initial seek must preserve video track initialization")
            assertEquals(expected, output.video.sampleScans.size)
            assertTrue(expected > 250)
            assertTrue(output.maps.last().isEstimated)
            lease.completed = true
            testScheduler.advanceTimeBy(5_000L)
            testScheduler.runCurrent()
            assertTrue(output.maps.last().isSeekable)
            assertEquals(false, output.maps.last().isEstimated)
        } finally {
            extractor.release()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed initial growing probe releases the estimated placeholder`(slowClose: Boolean) = runTest {
        val bytes = growingMatroskaFixture()
        val output = ScanCapturingOutput()
        val callbacks = mutableListOf<SeekMap>()
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val lease = ProbeLease(bytes, if (slowClose) bytes.size / 2 else 0).apply {
            suspendReads = slowClose
            afterClose = {
                closeStarted.complete(Unit)
                if (slowClose) allowClose.await()
            }
        }
        val extractor = GrowingRecordingExtractor(
            GrowingMatroskaContainer, lease = lease,
            onSeekMap = callbacks::add, probeDispatcher = StandardTestDispatcher(testScheduler),
        )
        var cursor = 0
        val input = DefaultExtractorInput(DataReader { destination, offset, length ->
            if (cursor == bytes.size) C.RESULT_END_OF_INPUT else {
                val count = minOf(length, bytes.size - cursor)
                bytes.copyInto(destination, offset, cursor, cursor + count)
                cursor += count
                count
            }
        }, 0, C.LENGTH_UNSET.toLong())
        try {
            extractor.init(output)
            while (extractor.read(input, PositionHolder()) == Extractor.RESULT_CONTINUE) Unit
            assertTrue(output.maps.isEmpty(), "Publication must wait for the first probe result")
            testScheduler.runCurrent()
            if (slowClose) {
                testScheduler.advanceTimeBy(4_999L)
                testScheduler.runCurrent()
                assertTrue(output.maps.isEmpty())
                testScheduler.advanceTimeBy(1L)
                testScheduler.runCurrent()
                assertTrue(closeStarted.isCompleted)
            }
            assertEquals(listOf(EstimatedUnseekableGrowingSeekMap), output.maps)
            assertEquals(output.maps, callbacks)
            lease.suspendReads = false
            allowClose.complete(Unit)
            testScheduler.runCurrent()
            lease.available = bytes.size / 2
            testScheduler.advanceTimeBy(5_000L)
            testScheduler.runCurrent()
            assertTrue(output.maps.last().durationUs > 0, "Later probe samples must still update the placeholder")
            assertTrue(output.maps.last().isEstimated)
            assertEquals(output.maps, callbacks)
        } finally {
            allowClose.complete(Unit)
            extractor.release()
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    @Test
    fun `TS recordings switch each codec at the first progressive picture`() {
        listOf("h264-switch.ts", "h265-switch.ts", "mpeg2-switch.ts").forEach { asset ->
            assertSwitch(extract(asset) { it is TsExtractor }, interlacedSamples = 3, totalSamples = 6, label = asset)
        }
    }

    @Test
    fun `growing TS recording switches H264 from interlaced to progressive`() {
        assertSwitch(extract("h264-switch.ts", GrowingRecordingExtractor(GrowingTsContainer)), interlacedSamples = 3, totalSamples = 6)
    }

    @Test
    fun `MKV recording reports the scan type its codec setup declares`() {
        val interlaced = extract("h264-interlaced.mkv") { it is MatroskaExtractor }
        val progressive = extract("h264-progressive.mkv") { it is MatroskaExtractor }

        assertEquals(List(3) { VideoScanType.INTERLACED }, interlaced.sampleScans)
        assertEquals(List(3) { VideoScanType.PROGRESSIVE }, progressive.sampleScans)
    }

    @Test
    fun `MKV recordings inspect in-band H264 H265 and MPEG-2 switches`() {
        listOf("h264-switch.mkv", "h265-switch.mkv", "mpeg2-switch.mkv").forEach { name ->
            assertSwitch(extract(name) { it is MatroskaExtractor }, 3, 6, initialUnknown = name.startsWith("mpeg2"), label = name)
        }
    }

    @Test
    fun `MP4 recordings report codec setup and inspect avc3 and hev1 switches`() {
        assertEquals(List(3) { VideoScanType.INTERLACED }, extract("h264-interlaced.mp4") { it is Mp4Extractor }.sampleScans)
        assertEquals(List(3) { VideoScanType.PROGRESSIVE }, extract("h264-progressive.mp4") { it is Mp4Extractor }.sampleScans)
        listOf("h264-switch.mp4", "h265-switch.mp4").forEach { name ->
            assertSwitch(extract(name) { it is Mp4Extractor }, 3, 6, label = name)
        }
    }

    @Test
    fun `fragmented MP4 and MPEG-PS recordings inspect in-band switches`() {
        assertSwitch(extract("h264-switch-fragmented.mp4") { it is FragmentedMp4Extractor }, 3, 6)
        assertSwitch(extract("mpeg2-switch.mpg") { it is PsExtractor }, 3, 6)
    }

    @Test
    fun `production default factory wraps TS MKV and MP4 extractors`() {
        val extractors = VideoScanExtractorsFactory(DefaultExtractorsFactory()).createExtractors()
        assertTrue(extractors.all { it is VideoScanExtractor })
        assertSwitch(extract("h264-switch.ts", extractors.single { it.underlyingImplementation is TsExtractor }), 3, 6)
        assertSwitch(extract("h264-switch.mkv", extractors.single { it.underlyingImplementation is MatroskaExtractor }), 3, 6)
        assertSwitch(extract("h264-switch.mp4", extractors.single { it.underlyingImplementation is Mp4Extractor }), 3, 6)
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    fun `extractor factory forwards non-deprecated configuration and URI creation`() {
        val calls = mutableListOf<String>()
        val delegate = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = emptyArray<Extractor>().also { calls += "default" }
            override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
                assertSame(Uri.EMPTY, uri)
                assertTrue(responseHeaders.isEmpty())
                calls += "uri"
                return emptyArray()
            }
            override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
                assertSame(SubtitleParser.Factory.UNSUPPORTED, subtitleParserFactory)
                calls += "subtitle"
            }
            override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory =
                apply { calls += "dependencies:$codecsToParseWithinGopSampleDependencies" }
            override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply { calls += "metadata:$parseHagcMetadata" }
        }
        val factory = VideoScanExtractorsFactory(delegate)
        assertSame(factory, factory.setSubtitleParserFactory(SubtitleParser.Factory.UNSUPPORTED))
        assertSame(factory, factory.experimentalSetCodecsToParseWithinGopSampleDependencies(7))
        assertSame(factory, factory.setParseHagcMetadata(true))
        factory.createExtractors()
        factory.createExtractors(Uri.EMPTY, emptyMap())
        assertEquals(listOf("subtitle", "dependencies:7", "metadata:true", "default", "uri"), calls)
    }

    @Test
    fun `extractor decoration preserves rejection lifecycle and output signals`() {
        var seek: Pair<Long, Long>? = null
        var released = false
        var inspectedVideo: VideoScanTrackOutput? = null
        val delegate = object : Extractor {
            override fun sniff(input: ExtractorInput): Boolean = false
            override fun init(output: ExtractorOutput) {
                inspectedVideo = (output.track(0, C.TRACK_TYPE_VIDEO) as VideoScanTrackOutput).apply {
                    format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                        .setInitializationData(listOf(scanSps(false, 0, false), scanPps(false, 0, 0))).build())
                }
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT
            override fun seek(position: Long, timeUs: Long) { seek = position to timeUs }
            override fun release() { released = true }
        }
        val extractor = VideoScanExtractor(delegate)
        assertEquals(false, extractor.sniff(input(byteArrayOf(), 0)))
        val captured = ScanCapturingOutput()
        extractor.init(captured)
        val video = checkNotNull(inspectedVideo)
        writeSample(video, scanSps(false, 0, true) + scanSlice(false, 0))
        extractor.seek(17, 29)
        assertEquals(17L to 29L, seek)
        writeSample(video, byteArrayOf(0x55))
        assertEquals(listOf(VideoScanType.PROGRESSIVE, VideoScanType.INTERLACED), captured.video.sampleScans)
        extractor.release()
        assertTrue(released)

        var ended = false
        var publishedMap: SeekMap? = null
        val map = SeekMap.Unseekable(31)
        val output = VideoScanExtractorOutput(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() { ended = true }
            override fun seekMap(seekMap: SeekMap) { publishedMap = seekMap }
        })
        output.endTracks()
        assertTrue(ended)
        output.seekMap(map)
        assertSame(map, publishedMap)
        var duration: Long? = null
        val track = VideoScanTrackOutput(object : TrackOutput by DiscardingTrackOutput() {
            override fun durationUs(durationUs: Long) { duration = durationUs }
        })
        track.durationUs(43)
        assertEquals(43L, duration)
    }

    @Test
    fun `backward TS seek restores interlaced samples after reading the progressive part`() {
        val bytes = resource("h264-switch.ts")
        val extractor = VideoScanExtractor(TsExtractor(SubtitleParser.Factory.UNSUPPORTED))
        val output = ScanCapturingOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes)
            assertSwitch(output.video, 3, 6)
            output.video.sampleScans.clear()
            extractor.seek(0L, 0L)
            drain(extractor, bytes)
            assertEquals(List(3) { VideoScanType.INTERLACED } + List(3) { VideoScanType.PROGRESSIVE }, output.video.sampleScans)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `corrupt in-band H264 and H265 SPS cannot throw or replace valid declarations in TS`() {
        listOf("h264" to PROGRESSIVE_H264_SPS_PREFIX, "h265" to H265_SPS_PREFIX).forEach { (codec, prefix) ->
            val bytes = resource("$codec-switch.ts")
            val start = bytes.indexOf(prefix, bytes.indexOf(prefix) + 1) + START_CODE.size
            assertTrue(start >= START_CODE.size)
            val end = bytes.indexOf(START_CODE, start)
            assertTrue(end > start)
            val header = if (codec == "h264") byteArrayOf(0x67) else byteArrayOf(0x42, 0x01)
            val hugeId = if (codec == "h264") {
                header + byteArrayOf(66, 0, 30) + ByteArray(8)
            } else {
                scanH265Sps(0, progressive = true).dropLast(1).toByteArray() + ByteArray(8)
            }
            listOf(header, header + ByteArray(6) { 0xFF.toByte() }, hugeId).forEach { corrupt ->
                val damaged = bytes.copyOf()
                damaged.fill(0, start, end)
                corrupt.copyInto(damaged, start, 0, minOf(corrupt.size, end - start))
                val track = extract(damaged, VideoScanExtractor(TsExtractor(SubtitleParser.Factory.UNSUPPORTED)))
                assertEquals(List(6) { VideoScanType.INTERLACED }, track.sampleScans)
            }
        }
    }

    @Test
    fun `truncated H264 POC cycle count suffix cannot become a large loop count`() {
        val track = ScanCapturingTrack()
        val output = configuredTrack(track, hevc = false)
        // Baseline profile, SPS id 0, frame_num 0, POC type 1, zero offsets; then a count with
        // 30 leading zeros and its stop bit, but only one of the required 30 suffix bits.
        val prefix = "010000100000000000011110" + ue(0) + ue(0) + ue(1) + "0" + ue(0) + ue(0)
        val truncated = rbsp(byteArrayOf(0x67), prefix + "0".repeat(30) + "1")
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2)) {
            writeSample(output, annexB(truncated) + scanSlice(false, 0))
            assertEquals(listOf(VideoScanType.INTERLACED), track.sampleScans)
        }
    }

    @Test
    fun `inactive parameter sets do not change H264 or H265 pictures until their PPS is activated`() {
        listOf(false, true).forEach { hevc ->
            listOf(1, 2).forEach { id ->
                val track = ScanCapturingTrack()
                val output = configuredTrack(track, hevc)
                writeSample(output, scanSps(hevc, id, true) + scanSlice(hevc, 0))
                writeSample(output, scanPps(hevc, id, id) + scanSlice(hevc, 0))
                val activatedSlice = scanSlice(hevc, id).apply {
                    if (!hevc && id == 2) this[START_CODE.size] = 0x61
                }
                writeSample(output, activatedSlice)
                assertEquals(listOf(VideoScanType.INTERLACED, VideoScanType.INTERLACED, VideoScanType.PROGRESSIVE), track.sampleScans)
            }
        }
    }

    @Test
    fun `unresolved parameter-set ids are unknown and out of range ids are ignored`() {
        listOf(false, true).forEach { hevc ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc)
            writeSample(output, scanSps(hevc, if (hevc) 16 else 32, true) + scanSlice(hevc, 0))
            writeSample(output, scanPps(hevc, if (hevc) 64 else 256, 0) + scanSlice(hevc, 0))
            writeSample(output, scanPps(hevc, 1, 1) + scanSlice(hevc, 1))
            assertEquals(listOf(VideoScanType.INTERLACED, VideoScanType.INTERLACED, VideoScanType.UNKNOWN), track.sampleScans)
        }
    }

    @Test
    fun `H265 SPS sub-layer PTL flags locate the correct sequence id`() {
        listOf(2, 6).forEach { subLayers ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc = true)
            writeSample(output, annexB(scanH265Sps(1, true, subLayers)) + scanPps(true, 1, 1) + scanSlice(true, 1))
            assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
        }
    }

    @Test
    fun `H264 scaling-list termination and POC type one retain the declared scan`() {
        val tail = ue(1) + "0" + ue(3) + ue(3) + "1100"
        val scaling = "011001000000000000011110" + ue(0) + ue(1) + ue(0) + ue(0) + "01" +
            "1" + ue(16) + "0000000" + ue(0) + ue(0) + ue(0) + tail
        val poc = "010000100000000000011110" + ue(0) + ue(0) + ue(1) + "0" +
            ue(1) + ue(2) + ue(2) + ue(1) + ue(2) + tail
        val zeroCyclePoc = "010000100000000000011110" + ue(0) + ue(0) + ue(1) + "0" +
            ue(0) + ue(0) + ue(0) + tail
        listOf(scaling, poc, zeroCyclePoc).forEach { bits ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc = false)
            writeSample(output, annexB(rbsp(byteArrayOf(0x67), bits)) + scanSlice(false, 0))
            assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
        }
    }

    @Test
    fun `malformed active sequence declarations cannot replace the valid scan`() {
        val invalidH264 = annexB(rbsp(byteArrayOf(0x67), "010000100000000000011110" + ue(0) + ue(13) +
            ue(0) + ue(0) + ue(1) + "0" + ue(3) + ue(3) + "1100"))
        val hevc = scanH265Sps(0, true)
        listOf(false to invalidH264,
            true to annexB(hevc.copyOf().also { it[0] = 0xC2.toByte() }),
            true to annexB(hevc.copyOf().also { it[1] = 0 }),
            true to annexB(scanH265Sps(0, true, subLayers = 7))).forEach { (h265, invalid) ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, h265)
            writeSample(output, invalid + scanSlice(h265, 0))
            assertEquals(listOf(VideoScanType.INTERLACED), track.sampleScans)
        }
    }

    @Test
    fun `minimal MPEG-2 sequence extension declares the sample scan`() {
        val track = ScanCapturingTrack()
        val output = VideoScanTrackOutput(track)
        output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_MPEG2).build())
        writeSample(output, annexB(byteArrayOf(0xB5.toByte(), 0x10, 0x08)) + START_CODE)
        assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
    }

    @Test
    fun `H265 VCL type endpoints activate the referenced picture`() {
        listOf(0, 15, 16, 23, 24, 31).forEach { type ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc = true)
            val slice = annexB(rbsp(byteArrayOf((type shl 1).toByte(), 1),
                (if (type in 16..23) "10" else "1") + ue(1)))
            writeSample(output, scanSps(true, 1, true) + scanPps(true, 1, 1) + slice)
            assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
        }
    }

    @Test
    fun `oversize parameter sets are ignored without affecting samples or retained declarations`() {
        listOf(false, true).forEach { hevc ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc)
            val oversized = scanSps(hevc, 0, true) + ByteArray(8193) { 0x55 }
            writeSample(output, oversized + scanSlice(hevc, 0))
            assertEquals(listOf(VideoScanType.INTERLACED), track.sampleScans)
        }
    }

    @Test
    fun `DataReader inspection handles partial reads eof and non-main data without copying`() {
        val track = ScanCapturingTrack()
        val output = configuredTrack(track, hevc = false)
        val progressive = scanSps(false, 1, true) + scanPps(false, 1, 1) + scanSlice(false, 1)
        output.sampleData(ParsableByteArray(progressive), progressive.size, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
        writeSample(output, scanSlice(false, 0))
        val reader = BytesReader(progressive, 0, maximumRead = 1)
        var read: Int
        do {
            read = output.sampleData(reader, progressive.size, true, TrackOutput.SAMPLE_DATA_PART_MAIN)
        } while (read != C.RESULT_END_OF_INPUT)
        output.sampleMetadata(1L, C.BUFFER_FLAG_KEY_FRAME, progressive.size, 0, null)
        assertEquals(listOf(VideoScanType.INTERLACED, VideoScanType.PROGRESSIVE), track.sampleScans)
    }

    @Test
    fun `one-byte H264 and H265 parameter and slice fragments resolve their activation`() {
        listOf(false, true).forEach { hevc ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc)
            val bytes = scanSps(hevc, 1, true) + scanPps(hevc, 1, 1) + scanSlice(hevc, 1)
            bytes.forEach { byte -> output.sampleData(ParsableByteArray(byteArrayOf(byte)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN) }
            output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
            assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
        }
    }

    @Test
    fun `seek clears partial and pending evidence while live discontinuity retains parameter maps`() {
        val track = ScanCapturingTrack()
        val output = configuredTrack(track, hevc = false)
        val progressive = scanSps(false, 0, true)
        writeSample(output, progressive + scanSlice(false, 0))
        output.resetForDiscontinuity()
        writeSample(output, scanSlice(false, 0))
        output.sampleData(ParsableByteArray(progressive), progressive.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.resetForSeek()
        writeSample(output, scanSlice(false, 0))
        assertEquals(listOf(VideoScanType.PROGRESSIVE, VideoScanType.PROGRESSIVE, VideoScanType.INTERLACED), track.sampleScans)
    }

    @Test
    fun `reset discards pending scan evidence before an inconclusive sample`() {
        listOf(false, true).forEach { seek ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc = false)
            val pending = scanSps(false, 0, true) + scanSlice(false, 0)
            output.sampleData(ParsableByteArray(pending), pending.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            if (seek) output.resetForSeek() else output.resetForDiscontinuity()
            writeSample(output, byteArrayOf(0x55))
            assertEquals(listOf(VideoScanType.INTERLACED), track.sampleScans)
        }
    }

    @Test
    fun `live discontinuity discards uncommitted H264 and H265 replacement parameter sets`() {
        listOf(false, true).forEach { hevc ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc)
            writeSample(output, scanSps(hevc, 0, false) + scanPps(hevc, 0, 0) + scanSlice(hevc, 0))
            val unfinished = scanSps(hevc, 0, true) + START_CODE
            output.sampleData(ParsableByteArray(unfinished), unfinished.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            output.resetForDiscontinuity()
            writeSample(output, scanSlice(hevc, 0))
            assertEquals(List(2) { VideoScanType.INTERLACED }, track.sampleScans)
        }
    }

    @Test
    fun `parameter updates commit in byte order rather than when lookahead arrives`() {
        listOf(false, true).forEach { hevc ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc)
            val first = scanSps(hevc, 0, false) + scanPps(hevc, 0, 0) + scanSlice(hevc, 0)
            val unfinished = scanSps(hevc, 0, true) + START_CODE
            val bytes = first + unfinished
            output.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, first.size, unfinished.size, null)
            output.resetForDiscontinuity()
            writeSample(output, scanSlice(hevc, 0))
            assertEquals(List(2) { VideoScanType.INTERLACED }, track.sampleScans)
        }
    }

    @Test
    fun `staged parameter update overflow cannot discard committed maps before discontinuity`() {
        listOf(false, true).forEach { hevc ->
            val track = ScanCapturingTrack()
            val output = configuredTrack(track, hevc)
            writeSample(output, scanSlice(hevc, 0))
            repeat(1100) {
                val bytes = scanSps(hevc, 0, true) + START_CODE
                output.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            }
            output.resetForDiscontinuity()
            writeSample(output, scanSlice(hevc, 0))
            assertEquals(List(2) { VideoScanType.INTERLACED }, track.sampleScans)
        }
    }

    @Test
    fun `first chunk before format preserves H264 PPS activation without an unknown format`() {
        val track = ScanCapturingTrack()
        val output = VideoScanTrackOutput(track)
        val chunk = scanSps(false, 0, false) + scanPps(false, 0, 0) +
            scanSps(false, 1, true) + scanPps(false, 1, 1) + scanSlice(false, 1)
        output.sampleData(ParsableByteArray(chunk), chunk.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.format(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                .setInitializationData(listOf(scanSps(false, 0, false), scanPps(false, 0, 0))).build(),
        )
        output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, chunk.size, 0, null)
        assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
        assertEquals(listOf(VideoScanType.INTERLACED, VideoScanType.PROGRESSIVE), track.formats.map(Format::videoScanType))
    }

    @Test
    fun `adjacent pre-format chunks replay as a single complete declaration`() {
        val track = ScanCapturingTrack()
        val output = VideoScanTrackOutput(track)
        val chunk = scanSps(false, 1, true) + scanPps(false, 1, 1) + scanSlice(false, 1)
        chunk.forEach { output.sampleData(ParsableByteArray(byteArrayOf(it)), 1, TrackOutput.SAMPLE_DATA_PART_MAIN) }
        output.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
            .setInitializationData(listOf(scanSps(false, 0, false), scanPps(false, 0, 0))).build())
        output.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, chunk.size, 0, null)
        assertEquals(listOf(VideoScanType.PROGRESSIVE), track.sampleScans)
    }

    @Test
    fun `pre-format replay retains absolute positions across non-main bytes and stops at its cap`() {
        listOf(7, 64).forEach { gap ->
            val track = ScanCapturingTrack()
            val output = VideoScanTrackOutput(track)
            val first = scanSps(false, 1, true)
            val second = scanPps(false, 1, 1) + scanSlice(false, 1)
            output.sampleData(ParsableByteArray(first), first.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            output.sampleData(ParsableByteArray(ByteArray(gap)), gap, TrackOutput.SAMPLE_DATA_PART_ENCRYPTION)
            output.sampleData(ParsableByteArray(second), second.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            val padding = ByteArray(64 * 1024) { 0x55 }
            output.sampleData(ParsableByteArray(padding), padding.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            val ignored = scanSps(false, 1, false) + scanSlice(false, 1)
            output.sampleData(ParsableByteArray(ignored), ignored.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            output.format(
                Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setInitializationData(listOf(scanSps(false, 0, false), scanPps(false, 0, 0))).build(),
            )
            val offset = second.size + padding.size + ignored.size
            output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, first.size + gap, offset, null)
            output.sampleMetadata(1L, C.BUFFER_FLAG_KEY_FRAME, second.size + padding.size + ignored.size, 0, null)
            assertEquals(listOf(VideoScanType.INTERLACED, VideoScanType.PROGRESSIVE), track.sampleScans)
        }
    }

    @Test
    fun `live streams switch each codec at the first progressive picture`() {
        listOf(
            "h264-switch.h264" to SubscriptionStreamType.H264,
            "h265-switch.h265" to SubscriptionStreamType.H265,
            "mpeg2-switch.m2v" to SubscriptionStreamType.MPEG2_VIDEO,
        ).forEach { (asset, codec) ->
            assertSwitch(live(asset, codec), interlacedSamples = 3, totalSamples = 6, label = asset)
        }
    }

    @Test
    fun `live discontinuity discards a parameter set that started before it`() {
        val bytes = resource("h264-switch.h264")
        val progressiveSps = bytes.indexOf(PROGRESSIVE_H264_SPS_PREFIX, from = bytes.indexOf(PROGRESSIVE_H264_SPS_PREFIX) + 1)
        assertTrue(progressiveSps > 0)
        val split = progressiveSps + PROGRESSIVE_H264_SPS_PREFIX.size
        val output = ScanCapturingOutput()
        val adapter = SubscriptionElementaryStreamAdapter(supportedReader(SubscriptionStreamType.H264), output, 0)

        adapter.accept(packet(bytes.copyOfRange(0, split), presentationTimeUs = 0L, keyFrame = true))
        adapter.accept(SubscriptionEvent.Dropped(1L))
        adapter.accept(packet(bytes.copyOfRange(split, bytes.size), presentationTimeUs = 1_000L, keyFrame = false))
        adapter.end()

        // The same bytes without the discontinuity form a complete progressive SPS.
        assertTrue(output.video.sampleScans.isNotEmpty())
        assertTrue(output.video.sampleScans.all { it == VideoScanType.INTERLACED })
    }

    @Test
    fun `ambiguous or absent H265 source flags preserve existing metadata and report unknown scan`() {
        listOf(
            Triple("ambiguous flags", 0xD0, Metadata(5L, OtherMetadata("kept"))),
            Triple("absent flags", 0x10, null),
        ).forEach { (label, flags, metadata) ->
            val track = ScanCapturingTrack()
            VideoScanTrackOutput(track).format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H265)
                    .setInitializationData(listOf(h265Sps(progressiveConstraintByte = flags)))
                    .setMetadata(metadata)
                    .build(),
            )

            val format = track.formats.single()
            assertEquals(VideoScanType.UNKNOWN, format.videoScanType(), label)
            assertEquals(metadata, format.metadata, label)
        }
    }

    @Test
    fun `inspected format keeps other metadata and replaces a stale scan entry`() {
        val track = ScanCapturingTrack()
        val other = OtherMetadata("kept")
        VideoScanTrackOutput(track).format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setInitializationData(listOf(h265Sps(progressiveConstraintByte = 0x90)))
                .setMetadata(Metadata(VideoScanMetadata(VideoScanType.INTERLACED), other))
                .build(),
        )

        val metadata = checkNotNull(track.formats.single().metadata)
        assertEquals(VideoScanType.PROGRESSIVE, track.formats.single().videoScanType())
        assertEquals(listOf<Metadata.Entry>(other, VideoScanMetadata(VideoScanType.PROGRESSIVE)), List(metadata.length(), metadata::get))
    }

    @Test
    fun `formats without an SDK scan entry are unknown`() {
        assertEquals(VideoScanType.UNKNOWN, Format.Builder().build().videoScanType())
        assertEquals(
            VideoScanType.UNKNOWN,
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_VP9).build().videoScanType(),
        )
    }

    @Test
    fun `non-video tracks reach the delegate output unchanged`() {
        val audio = DiscardingTrackOutput()
        val output = VideoScanExtractorOutput(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = audio
                override fun endTracks(): Unit = Unit
                override fun seekMap(seekMap: SeekMap): Unit = Unit
            },
        )

        assertSame(audio, output.track(1, C.TRACK_TYPE_AUDIO))
        assertSame(audio, output.track(2, C.TRACK_TYPE_TEXT))
    }

    private fun assertSwitch(
        track: ScanCapturingTrack,
        interlacedSamples: Int,
        totalSamples: Int,
        initialUnknown: Boolean = false,
        label: String? = null,
    ) {
        assertEquals(
            List(interlacedSamples) { VideoScanType.INTERLACED } +
                List(totalSamples - interlacedSamples) { VideoScanType.PROGRESSIVE },
            track.sampleScans,
            label,
        )
        assertEquals(
            (if (initialUnknown) listOf(VideoScanType.UNKNOWN) else emptyList()) +
                listOf(VideoScanType.INTERLACED, VideoScanType.PROGRESSIVE),
            track.formats.map(Format::videoScanType),
            label,
        )
    }

    /** Selects a real container extractor without requiring the Android URI test stub. */
    private fun extract(name: String, select: (Extractor) -> Boolean): ScanCapturingTrack {
        val recordingExtractors = ExtractorsFactory {
            arrayOf(
                MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
                TsExtractor(SubtitleParser.Factory.UNSUPPORTED),
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                PsExtractor(),
            )
        }
        val extractor = VideoScanExtractorsFactory(recordingExtractors).createExtractors()
            .single { select(it.underlyingImplementation) }
        return extract(name, extractor)
    }

    private fun extract(name: String, extractor: Extractor): ScanCapturingTrack {
        return extract(resource(name), extractor)
    }

    private fun extract(bytes: ByteArray, extractor: Extractor, output: ScanCapturingOutput = ScanCapturingOutput()): ScanCapturingTrack {
        assertTrue(extractor.sniff(input(bytes, 0L)))
        extractor.init(output)
        try {
            drain(extractor, bytes)
        } finally {
            extractor.release()
        }
        return output.video
    }

    private fun drain(extractor: Extractor, bytes: ByteArray) {
        var input = input(bytes, 0L)
        val seekPosition = PositionHolder()
        while (true) {
            when (extractor.read(input, seekPosition)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = input(bytes, seekPosition.position)
                else -> return
            }
        }
    }

    private fun configuredTrack(track: ScanCapturingTrack, hevc: Boolean): VideoScanTrackOutput = VideoScanTrackOutput(track).apply {
        format(
            Format.Builder().setSampleMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setInitializationData(listOf(scanSps(hevc, 0, false), scanPps(hevc, 0, 0))).build(),
        )
    }

    private fun writeSample(output: VideoScanTrackOutput, bytes: ByteArray) {
        output.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }

    /** Delivers the elementary stream in TS-packet-sized pieces, so parameter sets span packets. */
    private fun live(name: String, type: SubscriptionStreamType): ScanCapturingTrack {
        val bytes = resource(name)
        val output = ScanCapturingOutput()
        val adapter = SubscriptionElementaryStreamAdapter(supportedReader(type), output, 0)
        (bytes.indices step LIVE_PACKET_BYTES).forEachIndexed { index, start ->
            val chunk = bytes.copyOfRange(start, minOf(start + LIVE_PACKET_BYTES, bytes.size))
            adapter.accept(packet(chunk, presentationTimeUs = index * 1_000L, keyFrame = index == 0))
        }
        adapter.end()
        return output.video
    }

    private fun supportedReader(type: SubscriptionStreamType) =
        (createElementaryStreamReader(stream(type)) as ReaderResult.Supported).reader

    private fun packet(bytes: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) = SubscriptionEvent.Packet(
        frameType = if (keyFrame) MuxFrameType.I else MuxFrameType.UNKNOWN,
        streamIndex = StreamIndex(0L),
        decodingTimeUs = presentationTimeUs,
        presentationTimeUs = presentationTimeUs,
        durationUs = 40_000L,
        payload = BytesBinary(bytes),
    )

    /** Returns the progressive H.265 SPS of the fixture with its first constraint byte replaced. */
    private fun h265Sps(progressiveConstraintByte: Int): ByteArray {
        val bytes = resource("h265-switch.h265")
        val start = bytes.indexOf(H265_SPS_PREFIX, from = bytes.indexOf(H265_SPS_PREFIX) + 1)
        assertTrue(start > 0)
        val end = bytes.indexOf(START_CODE, from = start + START_CODE.size)
        val sps = bytes.copyOfRange(start, end)
        // Start code, NAL header, sub-layer byte, profile byte and four escaped compatibility bytes.
        val constraintIndex = START_CODE.size + 2 + 1 + 1 + 5
        assertEquals(0x90, sps[constraintIndex].toInt() and 0xFF)
        sps[constraintIndex] = progressiveConstraintByte.toByte()
        return sps
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/video-scan/$name")).use { it.readBytes() }

    private fun input(bytes: ByteArray, position: Long) =
        DefaultExtractorInput(BytesReader(bytes, position.toInt()), position, C.LENGTH_UNSET.toLong())

    private fun stream(type: SubscriptionStreamType): SubscriptionStream = SubscriptionStream(
        index = StreamIndex(0L),
        type = type,
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
        rate = 3L,
        rdsUecp = null,
    )
}

private const val LIVE_PACKET_BYTES = 184
private val START_CODE = byteArrayOf(0, 0, 1)
private val PROGRESSIVE_H264_SPS_PREFIX = byteArrayOf(0, 0, 1, 0x67)
private val H265_SPS_PREFIX = byteArrayOf(0, 0, 1, 0x42, 0x01)

// Synthetic prefixes, not decodable pictures: exercise parameter-set activation independently of
// Media3's decoder setup parser. No maintained Media3 API writes Exp-Golomb/RBSP test prefixes.
private fun scanSps(hevc: Boolean, id: Int, progressive: Boolean): ByteArray = annexB(
    if (hevc) scanH265Sps(id, progressive) else rbsp(
        byteArrayOf(0x67),
        "010000100000000000011110" + ue(id) + ue(0) + ue(0) + ue(0) + ue(1) + "0" + ue(3) + ue(3) +
            (if (progressive) "1" else "00") + "100",
    ),
)

private fun scanH265Sps(id: Int, progressive: Boolean, subLayers: Int = 0): ByteArray = rbsp(
    byteArrayOf(0x42, 0x01),
    "0000" + subLayers.toString(2).padStart(3, '0') + "1" + "00000001" + "0".repeat(32) +
        (if (progressive) "10" else "01") + "0".repeat(46) + "01111000" +
        (if (subLayers == 0) "" else "11".repeat(subLayers) + "0".repeat((8 - subLayers) * 2) + "0".repeat(96 * subLayers)) +
        ue(id),
)

private fun scanPps(hevc: Boolean, id: Int, sequenceId: Int): ByteArray =
    annexB(rbsp(if (hevc) byteArrayOf(0x44, 0x01) else byteArrayOf(0x68), ue(id) + ue(sequenceId)))

private fun scanSlice(hevc: Boolean, pictureId: Int): ByteArray = annexB(
    rbsp(if (hevc) byteArrayOf(0x26, 0x01) else byteArrayOf(0x65), (if (hevc) "10" else ue(0) + ue(2)) + ue(pictureId)),
)

private fun annexB(unit: ByteArray): ByteArray = START_CODE + unit

private fun ue(value: Int): String = (value + 1).toString(2).let { "0".repeat(it.length - 1) + it }

private fun rbsp(header: ByteArray, bits: String): ByteArray {
    val padded = (bits + "1").let { it.padEnd((it.length + 7) / 8 * 8, '0') }
    val escaped = mutableListOf<Byte>()
    var zeros = 0
    padded.chunked(8).forEach { chunk ->
        val byte = chunk.toInt(2)
        if (zeros == 2 && byte <= 3) {
            escaped += 3.toByte()
            zeros = 0
        }
        escaped += byte.toByte()
        zeros = if (byte == 0) zeros + 1 else 0
    }
    return header + escaped.toByteArray()
}

private fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0): Int =
    (from..size - pattern.size).firstOrNull { start ->
        pattern.indices.all { this[start + it] == pattern[it] }
    } ?: -1

private data class OtherMetadata(val value: String) : Metadata.Entry

private class ScanCapturingOutput : ExtractorOutput {
    val video = ScanCapturingTrack()
    val maps = mutableListOf<SeekMap>()
    val formatsByType = mutableMapOf<Int, MutableList<Format>>()

    override fun track(id: Int, type: Int): TrackOutput {
        val target = if (type == C.TRACK_TYPE_VIDEO) video else DiscardingTrackOutput()
        return object : TrackOutput by target {
            override fun format(format: Format) {
                formatsByType.getOrPut(type) { mutableListOf() } += format
                target.format(format)
            }
        }
    }

    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap) { maps += seekMap }
}

private class ScanCapturingTrack : TrackOutput {
    val formats = mutableListOf<Format>()
    val sampleScans = mutableListOf<VideoScanType>()

    override fun format(format: Format) {
        formats += format
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val count = input.read(ByteArray(length), 0, length)
        check(count != C.RESULT_END_OF_INPUT || allowEndOfInput)
        return count
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.skipBytes(length)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        sampleScans += formats.last().videoScanType()
    }
}

private class BytesReader(private val bytes: ByteArray, private var position: Int, private val maximumRead: Int = Int.MAX_VALUE) : DataReader {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= bytes.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, bytes.size - position, maximumRead)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}

private class BytesBinary(private val bytes: ByteArray) : SubscriptionBinary {
    override val size: Int = bytes.size

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}
