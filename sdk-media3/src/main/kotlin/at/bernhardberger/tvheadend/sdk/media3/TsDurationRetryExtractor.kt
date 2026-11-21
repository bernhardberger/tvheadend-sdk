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
import androidx.media3.extractor.ts.TsExtractor

/**
 * Retries Media3's transport stream duration read with a larger search window when the default
 * window finds no PCR at one end of a finished recording.
 *
 * Media3's TS extractor reads the duration from the first PCR within its timestamp search window
 * at the start of the input and the last PCR within that window at its end. Without both, it
 * publishes an unseekable seek map without a duration, and every seek is refused. A remuxed
 * recording can start with more data of another stream than that window holds before its first
 * PCR. Only that result, with a known input length longer than the window searched and before any
 * sample was written, makes this decorator drop the attempt and start [createExtractor] with a
 * larger window from [RECORDING_TS_DURATION_RETRY_WINDOWS] from the start of the input. Every
 * other result passes through unchanged, so a recording whose duration the default window finds
 * pays nothing extra.
 *
 * A retry reads the input again up to its program tables before it searches both ends, so it uses
 * only a window at least as large as the span the first attempt read to declare its tracks. Each
 * retry therefore has its extractor read at most about three times its window: up to the tables
 * and one window at each end. A recording whose tables lie beyond every window keeps its
 * unseekable map without another read of that span. A recording without usable PCRs at all also
 * pays for the retries before it gets the same unseekable map.
 *
 * A dropped attempt has declared its tracks, which the next attempt declares again with the same
 * ids. A format a replacement attempt repeats before its first sample is not forwarded again; the
 * dropped attempt's other output is discarded. The window also sizes every step of the
 * extractor's binary seek search, which is why a retry takes the smallest window that fits instead
 * of the largest one.
 */
internal class TsDurationRetryExtractor(
    first: Extractor,
    private val createExtractor: (timestampSearchBytes: Int) -> Extractor,
) : Extractor {
    private val retryWindows = RECORDING_TS_DURATION_RETRY_WINDOWS
    private var extractor = first
    private var searchedBytes = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
    private var nextRetry = 0
    private var downstream: ExtractorOutput? = null
    private var attempt: AttemptOutput? = null
    private var tracksEnded = false
    private var inputLength = C.LENGTH_UNSET.toLong()
    private var tracksDeclaredAt = C.INDEX_UNSET.toLong()
    private var retryIndex = C.INDEX_UNSET
    private val forwardedFormats = HashMap<Int, Format>()

    override fun sniff(input: ExtractorInput): Boolean = extractor.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = extractor.sniffFailureDetails

    override fun getUnderlyingImplementation(): Extractor = extractor.underlyingImplementation

    override fun init(output: ExtractorOutput) {
        downstream = output
        start()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        inputLength = input.length
        val result = extractor.read(input, seekPosition)
        if (tracksEnded && tracksDeclaredAt == C.INDEX_UNSET.toLong()) tracksDeclaredAt = input.position
        if (attempt?.dropped != true) return result
        extractor.release()
        searchedBytes = retryWindows[retryIndex]
        nextRetry = retryIndex + 1
        extractor = createExtractor(searchedBytes)
        start()
        seekPosition.position = 0
        return Extractor.RESULT_SEEK
    }

    override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)

    override fun release() = extractor.release()

    private fun start() {
        val next = AttemptOutput(checkNotNull(downstream), replacement = attempt != null)
        attempt = next
        extractor.init(next)
    }

    /** The index of the window to retry with, or `null` to publish [seekMap]. */
    private fun retryWindow(seekMap: SeekMap, attempt: AttemptOutput): Int? {
        val timeless = !seekMap.isSeekable && seekMap.durationUs == C.TIME_UNSET && !attempt.sampleWritten
        val searchable = inputLength != C.LENGTH_UNSET.toLong() && inputLength > searchedBytes &&
            tracksDeclaredAt != C.INDEX_UNSET.toLong()
        if (!timeless || !searchable) return null
        return (nextRetry until retryWindows.size).firstOrNull { retryWindows[it] >= tracksDeclaredAt }
    }

    /** One extractor's view of the output; dropping it discards whatever that extractor still writes. */
    private inner class AttemptOutput(private val output: ExtractorOutput, val replacement: Boolean) : ExtractorOutput {
        var dropped = false
            private set
        var sampleWritten = false

        override fun track(id: Int, type: Int): TrackOutput = AttemptTrack(this, id, output.track(id, type))

        override fun endTracks() {
            if (dropped || tracksEnded) return
            tracksEnded = true
            output.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) {
            if (dropped) return
            val retry = retryWindow(seekMap, this)
            if (retry == null) {
                output.seekMap(seekMap)
            } else {
                retryIndex = retry
                dropped = true
            }
        }
    }

    private inner class AttemptTrack(
        private val attempt: AttemptOutput,
        private val id: Int,
        private val target: TrackOutput,
    ) : TrackOutput {
        private val discarding = DiscardingTrackOutput()
        private val sink: TrackOutput get() = if (attempt.dropped) discarding else target

        override fun format(format: Format) {
            if (attempt.dropped) return
            if (attempt.replacement && !attempt.sampleWritten && forwardedFormats[id] == format) return
            forwardedFormats[id] = format
            target.format(format)
        }

        override fun durationUs(durationUs: Long) = sink.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            attempt.sampleWritten = true
            return sink.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            attempt.sampleWritten = true
            sink.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            attempt.sampleWritten = true
            sink.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }
}

/** Search windows a finished TS recording's duration read retries with, in order. */
internal val RECORDING_TS_DURATION_RETRY_WINDOWS: List<Int> =
    listOf(4 * TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES, 16 * TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
