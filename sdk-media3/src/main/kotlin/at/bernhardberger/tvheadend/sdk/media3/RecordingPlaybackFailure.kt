@file:androidx.media3.common.util.UnstableApi
@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.media3

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.upstream.Loader
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/** Groups recording failures by Media3 error code and loading cause for application presentation. */
@ExperimentalTvheadendApi
public enum class RecordingPlaybackFailureCategory {
    /** Identifies media parsing errors or unexpected exceptions during loading. */
    MALFORMED_MEDIA,

    /** Identifies decoder initialization, decoder query, or unsupported decoding capabilities. */
    UNSUPPORTED_FORMAT,

    /** Identifies other I/O or network errors, including loading failures caused by an [Error]. */
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
    /** Groups the failure using Media3's error code and unexpected loading cause. */
    public val category: RecordingPlaybackFailureCategory,
    /**
     * Shows Media3's error-code name and a parser or unexpected loading message.
     *
     * Parsing errors add the parser's message. [PlaybackException.ERROR_CODE_IO_UNSPECIFIED] with
     * [androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException] adds its cause's message
     * when that cause is an [Exception], and uses [RecordingPlaybackFailureCategory.MALFORMED_MEDIA].
     * This message comes from whatever code threw during loading; it is display-only.
     * Other I/O and network errors carry only the code name.
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
    val unexpectedCause = if (code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
        generateSequence(error.cause) { it.cause }.take(MAX_RECORDING_FAILURE_CAUSES)
            .filterIsInstance<Loader.UnexpectedLoaderException>()
            .mapNotNull { it.cause as? Exception }.firstOrNull()
    } else null
    val category = if (unexpectedCause != null) {
        RecordingPlaybackFailureCategory.MALFORMED_MEDIA
    } else recordingPlaybackFailureCategory(code)
    val rawMessage = if (parserError != null) {
        // Media3 1.11.1 ParserException.getMessage appends this instance's metadata suffix.
        parserError.message
            ?.removeSuffix(" {contentIsMalformed=${parserError.contentIsMalformed}, dataType=${parserError.dataType}}")
    } else unexpectedCause?.message
    val message = rawMessage
        ?.filterNot {
            when (Character.getType(it)) {
                Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
                else -> it.isISOControl()
            }
        }?.takeIf { it.isNotBlank() }
    val detail = error.errorCodeName + if (message == null) "" else ": $message"
    if (detail.length <= MAX_RECORDING_FAILURE_DETAIL_LENGTH) {
        return RecordingPlaybackFailure(category, detail)
    }
    var end = MAX_RECORDING_FAILURE_DETAIL_LENGTH - 1
    if (detail[end - 1].isHighSurrogate() && detail[end].isLowSurrogate()) end--
    return RecordingPlaybackFailure(category, detail.take(end) + "…")
}
