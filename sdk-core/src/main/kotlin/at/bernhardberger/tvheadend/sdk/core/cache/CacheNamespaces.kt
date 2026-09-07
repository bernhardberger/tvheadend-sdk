@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.protobuf.ProtoBuf

private const val NAMESPACE_HEX_LENGTH = 32
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * Derives the restart-stable [CacheNamespace] for one server profile.
 *
 * The namespace is the first [NAMESPACE_HEX_LENGTH] lowercase hex characters of the SHA-256
 * digest over a protobuf list of host, decimal port and username, prefixed with `v2-`.
 * Legacy delimiter-based namespaces are never read or migrated because their identity is ambiguous.
 * An anonymous profile uses an empty [username]. The password never participates in the digest.
 */
internal fun cacheNamespace(host: String, port: Int, username: String): CacheNamespace {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(ProtoBuf.encodeToByteArray(ListSerializer(String.serializer()), listOf(host, port.toString(), username)))
    val hex = buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
    return CacheNamespace("v2-${hex.substring(0, NAMESPACE_HEX_LENGTH)}")
}
