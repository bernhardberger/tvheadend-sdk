package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType

/**
 * One ordered recording-file stream observation, available without opening playback.
 * [type] is an open codec set; consumers must handle unknown and future values.
 * [language] is untrusted display-only server text. Dimensions are independent observations.
 */
@ExperimentalTvheadendApi
public data class DvrRecordingStream(
    public val type: SubscriptionStreamType = SubscriptionStreamType.UNKNOWN,
    public val language: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
) {
    override fun toString(): String = "DvrRecordingStream(<redacted>)"
}
