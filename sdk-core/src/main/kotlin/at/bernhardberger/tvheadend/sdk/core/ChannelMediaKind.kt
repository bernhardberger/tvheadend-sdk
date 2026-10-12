package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/** Server service classification. Consumers must retain a fallback for future values. */
@ExperimentalTvheadendApi
public class ChannelMediaKind private constructor(private val label: String) {
    override fun toString(): String = label

    /** Known service classifications. */
    public companion object {
        /** The service can deliver television video. */
        @JvmField
        public val TV: ChannelMediaKind = ChannelMediaKind("TV")
        /** The server classifies the service as radio. */
        @JvmField
        public val RADIO: ChannelMediaKind = ChannelMediaKind("RADIO")
        /** The server classifies the service as neither TV nor radio, possibly because it is unscanned. */
        @JvmField
        public val OTHER: ChannelMediaKind = ChannelMediaKind("OTHER")
        /** Classification evidence is insufficient or unrecognized. */
        @JvmField
        public val UNKNOWN: ChannelMediaKind = ChannelMediaKind("UNKNOWN")
    }
}

/** Classification from service content alone; descriptive labels are not classification evidence. */
@ExperimentalTvheadendApi
public val ChannelService.mediaKind: ChannelMediaKind
    get() = when (content) {
        0L -> ChannelMediaKind.OTHER
        1L -> ChannelMediaKind.TV
        2L -> ChannelMediaKind.RADIO
        else -> ChannelMediaKind.UNKNOWN
    }

/**
 * Catalog classification, not the selected tracks of a playing item.
 *
 * Any TV service takes precedence over radio. Otherwise any radio service wins; OTHER requires
 * a non-empty list consisting entirely of OTHER services. Missing, empty or remaining unknown
 * evidence yields UNKNOWN. Consumers must retain a fallback for future classifications.
 */
@ExperimentalTvheadendApi
public val Channel.mediaKind: ChannelMediaKind
    get() {
        val kinds = services?.map { it.mediaKind }.orEmpty()
        return when {
            ChannelMediaKind.TV in kinds -> ChannelMediaKind.TV
            ChannelMediaKind.RADIO in kinds -> ChannelMediaKind.RADIO
            kinds.isNotEmpty() && kinds.all { it == ChannelMediaKind.OTHER } -> ChannelMediaKind.OTHER
            else -> ChannelMediaKind.UNKNOWN
        }
    }
