@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import at.bernhardberger.tvheadend.sdk.playback.MuxFrameType
import at.bernhardberger.tvheadend.sdk.playback.StreamIndex
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionBinary
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionEvent
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class ActualElementaryStreamReaderFixtureTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun enableStrictMedia3BoundsChecks() {
            ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        }
    }

    @Test
    fun `recorded MPEG audio emits exact format and sample metadata`() {
        assertRecordedAudio(
            type = SubscriptionStreamType.MPEG2_AUDIO,
            resource = "/recorded-mux/mpeg-audio.bin",
            presentationTimeUs = 311_211L,
            expectedMimeType = MimeTypes.AUDIO_MPEG_L2,
            expectedSize = 576,
            expectedSha256 = "b99e3427331676dc0aac71bcd212494768fb6d608be1ee0d128726bcf9ae6d97",
        )
    }

    @Test
    fun `recorded AC3 emits exact format and sample metadata`() {
        assertRecordedAudio(
            type = SubscriptionStreamType.AC3,
            resource = "/recorded-mux/ac3.bin",
            presentationTimeUs = 279_211L,
            expectedMimeType = MimeTypes.AUDIO_AC3,
            expectedSize = 1_536,
            expectedSha256 = "3ec44cd72eac63889ed50b4859ed5788c2f1613121eb72130a54e68f3b992bfc",
        )
    }

    @Test
    fun `ADTS AAC emits exact format and sample metadata`() {
        assertRecordedAudio(
            type = SubscriptionStreamType.AAC,
            resource = "/recorded-mux/aac-adts.bin",
            presentationTimeUs = 421_000L,
            expectedMimeType = MimeTypes.AUDIO_AAC,
            expectedSize = 288,
            expectedSha256 = "aa381944d998c57e5f56ce4a44ceeeb587d3ff6726647869cec75f38d8c4dbfa",
            expectedChannelCount = 1,
            expectedInitializationData = listOf("1188"),
            expectedPayloadOffset = 7,
        )
    }

    @Test
    fun `malformed AAC emits no format or sample`() {
        val result = createElementaryStreamReader(stream(SubscriptionStreamType.AAC))
        assertTrue(result is ReaderResult.Supported)
        val output = CapturingExtractorOutput()
        val adapter = SubscriptionElementaryStreamAdapter(
            (result as ReaderResult.Supported).reader,
            output,
            7,
        )

        adapter.accept(
            SubscriptionEvent.Packet(
                frameType = MuxFrameType.UNKNOWN,
                streamIndex = StreamIndex(0L),
                decodingTimeUs = 421_000L,
                presentationTimeUs = 421_000L,
                durationUs = 21_333L,
                payload = ByteArrayBinary(byteArrayOf(0x56, 0xe0.toByte(), 0x00, 0x01, 0x02)),
            ),
        )
        adapter.end()

        assertEquals(7, output.trackId)
        assertEquals(C.TRACK_TYPE_AUDIO, output.trackType)
        assertNull(output.trackOutput.format)
        assertTrue(output.trackOutput.metadata.isEmpty())
        assertTrue(output.trackOutput.bytes.isEmpty())
    }

    @Test
    fun `every stream type has an explicit reader classification`() {
        val supported = SubscriptionStreamType.entries.filterTo(mutableSetOf()) { type ->
            createElementaryStreamReader(
                stream(
                    type = type,
                    compositionId = if (type == SubscriptionStreamType.DVB_SUBTITLE) 1L else null,
                    ancillaryId = if (type == SubscriptionStreamType.DVB_SUBTITLE) 2L else null,
                ),
            ) is ReaderResult.Supported
        }

        assertEquals(
            setOf(
                SubscriptionStreamType.MPEG2_VIDEO,
                SubscriptionStreamType.H264,
                SubscriptionStreamType.H265,
                SubscriptionStreamType.AAC,
                SubscriptionStreamType.AC3,
                SubscriptionStreamType.EAC3,
                SubscriptionStreamType.MPEG2_AUDIO,
                SubscriptionStreamType.DVB_SUBTITLE,
            ),
            supported,
        )
    }

    @Test
    fun `recorded H264 emits exact format initialization data and sample metadata`() {
        assertEquals(
            VideoOracle(
                sampleMimeType = MimeTypes.VIDEO_H264,
                width = 720,
                height = 576,
                initializationData = listOf(
                    "000001674d401e9662816849b044a0a0a0be000007d0000186a1c90000cfb60007ca14d6b019a0884516",
                    "00000168ff7c80",
                ),
                sampleCount = 49,
                firstSamples = listOf(
                    CapturedSampleMetadata(1_117_733L, 0, 66, 36_120, null),
                    CapturedSampleMetadata(1_117_733L, C.BUFFER_FLAG_KEY_FRAME, 29_419, 6_701, null),
                    CapturedSampleMetadata(1_237_733L, 0, 5, 6_696, null),
                ),
            ),
            readRecordedVideo(16, 1, SubscriptionStreamType.H264),
        )
    }

    @Test
    fun `recorded H262 emits exact format initialization data and sample metadata`() {
        assertEquals(
            VideoOracle(
                sampleMimeType = MimeTypes.VIDEO_MPEG2,
                width = 720,
                height = 576,
                initializationData = listOf(
                    "000001b32d02403303d7a38210202020262c342c2c262c2c3434363a363634362c34363a3a363a4050404436443a363034443a4a444c506050464c3a443a4a445c60745c46464c708a8a70a71012111113141714141316141819191c1a191718151617181a1b1d1d221e1d1a1b191715161a1b1c1d222124211f1f1c1a1b1f20242326231e2021262928272b000001b5148200010000",
                ),
                sampleCount = 64,
                firstSamples = listOf(
                    CapturedSampleMetadata(759_211L, C.BUFFER_FLAG_KEY_FRAME, 74_328, 2_395, null),
                    CapturedSampleMetadata(679_211L, 0, 2_395, 2_462, null),
                    CapturedSampleMetadata(719_211L, 0, 2_462, 13_627, null),
                ),
            ),
            readRecordedVideo(4, 1, SubscriptionStreamType.MPEG2_VIDEO),
        )
    }

    private fun readRecordedVideo(
        channelOrdinal: Int,
        streamOrdinal: Int,
        type: SubscriptionStreamType,
    ): VideoOracle {
        val output = CapturingExtractorOutput()
        val result = createElementaryStreamReader(stream(type))
        assertTrue(result is ReaderResult.Supported)
        val adapter = SubscriptionElementaryStreamAdapter(
            (result as ReaderResult.Supported).reader,
            output,
            7,
        )
        recordedPackets(channelOrdinal, streamOrdinal).forEach { packet ->
            val bytes = checkNotNull(
                javaClass.getResourceAsStream("/recorded-mux/${packet.file}"),
            ).use { it.readBytes() }
            adapter.accept(
                SubscriptionEvent.Packet(
                    frameType = when (packet.frameType) {
                        73 -> MuxFrameType.I
                        80 -> MuxFrameType.P
                        66 -> MuxFrameType.B
                        else -> MuxFrameType.UNKNOWN
                    },
                    streamIndex = StreamIndex(0L),
                    decodingTimeUs = packet.decodingTimeUs,
                    presentationTimeUs = packet.presentationTimeUs,
                    durationUs = packet.durationUs,
                    payload = ByteArrayBinary(bytes),
                ),
            )
        }
        adapter.end()

        assertEquals(7, output.trackId)
        assertEquals(C.TRACK_TYPE_VIDEO, output.trackType)
        val format = checkNotNull(output.trackOutput.format)
        assertEquals(MimeTypes.VIDEO_MP2T, format.containerMimeType)
        return VideoOracle(
            sampleMimeType = format.sampleMimeType,
            width = format.width,
            height = format.height,
            initializationData = format.initializationData.map { it.toHex() },
            sampleCount = output.trackOutput.metadata.size,
            firstSamples = output.trackOutput.metadata.take(3),
        )
    }

    private fun recordedPackets(channelOrdinal: Int, streamOrdinal: Int): List<RecordedPacket> {
        val manifest = checkNotNull(javaClass.getResourceAsStream("/recorded-mux/manifest.json"))
            .bufferedReader()
            .use { it.readText() }
        val packetPattern = Regex(
            """\{\s*"streamOrdinal": (\d+),\s*"packetOrdinal": \d+,\s*"presentationTimeUs": (-?\d+),\s*"decodingTimeUs": (null|-?\d+),\s*"durationUs": (-?\d+),\s*"frameType": (-?\d+),\s*"size": \d+,\s*"sha256": "[0-9a-f]{64}",\s*"file": "([^"]+)"\s*}""",
        )
        val prefix = "channel-${channelOrdinal.toString().padStart(3, '0')}-stream-${streamOrdinal.toString().padStart(2, '0')}-"
        return packetPattern.findAll(manifest).mapNotNull { match ->
            val values = match.groupValues
            val file = values[6]
            if (values[1].toInt() != streamOrdinal || !file.startsWith(prefix)) return@mapNotNull null
            RecordedPacket(
                presentationTimeUs = values[2].toLong(),
                decodingTimeUs = values[3].takeUnless { it == "null" }?.toLong(),
                durationUs = values[4].toLong(),
                frameType = values[5].toInt(),
                file = file,
            )
        }.toList().also { check(it.isNotEmpty()) { "Recorded video fixture is missing" } }
    }

    private fun assertRecordedAudio(
        type: SubscriptionStreamType,
        resource: String,
        presentationTimeUs: Long,
        expectedMimeType: String,
        expectedSize: Int,
        expectedSha256: String,
        expectedChannelCount: Int? = null,
        expectedInitializationData: List<String> = emptyList(),
        expectedPayloadOffset: Int = 0,
    ) {
        val bytes = checkNotNull(javaClass.getResourceAsStream(resource)).use { it.readBytes() }
        assertEquals(expectedSha256, bytes.sha256())
        val result = createElementaryStreamReader(stream(type))
        assertTrue(result is ReaderResult.Supported)
        val output = CapturingExtractorOutput()
        val adapter = SubscriptionElementaryStreamAdapter(
            (result as ReaderResult.Supported).reader,
            output,
            7,
        )

        adapter.accept(
            SubscriptionEvent.Packet(
                frameType = MuxFrameType.UNKNOWN,
                streamIndex = StreamIndex(0L),
                decodingTimeUs = presentationTimeUs,
                presentationTimeUs = presentationTimeUs,
                durationUs = 1L,
                payload = ByteArrayBinary(bytes),
            ),
        )
        adapter.end()

        assertEquals(7, output.trackId)
        assertEquals(C.TRACK_TYPE_AUDIO, output.trackType)
        val format = output.trackOutput.format
        assertNotNull(format)
        assertEquals(MimeTypes.VIDEO_MP2T, format!!.containerMimeType)
        assertEquals(expectedMimeType, format.sampleMimeType)
        assertEquals("de", format.language)
        assertEquals(48_000, format.sampleRate)
        if (expectedChannelCount == null) {
            assertTrue(format.channelCount > 0)
        } else {
            assertEquals(expectedChannelCount, format.channelCount)
        }
        assertEquals(expectedInitializationData, format.initializationData.map { it.toHex() })

        val metadata = output.trackOutput.metadata.single()
        assertEquals(presentationTimeUs, metadata.timeUs)
        assertEquals(C.BUFFER_FLAG_KEY_FRAME, metadata.flags)
        assertEquals(expectedSize, metadata.size)
        assertEquals(0, metadata.offset)
        assertNull(metadata.cryptoData)
        assertArrayEquals(bytes.copyOfRange(expectedPayloadOffset, bytes.size), output.trackOutput.bytes.toByteArray())
    }

    private fun stream(
        type: SubscriptionStreamType,
        compositionId: Long? = null,
        ancillaryId: Long? = null,
    ): SubscriptionStream = SubscriptionStream(
        index = StreamIndex(0L),
        type = type,
        language = "ger",
        compositionId = compositionId,
        ancillaryId = ancillaryId,
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
        codecMetadata = null,
    )
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

