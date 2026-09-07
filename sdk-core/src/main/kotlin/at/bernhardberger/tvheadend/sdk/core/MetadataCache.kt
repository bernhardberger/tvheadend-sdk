package at.bernhardberger.tvheadend.sdk.core

import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Persistence policy for channel, tag, EPG, and artwork metadata.
 *
 * The SDK owns every mechanic: the layout below [root], the restart-stable namespace derived from
 * the connected server profile, serialisation, atomic writes, corruption handling, and eviction.
 * The consumer only chooses the root directory, retention, and the artwork byte budget. Nothing
 * under the root is a consumer contract; its contents may change between releases and files
 * written by one release may be discarded by the next.
 *
 * Cached snapshots never claim [RetainedMetadataAuthority.CURRENT]. A cold start publishes a
 * cached catalog and EPG as [ChannelRepositoryState.Stale] and [EpgRepositoryState.Stale] until a
 * connection completes its initial synchronisation.
 */
public class MetadataCachePolicy private constructor(
    internal val root: File,
    /** Maximum age of a cached channel catalog or EPG snapshot before it is ignored and deleted. */
    public val metadataRetention: Duration,
    /** Maximum age of a cached artwork entry before it is refetched. */
    public val artworkRetention: Duration,
    /** Byte budget for cached artwork; least recently used entries are evicted above it. */
    public val artworkMaxBytes: Long,
) {
    init {
        require(metadataRetention.isFinite() && metadataRetention.isPositive()) {
            "MetadataCachePolicy metadataRetention must be positive"
        }
        require(artworkRetention.isFinite() && artworkRetention.isPositive()) {
            "MetadataCachePolicy artworkRetention must be positive"
        }
        require(artworkMaxBytes > 0L) {
            "MetadataCachePolicy artworkMaxBytes must be positive"
        }
    }

    override fun toString(): String =
        "MetadataCachePolicy(metadataRetention=$metadataRetention, " +
            "artworkRetention=$artworkRetention, artworkMaxBytes=$artworkMaxBytes)"

    public companion object {
        /**
         * Creates a policy rooted at [root], which must be app-private storage.
         *
         * The SDK creates the directory on first write. The consumer must not place other files
         * under it. A root must have one owning session runtime; do not share it between
         * independently running application processes.
         */
        public fun create(
            root: File,
            metadataRetention: Duration = DEFAULT_METADATA_RETENTION,
            artworkRetention: Duration = DEFAULT_ARTWORK_RETENTION,
            artworkMaxBytes: Long = DEFAULT_ARTWORK_MAX_BYTES,
        ): MetadataCachePolicy = MetadataCachePolicy(
            root = root,
            metadataRetention = metadataRetention,
            artworkRetention = artworkRetention,
            artworkMaxBytes = artworkMaxBytes,
        )

        private val DEFAULT_METADATA_RETENTION: Duration = 7.days
        private val DEFAULT_ARTWORK_RETENTION: Duration = 30.days
        private const val DEFAULT_ARTWORK_MAX_BYTES: Long = 64L shl 20
    }
}

/** Consumer view of the persistent metadata cache owned by a [TvheadendSession]. */
public interface SessionCache {
    /**
     * Aggregate cache usage. Values are approximate and never expose paths or server identity.
     *
     * Measured when the session restores, writes, or clears the cache; before the first
     * connection of a process it reports [CacheStatistics.EMPTY] even if files exist.
     */
    public val statistics: StateFlow<CacheStatistics>

    /**
     * Deletes every cached namespace under the policy root.
     *
     * In-memory session state is untouched. A connected session persists its current catalog
     * and EPG snapshot again right away, so clearing resets stale or damaged files rather than
     * freeing storage for the rest of the session. A normal return follows the deletion attempt.
     * Cancellation waits for any in-flight writer to retire and does not guarantee deletion;
     * persistence resumes even when the caller cancels. Filesystem failures are best-effort.
     */
    public suspend fun clear()
}

/** Aggregate usage of the persistent metadata cache. Consumers construct it only for fakes. */
public class CacheStatistics(
    /** Bytes used by cached channel catalogs and EPG snapshots across all namespaces. */
    public val metadataBytes: Long,
    /** Bytes used by cached artwork across all namespaces. */
    public val artworkBytes: Long,
    /** Number of cached artwork entries across all namespaces. */
    public val artworkEntryCount: Int,
) {
    init {
        require(metadataBytes >= 0L) { "Metadata bytes must not be negative" }
        require(artworkBytes >= 0L) { "Artwork bytes must not be negative" }
        require(artworkEntryCount >= 0) { "Artwork entry count must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is CacheStatistics &&
            metadataBytes == other.metadataBytes &&
            artworkBytes == other.artworkBytes &&
            artworkEntryCount == other.artworkEntryCount

    override fun hashCode(): Int {
        var result = metadataBytes.hashCode()
        result = 31 * result + artworkBytes.hashCode()
        result = 31 * result + artworkEntryCount
        return result
    }

    override fun toString(): String =
        "CacheStatistics(metadataBytes=$metadataBytes, artworkBytes=$artworkBytes, " +
            "artworkEntryCount=$artworkEntryCount)"

    public companion object {
        /** Statistics for an empty or disabled cache. */
        public val EMPTY: CacheStatistics = CacheStatistics(0L, 0L, 0)
    }
}
