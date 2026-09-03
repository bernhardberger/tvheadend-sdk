package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.IOException
import java.util.UUID
import java.util.WeakHashMap
import okio.FileSystem
import okio.buffer
import okio.source

/** Opaque Coil model for one authenticated TVHeadend image-cache entry. */
public class TvheadendArtwork private constructor(
    internal val session: TvheadendSession,
    internal val currentSession: CurrentSessionObservation,
    internal val id: ArtworkId,
) {
    override fun toString(): String = "TvheadendArtwork(<redacted>)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is TvheadendArtwork &&
            session === other.session &&
            currentSession === other.currentSession &&
            id == other.id

    override fun hashCode(): Int {
        var result = System.identityHashCode(session)
        result = 31 * result + System.identityHashCode(currentSession)
        return 31 * result + id.hashCode()
    }

    public companion object {
        /**
         * Creates an authenticated artwork model from an HTSP image-cache selector.
         *
         * Returns null for absent values, external URLs, and malformed or unsupported selectors.
         */
        public fun create(
            session: TvheadendSession,
            currentSession: CurrentSessionObservation,
            source: String?,
        ): TvheadendArtwork? {
            val normalized = source?.removePrefix("/") ?: return null
            if (!normalized.startsWith(ARTWORK_SELECTOR_PREFIX)) return null
            val value = normalized.removePrefix(ARTWORK_SELECTOR_PREFIX)
            if (value.isEmpty() || value.any { character -> character !in '0'..'9' }) return null
            val id = value.toIntOrNull()?.takeIf { parsed -> parsed > 0 } ?: return null
            return TvheadendArtwork(session, currentSession, ArtworkId(id))
        }
    }
}

/**
 * Registers the Coil memory key and fetcher for [TvheadendArtwork].
 *
 * Keys are opaque, process-local, and scoped to one connection-generation authority. Fetches use
 * a stream source without a disk cache key; this integration does not authorize persistent caching
 * of authenticated artwork. A decoder can request a source-lifetime temporary file, which Coil
 * deletes when it closes the source.
 */
public fun ComponentRegistry.Builder.addTvheadendArtwork(): ComponentRegistry.Builder = apply {
    add(TvheadendArtworkKeyer)
    add(TvheadendArtworkFetcherFactory)
}

/** Typed Coil failure that preserves the SDK artwork classification. */
public class TvheadendArtworkLoadException(
    public val failure: ArtworkFailure,
) : IOException("TVHeadend artwork load failed")

private data object TvheadendArtworkKeyer : Keyer<TvheadendArtwork> {
    override fun key(data: TvheadendArtwork, options: Options): String? = data.memoryCacheKey()
}

internal fun TvheadendArtwork.memoryCacheKey(): String? {
    if (!session.isCurrent(currentSession)) return null
    return TvheadendArtworkMemoryKeys.key(currentSession.generationIdentity, id)
}

private object TvheadendArtworkMemoryKeys {
    private val lock = Any()
    private val processNamespace = UUID.randomUUID().toString()
    private val generationKeys = WeakHashMap<SessionGenerationIdentity, Long>()
    private var nextGenerationKey = 0L

    internal fun key(generation: SessionGenerationIdentity, id: ArtworkId): String =
        synchronized(lock) {
            val generationKey = generationKeys.getOrPut(generation) { nextGenerationKey++ }
            UUID.nameUUIDFromBytes(
                "$processNamespace:$generationKey:${id.value}".toByteArray(),
            ).toString()
        }
}

private data object TvheadendArtworkFetcherFactory : Fetcher.Factory<TvheadendArtwork> {
    override fun create(
        data: TvheadendArtwork,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher = TvheadendArtworkFetcher(data)
}

internal class TvheadendArtworkFetcher(
    private val artwork: TvheadendArtwork,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val content = when (
            val loaded = artwork.session.artwork.loadArtwork(artwork.currentSession, artwork.id)
        ) {
            is ArtworkLoadResult.Available -> loaded.content
            is ArtworkLoadResult.Unavailable -> throw TvheadendArtworkLoadException(loaded.failure)
        }
        return SourceFetchResult(
            source = ImageSource(
                source = content.openStream().source().buffer(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }
}

private const val ARTWORK_SELECTOR_PREFIX = "imagecache/"
