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
        while (true) {
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
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (type == C.TRACK_TYPE_VIDEO) videoSamples += timeUs to flags
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
