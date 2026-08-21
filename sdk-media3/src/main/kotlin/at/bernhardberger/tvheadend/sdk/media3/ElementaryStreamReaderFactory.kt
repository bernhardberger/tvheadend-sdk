@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.SubscriptionInfrastructureApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.MimeTypes
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DvbSubtitleReader
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.H262Reader
import androidx.media3.extractor.ts.H264Reader
import androidx.media3.extractor.ts.H265Reader
import androidx.media3.extractor.ts.MpegAudioReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStream
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType

internal sealed interface ReaderResult {
    class Supported(val reader: ElementaryStreamReader) : ReaderResult
    data object Unsupported : ReaderResult
}

internal fun createElementaryStreamReader(stream: SubscriptionStream): ReaderResult =
    when (stream.type) {
        SubscriptionStreamType.MPEG2_VIDEO -> ReaderResult.Supported(
            H262Reader(MimeTypes.VIDEO_MP2T),
        )
        SubscriptionStreamType.H264 -> ReaderResult.Supported(
            H264Reader(emptySeiReader(), false, true, MimeTypes.VIDEO_MP2T),
        )
        SubscriptionStreamType.H265 -> ReaderResult.Supported(
            H265Reader(emptySeiReader(), MimeTypes.VIDEO_MP2T),
        )
        SubscriptionStreamType.AC3,
        SubscriptionStreamType.EAC3,
        -> ReaderResult.Supported(
            Ac3Reader(stream.language, 0, MimeTypes.VIDEO_MP2T),
        )
        SubscriptionStreamType.MPEG2_AUDIO -> ReaderResult.Supported(
            MpegAudioReader(stream.language, 0, MimeTypes.VIDEO_MP2T),
        )
        SubscriptionStreamType.DVB_SUBTITLE -> createDvbReader(stream)
        SubscriptionStreamType.AAC,
        SubscriptionStreamType.TEXT_SUBTITLE,
        SubscriptionStreamType.TELETEXT,
        SubscriptionStreamType.UNKNOWN,
        -> ReaderResult.Unsupported
    }

private fun emptySeiReader(): SeiReader = SeiReader(emptyList(), MimeTypes.VIDEO_MP2T)

private fun createDvbReader(stream: SubscriptionStream): ReaderResult {
    val compositionId = stream.compositionId ?: return ReaderResult.Unsupported
    val ancillaryId = stream.ancillaryId ?: return ReaderResult.Unsupported
    if (compositionId !in 0L..0xffffL || ancillaryId !in 0L..0xffffL) {
        return ReaderResult.Unsupported
    }
    val initializationData = byteArrayOf(
        (compositionId ushr 8).toByte(),
        compositionId.toByte(),
        (ancillaryId ushr 8).toByte(),
        ancillaryId.toByte(),
    )
    return ReaderResult.Supported(
        DvbSubtitleReader(
            listOf(TsPayloadReader.DvbSubtitleInfo(stream.language ?: "und", 0, initializationData)),
            MimeTypes.VIDEO_MP2T,
        ),
    )
}
