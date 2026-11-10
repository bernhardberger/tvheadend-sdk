package at.bernhardberger.tvheadend.sdk.core

/** Identifies channel or tag artwork stored by Tvheadend or served over HTTP(S). */
public sealed interface ChannelIcon {
    /** Identifies artwork loaded through [ArtworkLoader]. */
    public data class ImageCache(public val artworkId: ArtworkId) : ChannelIcon {
        override fun toString(): String = "ChannelIcon.ImageCache(<redacted>)"
    }

    /** Preserves a remote URL for the application to load and cache. */
    public class RemoteUrl private constructor(
        /** Contains the original HTTP(S) URL; do not include it in logs or diagnostics. */
        public val url: String,
    ) : ChannelIcon {
        override fun equals(other: Any?): Boolean = other is RemoteUrl && url == other.url

        override fun hashCode(): Int = url.hashCode()

        override fun toString(): String = "ChannelIcon.RemoteUrl(<redacted>)"

        internal companion object {
            fun parse(value: String): RemoteUrl? = if (isRemoteArtworkUrl(value)) RemoteUrl(value) else null
        }
    }

    public companion object {
        /**
         * Parses an image-cache selector or an absolute HTTP(S) URL with a server host.
         *
         * Returns null for absent values, invalid authorities, control characters and other
         * schemes. Path and query spelling, including unescaped characters, is preserved
         * without trimming or resolving it. The SDK never fetches [RemoteUrl]; the
         * application owns its network policy and cache. Programme and recording images use
         * [ArtworkId.parse] and [RemoteArtworkUrl.parse].
         */
        @JvmStatic
        public fun parse(selector: String?): ChannelIcon? {
            if (selector == null) return null
            ArtworkId.parse(selector)?.let { return ImageCache(it) }
            return RemoteUrl.parse(selector)
        }
    }
}