private data class VideoOracle(
    val sampleMimeType: String?,
    val width: Int,
    val height: Int,
    val initializationData: List<String>,
    val sampleCount: Int,
    val firstSamples: List<CapturedSampleMetadata>,
)

private data class RecordedPacket(
    val presentationTimeUs: Long,
    val decodingTimeUs: Long?,
    val durationUs: Long,
    val frameType: Int,
    val file: String,
)

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

private class CapturingExtractorOutput : ExtractorOutput {
    val trackOutput = CapturingTrackOutput()
    var trackId: Int? = null
    var trackType: Int? = null

    override fun track(id: Int, type: Int): TrackOutput {
        trackId = id
        trackType = type
        return trackOutput
    }

    override fun endTracks(): Unit = Unit
    override fun seekMap(seekMap: SeekMap): Unit = Unit
}

private data class CapturedSampleMetadata(
    val timeUs: Long,
    val flags: Int,
    val size: Int,
    val offset: Int,
    val cryptoData: TrackOutput.CryptoData?,
)

private class CapturingTrackOutput : TrackOutput {
    var format: Format? = null
    val bytes = mutableListOf<Byte>()
    val metadata = mutableListOf<CapturedSampleMetadata>()

    override fun format(format: Format) {
        this.format = format
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        val buffer = ByteArray(length)
        val count = input.read(buffer, 0, length)
        if (count > 0) bytes += buffer.copyOf(count).toList()
        return count
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        val buffer = ByteArray(length)
        data.readBytes(buffer, 0, length)
        bytes += buffer.toList()
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        metadata += CapturedSampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}

private class ByteArrayBinary(private val bytes: ByteArray) : SubscriptionBinary {
    override val size: Int = bytes.size

    override fun copyInto(destination: ByteArray, destinationOffset: Int): Int {
        bytes.copyInto(destination, destinationOffset)
        return bytes.size
    }
}
