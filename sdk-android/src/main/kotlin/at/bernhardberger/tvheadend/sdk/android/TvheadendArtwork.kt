@file:OptIn(ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.android

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CachedArtworkReference
import at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation
import at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity
import at.bernhardberger.tvheadend.sdk.core.TvheadendSession
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.buffer
import okio.source

/** Opaque Coil model for one authenticated Tvheadend image-cache entry. */
public class TvheadendArtwork private constructor(
    internal val session: TvheadendSession,
    internal val currentSession: CurrentSessionObservation?,
    internal val id: ArtworkId,
    internal val cached: CachedArtworkReference? = null,
) {
    internal val persistentKey: String? =
        cached?.cacheKey ?: currentSession?.let { session.artwork.cacheKey(it, id) }

    override fun toString(): String = "TvheadendArtwork(<redacted>)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is TvheadendArtwork &&
            session === other.session &&
            currentSession === other.currentSession &&
            id == other.id &&
            cached == other.cached

    override fun hashCode(): Int {
        var result = System.identityHashCode(session)
        result = 31 * result + System.identityHashCode(currentSession)
        result = 31 * result + id.hashCode()
        return 31 * result + cached.hashCode()
    }

    public companion object {
        /**
         * Creates an authenticated artwork model for one image-cache entry.
         *
         * Channel and tag icons already carry an [ArtworkId]; parse other image-cache selectors
         * with [ArtworkId.parse].
         */
        public fun create(
            session: TvheadendSession,
            currentSession: CurrentSessionObservation,
            id: ArtworkId,
        ): TvheadendArtwork = TvheadendArtwork(session, currentSession, id)

        /**
         * Creates a disk-only model for [id] in the active profile's persistent artwork cache,
         * usable in any session state, including before ready and offline.
         *
         * Returns null without a cache policy or an active profile. The memory key equals that of
         * [create] for the same profile and artwork, so switching to [create] once a current
         * session exists reuses the decoded image. Fetches never reach the server and fail with
         * [TvheadendArtworkLoadException] on a cache miss or once another profile is active.
         */
        @ExperimentalTvheadendApi
        public fun cached(session: TvheadendSession, id: ArtworkId): TvheadendArtwork? =
            session.artwork.cachedArtwork(id)?.let { TvheadendArtwork(session, null, id, it) }
    }
}

/**
 * Registers the Coil memory key and fetcher for [TvheadendArtwork].
 *
 * With an SDK cache policy, keys are opaque and restart-stable for the profile and artwork ID.
 * Otherwise they are process-local and generation-scoped. Fetches validate current authority and
 * use a stream source without a Coil disk cache key: the SDK owns persistent bytes, retention,
 * eviction and clearing. Coil memory hits may reuse already-decoded content without a fetch.
 *
 * Experimental [TvheadendArtwork.cached] models read only the SDK's persistent cache for the
 * profile active at creation, report [DataSource.DISK], and share the restart-stable key of
 * current-session models for the same profile and artwork.
 */
public fun ComponentRegistry.Builder.addTvheadendArtwork(): ComponentRegistry.Builder = apply {
    add(TvheadendArtworkKeyer)
    add(TvheadendArtworkFetcherFactory)
}

/** Typed Coil failure that preserves the SDK artwork classification. */
public class TvheadendArtworkLoadException(
    public val failure: TvheadendFailure,
) : IOException("Tvheadend artwork load failed")

private data object TvheadendArtworkKeyer : Keyer<TvheadendArtwork> {
    override fun key(data: TvheadendArtwork, options: Options): String? = data.memoryCacheKey()
}

internal fun TvheadendArtwork.memoryCacheKey(): String? {
    return persistentKey ?: TvheadendArtworkMemoryKeys.key(checkNotNull(currentSession).generationIdentity, id)
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
        currentCoroutineContext().ensureActive()
        val cached = artwork.cached
        val loaded = if (cached != null) {
            artwork.session.artwork.loadCachedArtwork(cached)
        } else {
            val currentSession = checkNotNull(artwork.currentSession)
            if (!artwork.session.isCurrent(currentSession)) {
                throw TvheadendArtworkLoadException(TvheadendFailure.OBSERVATION_EXPIRED)
            }
            artwork.session.artwork.loadArtwork(currentSession, artwork.id)
        }
        val content = when (loaded) {
            is ArtworkLoadResult.Available -> loaded.content
            is ArtworkLoadResult.Unavailable -> throw TvheadendArtworkLoadException(loaded.failure)
        }
        return SourceFetchResult(
            source = ImageSource(
                source = content.openStream().source().buffer(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = null,
            dataSource = if (cached != null) DataSource.DISK else DataSource.NETWORK,
        )
    }
}
