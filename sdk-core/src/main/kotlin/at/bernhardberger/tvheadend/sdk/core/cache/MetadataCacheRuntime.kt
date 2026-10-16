@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import at.bernhardberger.tvheadend.sdk.core.SessionCache
import at.bernhardberger.tvheadend.sdk.core.session.PublishedSnapshots
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Catalog and EPG snapshot restored from persistence for one namespace. */
internal class RestoredSnapshots(
    val catalog: CachedCatalog?,
    val epgSnapshot: CachedEpg?,
    val isCurrent: () -> Boolean = { true },
)

/**
 * Owns the persistence side of one session: cold-start restore, a coalescing writer, clear.
 *
 * All store access runs on [ioDispatcher]. The writer observes [PublishedSnapshots] and writes
 * the catalog whenever content or receipt times change, also while an EPG write waits, and the EPG
 * at most once per [epgWriteInterval]; [stop] flushes the latest EPG under [NonCancellable],
 * including a publication the session retired before stopping the writer. The store never
 * throws for IO problems, so a failing cache cannot fail the session.
 */
internal class MetadataCacheRuntime(
    private val store: MetadataCacheStore,
    private val policy: MetadataCachePolicy,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val epgWriteInterval: Duration = DEFAULT_EPG_WRITE_INTERVAL,
) : SessionCache {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(ioDispatcher + rootJob)
    private val lifecycle = Mutex()
    private val storeAccess = Mutex()
    private val restoreRevision = AtomicLong()
    private val artworkStore = FileArtworkCacheStore(policy.root)
    private var clearEpoch = 0L
    private val mutableStatistics = MutableStateFlow(CacheStatistics.EMPTY)
    private var writer: Writer? = null

    override val statistics: StateFlow<CacheStatistics> = mutableStatistics.asStateFlow()

    /** Restores snapshots for [namespace] that are within the metadata retention. */
    suspend fun restore(
        namespace: CacheNamespace,
        accept: (RestoredSnapshots) -> Unit = {},
    ): RestoredSnapshots {
        val revision = restoreRevision.get()
        return withContext(ioDispatcher) {
            val restored = lifecycle.withLock {
                accessStore {
                    val notBefore = clock.now() - policy.metadataRetention
                    val catalog = store.loadCatalog(namespace, notBefore)
                    val epgSnapshot = store.loadEpg(namespace, notBefore)
                    artworkStore.prune(clock.now() - policy.artworkRetention, policy.artworkMaxBytes)
                    refreshStatistics()
                    RestoredSnapshots(catalog, epgSnapshot) { restoreRevision.get() == revision }
                }
            }
            // Index construction and acceptance hold no cache locks. Acceptance rechecks the
            // atomic epoch inside the owner/metadata critical section, avoiding lock inversion
            // when an inline observation collector clears the cache during publication.
            restored.epgSnapshot?.snapshot?.prepareForObservation()
            currentCoroutineContext().ensureActive()
            accept(restored)
            restored
        }
    }

    suspend fun loadArtwork(
        namespace: CacheNamespace,
        id: ArtworkId,
        isCurrent: () -> Boolean,
        fetch: suspend () -> ArtworkLoadResult,
    ): ArtworkLoadResult {
        var epoch = 0L
        val cached = accessStore {
            epoch = clearEpoch
            val now = clock.now()
            val bytes = artworkStore.load(namespace, id, now, now - policy.artworkRetention)
            refreshStatistics()
            bytes
        }
        if (!isCurrent()) return ArtworkLoadResult.Unavailable(TvheadendFailure.CONNECTION_CHANGED)
        if (cached != null) return ArtworkLoadResult.Available(ArtworkContent.create(cached))
        val result = fetch()
        accessStore {
            if (epoch == clearEpoch && isCurrent()) {
                val now = clock.now()
                when (result) {
                    is ArtworkLoadResult.Available -> artworkStore.store(
                        namespace, id, result.content.openStream().use { it.readBytes() }, now,
                        now - policy.artworkRetention, policy.artworkMaxBytes,
                    )
                    is ArtworkLoadResult.Unavailable -> if (result.failure == TvheadendFailure.FILE_UNAVAILABLE) {
                        artworkStore.remove(namespace, id, now - policy.artworkRetention)
                    }
                }
                refreshStatistics()
            }
        }
        return if (isCurrent()) result else ArtworkLoadResult.Unavailable(TvheadendFailure.CONNECTION_CHANGED)
    }

    private suspend fun <T> accessStore(block: suspend () -> T): T = withContext(ioDispatcher) {
        storeAccess.withLock { block() }
    }

    /** Starts writing [publications] for [namespace]; a previous writer is stopped first. */
    suspend fun start(namespace: CacheNamespace, publications: StateFlow<PublishedSnapshots?>) {
        lifecycle.withLock {
            writer?.stop(retiredPublication = null)
            writer = launchWriter(namespace, publications)
        }
    }

    private fun launchWriter(namespace: CacheNamespace, publications: StateFlow<PublishedSnapshots?>): Writer =
        Writer(namespace, publications).also { it.job = scope.launch { it.run() } }

    /**
     * Stops the writer and flushes the latest catalog and EPG snapshot to the writer's namespace.
     * Safe to call repeatedly.
     *
     * [retiredPublication] is the publication the session retired when it cleared or reset its
     * state; it is flushed instead of the observed publication, which a clear has already reset
     * to `null`. Callers must capture it before the next profile can publish.
     */
    suspend fun stop(retiredPublication: PublishedSnapshots? = null) {
        lifecycle.withLock {
            writer?.stop(retiredPublication)
            writer = null
        }
    }

    override suspend fun clear() {
        currentCoroutineContext().ensureActive()
        // Never acquire a monitor here: an inline collector can call clear under publication
        // locks while a restore is waiting to take those locks. The atomic read at acceptance
        // linearizes the seed before clear, or rejects a load invalidated by clear.
        restoreRevision.incrementAndGet()
        lifecycle.withLock {
            val active = writer
            try {
                // Clear need not flush bytes it will delete, but must finish retiring the old
                // writer before a cancelled caller can restore persistence.
                withContext(NonCancellable) { active?.job?.cancelAndJoin() }
                writer = null
                accessStore {
                    clearEpoch++
                    store.clear()
                    refreshStatistics()
                }
            } finally {
                if (active != null) {
                    writer = launchWriter(active.namespace, active.publications)
                }
            }
        }
    }

    /** Flushes as [stop] does and releases every coroutine; the runtime is unusable afterwards. */
    suspend fun shutdown(retiredPublication: PublishedSnapshots? = null) {
        stop(retiredPublication)
        rootJob.cancelAndJoin()
    }

    private suspend fun refreshStatistics() {
        mutableStatistics.value = store.statistics()
    }

    private inner class Writer(
        val namespace: CacheNamespace,
        val publications: StateFlow<PublishedSnapshots?>,
    ) {
        lateinit var job: Job
        private var writtenCatalog: ChannelCatalog? = null
        private var writtenCatalogTimes: MetadataRefreshTimes? = null
        private var writtenEpg: EpgSnapshot? = null
        private var writtenEpgTimes: MetadataRefreshTimes? = null
        private var writtenQueryTimes: Map<ChannelId, Instant>? = null
        private var lastEpgWrite: Instant = Instant.DISTANT_PAST

        suspend fun run() {
            while (currentCoroutineContext().isActive) {
                val snapshots = publications.filterNotNull().first(::isNewer)
                if (isNewCatalog(snapshots)) {
                    writeCatalog(snapshots)
                }
                if (isNewEpg(snapshots)) {
                    writeCatalogsUntil(lastEpgWrite + epgWriteInterval)
                    val latest = publications.value ?: snapshots
                    if (isNewCatalog(latest)) writeCatalog(latest)
                    writeEpg(latest)
                }
            }
        }

        /** Keeps catalog writes immediate while a pending EPG write waits for [deadline]. */
        private suspend fun writeCatalogsUntil(deadline: Instant) {
            while (true) {
                val remaining = deadline - clock.now()
                if (!remaining.isPositive()) return
                // Only the wait is bounded; a started catalog write is never cut short.
                val changed = withTimeoutOrNull(remaining) {
                    publications.filterNotNull().first(::isNewCatalog)
                } ?: return
                writeCatalog(changed)
            }
        }

        suspend fun stop(retiredPublication: PublishedSnapshots?) {
            job.cancelAndJoin()
            withContext(NonCancellable + ioDispatcher) {
                val latest = retiredPublication ?: publications.value ?: return@withContext
                if (isNewCatalog(latest)) writeCatalog(latest)
                if (isNewEpg(latest)) writeEpg(latest)
            }
        }

        private fun isNewer(snapshots: PublishedSnapshots): Boolean =
            isNewCatalog(snapshots) || isNewEpg(snapshots)

        private fun isNewCatalog(snapshots: PublishedSnapshots): Boolean =
            snapshots.catalog !== writtenCatalog ||
                snapshots.channelRefreshTimes != writtenCatalogTimes

        private fun isNewEpg(snapshots: PublishedSnapshots): Boolean =
            snapshots.epgSnapshot !== writtenEpg ||
                snapshots.epgRefreshTimes != writtenEpgTimes ||
                snapshots.epgLastQueriedAt != writtenQueryTimes

        private suspend fun writeCatalog(snapshots: PublishedSnapshots) {
            accessStore {
                store.storeCatalog(namespace, CachedCatalog(snapshots.catalog, snapshots.channelRefreshTimes), clock.now())
                refreshStatistics()
            }
            writtenCatalog = snapshots.catalog
            writtenCatalogTimes = snapshots.channelRefreshTimes
        }

        private suspend fun writeEpg(snapshots: PublishedSnapshots) {
            val now = clock.now()
            accessStore {
                store.storeEpg(namespace, CachedEpg(snapshots.epgSnapshot, snapshots.epgRefreshTimes,
                    snapshots.epgLastQueriedAt), now)
                refreshStatistics()
            }
            writtenEpg = snapshots.epgSnapshot
            writtenEpgTimes = snapshots.epgRefreshTimes
            writtenQueryTimes = snapshots.epgLastQueriedAt
            lastEpgWrite = now
        }
    }

    private companion object {
        val DEFAULT_EPG_WRITE_INTERVAL: Duration = 60.seconds
    }
}

/** Session cache exposed when no [MetadataCachePolicy] was supplied. */
internal object DisabledSessionCache : SessionCache {
    override val statistics: StateFlow<CacheStatistics> = MutableStateFlow(CacheStatistics.EMPTY)

    override suspend fun clear() {
        currentCoroutineContext().ensureActive()
    }
}
