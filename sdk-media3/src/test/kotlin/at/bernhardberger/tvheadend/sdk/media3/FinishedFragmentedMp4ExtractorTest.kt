@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Log
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FinishedFragmentedMp4ExtractorTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `finished fragments supply duration instead of the initial moov`(corruptDuration: Boolean) {
        val bytes = finishedMp4Fixture(corruptDuration)
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes)
            assertTrue(output.map.isSeekable)
            assertTrue(output.map.durationUs in 5_900_000L..6_200_000L, "Duration must describe the six-second samples")
            assertTrue(output.videoSamples.size >= 150)
        } finally {
            extractor.release()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `large trailing fragment index does not hide duration or keyframe seeks`(expandLastMdat: Boolean) {
        val original = finishedMp4Fixture()
        val source = ByteBuffer.wrap(original)
        val oldSize = source.getInt(original.size - 4)
        val oldStart = original.size - oldSize
        val mediaPadding = if (expandLastMdat) 3 * 1024 * 1024 else 0
        val start = oldStart + mediaPadding
        val padding = MP4_PROBE_BYTES + 8
        val bytes = ByteArray(original.size + padding + mediaPadding)
        original.copyInto(bytes, 0, 0, oldStart)
        original.copyInto(bytes, start, oldStart, original.size - 16)
        val buffer = ByteBuffer.wrap(bytes)
        if (expandLastMdat) {
            var position = 0
            var lastMdat = 0
            while (position < oldStart) {
                if (source.getInt(position + 4) == Mp4Box.TYPE_mdat) lastMdat = position
                position += source.getInt(position)
            }
            buffer.putInt(lastMdat, source.getInt(lastMdat) + mediaPadding)
        }
        val freeStart = original.size - 16 + mediaPadding
        buffer.putInt(freeStart, padding)
        buffer.putInt(freeStart + 4, Mp4Box.TYPE_free)
        original.copyInto(bytes, freeStart + padding, original.size - 16)
        buffer.putInt(start, oldSize + padding)
        buffer.putInt(bytes.size - 4, oldSize + padding)
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            var preparationBytes = 0L
            drain(extractor, bytes, onRead = { if (output.videoSamples.isEmpty()) preparationBytes += it })
            assertTrue(output.map.durationUs in 5_900_000L..6_200_000L, "The trailing index must not hide the final samples")
            assertTrue(output.map.isSeekable)
            assertTrue(preparationBytes < start + 64 * 1024L, "Probe growth must read only newly added bytes and skip the index")
            val point = output.map.getSeekPoints(4_900_000L).first
            extractor.seek(point.position, 4_900_000L)
            output.videoSamples.clear()
            drain(extractor, bytes, point.position)
            assertTrue(output.videoSamples.first().second and C.BUFFER_FLAG_KEY_FRAME != 0)
            assertTrue(output.videoSamples.first().first in 3_800_000L..4_900_000L)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `fragment timestamps ignore an edit that does not cover the media timeline`() {
        val original = finishedMp4Fixture()
        val old = ByteBuffer.wrap(original)
        val trakType = (4 until original.size - 8).first { old.getInt(it) == Mp4Box.TYPE_trak }
        val trak = trakType - 4
        val inserted = trak + old.getInt(trak)
        val bytes = ByteArray(original.size + 36)
        original.copyInto(bytes, 0, 0, inserted)
        original.copyInto(bytes, inserted + 36, inserted)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(inserted)
        listOf(36, Mp4Box.TYPE_edts, 28, Mp4Box.TYPE_elst, 0, 1, 1, 1280, 0x10000).forEach(buffer::putInt)
        buffer.putInt(trak, old.getInt(trak) + 36)
        val moov = (4 until trak).first { old.getInt(it) == Mp4Box.TYPE_moov } - 4
        buffer.putInt(moov, old.getInt(moov) + 36)
        val tkhd = (trakType + 4 until inserted).first { buffer.getInt(it) == Mp4Box.TYPE_tkhd }
        buffer.putInt(tkhd + 24, 6000)
        var offset = 0
        while (buffer.getInt(offset + 4) != Mp4Box.TYPE_moov) offset += buffer.getInt(offset)
        val track = checkNotNull(RecordingMp4Fragments.track(bytes.copyOfRange(offset, offset + buffer.getInt(offset))))
        val fragment = checkNotNull(RecordingMp4Fragments.find(bytes, 0, bytes.size.toLong(), track, false))
        // FFprobe: the first moof's video frame has PTS 120 ms (the first two frames are in moov's mdat).
        assertEquals(120_000L, fragment.timeUs, "A partial edit must not shift fragment timestamps")
    }

    @Test
    fun `an interrupted late seek does not contaminate an earlier seek`() {
        val original = finishedMp4Fixture()
        val padded = ByteArrayOutputStream()
        var offset = 0
        while (offset < original.size) {
            val box = checkNotNull(RecordingMp4Fragments.header(original, offset, original.size))
            val bytes = original.copyOfRange(offset, box.end)
            if (box.type == Mp4Box.TYPE_moof) {
                val buffer = ByteBuffer.wrap(bytes)
                for (position in 4 until bytes.size - 20) {
                    if (buffer.getInt(position) == Mp4Box.TYPE_tfhd) {
                        buffer.putLong(position + 12, buffer.getLong(position + 12) + padded.size() - offset)
                    }
                }
            }
            padded.write(bytes)
            if (box.type == Mp4Box.TYPE_mdat) {
                val free = ByteArray(128 * 1024)
                ByteBuffer.wrap(free).putInt(free.size).putInt(Mp4Box.TYPE_free)
                padded.write(free)
            }
            offset = box.end
        }
        val bytes = padded.toByteArray()
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes)
            val late = output.map.getSeekPoints(5_900_000L).first
            extractor.seek(late.position, 5_900_000L)
            drain(extractor, bytes, late.position, maximumReads = 3)
            val early = output.map.getSeekPoints(700_000L).first
            extractor.seek(early.position, 700_000L)
            output.videoSamples.clear()
            drain(extractor, bytes, early.position)
            assertTrue(output.videoSamples.first().first <= 700_000L, "A replacement seek must not retain the old floor")
            assertTrue(output.videoSamples.first().second and C.BUFFER_FLAG_KEY_FRAME != 0)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `healthy unknown length fragments retain the delegate duration`() {
        val bytes = finishedMp4Fixture()
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes, length = C.LENGTH_UNSET.toLong())
            assertTrue(output.map.durationUs > 0, "A healthy header duration must pass through")
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `audio only fragmented recordings have duration and can seek`() {
        val bytes = File("src/test/resources/finished-mp4/audio-only.mp4").readBytes()
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes)
            assertTrue(output.map.durationUs in 5_900_000L..6_200_000L)
            assertTrue(output.map.isSeekable)
            val point = output.map.getSeekPoints(4_500_000L).first
            extractor.seek(point.position, 4_500_000L)
            output.audioSamples.clear()
            drain(extractor, bytes, point.position)
            assertTrue(output.audioSamples.first().first in 4_400_000L..4_500_000L)
            assertTrue(output.audioSamples.first().second and C.BUFFER_FLAG_KEY_FRAME != 0)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `search without safe fragments stays within a per seek byte budget`() {
        val bytes = finishedMp4Fixture()
        var offset = 0
        while (ByteBuffer.wrap(bytes).getInt(offset + 4) != Mp4Box.TYPE_moov) {
            offset += ByteBuffer.wrap(bytes).getInt(offset)
        }
        val size = ByteBuffer.wrap(bytes).getInt(offset)
        val track = checkNotNull(RecordingMp4Fragments.track(bytes.copyOfRange(offset, offset + size)))
            .let { it.copy(restartTrackIds = it.restartTrackIds + 999) }
        val fileSize = 128 * 1024 * 1024L
        val seeker = RecordingMp4BinarySearchSeeker(track, 6_080_000L, fileSize)
        var totalRead = 0L
        fun input(position: Long): DefaultExtractorInput {
            var cursor = position
            return DefaultExtractorInput(DataReader { destination, start, length ->
                val count = minOf(length.toLong(), fileSize - cursor).toInt()
                if (count == 0) C.RESULT_END_OF_INPUT else {
                    destination.fill(0, start, start + count)
                    if (cursor < bytes.size) {
                        val copy = minOf(count.toLong(), bytes.size - cursor).toInt()
                        bytes.copyInto(destination, start, cursor.toInt(), cursor.toInt() + copy)
                    }
                    cursor += count
                    totalRead += count
                    count
                }
            }, position, fileSize)
        }
        seeker.setSeekTargetUs(5_000_000L)
        var current = input(seeker.seekMap.getSeekPoints(5_000_000L).first.position)
        val holder = PositionHolder()
        while (seeker.isSeeking) {
            if (seeker.handlePendingSeek(current, holder) == Extractor.RESULT_SEEK) current = input(holder.position)
        }
        assertEquals(0L, current.position, "No unsafe fragment may become the seek floor")
        assertTrue(totalRead <= 32 * 1024 * 1024L, "A seek must have a total read budget")
    }

    @Test
    fun `unknown length fragments do not publish corrupt header duration`() {
        val bytes = finishedMp4Fixture(true)
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes, length = C.LENGTH_UNSET.toLong())
            assertEquals(C.TIME_UNSET, output.map.durationUs)
            assertTrue(!output.map.isSeekable)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun `finished fragment seeks land on earlier sync samples and continue extracting`() {
        val bytes = finishedMp4Fixture()
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        extractor.init(output)
        try {
            drain(extractor, bytes)
            listOf(0L, 700_000L, 2_600_000L, 4_900_000L, 5_900_000L).forEach { target ->
                val point = output.map.getSeekPoints(target).first
                extractor.seek(point.position, target)
                output.videoSamples.clear()
                drain(extractor, bytes, point.position)
                assertTrue(output.videoSamples.isNotEmpty())
                val (timeUs, flags) = output.videoSamples.first()
                assertTrue(flags and C.BUFFER_FLAG_KEY_FRAME != 0, "The first video sample must be a sync sample")
                assertTrue(timeUs <= maxOf(target, 80_000L), "Seeking must not overshoot the target")
                assertTrue(timeUs >= target - 1_100_000L, "Seeking must use the nearest earlier keyframe")
            }
        } finally {
            extractor.release()
        }
    }

    private fun recordingMp4Extractor(): Extractor = createRecordingExtractorsFactory().createExtractors()
        .single { it.underlyingImplementation is FragmentedMp4Extractor }

    @Test
    fun `seeking skips video keyframes missing an initialized audio track`() {
        val bytes = finishedMp4Fixture()
        val moov = generateSequence(0) { offset ->
            RecordingMp4Fragments.header(bytes, offset, bytes.size)?.end?.takeIf { it < bytes.size }
        }.map { checkNotNull(RecordingMp4Fragments.header(bytes, it, bytes.size)) }
            .first { it.type == Mp4Box.TYPE_moov }
        val track = checkNotNull(RecordingMp4Fragments.track(bytes.copyOfRange(moov.end - moov.size, moov.end)))
        var fragment = checkNotNull(RecordingMp4Fragments.find(bytes, 0, bytes.size.toLong(), track, true))
        while (fragment.timeUs < 2_000_000L) {
            fragment = checkNotNull(RecordingMp4Fragments.find(bytes, 0, bytes.size.toLong(), track, true, fragment.endPosition.toInt()))
        }
        // Replace only audio's traf with an equal-size free box; video offsets and sample bytes stay valid.
        var child = fragment.position.toInt() + 8
        val buffer = ByteBuffer.wrap(bytes)
        while (true) {
            val box = checkNotNull(RecordingMp4Fragments.header(bytes, child, bytes.size))
            if (box.type == Mp4Box.TYPE_traf && buffer.getInt(child + 20) == 2) {
                buffer.putInt(child + 4, Mp4Box.TYPE_free)
                break
            }
            child = box.end
        }
        val extractor = recordingMp4Extractor()
        val output = FragmentOutput()
        val logLevel = Log.getLogLevel()
        Log.setLogLevel(Int.MAX_VALUE)
        extractor.init(output)
        try {
            drain(extractor, bytes)
            val point = output.map.getSeekPoints(2_600_000L).first
            extractor.seek(point.position, 2_600_000L)
            output.videoSamples.clear()
            drain(extractor, bytes, point.position)
            assertTrue(output.videoSamples.first().first < 2_000_000L, "An incomplete fragment cannot be a seek floor")
            assertTrue(output.videoSamples.first().second and C.BUFFER_FLAG_KEY_FRAME != 0)
        } finally {
            extractor.release()
            Log.setLogLevel(logLevel)
        }
    }


    private fun drain(
        extractor: Extractor,
        bytes: ByteArray,
        start: Long = 0,
        length: Long = bytes.size.toLong(),
        onRead: (Int) -> Unit = {},
        maximumReads: Int = Int.MAX_VALUE,
    ) {
        fun input(position: Long): DefaultExtractorInput {
            var cursor = position.toInt()
            return DefaultExtractorInput(DataReader { buffer, offset, length ->
                if (cursor == bytes.size) C.RESULT_END_OF_INPUT else {
                    val count = minOf(length, bytes.size - cursor)
                    bytes.copyInto(buffer, offset, cursor, cursor + count)
                    cursor += count
                    onRead(count)
                    count
                }
            }, position, length)
        }
        var input = input(start)
        val holder = PositionHolder()
        repeat(maximumReads) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = input(holder.position)
                else -> return
            }
        }
    }

    private class FragmentOutput : ExtractorOutput {
        lateinit var map: SeekMap
        val videoSamples = mutableListOf<Pair<Long, Int>>()
        val audioSamples = mutableListOf<Pair<Long, Int>>()
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (type == C.TRACK_TYPE_VIDEO) videoSamples += timeUs to flags
                if (type == C.TRACK_TYPE_AUDIO) audioSamples += timeUs to flags
            }
        }
        override fun endTracks(): Unit = Unit
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
}

internal fun finishedMp4Fixture(corruptDuration: Boolean = false): ByteArray {
    val bytes = File("src/test/resources/finished-mp4/synthetic.mp4").readBytes()
    if (corruptDuration) {
        // FFmpeg cannot reproduce Tvheadend's unsigned negative durations. Patch only video track 1.
        val buffer = ByteBuffer.wrap(bytes)
        for (offset in 4 until bytes.size - 32) {
            if (buffer.getInt(offset) == 0x746b6864 && buffer.getInt(offset + 16) == 1) {
                buffer.putInt(offset + 24, 0xffffffb0.toInt())
                val mdhd = (offset + 28 until bytes.size - 24).first { buffer.getInt(it) == 0x6d646864 }
                buffer.putInt(mdhd + 20, 0xfffffae1.toInt())
                break
            }
        }
    }
    return bytes
}
