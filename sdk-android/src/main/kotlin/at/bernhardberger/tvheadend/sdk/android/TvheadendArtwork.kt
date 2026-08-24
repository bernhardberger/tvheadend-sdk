package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoader
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.io.IOException
import kotlin.jvm.JvmSynthetic
import okio.FileSystem
import okio.buffer
import okio.source

/** Opaque Coil model for one authenticated TVHeadend image-cache entry. */
public class TvheadendArtwork private constructor(
    internal val loader: ArtworkLoader,
    internal val id: ArtworkId,
) {
    override fun toString(): String = "TvheadendArtwork(<redacted>)"

    override fun equals(other: Any?): Boolean =
        this === other || other is TvheadendArtwork && loader === other.loader && id == other.id

    override fun hashCode(): Int = 31 * System.identityHashCode(loader) + id.hashCode()

    public companion object {
        /**
         * Creates an authenticated artwork model from an HTSP image-cache selector.
         *
         * Returns null for absent values, external URLs, and malformed or unsupported selectors.
         */
        public fun create(
            session: TvheadendSession,
            source: String?,
        ): TvheadendArtwork? = create(session.artwork, source)

        @JvmSynthetic
        internal fun create(
            loader: ArtworkLoader,
            source: String?,
        ): TvheadendArtwork? {
            val normalized = source?.removePrefix("/") ?: return null
            if (!normalized.startsWith(ARTWORK_SELECTOR_PREFIX)) return null
            val value = normalized.removePrefix(ARTWORK_SELECTOR_PREFIX)
            if (value.isEmpty() || value.any { character -> character !in '0'..'9' }) return null
            val id = value.toIntOrNull()?.takeIf { parsed -> parsed > 0 } ?: return null
            return TvheadendArtwork(loader, ArtworkId(id))
        }
    }
}

/** Creates the Coil component that fetches [TvheadendArtwork] through its authenticated session. */
public fun createTvheadendArtworkFetcherFactory(): Fetcher.Factory<TvheadendArtwork> =
    TvheadendArtworkFetcherFactory

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
        val content = when (val loaded = artwork.loader.loadArtwork(artwork.id)) {
            is ArtworkLoadResult.Available -> loaded.content
            is ArtworkLoadResult.Unavailable -> throw loaded.failure.toIOException()
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

private fun ArtworkFailure.toIOException(): IOException =
    IOException("TVHeadend artwork load failed: $this")

private const val ARTWORK_SELECTOR_PREFIX = "imagecache/"
