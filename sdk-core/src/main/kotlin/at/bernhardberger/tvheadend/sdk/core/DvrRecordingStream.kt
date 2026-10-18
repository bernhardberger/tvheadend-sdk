package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType
import kotlin.time.Duration

/**
 * One ordered recording-file stream observation, available without opening playback.
 * [type] is an open codec set; consumers must handle unknown and future values.
 * [language] is untrusted display-only server text. Dimensions are independent observations.
 *
 * [frameDuration] is the positive video frame duration the server declared when it wrote the file,
 * or null when it is absent or zero. It is a display hint with the same caveats as
 * `TvheadendPlaybackCoordinator.declaredVideoFrameDuration`: Tvheadend may have guessed it, and
 * interlaced content reports the frame, not field, duration.
 */
@ExperimentalTvheadendApi
public data class DvrRecordingStream(
    public val type: SubscriptionStreamType = SubscriptionStreamType.UNKNOWN,
    public val language: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
    public val frameDuration: Duration? = null,
) {
    override fun toString(): String = "DvrRecordingStream(<redacted>)"
}
