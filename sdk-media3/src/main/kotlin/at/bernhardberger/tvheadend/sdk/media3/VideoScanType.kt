package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.Format
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/**
 * Scan type that a video bitstream declares for its pictures.
 *
 * The SDK reads it from the stream's sequence parameters, never from the picture height or the
 * frame rate:
 * - H.264: `frame_mbs_only_flag` of the sequence parameter set. A stream that allows field or
 *   MBAFF coding is [INTERLACED].
 * - H.265: `general_progressive_source_flag` and `general_interlaced_source_flag` of the base-layer
 *   sequence parameter set. Any other combination than exactly one of them is [UNKNOWN].
 * - MPEG-2: `progressive_sequence` of the sequence extension. MPEG-1 video is [UNKNOWN].
 */
@ExperimentalTvheadendApi
public enum class VideoScanType {
    /** The stream declares progressive pictures. */
    PROGRESSIVE,

    /** The stream declares interlaced content, coded as fields or as interlaced frames. */
    INTERLACED,

    /** The stream declares no unambiguous scan type, or the SDK did not inspect it. */
    UNKNOWN,
}

/**
 * Returns the scan type the SDK detected for the video stream this format describes.
 *
 * SDK live, recording and growing recording sources attach the value to their video formats.
 * Every other format, including audio, text and non-SDK video formats, returns
 * [VideoScanType.UNKNOWN]. Read it from the format being decoded, such as `ExoPlayer.videoFormat`
 * or `AnalyticsListener.onVideoInputFormatChanged`. Formats in `Tracks` describe only the start
 * of a stream.
 *
 * The SDK inspects in-band parameter sets in live streams and all recording containers, including
 * TS, MPEG-PS, MKV and MP4. Codec setup supplies the initial value until then; unusable or ambiguous
 * declarations give [VideoScanType.UNKNOWN]. For H.264 and H.265, the value follows the parameter
 * set a picture activates, not an inactive set merely announced in the stream. The SDK issues a
 * new format before the first sample with a changed scan type, for example when an advertisement
 * switches 1080i to 720p. After a recording seek, it uses post-seek parameter sets or the current
 * codec setup, not evidence retained from before the seek. Live timeshift seeks and discontinuities
 * retain parameter sets from committed samples already received by the decoder until new ones arrive;
 * declarations in discarded, unfinished samples do not carry over.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@ExperimentalTvheadendApi
public fun Format.videoScanType(): VideoScanType =
    metadata?.getFirstEntryOfType(VideoScanMetadata::class.java)?.scanType ?: VideoScanType.UNKNOWN
