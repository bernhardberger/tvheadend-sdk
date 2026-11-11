@file:androidx.media3.common.util.UnstableApi
@file:OptIn(
    at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class,
    at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class,
)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MimeTypes
import androidx.media3.extractor.BinarySearchSeeker
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.GrowingTsBinarySearch
import androidx.media3.extractor.ts.TsExtractor
import at.bernhardberger.tvheadend.sdk.playback.GrowingRecordingFileLease
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Media3 owns TS parsing, PCR decoding, and binary-search timestamp seeking. */
internal object GrowingTsContainer : GrowingContainerAdapter<GrowingTsExtent> {
    override val deliveryUnitBytes: Int = GROWING_TS_PACKET_BYTES
    override val seekValidatedVideoMimeTypes: Set<String> = setOf(
        MimeTypes.VIDEO_MPEG2,
        MimeTypes.VIDEO_H264,
        MimeTypes.VIDEO_H265,
    )

    override fun createExtractor(): Extractor =
        VideoScanExtractor(TsExtractor(SubtitleParser.Factory.UNSUPPORTED))

    override fun alignIndexPosition(position: Long): Long =
        position / GROWING_TS_PACKET_BYTES * GROWING_TS_PACKET_BYTES

    override fun createExtentProbe(lease: GrowingRecordingFileLease): GrowingRecordingExtentProbe<GrowingTsExtent> =
        GrowingTsExtentProbe(lease)

    override fun isContinuous(previous: GrowingTsExtent, next: GrowingTsExtent): Boolean =
        next.firstPcr == previous.firstPcr && next.pcrPid == previous.pcrPid &&
            next.sizeBytes >= previous.sizeBytes && next.durationUs >= previous.durationUs

    override fun createBinarySearchSeeker(extent: GrowingTsExtent): BinarySearchSeeker =
        GrowingTsBinarySearch.create(
            extent.firstPcr, extent.durationUs, extent.sizeBytes, extent.pcrPid, GROWING_TS_SEARCH_BYTES,
        )
}

internal fun createGrowingTsExtractorsFactory(
    onSeekMap: (SeekMap) -> Unit = {},
    lease: GrowingRecordingFileLease? = null,
    probeDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ExtractorsFactory = createGrowingRecordingExtractorsFactory(GrowingTsContainer, onSeekMap, lease, probeDispatcher)

internal const val GROWING_TS_PACKET_BYTES: Int = 188
