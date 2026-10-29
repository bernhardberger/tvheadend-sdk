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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 * after one [epgWriteInterval] from its first observed live publication, and not before a running
 * restore of its namespace has accepted the restored guide, then at most once per interval
 * across automatic retries;
 * [stop] flushes the latest EPG under [NonCancellable],
 * including a publication the session retired before stopping the writer. The store never
 * throws for IO problems, so a failing cache cannot fail the session.
 *
 * Catalog and EPG files are guarded by `metadataAccess`, artwork files and `clearEpoch` by
 * `artworkAccess`, so artwork reads never wait for a guide decode. Artwork index admission and
 * completion hold that lock, but independent artwork file reads run outside it. Locks are only taken in the
 * order `lifecycle`, `metadataAccess`, `artworkAccess`; only [clear] holds more than one store lock.
 * Each lock refreshes only its own part of [statistics].
 */
internal class MetadataCacheRuntime(
    private val store: MetadataCacheStore,
    private val policy: MetadataCachePolicy,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val epgWriteInterval: Duration = DEFAULT_EPG_WRITE_INTERVAL,
    private val artworkStore: FileArtworkCacheStore = FileArtworkCacheStore(policy.root),
) : SessionCache {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(ioDispatcher + rootJob)
    private val lifecycle = Mutex()
    private val metadataAccess = Mutex()
    private val artworkAccess = Mutex()
    private val restoreRevision = AtomicLong()
    // Namespaces whose restore may still accept a guide; a writer's first EPG write waits for them.
    private val restoring = MutableStateFlow<List<CacheNamespace>>(emptyList())
    private var artworkFlush: Job? = null
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
        restoring.update { namespaces -> namespaces + namespace }
        // Released only after acceptance, so a late guide is published before the first EPG write.
        return try { restoreAccepting(namespace, revision, accept) } finally {
            restoring.update { namespaces -> namespaces - namespace }
        }
    }

    private suspend fun restoreAccepting(
        namespace: CacheNamespace,
        revision: Long,
        accept: (RestoredSnapshots) -> Unit,
    ): RestoredSnapshots {
        return withContext(ioDispatcher) {
            val catalog = lifecycle.withLock {
                accessMetadata { store.loadCatalog(namespace, clock.now() - policy.metadataRetention) }
            }
            // Publish the small catalog before EPG decoding, with neither cache lock held.
            currentCoroutineContext().ensureActive()
            accept(RestoredSnapshots(catalog, null) { restoreRevision.get() == revision })
            val restored = lifecycle.withLock {
                accessMetadata {
                    val notBefore = clock.now() - policy.metadataRetention
                    val epgSnapshot = store.loadEpg(namespace, notBefore)
                    refreshMetadataStatistics()
                    RestoredSnapshots(catalog, epgSnapshot) { restoreRevision.get() == revision }
                }
            }
            // Index construction and acceptance hold no cache locks. Acceptance rechecks the
            // atomic epoch inside the owner/metadata critical section, avoiding lock inversion
            // when an inline observation collector clears the cache during publication.
            restored.epgSnapshot?.snapshot?.prepareForObservation()
            currentCoroutineContext().ensureActive()
            accept(RestoredSnapshots(null, restored.epgSnapshot, restored.isCurrent))
            // The prune walks every namespace, so it follows acceptance off the restore's
            // completion path. A prune after clear finds no namespaces and recreates nothing.
            scope.launch {
                accessArtwork {
                    artworkStore.prune(clock.now() - policy.artworkRetention, policy.artworkMaxBytes)
                    refreshArtworkStatistics()
                }
            }
            restored
        }
    }

    /**
     * Reads [id] from [namespace], else fetches and stores it unless [clear] or [isCurrent]
     * retired the fetch meanwhile. A read overlapping [clear] linearizes before it and may return
     * bytes read before the deletion; a read that starts after [clear] returned never does.
     */
    suspend fun loadArtwork(
        namespace: CacheNamespace,
        id: ArtworkId,
        isCurrent: () -> Boolean,
        fetch: suspend () -> ArtworkLoadResult,
    ): ArtworkLoadResult {
        val (epoch, cached) = readArtwork(namespace, id)
        if (!isCurrent()) return ArtworkLoadResult.Unavailable(TvheadendFailure.CONNECTION_CHANGED)
        if (cached != null) return ArtworkLoadResult.Available(ArtworkContent.create(cached))
        val result = fetch()
        accessArtwork {
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
                refreshArtworkStatistics()
            }
        }
        return if (isCurrent()) result else ArtworkLoadResult.Unavailable(TvheadendFailure.CONNECTION_CHANGED)
    }

    /**
     * Reads [id] from [namespace] without fetching, storing or removing beyond a normal disk hit.
     * [isCurrent] is checked before and after the read so a racing profile switch returns no bytes.
     * Like [loadArtwork], a read overlapping [clear] linearizes before it.
     */
    suspend fun loadCachedArtwork(
        namespace: CacheNamespace,
        id: ArtworkId,
        isCurrent: () -> Boolean,
    ): ArtworkLoadResult {
        val (_, cached) = readArtwork(namespace, id, isCurrent)
        return when {
            !isCurrent() -> ArtworkLoadResult.Unavailable(TvheadendFailure.CONNECTION_CHANGED)
            cached == null -> ArtworkLoadResult.Unavailable(TvheadendFailure.NOT_READY)
            else -> ArtworkLoadResult.Available(ArtworkContent.create(cached))
        }
    }

    private suspend fun <T> accessMetadata(block: suspend () -> T): T = withContext(ioDispatcher) {
        metadataAccess.withLock { block() }
    }

    private suspend fun <T> accessArtwork(block: suspend () -> T): T = withContext(ioDispatcher) {
        artworkAccess.withLock { block() }
    }

    private suspend fun readArtwork(
        namespace: CacheNamespace,
        id: ArtworkId,
        isCurrent: () -> Boolean = { true },
    ): Pair<Long, ByteArray?> {
        val (epoch, pending) = accessArtwork {
            val before = artworkStore.revision
            val pending = if (isCurrent()) {
                artworkStore.prepare(namespace, id, clock.now() - policy.artworkRetention)
            } else null
            if (before != artworkStore.revision) refreshArtworkStatistics()
            scheduleArtworkFlush()
            clearEpoch to pending
        }
        if (pending == null) return epoch to null
        val bytes = withContext(ioDispatcher) { artworkStore.read(pending) }
        accessArtwork {
            if (clearEpoch == epoch) {
                val before = artworkStore.revision
                artworkStore.complete(pending, bytes, clock.now())
                if (before != artworkStore.revision) refreshArtworkStatistics()
                scheduleArtworkFlush()
            }
        }
        return epoch to bytes
    }

    // Called under artworkAccess. One bounded batch, rather than an index rewrite per logo.
    private fun scheduleArtworkFlush() {
        if (artworkFlush != null) return
        artworkFlush = scope.launch {
            delay(5.seconds)
            accessArtwork {
                artworkStore.flush()
                artworkFlush = null
            }
        }
    }

    /** Starts writing [publications] for [namespace]; a previous writer is stopped first. */
    suspend fun start(namespace: CacheNamespace, publications: StateFlow<PublishedSnapshots?>) {
        lifecycle.withLock {
            writer?.stop(retiredPublication = null)
            writer = launchWriter(namespace, publications)
        }
    }

    private fun launchWriter(
        namespace: CacheNamespace,
        publications: StateFlow<PublishedSnapshots?>,
        deferFirstEpgWrite: Boolean = true,
    ): Writer = Writer(namespace, publications, deferFirstEpgWrite).also { it.job = scope.launch { it.run() } }

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
            accessArtwork {
                artworkFlush?.cancel()
                artworkFlush = null
                artworkStore.flush()
            }
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
                // Store clear deletes artwork directories too, so it holds both locks in order.
                accessMetadata {
                    artworkAccess.withLock {
                        clearEpoch++
                        artworkFlush?.cancel()
                        artworkFlush = null
                        artworkStore.invalidate()
                        store.clear()
                        mutableStatistics.value = store.statistics()
                    }
                }
            } finally {
                if (active != null) {
                    // Explicit clear keeps its immediate rewrite of the current publication.
                    writer = launchWriter(
                        active.namespace, active.publications, deferFirstEpgWrite = active.publications.value == null,
                    )
                }
            }
        }
    }

    /** Flushes as [stop] does and releases every coroutine; the runtime is unusable afterwards. */
    suspend fun shutdown(retiredPublication: PublishedSnapshots? = null) {
        stop(retiredPublication)
        rootJob.cancelAndJoin()
    }

    private suspend fun refreshMetadataStatistics() {
        val metadataBytes = store.metadataBytes()
        mutableStatistics.update { CacheStatistics(metadataBytes, it.artworkBytes, it.artworkEntryCount) }
    }

    private suspend fun refreshArtworkStatistics() {
        val measured = store.statistics()
        mutableStatistics.update { CacheStatistics(it.metadataBytes, measured.artworkBytes, measured.artworkEntryCount) }
    }

    private inner class Writer(
        val namespace: CacheNamespace,
        val publications: StateFlow<PublishedSnapshots?>,
        private val deferFirstEpgWrite: Boolean,
    ) {
        lateinit var job: Job
        private var writtenCatalog: ChannelCatalog? = null
        private var writtenCatalogTimes: MetadataRefreshTimes? = null
        private var writtenEpg: EpgSnapshot? = null
        private var writtenEpgTimes: MetadataRefreshTimes? = null
        private var writtenQueryTimes: Map<ChannelId, Instant>? = null
        private var epgDeadline: Instant? = null

        suspend fun run() {
            while (currentCoroutineContext().isActive) {
                val snapshots = publications.filterNotNull().first(::isNewer)
                if (epgDeadline == null) {
                    epgDeadline = clock.now() + if (deferFirstEpgWrite) epgWriteInterval else Duration.ZERO
                }
                if (isNewCatalog(snapshots)) {
                    writeCatalog(snapshots)
                }
                if (isNewEpg(snapshots)) {
                    writeCatalogsUntil(checkNotNull(epgDeadline))
                    // A live-only first write would replace the restored guide still being accepted.
                    if (writtenEpg == null) writeCatalogsWhileRestoring()
                    val latest = publications.value ?: snapshots
                    if (isNewCatalog(latest)) writeCatalog(latest)
                    writeEpg(latest, useLatestPublication = true)
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

        /** Keeps catalog writes immediate while a restore of [namespace] is still running. */
        private suspend fun writeCatalogsWhileRestoring() {
            while (true) {
                val (restoringNamespace, changed) = combine(restoring, publications) { namespaces, latest ->
                    (namespace in namespaces) to latest?.takeIf(::isNewCatalog)
                }.first { (restoringNamespace, changed) -> !restoringNamespace || changed != null }
                if (!restoringNamespace) return
                writeCatalog(checkNotNull(changed))
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
            (snapshots.catalog !== writtenCatalog && snapshots.catalog != writtenCatalog) ||
                snapshots.channelRefreshTimes != writtenCatalogTimes

        private fun isNewEpg(snapshots: PublishedSnapshots): Boolean =
            snapshots.epgSnapshot !== writtenEpg ||
                snapshots.epgRefreshTimes != writtenEpgTimes ||
                snapshots.epgLastQueriedAt != writtenQueryTimes

        private suspend fun writeCatalog(snapshots: PublishedSnapshots) {
            accessMetadata {
                store.storeCatalog(namespace, CachedCatalog(snapshots.catalog, snapshots.channelRefreshTimes), clock.now())
                refreshMetadataStatistics()
            }
            writtenCatalog = snapshots.catalog
            writtenCatalogTimes = snapshots.channelRefreshTimes
        }

        private suspend fun writeEpg(fallback: PublishedSnapshots, useLatestPublication: Boolean = false) {
            accessMetadata {
                // Ordinary writes resolve after store admission, which may have suspended
                // across newer publications. Stop's explicit retired publication stays pinned.
                val snapshots = if (useLatestPublication) publications.value ?: fallback else fallback
                val now = clock.now()
                store.storeEpg(namespace, CachedEpg(snapshots.epgSnapshot, snapshots.epgRefreshTimes,
                    snapshots.epgLastQueriedAt), now)
                refreshMetadataStatistics()
                writtenEpg = snapshots.epgSnapshot
                writtenEpgTimes = snapshots.epgRefreshTimes
                writtenQueryTimes = snapshots.epgLastQueriedAt
                epgDeadline = now + epgWriteInterval
            }
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
