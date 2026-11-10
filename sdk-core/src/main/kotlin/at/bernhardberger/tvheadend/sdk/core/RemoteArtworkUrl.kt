package at.bernhardberger.tvheadend.sdk.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Programme or recording artwork that Tvheadend names by an absolute HTTP(S) URL instead of an
 * image-cache selector, as it does for XMLTV images while its image cache is off.
 *
 * The SDK never fetches it; the application owns its network policy and cache. Channel and tag
 * icons use [ChannelIcon.RemoteUrl] for the same case.
 */
public class RemoteArtworkUrl private constructor(
    /** Contains the original HTTP(S) URL; do not include it in logs or diagnostics. */
    public val url: String,
) {
    override fun equals(other: Any?): Boolean = other is RemoteArtworkUrl && url == other.url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = "RemoteArtworkUrl(<redacted>)"

    public companion object {
        /**
         * Parses an absolute HTTP(S) URL with a server host.
         *
         * Returns null for absent values, image-cache selectors, invalid authorities, control
         * characters and other schemes. Path and query spelling, including unescaped
         * characters, is preserved without trimming or resolving it.
         */
        @JvmStatic
        public fun parse(value: String?): RemoteArtworkUrl? =
            value?.takeIf(::isRemoteArtworkUrl)?.let(::RemoteArtworkUrl)
    }
}

/** Whether [value] is an absolute HTTP(S) URL with a valid server authority. */
internal fun isRemoteArtworkUrl(value: String): Boolean {
    if (value.any { it.isISOControl() }) return false
    val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return false
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: value.length
    val uri = try {
        // Tvheadend may leave names unescaped in paths and queries.
        // Let the JDK validate the authority; preserve the rest for the app loader.
        URI(value.substring(0, authorityEnd)).parseServerAuthority()
    } catch (_: URISyntaxException) {
        return false
    }
    if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
        return false
    }
    return !uri.host.isNullOrEmpty() && uri.port in -1..65535
}
