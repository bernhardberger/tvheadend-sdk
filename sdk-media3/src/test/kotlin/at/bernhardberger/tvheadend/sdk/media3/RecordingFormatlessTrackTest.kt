@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.test.utils.Dumper
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeTrackOutput
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recording playback of finished recordings Media3 does not handle alone: a program map that
 * announces a stream which never carries data, as broadcasters do for audio description, audio
 * roles in a descriptor Media3 does not read, and recordings Media3 cannot time from its default
 * search. Media3's TS extractor needs the Android runtime, so this runs on Robolectric, unlike the
 * plain JVM recording data source tests.
 */
private typealias Step = (ExtractorOutput) -> Unit

@RunWith(RobolectricTestRunner::class)
// Robolectric's API 36 image needs JDK internals that a JDK 21 test JVM does not open.
@Config(sdk = [34])
class RecordingFormatlessTrackTest {
    @Test
    fun `a finished recording Media3 cannot time from its default search seeks and reaches ended`() {
        // A fragmented MP4 with invalid header durations, and a TS whose first PCR follows a long lead-in.
        listOf(
            Triple(finishedMp4Fixture(true), 5_900L..6_200L, 4_500L),
            Triple(withLeadIn(LATE_PCR_LEAD_IN), 2_900L..3_000L, 2_000L),
        ).forEach { (recording, duration, seekTo) ->
            val player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
            try {
                player.setMediaSource(createTvheadendRecordingMediaSource(FakeRecordingTransport(recording), RecordingMediaIdentity()))
                player.prepare()
                TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)
                assertTrue(player.isCurrentMediaItemSeekable)
                assertTrue(player.duration in duration)
                player.seekTo(seekTo)
                TestPlayerRunHelper.run(player).untilPendingCommandsAreFullyHandled()
                // Media3 resolves a seek it cannot perform to the start once the playback thread handles it.
                assertTrue(player.currentPosition >= seekTo)
                player.play()
                TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun `a TS Media3 cannot time from its default search retries only within the bounded windows`() {
        val plain = { recordingTsExtractor(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES) }
        // Without the track filter, so the strict track outputs also see a subtitle track that carries no data.
        val retrying = { TsDurationRetryExtractor(plain(), ::recordingTsExtractor) }
        val timed = extract(plain, fixture())
        val largest = RECORDING_TS_DURATION_RETRY_WINDOWS.last()
        // Input, whether the retrying extractor times it, and whether it reads the input again for that.
        listOf(
            Triple(fixture(), true, false),
            Triple(withDvbSubtitlePmt(withLeadIn(LATE_PCR_LEAD_IN)), true, true),
            Triple(withLeadIn(largest + LATE_PCR_LEAD_IN), false, true),
            Triple(withLeadIn(largest + LATE_PCR_LEAD_IN, beforeTables = true), false, false),
        ).forEach { (input, seekable, rereads) ->
            val reference = extract(plain, input)
            val retried = extract(retrying, input)

            assertEquals(seekable, retried.seekMap?.isSeekable)
            if (seekable) assertEquals(timed.seekMap?.durationUs, retried.seekMap?.durationUs)
            assertEquals(rereads, retried.restarts > reference.restarts)
            assertEquals(reference.tracks, retried.tracks)
        }
    }

    @Test
    fun `a completed recording with an announced stream that carries no data prepares its audio and video`() {
        val player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
        try {
            val identity = RecordingMediaIdentity()
            player.setMediaSource(createTvheadendRecordingMediaSource(FakeRecordingTransport(silentAudioDescription()), identity))
            player.prepare()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)

            assertEquals(
                listOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC),
                player.currentTracks.groups.map { group -> group.getTrackFormat(0).sampleMimeType },
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `an announced stream that carries no data is left out a bounded time into the recording`() {
        // Also with a lead-in that makes the completed recording extractor retry its duration read.
        val inputs = listOf(silentAudioDescription(), withLeadIn(LATE_PCR_LEAD_IN, original = silentAudioDescription()))
        for (input in inputs) recordingExtractors().forEach { (filtered, plain) ->
            val left = extract(filtered, input)
            val reference = extract(plain, input)

            assertTrue(reference.tracks.any { it.lastFormat == null })
            assertEquals(reference.tracks.filter { it.lastFormat != null }, left.tracks)
            // Running recordings may never reach the end of input, so the bound is media time.
            assertTrue(left.tracksEndedAt < input.size)
        }
    }

    @Test
    fun `a stream whose every track gets a format is extracted unchanged`() {
        // Descriptors of an audio stream, none of them a readable supplementary audio descriptor.
        val unreadable = listOf(
            "",
            "0a04716b7303", // ISO 639 only, with a language live playback reports differently.
            "0a0464657500 7f050487646575", // Another extension descriptor.
            "0a0464657500 7f00",
            "0a0464657500 7f0106",
            "0a0464657500 7f060687", // Longer than the descriptor loop.
            "0a0464657500 7f",
        )
        // The stream under test comes first, so that a bad length runs into the next stream's entry.
        val inputs = listOf(fixture()) + unreadable.map { withAudioDescriptors(it, "0a0464657500") }
        inputs.forEachIndexed { index, input ->
            recordingExtractors().forEach { (filtered, plain) ->
                val reference = extract(plain, input)

                assertEquals("$index", 3, reference.tracks.size)
                assertEquals("$index", reference.tracks, extract(filtered, input).tracks)
            }
        }
    }

    @Test
    fun `a supplementary audio descriptor gives recorded audio its live role and language`() {
        // Descriptors of the second audio stream, with the role flags and language live playback reports for them.
        listOf(
            Triple("0a04656e6700 7f050687646575", C.ROLE_FLAG_DESCRIBES_VIDEO, "de"),
            Triple("7f050607646575 0a04656e6700", C.ROLE_FLAG_DESCRIBES_VIDEO, "de"), // mix_type 0, before ISO 639.
            Triple("0a0464657500 7f02068e", C.ROLE_FLAG_DESCRIBES_VIDEO, "de"), // Spoken subtitles, no language.
            Triple("0a04716b7302 7f05068b646575", C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY, "de"), // Clear audio.
            Triple("0a0464657503 7f02068a", C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY, "de"), // Replaces ISO 639's 3.
            Triple("0a0464657503 7f020682", C.ROLE_FLAG_DESCRIBES_VIDEO, "de"), // Main audio keeps ISO 639's 3.
            Triple("0a0464657500 7f020692", 0, "de"), // A reserved editorial classification.
            Triple("0a04716b7300 7f020686", C.ROLE_FLAG_DESCRIBES_VIDEO, "qaa"),
            Triple("0a0400656e00 7f020686", C.ROLE_FLAG_DESCRIBES_VIDEO, C.LANGUAGE_UNDETERMINED), // Media3 trims to "en".
            Triple("0a0464657500 7f040687656e", C.ROLE_FLAG_DESCRIBES_VIDEO, "de"), // A truncated language code.
            Triple("0a0464657500 7f050687000000", C.ROLE_FLAG_DESCRIBES_VIDEO, C.LANGUAGE_UNDETERMINED),
            Triple("7f050687656e67 7f02068a", C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY, "en"),
            Triple("7f020686", C.ROLE_FLAG_DESCRIBES_VIDEO, null),
        ).forEachIndexed { index, (descriptors, roleFlags, language) ->
            val described = withAudioDescriptors("0a0464657500", descriptors)
            // One of them also with a lead-in that makes the completed recording extractor retry its duration read.
            val retried = if (index == 0) withLeadIn(LATE_PCR_LEAD_IN, original = described) else null
            for (input in listOfNotNull(described, retried)) {
                recordingExtractors().forEach { (recording, _) ->
                    val formats = extract(recording, input).tracks.associate { it.id to checkNotNull(it.lastFormat) }
                    val second = formats.getValue(AUDIO_DESCRIPTION_PID)

                    assertEquals(descriptors, roleFlags to language, second.roleFlags to second.language)
                    assertEquals(0 to "de", formats.getValue(MAIN_AUDIO_PID).let { it.roleFlags to it.language })
                }
            }
        }
    }

    @Test
    fun `default track selection plays the normal track instead of audio description`() {
        val player = TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build()
        try {
            // Audio description comes first, where equal tracks would make it the default.
            val recording = withAudioDescriptors("0a0464657500 7f050687646575", "0a0464657500")
            player.setMediaSource(createTvheadendRecordingMediaSource(FakeRecordingTransport(recording), RecordingMediaIdentity()))
            player.prepare()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_READY)

            val audio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            assertEquals(
                mapOf(C.ROLE_FLAG_DESCRIBES_VIDEO to false, 0 to true),
                audio.associate { it.getTrackFormat(0).roleFlags to it.isSelected },
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `held sample data stops being held at the byte bound without any further callback`() {
        val steps = listOf<Step>(
            { it.track(VIDEO_ID, C.TRACK_TYPE_VIDEO) },
            { it.endTracks() },
        ) + List(3) { { output: ExtractorOutput -> output.track(VIDEO_ID, C.TRACK_TYPE_VIDEO).sampleData(bytes(4), 4) } }
        var stepsRun = 0L
        val output = CapturingOutput { stepsRun }
        val filter = FormatlessTrackFilter(ScriptedExtractor(steps), maximumHeldBytes = 8)
        filter.init(output)

        // The script never ends its input, so only the bound can release the held track.
        repeat(steps.size) {
            filter.read(emptyInput(), PositionHolder())
            stepsRun++
        }

        assertEquals(listOf(VIDEO_ID), output.tracks.map { it.first })
        assertTrue(output.tracksEndedAt < steps.size)
    }

    @Test
    fun `a seek before the tracks are released keeps their formats and restarts the wait`() {
        val steps = listOf<Step>(
            { it.track(VIDEO_ID, C.TRACK_TYPE_VIDEO) },
            { it.track(AUDIO_ID, C.TRACK_TYPE_AUDIO) },
            { it.track(ALTERNATIVE_AUDIO_ID, C.TRACK_TYPE_AUDIO) },
            { it.endTracks() },
            { it.track(VIDEO_ID, C.TRACK_TYPE_VIDEO).format(VIDEO) },
            { it.track(AUDIO_ID, C.TRACK_TYPE_AUDIO).format(AUDIO) },
            sample(VIDEO_ID, C.TRACK_TYPE_VIDEO, 0),
            sample(AUDIO_ID, C.TRACK_TYPE_AUDIO, 0),
            sample(VIDEO_ID, C.TRACK_TYPE_VIDEO, FORMAT_WAIT_US / 2),
            // The seek lands here, well past the window's start before it.
            sample(VIDEO_ID, C.TRACK_TYPE_VIDEO, 5 * FORMAT_WAIT_US),
            sample(AUDIO_ID, C.TRACK_TYPE_AUDIO, 5 * FORMAT_WAIT_US),
            sample(AUDIO_ID, C.TRACK_TYPE_AUDIO, 5 * FORMAT_WAIT_US + FORMAT_WAIT_US / 2),
            { it.track(ALTERNATIVE_AUDIO_ID, C.TRACK_TYPE_AUDIO).format(AUDIO) },
        )
        val output = CapturingOutput { 0 }
        val filter = FormatlessTrackFilter(ScriptedExtractor(steps))
        filter.init(output)

        repeat(9) { filter.read(emptyInput(), PositionHolder()) }
        filter.seek(0, 0)
        repeat(4) { filter.read(emptyInput(), PositionHolder()) }

        assertEquals(listOf(VIDEO_ID, AUDIO_ID, ALTERNATIVE_AUDIO_ID), output.tracksAtEnd)
        val downstreamVideo = output.tracks.single { it.first == VIDEO_ID }.second
        assertEquals(VIDEO, downstreamVideo.lastFormat)
        assertEquals(1, downstreamVideo.sampleCount)
    }

    @Test
    fun `video timestamps running ahead of audio do not shorten the wait for another track`() {
        val steps = listOf<Step>(
            { it.track(VIDEO_ID, C.TRACK_TYPE_VIDEO) },
            { it.track(AUDIO_ID, C.TRACK_TYPE_AUDIO) },
            { it.track(ALTERNATIVE_AUDIO_ID, C.TRACK_TYPE_AUDIO) },
            { it.endTracks() },
            { it.track(AUDIO_ID, C.TRACK_TYPE_AUDIO).format(AUDIO) },
            sample(AUDIO_ID, C.TRACK_TYPE_AUDIO, 0),
            { it.track(VIDEO_ID, C.TRACK_TYPE_VIDEO).format(VIDEO) },
            // A decoder delay puts the first video sample a full window ahead of the audio.
            sample(VIDEO_ID, C.TRACK_TYPE_VIDEO, FORMAT_WAIT_US),
            sample(AUDIO_ID, C.TRACK_TYPE_AUDIO, FORMAT_WAIT_US / 2),
            { it.track(ALTERNATIVE_AUDIO_ID, C.TRACK_TYPE_AUDIO).format(AUDIO) },
        )
        val output = CapturingOutput { 0 }
        val filter = FormatlessTrackFilter(ScriptedExtractor(steps))
        filter.init(output)

        repeat(steps.size) { filter.read(emptyInput(), PositionHolder()) }

        assertEquals(listOf(VIDEO_ID, AUDIO_ID, ALTERNATIVE_AUDIO_ID), output.tracksAtEnd)
    }

    @Test
    fun `a stream whose video never gets a format declares every track and fails as before`() {
        val steps = listOf<Step>(
            { it.track(VIDEO_ID, C.TRACK_TYPE_VIDEO) },
            { it.track(AUDIO_ID, C.TRACK_TYPE_AUDIO) },
            { it.endTracks() },
            { it.track(AUDIO_ID, C.TRACK_TYPE_AUDIO).format(AUDIO) },
        ) + List(3) { index -> sample(AUDIO_ID, C.TRACK_TYPE_AUDIO, index * FORMAT_WAIT_US) }
        val output = CapturingOutput { 0 }
        val filter = FormatlessTrackFilter(ScriptedExtractor(steps), maximumHeldBytes = 8)
        filter.init(output)

        repeat(steps.size) { filter.read(emptyInput(), PositionHolder()) }

        assertEquals(listOf(VIDEO_ID, AUDIO_ID), output.tracksAtEnd)
        assertNull(output.tracks.first().second.lastFormat)
    }

    private fun sample(id: Int, type: Int, timeUs: Long): Step = { output ->
        output.track(id, type).run {
            sampleData(bytes(4), 4)
            sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, 4, 0, null)
        }
    }

    /** The completed and the growing recording extractor, each with the extractor it decorates. */
    private fun recordingExtractors(): List<Pair<() -> Extractor, () -> Extractor>> = listOf(
        { createRecordingExtractorsFactory().createExtractors().single { it.underlyingImplementation is TsExtractor } } to
            ::defaultTsExtractor,
        { createGrowingTsExtractorsFactory().createExtractors().single() } to
            { GrowingRecordingExtractor(GrowingTsContainer, delegate = defaultTsExtractor()) },
    )

    private fun defaultTsExtractor(): Extractor = VideoScanExtractorsFactory(DefaultExtractorsFactory()).createExtractors()
        .single { it.underlyingImplementation is TsExtractor }

    private fun extract(create: () -> Extractor, data: ByteArray): Extraction {
        val extractor = create()
        val input = FakeExtractorInput.Builder().setData(data).build()
        val output = CapturingOutput { input.position }
        extractor.init(output)
        val position = PositionHolder()
        var restarts = 0
        while (true) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    if (position.position == 0L) restarts++
                    input.setPosition(position.position.toInt())
                }
            }
        }
        extractor.release()
        return Extraction(output.tracks.map { it.capture() }, output.tracksEndedAt, output.seekMap, restarts)
    }

    private class CapturingOutput(private val position: () -> Long) : ExtractorOutput {
        val tracks = ArrayList<Pair<Int, FakeTrackOutput>>()
        var tracksEndedAt = Long.MAX_VALUE
        var tracksAtEnd: List<Int>? = null
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput =
            tracks.firstOrNull { it.first == id }?.second
                ?: FakeTrackOutput(type, /* deduplicateConsecutiveFormats = */ false).also { tracks += id to it }

        override fun endTracks() {
            tracksEndedAt = minOf(tracksEndedAt, position())
            if (tracksAtEnd == null) tracksAtEnd = tracks.map { it.first }
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    /** Runs one step against its output per read and never reaches the end of its input. */
    private class ScriptedExtractor(private val steps: List<Step>) : Extractor {
        private lateinit var output: ExtractorOutput
        private var next = 0

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            steps.getOrNull(next++)?.invoke(output)
            return Extractor.RESULT_CONTINUE
        }

        override fun seek(position: Long, timeUs: Long) = Unit

        override fun release() = Unit
    }

    private fun emptyInput(): ExtractorInput = FakeExtractorInput.Builder().setData(ByteArray(0)).build()

    private fun bytes(size: Int) = ParsableByteArray(ByteArray(size))

    private data class Extraction(
        val tracks: List<CapturedTrack>,
        val tracksEndedAt: Long,
        val seekMap: SeekMap?,
        val restarts: Int,
    )

    private data class CapturedTrack(val id: Int, val lastFormat: Format?, val samples: String)

    private fun Pair<Int, FakeTrackOutput>.capture(): CapturedTrack =
        CapturedTrack(first, second.lastFormat, Dumper().add(second).toString())

    private fun fixture(): ByteArray = checkNotNull(javaClass.getResourceAsStream(FIXTURE)).use { it.readBytes() }

    /** The fixture without any packet of its audio description stream, which its program map still announces. */
    private fun silentAudioDescription(): ByteArray {
        val packets = fixture().asList().chunked(TS_PACKET_BYTES)
        return packets.filterNot { packet -> (packet[1].toInt() and 0x1f shl 8 or (packet[2].toInt() and 0xff)) == AUDIO_DESCRIPTION_PID }
            .flatten()
            .toByteArray()
    }

    /** The fixture with the given PMT descriptors, in hex, for its two audio streams. */
    private fun withAudioDescriptors(first: String, second: String): ByteArray {
        fun entry(pid: Int, descriptors: String): String = descriptors.filterNot(Char::isWhitespace).let { hex ->
            "%02x%04x%04x".format(TsExtractor.TS_STREAM_TYPE_AAC_LATM, 0xe000 or pid, 0xf000 or hex.length / 2) + hex
        }
        return withPmtStreams(
            fixture(),
            "1be101f000" + entry(MAIN_AUDIO_PID, first) + entry(AUDIO_DESCRIPTION_PID, second),
        )
    }

    /** [original] with [bytes] of null packets after its program map, or before its first packet. */
    private fun withLeadIn(bytes: Int, beforeTables: Boolean = false, original: ByteArray = fixture()): ByteArray {
        val nullPacket = ByteArray(TS_PACKET_BYTES) { 0xff.toByte() }.apply {
            this[0] = 0x47
            this[1] = 0x1f
            this[2] = 0xff.toByte()
            this[3] = 0x10
        }
        val leadIn = ByteArray(bytes / TS_PACKET_BYTES * TS_PACKET_BYTES) { index -> nullPacket[index % TS_PACKET_BYTES] }
        val at = if (beforeTables) 0 else firstPmtPacket(original) + TS_PACKET_BYTES
        return original.copyOfRange(0, at) + leadIn + original.copyOfRange(at, original.size)
    }

    private companion object {
        const val VIDEO_ID = 1
        const val AUDIO_ID = 2
        const val ALTERNATIVE_AUDIO_ID = 3
        val VIDEO: Format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val AUDIO: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        const val FIXTURE = "/latm-audio-description/two-latm-audio.ts"
        const val TS_PACKET_BYTES = 188
        const val MAIN_AUDIO_PID = 0x102
        const val AUDIO_DESCRIPTION_PID = 0x103

        /** Puts the first PCR beyond Media3's default timestamp search window. */
        const val LATE_PCR_LEAD_IN = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES + 100 * TS_PACKET_BYTES
    }
}
