@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
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
     * Shows Media3's error-code name and, for parsing errors, the parser's message.
     *
     * Media3 builds parser messages from values in the recording's bytes. Other errors, including
     * I/O and network errors whose messages may name the server, carry only the code name.
     * Removes control and line-separator characters and bounds the complete line to 160 UTF-16
     * code units, including `…`, without splitting a surrogate pair.
     */
    public val detail: String,
) {
    /** Shows [category] without the display-only [detail]. */
    override public fun toString(): String = "RecordingPlaybackFailure(category=$category)"
}

private const val MAX_RECORDING_FAILURE_DETAIL_LENGTH: Int = 160
private const val MAX_RECORDING_FAILURE_CAUSES: Int = 32

internal fun recordingPlaybackFailureCategory(code: Int): RecordingPlaybackFailureCategory =
    when (code) {
        in 3000..3999 -> RecordingPlaybackFailureCategory.MALFORMED_MEDIA
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> RecordingPlaybackFailureCategory.UNSUPPORTED_FORMAT
        in 2000..2999 -> RecordingPlaybackFailureCategory.IO
        else -> RecordingPlaybackFailureCategory.OTHER
    }

internal fun recordingPlaybackFailure(error: PlaybackException): RecordingPlaybackFailure {
    val code = error.errorCode
    val parserError = if (code in 3000..3999) {
        generateSequence(error.cause) { it.cause }.take(MAX_RECORDING_FAILURE_CAUSES)
            .filterIsInstance<ParserException>().firstOrNull()
    } else null
    val message = parserError?.message
        // Media3 1.11.1 ParserException.getMessage appends this instance's metadata suffix.
        ?.removeSuffix(" {contentIsMalformed=${parserError.contentIsMalformed}, dataType=${parserError.dataType}}")
        ?.filterNot {
            when (Character.getType(it)) {
                Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
                else -> it.isISOControl()
            }
        }?.takeIf { it.isNotBlank() }
    val detail = error.errorCodeName + if (message == null) "" else ": $message"
    if (detail.length <= MAX_RECORDING_FAILURE_DETAIL_LENGTH) {
        return RecordingPlaybackFailure(recordingPlaybackFailureCategory(code), detail)
    }
    var end = MAX_RECORDING_FAILURE_DETAIL_LENGTH - 1
    if (detail[end - 1].isHighSurrogate() && detail[end].isLowSurrogate()) end--
    return RecordingPlaybackFailure(recordingPlaybackFailureCategory(code), detail.take(end) + "…")
}
