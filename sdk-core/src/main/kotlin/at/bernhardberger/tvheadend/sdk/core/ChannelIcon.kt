package at.bernhardberger.tvheadend.sdk.core

import java.net.URI
import java.net.URISyntaxException

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
            fun parse(value: String): RemoteUrl? {
                if (value.any { it.isISOControl() }) return null
                val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
                val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
                    .takeIf { it >= 0 } ?: value.length
                val uri = try {
                    // Tvheadend may leave channel names unescaped in paths and queries.
                    // Let the JDK validate the authority; preserve the rest for the app loader.
                    URI(value.substring(0, authorityEnd)).parseServerAuthority()
                } catch (_: URISyntaxException) {
                    return null
                }
                if (!uri.scheme.equals("http", ignoreCase = true) &&
                    !uri.scheme.equals("https", ignoreCase = true)
                ) {
                    return null
                }
                if (uri.host.isNullOrEmpty() || uri.port !in -1..65535) return null
                return RemoteUrl(value)
            }
        }
    }

    public companion object {
        /**
         * Parses an image-cache selector or an absolute HTTP(S) URL with a server host.
         *
         * Returns null for absent values, invalid authorities, control characters and other
         * schemes. Path and query spelling, including unescaped characters, is preserved
         * without trimming or resolving it. The SDK never fetches [RemoteUrl]; the
         * application owns its network policy and cache. Programme and recording images still
         * use [ArtworkId.parse].
         */
        @JvmStatic
        public fun parse(selector: String?): ChannelIcon? {
            if (selector == null) return null
            ArtworkId.parse(selector)?.let { return ImageCache(it) }
            return RemoteUrl.parse(selector)
        }
    }
}
