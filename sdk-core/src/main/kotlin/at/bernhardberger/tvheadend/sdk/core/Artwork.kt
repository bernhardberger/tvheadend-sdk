package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Positive TVHeadend image-cache identifier. */
@JvmInline
public value class ArtworkId(public val value: Int) {
    init {
        require(value > 0) { "Artwork ID must be positive" }
    }

    override fun toString(): String = "ArtworkId(<redacted>)"

    public companion object {
        /**
         * Parses a TVHeadend image-cache selector such as `imagecache/12`; a leading `/` is tolerated.
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
 * Loads encoded image-cache artwork on the currently bound connection generation.
 *
 * TVHeadend requires recorder access for authenticated image-cache files and reports
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
}
