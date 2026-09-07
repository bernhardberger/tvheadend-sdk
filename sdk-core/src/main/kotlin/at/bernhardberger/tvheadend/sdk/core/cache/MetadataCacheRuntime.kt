package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import at.bernhardberger.tvheadend.sdk.core.SessionCache
import at.bernhardberger.tvheadend.sdk.core.session.PublishedSnapshots
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Catalog and EPG snapshot restored from persistence for one namespace. */
internal class RestoredSnapshots(
    val catalog: ChannelCatalog?,
    val epgSnapshot: EpgSnapshot?,
)

/**
 * Owns the persistence side of one session: cold-start restore, a coalescing writer, clear.
 *
 * All store access runs on [ioDispatcher]. The writer observes [PublishedSnapshots] and writes
 * the catalog whenever a new instance is published and the EPG at most once per
 * [epgWriteInterval]; [stop] flushes the latest EPG under [NonCancellable]. The store never
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
    private val mutableStatistics = MutableStateFlow(CacheStatistics.EMPTY)
    private var writer: Writer? = null

    override val statistics: StateFlow<CacheStatistics> = mutableStatistics.asStateFlow()

    /** Restores snapshots for [namespace] that are within the metadata retention. */
    suspend fun restore(namespace: CacheNamespace): RestoredSnapshots = withContext(ioDispatcher) {
        val notBefore = clock.now() - policy.metadataRetention
        val catalog = store.loadCatalog(namespace, notBefore)
        val epgSnapshot = store.loadEpg(namespace, notBefore)
        refreshStatistics()
        RestoredSnapshots(catalog, epgSnapshot)
    }

    /** Starts writing [publications] for [namespace]; a previous writer is stopped first. */
    suspend fun start(namespace: CacheNamespace, publications: StateFlow<PublishedSnapshots?>) {
        lifecycle.withLock {
            writer?.stop()
            writer = launchWriter(namespace, publications)
        }
    }

    private fun launchWriter(namespace: CacheNamespace, publications: StateFlow<PublishedSnapshots?>): Writer =
        Writer(namespace, publications).also { it.job = scope.launch { it.run() } }

    /** Stops the writer and flushes the latest EPG snapshot. Safe to call repeatedly. */
    suspend fun stop() {
        lifecycle.withLock {
            writer?.stop()
            writer = null
        }
    }

    override suspend fun clear() {
        currentCoroutineContext().ensureActive()
        lifecycle.withLock {
            val active = writer
            active?.stop()
            writer = null
            withContext(ioDispatcher) {
                store.clear()
                refreshStatistics()
            }
            if (active != null) {
                writer = launchWriter(active.namespace, active.publications)
            }
        }
    }

    /** Flushes and releases every coroutine; the runtime is unusable afterwards. */
    suspend fun shutdown() {
        stop()
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
        private var writtenEpg: EpgSnapshot? = null
        private var lastEpgWrite: Instant = Instant.DISTANT_PAST

        suspend fun run() {
            while (currentCoroutineContext().isActive) {
                val snapshots = publications.filterNotNull().first(::isNewer)
                if (snapshots.catalog !== writtenCatalog) {
                    writeCatalog(snapshots.catalog)
                }
                if (snapshots.epgSnapshot !== writtenEpg) {
                    val wait = epgWriteInterval - (clock.now() - lastEpgWrite)
                    if (wait.isPositive()) delay(wait)
                    val latest = publications.value ?: snapshots
                    if (latest.catalog !== writtenCatalog) writeCatalog(latest.catalog)
                    writeEpg(latest.epgSnapshot)
                }
            }
        }

        suspend fun stop() {
            job.cancelAndJoin()
            withContext(NonCancellable + ioDispatcher) {
                val latest = publications.value ?: return@withContext
                if (latest.catalog !== writtenCatalog) writeCatalog(latest.catalog)
                if (latest.epgSnapshot !== writtenEpg) writeEpg(latest.epgSnapshot)
            }
        }

        private fun isNewer(snapshots: PublishedSnapshots): Boolean =
            snapshots.catalog !== writtenCatalog || snapshots.epgSnapshot !== writtenEpg

        private suspend fun writeCatalog(catalog: ChannelCatalog) {
            store.storeCatalog(namespace, catalog, clock.now())
            writtenCatalog = catalog
            refreshStatistics()
        }

        private suspend fun writeEpg(snapshot: EpgSnapshot) {
            val now = clock.now()
            store.storeEpg(namespace, snapshot, now)
            writtenEpg = snapshot
            lastEpgWrite = now
            refreshStatistics()
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
