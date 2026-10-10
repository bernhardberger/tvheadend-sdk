@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.protobuf.ProtoBuf

private const val NAMESPACE_HEX_LENGTH = 32

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
    return CacheNamespace("v2-${digest.toHexString().take(NAMESPACE_HEX_LENGTH)}")
}
