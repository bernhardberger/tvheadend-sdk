package at.bernhardberger.tvheadend.sdk.media3

import at.bernhardberger.tvheadend.sdk.playback.SubscriptionStreamType

internal fun fixtureStreamType(label: String): SubscriptionStreamType = when (label) {
    "MPEG2_VIDEO" -> SubscriptionStreamType.MPEG2_VIDEO
    "H264" -> SubscriptionStreamType.H264
    "H265" -> SubscriptionStreamType.H265
    "AAC" -> SubscriptionStreamType.AAC
    "AC3" -> SubscriptionStreamType.AC3
    "EAC3" -> SubscriptionStreamType.EAC3
    "MPEG2_AUDIO" -> SubscriptionStreamType.MPEG2_AUDIO
    "DVB_SUBTITLE" -> SubscriptionStreamType.DVB_SUBTITLE
    "TEXT_SUBTITLE" -> SubscriptionStreamType.TEXT_SUBTITLE
    "TELETEXT" -> SubscriptionStreamType.TELETEXT
    "UNKNOWN" -> SubscriptionStreamType.UNKNOWN
    else -> error("Unsupported fixture stream type")
}
