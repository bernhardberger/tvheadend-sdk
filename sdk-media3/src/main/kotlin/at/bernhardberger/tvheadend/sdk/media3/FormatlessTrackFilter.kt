@file:androidx.media3.common.util.UnstableApi

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * Lets a progressive recording prepare when its program map announces a track that never gets a
 * format, such as an audio description stream that carries no packets.
 *
 * Media3's progressive period prepares only once every declared track has a format. Until the
 * delegate has given every declared track a format, this decorator holds the track list back and
 * keeps what the delegate writes. Once every track has a format, the tracks go downstream in the
 * delegate's declaration order with everything written so far, so such a stream prepares with
 * the same tracks and samples as without the decorator. The bound is the live period's: once
 * audio and video formats exist, any other track gets [FORMAT_WAIT_US] of media time, or until
 * the input ends, before it is left out. Each audio and video track measures that time from its
 * own first sample, because video timestamps run ahead of audio at the same file position.
 * Without the audio and video formats, the decorator stops holding after [maximumHeldBytes] or at
 * the end of input and declares every track, so the stream fails to prepare as it would without
 * the decorator. The delegate still parses all data.
 */
internal class FormatlessTrackFilter(
    private val delegate: Extractor,
    private val maximumHeldBytes: Long = MAXIMUM_FORMAT_WAIT_HELD_BYTES,
) : Extractor {
    private val tracks = ArrayList<HeldTrack>()
    private var downstream: ExtractorOutput? = null
    private var delegateEndedTracks = false
    private var resolved = false
    private var heldBytes = 0L
    private var requiredFormatsSeen = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    override fun init(output: ExtractorOutput) {
        downstream = output
        delegate.init(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    if (resolved) return output.track(id, type)
                    tracks.firstOrNull { it.id == id }?.let { return it }
                    return HeldTrack(id, type).also { tracks += it }
                }

                override fun endTracks() {
                    delegateEndedTracks = true
                    if (resolved) output.endTracks() else maybeResolve(endOfInput = false)
                }

                // The growing extractor publishes seek maps from its probe thread; nothing here is held.
                override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
            },
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(input, seekPosition)
        if (result == Extractor.RESULT_END_OF_INPUT) maybeResolve(endOfInput = true)
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        if (!resolved) {
            // Media3 resets its own sample queues on such a restart; keep only the formats.
            tracks.forEach(HeldTrack::discardSamples)
            heldBytes = 0L
        }
        delegate.seek(position, timeUs)
    }

    override fun release() {
        tracks.forEach(HeldTrack::discardSamples)
        delegate.release()
    }

    private fun onSample(track: HeldTrack, timeUs: Long) {
        if (requiredFormatsSeen && track.format != null && track.type in REQUIRED_TRACK_TYPES) track.advanceTo(timeUs)
        maybeResolve(endOfInput = false)
    }

    private fun maybeResolve(endOfInput: Boolean) {
        if (resolved) return
        val formatless = tracks.filter { it.format == null }
        val required = hasRequiredFormats()
        if (required) requiredFormatsSeen = true
        val waitExpired = endOfInput || heldBytes >= maximumHeldBytes || (required && alternativesWaitExpired())
        when {
            !delegateEndedTracks -> if (endOfInput || heldBytes >= maximumHeldBytes) resolve(emptyList())
            formatless.isEmpty() -> resolve(emptyList())
            required && waitExpired -> resolve(formatless)
            endOfInput || heldBytes >= maximumHeldBytes -> resolve(emptyList())
        }
    }

    private fun hasRequiredFormats(): Boolean = tracks.any { it.format != null } &&
        REQUIRED_TRACK_TYPES.all { type ->
            tracks.none { it.type == type } || tracks.any { it.type == type && it.format != null }
        }

    private fun alternativesWaitExpired(): Boolean = REQUIRED_TRACK_TYPES.all { type ->
        tracks.none { it.type == type } || tracks.any { it.type == type && it.waitedUs >= FORMAT_WAIT_US }
    }

    private fun resolve(left: List<HeldTrack>) {
        resolved = true
        val output = checkNotNull(downstream)
        tracks.forEach { track ->
            track.release(if (track in left) DiscardingTrackOutput() else output.track(track.id, track.type))
        }
        heldBytes = 0L
        if (delegateEndedTracks) output.endTracks()
    }

    private inner class HeldTrack(val id: Int, val type: Int) : TrackOutput {
        var format: Format? = null
            private set
        private var target: TrackOutput? = null
        private val events = ArrayList<(TrackOutput) -> Unit>()
        private var firstSampleTimeUs = C.TIME_UNSET
        private var latestSampleTimeUs = C.TIME_UNSET

        /** Media time this track has advanced since its first sample after the audio and video formats existed. */
        val waitedUs: Long
            get() = if (firstSampleTimeUs == C.TIME_UNSET) 0L else latestSampleTimeUs - firstSampleTimeUs

        fun advanceTo(timeUs: Long) {
            if (firstSampleTimeUs == C.TIME_UNSET) firstSampleTimeUs = timeUs
            latestSampleTimeUs = maxOf(latestSampleTimeUs, timeUs)
        }

        fun release(output: TrackOutput) {
            target = output
            events.forEach { event -> event(output) }
            events.clear()
        }

        fun discardSamples() {
            events.clear()
            firstSampleTimeUs = C.TIME_UNSET
            latestSampleTimeUs = C.TIME_UNSET
            format?.let { held -> events += { output -> output.format(held) } }
        }

        override fun format(format: Format) {
            target?.let { return it.format(format) }
            this.format = format
            events += { output -> output.format(format) }
            maybeResolve(endOfInput = false)
        }

        override fun durationUs(durationUs: Long) {
            target?.let { return it.durationUs(durationUs) }
            events += { output -> output.durationUs(durationUs) }
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            target?.let { return it.sampleData(input, length, allowEndOfInput, sampleDataPart) }
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            hold(bytes.copyOf(read), sampleDataPart)
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            target?.let { return it.sampleData(data, length, sampleDataPart) }
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            hold(bytes, sampleDataPart)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            target?.let { return it.sampleMetadata(timeUs, flags, size, offset, cryptoData) }
            events += { output -> output.sampleMetadata(timeUs, flags, size, offset, cryptoData) }
            onSample(this, timeUs)
        }

        private fun hold(bytes: ByteArray, sampleDataPart: Int) {
            heldBytes += bytes.size
            events += { output -> output.sampleData(ParsableByteArray(bytes), bytes.size, sampleDataPart) }
            // Sample data can arrive without a later format or sample callback, as in a damaged stream.
            if (heldBytes >= maximumHeldBytes) maybeResolve(endOfInput = false)
        }
    }
}

/** Media time a track may stay without a format once audio and video formats exist, as in live playback. */
internal const val FORMAT_WAIT_US: Long = 1_000_000L

/** Bounds what is held while required formats are still missing; Media3's minimum buffer target. */
internal const val MAXIMUM_FORMAT_WAIT_HELD_BYTES: Long = 200L * C.DEFAULT_BUFFER_SEGMENT_SIZE

private val REQUIRED_TRACK_TYPES = intArrayOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
