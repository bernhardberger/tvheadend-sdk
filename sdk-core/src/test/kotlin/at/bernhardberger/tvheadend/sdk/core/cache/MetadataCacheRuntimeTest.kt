package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import at.bernhardberger.tvheadend.sdk.core.session.PublishedSnapshots
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MetadataCacheRuntimeTest {
    private val namespace = cacheNamespace("server", 9_982, "user")

    @Test
    fun `restore returns snapshots newer than the retention window and refreshes statistics`() = runTest {
        val store = RecordingStore()
        store.catalog = catalog(1)
        store.epg = EpgSnapshot.create()
        val runtime = runtime(store, retentionDays = 7)

        val restored = runtime.restore(namespace)

        assertSame(store.catalog, restored.catalog)
        assertSame(store.epg, restored.epgSnapshot)
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

        runtime.clear()
        runCurrent()

        assertEquals(
            listOf(Call.Clear, Call.StoreCatalog(catalog), Call.StoreEpg(EpgSnapshot.create())),
            store.calls.filterNot { it is Call.Load || it is Call.Statistics }.drop(2),
        )
        assertSame(catalog, store.catalog)
        runtime.shutdown()
    }

    private fun TestScope.runtime(
        store: RecordingStore,
        retentionDays: Int = 7,
        clock: Clock = FixedClock,
    ): MetadataCacheRuntime = MetadataCacheRuntime(
        store = store,
        policy = MetadataCachePolicy.create(File("unused"), metadataRetention = retentionDays.days),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = clock,
        epgWriteInterval = 60.seconds,
    )

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
        val calls = mutableListOf<Call>()

        fun writes(): List<Call> = calls.filter { it is Call.StoreCatalog || it is Call.StoreEpg }

        override suspend fun loadCatalog(namespace: CacheNamespace, notBefore: Instant): ChannelCatalog? {
            calls += Call.Load(notBefore)
            return catalog
        }

        override suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): EpgSnapshot? = epg

        override suspend fun storeCatalog(namespace: CacheNamespace, catalog: ChannelCatalog, storedAt: Instant) {
            calls += Call.StoreCatalog(catalog)
            this.catalog = catalog
        }

        override suspend fun storeEpg(namespace: CacheNamespace, snapshot: EpgSnapshot, storedAt: Instant) {
            calls += Call.StoreEpg(snapshot)
            epg = snapshot
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
