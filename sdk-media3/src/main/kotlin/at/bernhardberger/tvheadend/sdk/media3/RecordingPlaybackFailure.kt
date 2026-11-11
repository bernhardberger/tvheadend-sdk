@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.DecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/** Groups recording failures by Media3 error code for application presentation. */
@ExperimentalTvheadendApi
public enum class RecordingPlaybackFailureCategory {
    /** Identifies a media parsing error, including an unsupported container or manifest. */
    MALFORMED_MEDIA,

    /** Identifies decoder initialization, decoder query, or unsupported decoding capabilities. */
    UNSUPPORTED_FORMAT,

    /** Identifies an I/O or network error. */
    IO,

    /** Identifies any other error, including decoding failure or reclaimed decoder resources. */
    OTHER,
}

/**
 * Describes the installed recording's playback failure for display.
 *
 * Uses identity equality so separate failure events remain distinct. [detail] is display-only;
 * [toString] includes only [category]. Avoid logging or using [detail] as a diagnostic or key.
 */
@ExperimentalTvheadendApi
public class RecordingPlaybackFailure internal constructor(
    /** Groups the failure using only Media3's error code. */
    public val category: RecordingPlaybackFailureCategory,
    /**
     * Shows Media3's error-code name and, when safe, its parser or decoder exception message.
     *
     * Includes messages only for parsing and decoding codes. Omits URI or path-like messages,
     * strips control characters, and bounds the complete line to 160 characters, including `…`.
     */
    public val detail: String,
) {
    /** Shows [category] without the display-only [detail]. */
    override public fun toString(): String = "RecordingPlaybackFailure(category=$category)"
}

private const val MAX_RECORDING_FAILURE_DETAIL_LENGTH: Int = 160
private const val MAX_RECORDING_FAILURE_CAUSES: Int = 32

internal fun recordingPlaybackFailure(error: PlaybackException): RecordingPlaybackFailure {
    val code = error.errorCode
    val category = when (code) {
        in 3000..3999 -> RecordingPlaybackFailureCategory.MALFORMED_MEDIA
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> RecordingPlaybackFailureCategory.UNSUPPORTED_FORMAT
        in 2000..2999 -> RecordingPlaybackFailureCategory.IO
        else -> RecordingPlaybackFailureCategory.OTHER
    }
    val message = generateSequence(error.cause) { it.cause }.take(MAX_RECORDING_FAILURE_CAUSES)
        .firstOrNull {
            when (code) {
                in 3000..3999 -> it is ParserException
                in 4000..4999 -> it is DecoderException || it is DecoderInitializationException
                else -> false
            }
        }?.message?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        // Conservatively omit every slash-bearing message, including ambiguous relative paths.
        ?.takeUnless { it.contains("://") || '/' in it || '\\' in it }
        ?.takeIf { it.isNotBlank() }
    val detail = error.errorCodeName + if (message == null) "" else ": $message"
    val bounded = if (detail.length <= MAX_RECORDING_FAILURE_DETAIL_LENGTH) detail else
        detail.take(MAX_RECORDING_FAILURE_DETAIL_LENGTH - 1) + "…"
    return RecordingPlaybackFailure(category, bounded)
}
