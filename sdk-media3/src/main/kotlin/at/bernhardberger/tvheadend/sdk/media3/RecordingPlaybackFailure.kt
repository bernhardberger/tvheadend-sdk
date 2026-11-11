@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.decoder.DecoderException
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
     * Shows Media3's error-code name and, when content-checked, a parser or decoder message.
     *
     * Decoder initialization and query errors carry only the code name. Other parsing or decoding
     * messages must pass a conservative content check: letters, digits, spaces and
     * `. , ; : - _ ( ) [ ] ' " # + ! ?`, no dot between two letters or digits, and colons followed
     * by a space or message end.
     * This mostly admits parser messages; a single-label hostname cannot be recognized by text
     * alone. Removes control, format and line-separator characters before checking. Bounds the
     * complete line to 160 UTF-16 code units, including `…`, without splitting a surrogate pair.
     */
    public val detail: String,
) {
    /** Shows [category] without the display-only [detail]. */
    override public fun toString(): String = "RecordingPlaybackFailure(category=$category)"
}

private const val MAX_RECORDING_FAILURE_DETAIL_LENGTH: Int = 160
private const val MAX_RECORDING_FAILURE_CAUSES: Int = 32
private const val RECORDING_FAILURE_PUNCTUATION: String = ".,;:-_()[]'\"#+!?"
private val HOST_OR_PACKAGE_PATTERN: Regex = Regex("[\\p{L}\\p{N}]\\.[\\p{L}\\p{N}]")

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
    val exception = generateSequence(error.cause) { it.cause }.take(MAX_RECORDING_FAILURE_CAUSES)
        .firstOrNull {
            when (code) {
                in 3000..3999 -> it is ParserException
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> false
                in 4000..4999 -> it is DecoderException
                else -> false
            }
        }
    val message = exception?.message?.let { raw ->
        // Media3 1.11.1 ParserException.getMessage appends this instance's exact metadata suffix.
        if (exception is ParserException) {
            raw.removeSuffix(" {contentIsMalformed=${exception.contentIsMalformed}, dataType=${exception.dataType}}")
        } else raw
    }?.filterNot {
        when (Character.getType(it)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
            else -> it.isISOControl()
        }
    }?.takeIf(::hasDisplayMessageContent)
    val detail = error.errorCodeName + if (message == null) "" else ": $message"
    if (detail.length <= MAX_RECORDING_FAILURE_DETAIL_LENGTH) {
        return RecordingPlaybackFailure(recordingPlaybackFailureCategory(code), detail)
    }
    var end = MAX_RECORDING_FAILURE_DETAIL_LENGTH - 1
    if (detail[end - 1].isHighSurrogate() && detail[end].isLowSurrogate()) end--
    return RecordingPlaybackFailure(recordingPlaybackFailureCategory(code), detail.take(end) + "…")
}

private fun hasDisplayMessageContent(message: String): Boolean =
    message.isNotBlank() && message.codePoints().allMatch {
        Character.isLetterOrDigit(it) || it == ' '.code || (it < 128 && it.toChar() in RECORDING_FAILURE_PUNCTUATION)
    } && !HOST_OR_PACKAGE_PATTERN.containsMatchIn(message) && message.indices.none {
        message[it] == ':' && it + 1 < message.length && message[it + 1] != ' '
    }
