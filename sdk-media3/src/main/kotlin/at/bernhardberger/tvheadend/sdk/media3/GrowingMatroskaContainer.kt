@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MimeTypes
import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease

/** Keeps Matroska parsing in Media3 and seeking at validated Cluster boundaries. */
internal object GrowingMatroskaContainer : GrowingContainerAdapter<GrowingMatroskaExtent> {
    override val deliveryUnitBytes: Int = 1
    override val needsFormatlessTrackFilter: Boolean = false // Tracks declares formats before samples.
    override val supportsObservedIndex: Boolean = false // Sample read positions are not Cluster starts.
    override val seekValidatedVideoMimeTypes: Set<String> = setOf(
        MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MPEG2,
    )

    override fun createExtractor(): Extractor =
        VideoScanExtractor(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED))

    override fun alignIndexPosition(position: Long): Long = position

    override fun seekFallbackPosition(extent: GrowingMatroskaExtent): Long = extent.firstClusterPosition

    override fun createExtentProbe(lease: GrowingRecordingFileLease): GrowingRecordingExtentProbe<GrowingMatroskaExtent> =
        GrowingMatroskaExtentProbe(lease)

    override fun isContinuous(previous: GrowingMatroskaExtent, next: GrowingMatroskaExtent): Boolean =
        previous.origin == next.origin && previous.firstClusterPosition == next.firstClusterPosition &&
            previous.timecodeScaleNs == next.timecodeScaleNs && next.sizeBytes >= previous.sizeBytes &&
            next.durationUs >= previous.durationUs

    override fun createBinarySearchSeeker(extent: GrowingMatroskaExtent): BinarySearchSeeker =
        MatroskaBinarySearchSeeker(extent)
}

private class MatroskaBinarySearchSeeker(extent: GrowingMatroskaExtent) : BinarySearchSeeker(
    DefaultSeekTimestampConverter(),
    MatroskaTimestampSeeker(extent),
    extent.durationUs,
    extent.timeUs(extent.origin),
    extent.durationUs + 1L,
    extent.firstClusterPosition,
    extent.sizeBytes,
    1L,
    1,
) {

    private class MatroskaTimestampSeeker(private val extent: GrowingMatroskaExtent) : TimestampSeeker {
        private var floor = MatroskaCluster(extent.firstClusterPosition, extent.firstClusterEndPosition, extent.origin)
        private var ceilingTimeUs = extent.durationUs + 1L
        private var steps = 0
        // Allow one coarse cluster interval beyond the sampled extent, without integer overflow.
        private val maximumTimecode = extent.lastClusterTimecode +
            minOf(Long.MAX_VALUE - extent.lastClusterTimecode, (10_000_000_000L / extent.timecodeScaleNs).coerceAtLeast(1L))

        override fun onSeekFinished() {
            floor = MatroskaCluster(extent.firstClusterPosition, extent.firstClusterEndPosition, extent.origin)
            ceilingTimeUs = extent.durationUs + 1L
            steps = 0
        }

        override fun searchForTimestamp(input: ExtractorInput, targetTimestamp: Long): TimestampSearchResult {
            if (++steps > 64) return TimestampSearchResult.targetFoundResult(floor.position)
            val start = input.position
            // Do not rediscover the floor when Media3 interpolates within that same Cluster.
            val scanStart = maxOf(start, floor.endPosition)
            val candidate = scanCluster(input, scanStart)
            if (candidate == null) {
                return if (start <= floor.endPosition) {
                    TimestampSearchResult.targetFoundResult(floor.position)
                } else {
                    TimestampSearchResult.overestimatedResult(ceilingTimeUs, start)
                }
            }
            val timeUs = extent.timeUs(candidate.timecode)
            if (timeUs <= targetTimestamp) {
                floor = candidate
                return TimestampSearchResult.underestimatedResult(timeUs, candidate.position)
            }
            if (start <= floor.endPosition) return TimestampSearchResult.targetFoundResult(floor.position)
            ceilingTimeUs = timeUs
            // A raw ceiling is safe: Media3 resumes only at the validated floor on convergence.
            // Never return NO_TIMESTAMP, which would resume extraction at a raw search offset.
            return TimestampSearchResult.overestimatedResult(timeUs, start)
        }

        private fun scanCluster(input: ExtractorInput, scanStart: Long): MatroskaCluster? {
            if (scanStart >= extent.sizeBytes) return null
            val gap = scanStart - input.position
            if (gap > MATROSKA_MAX_SEARCH_BYTES) return null
            // TimestampSeeker may consume input; Media3 seeks back to our validated floor.
            // skipFully uses DefaultExtractorInput's bounded scratch buffer, unlike advancing peek.
            input.skipFully(gap.toInt())
            val chunkSize = 16 * 1024
            val overlap = 256
            val bytes = ByteArray(chunkSize + overlap)
            var base = scanStart
            var retained = 0
            val end = minOf(extent.sizeBytes, scanStart + MATROSKA_MAX_SEARCH_BYTES + overlap)
            while (base + retained < end) {
                val count = minOf(chunkSize.toLong(), end - base - retained).toInt()
                input.readFully(bytes, retained, count)
                val limit = retained + count
                findMatroskaCluster(
                    bytes, base, extent.sizeBytes, 0, limit, extent.trackNumbers,
                    extent.origin, maximumTimecode, extent.timecodeScaleNs,
                )?.takeIf { it.position <= extent.lastClusterPosition }?.let { return it }
                retained = minOf(overlap, limit)
                bytes.copyInto(bytes, 0, limit - retained, limit)
                base += limit - retained
            }
            return null
        }
    }
}
