package at.bernhardberger.tvheadend.sdk.core.cache

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val NAMESPACE_HEX_LENGTH = 32
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * Derives the restart-stable [CacheNamespace] for one server profile.
 *
 * The namespace is the first [NAMESPACE_HEX_LENGTH] lowercase hex characters of the SHA-256
 * digest over the UTF-8 bytes of `"$host:$port:$username"`. An anonymous profile uses an empty
 * [username]. The password never participates in the digest.
 */
internal fun cacheNamespace(host: String, port: Int, username: String): CacheNamespace {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$host:$port:$username".toByteArray(StandardCharsets.UTF_8))
    val hex = buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
    return CacheNamespace(hex.substring(0, NAMESPACE_HEX_LENGTH))
}
