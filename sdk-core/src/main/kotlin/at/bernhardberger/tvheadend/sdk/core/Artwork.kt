package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.cache.CacheNamespace
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

/** Positive Tvheadend image-cache identifier. */
@JvmInline
public value class ArtworkId(public val value: Int) {
    init {
        require(value > 0) { "Artwork ID must be positive" }
    }

    override fun toString(): String = "ArtworkId(<redacted>)"

    public companion object {
        /**
         * Parses a Tvheadend image-cache selector such as `imagecache/12`; a leading `/` is tolerated.
         *
         * Returns null for absent, external (for example `http://` or `https://`), non-image-cache,
         * non-decimal, zero, or out-of-range selectors.
         */
        @JvmStatic
        public fun parse(selector: String?): ArtworkId? {
            val normalized = selector?.removePrefix("/") ?: return null
            if (!normalized.startsWith(IMAGE_CACHE_SELECTOR_PREFIX)) {
                return null
            }
            val value = normalized.removePrefix(IMAGE_CACHE_SELECTOR_PREFIX)
            if (value.isEmpty() || value.any { it !in '0'..'9' }) {
                return null
            }
            return value.toIntOrNull()?.takeIf { it > 0 }?.let(::ArtworkId)
        }
    }
}

private const val IMAGE_CACHE_SELECTOR_PREFIX = "imagecache/"

/** Immutable encoded artwork bytes returned by the authenticated session. */
public class ArtworkContent internal constructor(
    private val bytes: ByteArray,
) {
    /** Encoded byte count. */
    public val sizeBytes: Int
        get() = bytes.size

    /** Opens an independent stream over the encoded bytes. */
    public fun openStream(): InputStream = ByteArrayInputStream(bytes)

    override fun toString(): String = "ArtworkContent(<redacted>)"

    public companion object {
        /** Creates immutable encoded content by copying [bytes]. */
        public fun create(bytes: ByteArray): ArtworkContent = ArtworkContent(bytes.copyOf())
    }
}

/** Typed result of loading encoded artwork through the active session. */
public sealed interface ArtworkLoadResult {
    /** Encoded artwork is available for decoding. */
    public class Available(public val content: ArtworkContent) : ArtworkLoadResult {
        override fun toString(): String = "ArtworkLoadResult.Available(<redacted>)"
    }

    /**
     * Artwork could not be loaded. Access denial means recorder access is missing; file
     * unavailability means the image-cache file cannot be served. A malformed reply implies
     * neither connection loss nor automatic retry.
     */
    public data class Unavailable(public val failure: TvheadendFailure) : ArtworkLoadResult {
        override fun toString(): String = "ArtworkLoadResult.Unavailable(failure=$failure)"
    }
}

/**
 * Opaque reference to one artwork entry in the persistent cache of the profile whose observation
 * [ArtworkLoader.cachedArtwork] created it from.
 *
 * It names neither a path, an endpoint nor the profile; equality is by captured profile and
 * artwork identity. It is not load authority for the server.
 */
@ExperimentalTvheadendApi
public class CachedArtworkReference internal constructor(
    internal val namespace: CacheNamespace,
    internal val artworkId: ArtworkId,
    /**
     * Opaque restart-stable image key, equal to [ArtworkLoader.cacheKey] for the same profile and
     * artwork. It must not be logged or interpreted as a path or namespace.
     */
    public val cacheKey: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is CachedArtworkReference && namespace == other.namespace && artworkId == other.artworkId

    override fun hashCode(): Int = 31 * namespace.hashCode() + artworkId.hashCode()

    override fun toString(): String = "CachedArtworkReference(<redacted>)"

    internal companion object {
        fun create(namespace: CacheNamespace, artworkId: ArtworkId): CachedArtworkReference =
            CachedArtworkReference(namespace, artworkId, artworkCacheKey(namespace, artworkId))
    }
}

/** Restart-stable image key for [artworkId] in the persistent cache [namespace]. */
internal fun artworkCacheKey(namespace: CacheNamespace, artworkId: ArtworkId): String =
    UUID.nameUUIDFromBytes(
        "tvheadend-artwork:${namespace.value}:${artworkId.value}".toByteArray(Charsets.UTF_8),
    ).toString()

/**
 * Loads encoded image-cache artwork on the currently bound connection generation.
 *
 * Tvheadend requires recorder access for authenticated image-cache files and reports
 * [TvheadendFailure.ACCESS_DENIED] when that permission is unavailable.
 */
public interface ArtworkLoader {
    /**
     * Opaque restart-stable image key when persistence is enabled and [currentSession] is current.
     * Returns null otherwise. Image integrations may retain this key with their request model;
     * it is not load authority and must not be logged or interpreted as a path or namespace.
     */
    public fun cacheKey(currentSession: CurrentSessionObservation, artworkId: ArtworkId): String? = null

    /** Loads [artworkId] without exposing an endpoint, credential, or protocol file selector. */
    public suspend fun loadArtwork(
        currentSession: CurrentSessionObservation,
        artworkId: ArtworkId,
    ): ArtworkLoadResult

    /**
     * Captures [artworkId], as named by [observation], in the persistent artwork cache of the
     * profile whose data [observation] contains, in any session state.
     *
     * Artwork ids are server-scoped, so the reference is bound to [observation]'s profile, never
     * to whichever profile is active when the model is mapped. Returns null without a cache
     * policy, when [observation] carries no profile data, or when its profile is not the active
     * one; an observation becomes usable again once its profile is active again. The default
     * returns null.
     */
    @ExperimentalTvheadendApi
    public fun cachedArtwork(
        observation: SessionObservation,
        artworkId: ArtworkId,
    ): CachedArtworkReference? = null

    /**
     * Reads [reference] from the persistent artwork cache only, never from the server, and neither
     * stores, refreshes nor removes entries beyond what a normal cache hit does.
     *
     * Bytes are returned only while the profile that created [reference] is still active:
     * otherwise the result is [TvheadendFailure.OBSERVATION_EXPIRED], or
     * [TvheadendFailure.CONNECTION_CHANGED] when the profile changed during the read. An explicit
     * connect or retry briefly leaves no profile active, even when the same profile resumes, and
     * disconnect leaves none until the next connect; loads in between fail the same way. An absent,
     * expired, corrupt or cleared entry is [TvheadendFailure.NOT_READY]; it implies nothing about
     * the server, and [loadArtwork] may still succeed once the session is ready. The default
     * returns [TvheadendFailure.NOT_SUPPORTED].
     */
    @ExperimentalTvheadendApi
    public suspend fun loadCachedArtwork(reference: CachedArtworkReference): ArtworkLoadResult =
        ArtworkLoadResult.Unavailable(TvheadendFailure.NOT_SUPPORTED)
}
