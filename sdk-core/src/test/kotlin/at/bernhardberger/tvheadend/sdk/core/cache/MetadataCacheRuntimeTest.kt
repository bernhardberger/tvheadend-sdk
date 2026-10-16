@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.core.session.PublishedSnapshots
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MetadataCacheRuntimeTest {
    private val namespace = cacheNamespace("server", 9_982, "user")

    @Test
    fun `first EPG write waits sixty seconds from sync while catalog is immediate`() = runTest {
        val store = RecordingStore()
        val runtime = runtime(store, clock = TestClock(this))
        val publications = MutableStateFlow<PublishedSnapshots?>(null)
        runtime.start(namespace, publications)
        advanceTimeBy(120.seconds) // Connecting time must not consume the write delay.
        val first = PublishedSnapshots(catalog(1), EpgSnapshot.create())
        publications.value = first
        runCurrent()
        assertEquals(listOf(Call.StoreCatalog(first.catalog)), store.writes())
        advanceTimeBy(59_999.milliseconds)
        runCurrent()
        assertTrue(store.calls.filterIsInstance<Call.StoreEpg>().isEmpty())
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(listOf(Call.StoreEpg(first.epgSnapshot)), store.calls.filterIsInstance<Call.StoreEpg>())
        advanceTimeBy(120.seconds)
        runCurrent()
        assertEquals(1, store.calls.filterIsInstance<Call.StoreEpg>().size)
        runtime.shutdown()
    }

    @Test
    fun `syncs every thirty seconds cannot postpone the first or later EPG writes`() = runTest {
        val store = RecordingStore()
        val clock = TestClock(this)
        val runtime = runtime(store, clock = clock)
        val metadata = PhaseOneSessionMetadata(clock = clock)
        runtime.start(namespace, metadata.publishedSnapshots)
        repeat(2) {
            metadata.resetWorkingStateRetainingPublishedSnapshot()
            val generation = GatewayGeneration()
            metadata.bindGeneration(generation)
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
            runCurrent()
            advanceTimeBy(29_999.milliseconds)
            runCurrent()
            assertTrue(store.calls.filterIsInstance<Call.StoreEpg>().isEmpty())
            advanceTimeBy(1.milliseconds)
        }
        runCurrent()
        assertEquals(1, store.calls.filterIsInstance<Call.StoreEpg>().size)
        repeat(2) {
            metadata.resetWorkingStateRetainingPublishedSnapshot()
            val next = GatewayGeneration()
            metadata.bindGeneration(next)
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(next))
            runCurrent()
            advanceTimeBy(29_999.milliseconds)
            runCurrent()
            assertEquals(1, store.calls.filterIsInstance<Call.StoreEpg>().size)
            advanceTimeBy(1.milliseconds)
        }
        runCurrent()
        assertEquals(2, store.calls.filterIsInstance<Call.StoreEpg>().size)
        runtime.shutdown()
    }

    @Test
    fun `EPG write waiting for store admission persists only the newest publication`() = runTest {
        val store = RecordingStore()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockedStore = object : MetadataCacheStore by store {
            override suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): CachedEpg? {
                loading.complete(Unit)
                release.await()
                return null
            }
        }
        val runtime = runtime(blockedStore, clock = TestClock(this))
        val catalog = catalog(1)
        val publications = MutableStateFlow<PublishedSnapshots?>(PublishedSnapshots(catalog, EpgSnapshot.create()))
        runtime.start(namespace, publications)
        runCurrent()
        val restore = launch { runtime.restore(namespace) }
        loading.await()
        advanceTimeBy(60.seconds)
        runCurrent() // The first EPG write has reached store admission and is blocked.
        assertTrue(store.calls.filterIsInstance<Call.StoreEpg>().isEmpty())
        val newest = PublishedSnapshots(catalog, EpgSnapshot.create())
        publications.value = newest
        runCurrent()
        release.complete(Unit)
        restore.join()
        runCurrent()
        assertEquals(1, store.calls.filterIsInstance<Call.StoreEpg>().size)
        assertSame(newest.epgSnapshot, store.epg)
        runtime.shutdown()
    }

    @Test
    fun `teardown at thirty seconds flushes latest EPG only to outgoing namespace`() = runTest {
        for (shutdown in listOf(false, true)) {
            val store = RecordingStore()
            val runtime = runtime(store, clock = TestClock(this))
            val publications = MutableStateFlow<PublishedSnapshots?>(PublishedSnapshots(catalog(1), EpgSnapshot.create()))
            runtime.start(namespace, publications)
            runCurrent()
            advanceTimeBy(30.seconds)
            val retired = PublishedSnapshots(catalog(1), EpgSnapshot.create())
            publications.value = retired
            runCurrent()
            assertTrue(store.epgNamespaces.isEmpty())
            publications.value = null
            if (shutdown) runtime.shutdown(retired) else runtime.stop(retired)
            assertEquals(listOf(namespace), store.epgNamespaces)
            assertSame(retired.epgSnapshot, store.epg)
            if (!shutdown) {
                val replacement = cacheNamespace("replacement", 9_982, "user")
                val incoming = PublishedSnapshots(catalog(2), EpgSnapshot.create())
                publications.value = incoming
                runtime.start(replacement, publications)
                runCurrent()
                advanceTimeBy(59_999.milliseconds)
                runCurrent()
                assertEquals(listOf(namespace), store.epgNamespaces)
                advanceTimeBy(1.milliseconds)
                runCurrent()
                assertEquals(listOf(namespace, replacement), store.epgNamespaces)
                assertSame(incoming.epgSnapshot, store.epg)
                runtime.shutdown()
            }
        }
    }

    @Test
    fun `unchanged content persists new times and retirement flush restores exact freshness`() = runTest {
        val store = RecordingStore()
        val runtime = runtime(store, clock = TestClock(this))
        val catalog = catalog(1)
        val snapshot = EpgSnapshot.create()
        val first = MetadataRefreshTimes(START - 1.days, START - 1.days)
        val publications = MutableStateFlow<PublishedSnapshots?>(
            PublishedSnapshots(catalog, snapshot, first, first, emptyMap()),
        )
        runtime.start(namespace, publications)
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()
        val updated = first.copy(lastUpdatedAt = START)
        val queries = mapOf(ChannelId(1) to START)
        val retired = PublishedSnapshots(catalog, snapshot, updated, updated, queries)
        publications.value = retired
        runCurrent()
        assertEquals(updated, store.catalogTimes)
        assertEquals(first, store.epgTimes) // still coalescing EPG writes
        assertEquals(3, store.writes().size)
        publications.value = null
        runtime.stop(retired)
        assertEquals(updated, store.epgTimes)
        assertEquals(queries, store.queryTimes)
        assertSame(snapshot, store.epg)
        val restored = runtime.restore(namespace)
        val metadata = at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata()
        assertTrue(metadata.seedRetainedSnapshots(restored.catalog, restored.epgSnapshot))
        val observed = metadata.observation.value
        assertSame(snapshot, observed.epgSnapshotForDisplay)
        assertEquals(updated, observed.channelRefreshTimes)
        assertEquals(updated, observed.epgRefreshTimes)
        assertEquals(queries, observed.epgLastQueriedAt)
        assertEquals(MetadataRefreshTimes(), observed.dvrRefreshTimes)
        metadata.clearAllState()
        assertEquals(MetadataRefreshTimes(), metadata.observation.value.epgRefreshTimes)
        assertTrue(metadata.observation.value.epgLastQueriedAt.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `restore returns snapshots newer than the retention window and refreshes statistics`() = runTest {
        val store = RecordingStore()
        store.catalog = catalog(1)
        store.epg = EpgSnapshot.create()
        val runtime = runtime(store, retentionDays = 7)

        val restored = runtime.restore(namespace)

        assertSame(store.catalog, restored.catalog?.catalog)
        assertSame(store.epg, restored.epgSnapshot?.snapshot)
        assertEquals(listOf(Call.Load(START - 7.days)), store.calls.filterIsInstance<Call.Load>())
        assertEquals(CacheStatistics(metadataBytes = 42L, artworkBytes = 0L, artworkEntryCount = 0), runtime.statistics.value)
        runtime.shutdown()
    }

    @Test
    fun `catalog is written on every publication while the EPG coalesces`() = runTest {
        val store = RecordingStore()
        val clock = TestClock(this)
        val runtime = runtime(store, clock = clock)
        val publications = MutableStateFlow<PublishedSnapshots?>(null)
        runtime.start(namespace, publications)

        val firstCatalog = catalog(1)
        val firstEpg = EpgSnapshot.create()
        publications.value = PublishedSnapshots(firstCatalog, firstEpg)
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(listOf(Call.StoreCatalog(firstCatalog), Call.StoreEpg(firstEpg)), store.writes())

        val secondCatalog = catalog(2)
        val secondEpg = EpgSnapshot.create()
        publications.value = PublishedSnapshots(secondCatalog, secondEpg)
        runCurrent()
        val thirdEpg = EpgSnapshot.create()
        publications.value = PublishedSnapshots(secondCatalog, thirdEpg)
        advanceTimeBy(30.seconds)
        runCurrent()

        assertEquals(listOf(Call.StoreCatalog(secondCatalog)), store.writes().drop(2))

        advanceTimeBy(31.seconds)
        runCurrent()

        assertEquals(listOf(Call.StoreEpg(thirdEpg)), store.writes().drop(3))
        runtime.shutdown()
    }

    @Test
    fun `catalog changes are written while a pending EPG write waits for its interval`() = runTest {
        val store = RecordingStore()
        val runtime = runtime(store, clock = TestClock(this))
        val publications = MutableStateFlow<PublishedSnapshots?>(null)
        runtime.start(namespace, publications)
        val firstCatalog = catalog(1)
        publications.value = PublishedSnapshots(firstCatalog, EpgSnapshot.create())
        runCurrent()
        val pendingEpg = EpgSnapshot.create()
        publications.value = PublishedSnapshots(firstCatalog, pendingEpg)
        runCurrent()
        advanceTimeBy(10.seconds)

        val changedCatalog = catalog(2)
        publications.value = PublishedSnapshots(changedCatalog, pendingEpg)
        runCurrent()

        assertEquals(Call.StoreCatalog(changedCatalog), store.writes().last())
        val changedAgain = catalog(3)
        publications.value = PublishedSnapshots(changedAgain, pendingEpg)
        runCurrent()
        assertEquals(Call.StoreCatalog(changedAgain), store.writes().last())

        advanceTimeBy(51.seconds)
        runCurrent()
        assertEquals(4, store.writes().size)
        assertSame(pendingEpg, store.epg)
        runtime.shutdown()
    }

    @Test
    fun `stopping flushes the retired publication after the session cleared it`() = runTest {
        val store = RecordingStore()
        val runtime = runtime(store, clock = TestClock(this))
        val publications = MutableStateFlow<PublishedSnapshots?>(null)
        runtime.start(namespace, publications)
        val catalog = catalog(1)
        publications.value = PublishedSnapshots(catalog, EpgSnapshot.create())
        runCurrent()
        val retired = PublishedSnapshots(catalog, EpgSnapshot.create())
        publications.value = retired
        runCurrent()
        publications.value = null
        runCurrent()

        assertEquals(1, store.writes().size)

        runtime.stop(retired)

        assertEquals(2, store.writes().size)
        assertSame(retired.epgSnapshot, store.epg)
        runtime.shutdown()
    }

    @Test
    fun `stopping flushes the newest EPG snapshot before the writer goes away`() = runTest {
        val store = RecordingStore()
        val runtime = runtime(store, clock = TestClock(this))
        val publications = MutableStateFlow<PublishedSnapshots?>(null)
        runtime.start(namespace, publications)
        val catalog = catalog(1)
        publications.value = PublishedSnapshots(catalog, EpgSnapshot.create())
        runCurrent()
        val pendingEpg = EpgSnapshot.create()
        publications.value = PublishedSnapshots(catalog, pendingEpg)
        runCurrent()

        runtime.stop()

        assertEquals(Call.StoreEpg(pendingEpg), store.writes().last())
        val writesAfterStop = store.writes().size
        publications.value = PublishedSnapshots(catalog(3), pendingEpg)
        runCurrent()
        assertEquals(writesAfterStop, store.writes().size)
        runtime.shutdown()
    }

    @Test
    fun `clear empties the store and rewrites the current publication`() = runTest {
        val store = RecordingStore()
        val runtime = runtime(store, clock = TestClock(this))
        val publications = MutableStateFlow<PublishedSnapshots?>(null)
        runtime.start(namespace, publications)
        val catalog = catalog(1)
        publications.value = PublishedSnapshots(catalog, EpgSnapshot.create())
        runCurrent()

        advanceTimeBy(30.seconds)
        assertTrue(store.calls.filterIsInstance<Call.StoreEpg>().isEmpty())
        runtime.clear()
        runCurrent()

        assertEquals(
            listOf(Call.Clear, Call.StoreCatalog(catalog), Call.StoreEpg(EpgSnapshot.create())),
            store.calls.filterNot { it is Call.Load || it is Call.Statistics }.drop(1),
        )
        assertSame(catalog, store.catalog)
        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(1, store.calls.filterIsInstance<Call.StoreEpg>().size)
        runtime.shutdown()
    }

    private fun TestScope.runtime(
        store: MetadataCacheStore,
        retentionDays: Int = 7,
        clock: Clock = FixedClock,
    ): MetadataCacheRuntime = MetadataCacheRuntime(
        store = store,
        policy = MetadataCachePolicy.create(File("unused"), metadataRetention = retentionDays.days),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        epgWriteInterval = 60.seconds,
    )

    @Test
    fun `cancelling clear while joining a writer restores persistence after retirement`() = runTest {
        val store = RecordingStore()
        val retiring = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blockWrite = true
        val delayedStore = object : MetadataCacheStore by store {
            override suspend fun storeCatalog(namespace: CacheNamespace, catalog: CachedCatalog, storedAt: Instant) {
                if (blockWrite) {
                    blockWrite = false
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            retiring.complete(Unit)
                            release.await()
                        }
                    }
                }
                store.storeCatalog(namespace, catalog, storedAt)
            }
        }
        val runtime = runtime(delayedStore)
        val publications = MutableStateFlow<PublishedSnapshots?>(PublishedSnapshots(catalog(1), EpgSnapshot.create()))
        runtime.start(namespace, publications)
        runCurrent()
        val clearing = launch { runtime.clear() }
        retiring.await()
        clearing.cancel()
        runCurrent()
        assertTrue(store.writes().isEmpty())
        release.complete(Unit)
        clearing.join()
        assertTrue(clearing.isCancelled)
        val latest = catalog(2)
        publications.value = PublishedSnapshots(latest, EpgSnapshot.create())
        runCurrent()
        assertSame(latest, store.catalog)
        runtime.shutdown()
    }

    @Test
    fun `cancelling clear inside the store restores persistence and stop still retires it`() = runTest {
        val store = RecordingStore()
        val deleting = CompletableDeferred<Unit>()
        val delayedStore = object : MetadataCacheStore by store {
            override suspend fun clear() {
                store.clear()
                deleting.complete(Unit)
                awaitCancellation()
            }
        }
        val runtime = runtime(delayedStore)
        val publications = MutableStateFlow<PublishedSnapshots?>(PublishedSnapshots(catalog(1), EpgSnapshot.create()))
        runtime.start(namespace, publications)
        runCurrent()
        val clearing = launch { runtime.clear() }
        deleting.await()
        clearing.cancel()
        clearing.join()
        assertTrue(clearing.isCancelled)
        val latest = catalog(2)
        publications.value = PublishedSnapshots(latest, EpgSnapshot.create())
        runCurrent()
        assertSame(latest, store.catalog)
        runtime.stop()
        publications.value = PublishedSnapshots(catalog(3), EpgSnapshot.create())
        runCurrent()
        assertSame(latest, store.catalog)
        runtime.shutdown()
    }

    private class TestClock(private val scope: TestScope) : Clock {
        override fun now(): Instant = START + scope.testScheduler.currentTime.milliseconds
    }

    private object FixedClock : Clock {
        override fun now(): Instant = START
    }

    private sealed interface Call {
        data class Load(val notBefore: Instant) : Call
        data class StoreCatalog(val catalog: ChannelCatalog) : Call
        data class StoreEpg(val snapshot: EpgSnapshot) : Call
        data object Clear : Call
        data object Statistics : Call
    }

    private class RecordingStore : MetadataCacheStore {
        var catalog: ChannelCatalog? = null
        var epg: EpgSnapshot? = null
        var catalogTimes = MetadataRefreshTimes()
        var epgTimes = MetadataRefreshTimes()
        var queryTimes: Map<ChannelId, Instant> = emptyMap()
        val calls = mutableListOf<Call>()
        val epgNamespaces = mutableListOf<CacheNamespace>()

        fun writes(): List<Call> = calls.filter { it is Call.StoreCatalog || it is Call.StoreEpg }

        override suspend fun loadCatalog(namespace: CacheNamespace, notBefore: Instant): CachedCatalog? {
            calls += Call.Load(notBefore)
            return catalog?.let { CachedCatalog(it, catalogTimes) }
        }

        override suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): CachedEpg? =
            epg?.let { CachedEpg(it, epgTimes, queryTimes) }

        override suspend fun storeCatalog(namespace: CacheNamespace, catalog: CachedCatalog, storedAt: Instant) {
            calls += Call.StoreCatalog(catalog.catalog)
            this.catalog = catalog.catalog
            catalogTimes = catalog.refreshTimes
        }

        override suspend fun storeEpg(namespace: CacheNamespace, snapshot: CachedEpg, storedAt: Instant) {
            epgNamespaces += namespace
            calls += Call.StoreEpg(snapshot.snapshot)
            epg = snapshot.snapshot
            epgTimes = snapshot.refreshTimes
            queryTimes = snapshot.lastQueriedAt
        }

        override suspend fun clear() {
            calls += Call.Clear
            catalog = null
            epg = null
        }

        override suspend fun statistics(): CacheStatistics {
            calls += Call.Statistics
            return CacheStatistics(metadataBytes = 42L, artworkBytes = 0L, artworkEntryCount = 0)
        }
    }

    private companion object {
        val START: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        fun catalog(id: Long): ChannelCatalog = ChannelCatalog.create(
            channels = listOf(Channel.create(id = ChannelId(id), name = "Channel $id")),
        )
    }
}
