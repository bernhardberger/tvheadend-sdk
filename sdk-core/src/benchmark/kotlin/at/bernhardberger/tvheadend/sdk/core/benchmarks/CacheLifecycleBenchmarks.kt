@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.benchmarks

import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.cache.FileMetadataCacheStore
import at.bernhardberger.tvheadend.sdk.core.cache.CachedCatalog
import at.bernhardberger.tvheadend.sdk.core.cache.CachedEpg
import at.bernhardberger.tvheadend.sdk.core.cache.cacheNamespace
import at.bernhardberger.tvheadend.sdk.core.session.PhaseOneSessionMetadata
import java.lang.management.ManagementFactory
import java.nio.file.Files
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/** Warm filesystem cache; metadata ownership transitions, not socket reconnect latency. */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
public open class CacheLifecycleBenchmarks {
    @Param("100", "500")
    public var channelCount: Int = 100

    @Param("0", "8")
    public var retainedSnapshots: Int = 0

    private lateinit var fixture: EpgFixture
    private lateinit var store: FileMetadataCacheStore
    private lateinit var root: java.io.File
    private val namespace = cacheNamespace("benchmark.invalid", 9982, "")
    private val held = ArrayDeque<EpgSnapshot>()
    private val heapCheckpoints = java.lang.Boolean.getBoolean("tvheadend.benchmark.heapCheckpoints")
    private var cycle = 0

    @Setup(Level.Trial)
    public fun setup(): Unit = runBlocking {
        fixture = EpgFixture(channelCount)
        root = Files.createTempDirectory("sdk-cache-benchmark-").toFile()
        store = FileMetadataCacheStore(root, object : kotlin.time.Clock {
            override fun now(): kotlin.time.Instant = fixture.from
        })
        val ready = fixture.readyMetadata().observation.value
        store.storeCatalog(namespace,
            CachedCatalog(checkNotNull(ready.channelCatalogForDisplay), ready.channelRefreshTimes), fixture.from)
        store.storeEpg(namespace, CachedEpg(checkNotNull(ready.epgSnapshotForDisplay), ready.epgRefreshTimes,
            ready.epgLastQueriedAt), fixture.from)
        checkNotNull(store.loadCatalog(namespace, fixture.from))
        check(checkNotNull(store.loadEpg(namespace, fixture.from)).snapshot.events.size == channelCount * 49)
        repeat(retainedSnapshots) {
            val snapshot = checkNotNull(store.loadEpg(namespace, fixture.from)).snapshot
            snapshot.prepareForObservation()
            retain(snapshot)
        }
    }

    @Benchmark
    public fun restoreResyncAndClear(): Unit = runBlocking {
        cycle++
        val metadata = PhaseOneSessionMetadata()
        checkpoint("baseline")
        restoreAndResync(metadata)
        checkpoint("current")
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        check(metadata.observation.value.currentSession == null)
        checkpoint("disconnected")
        metadata.clearAllState()
        check(metadata.observation.value.epgSnapshotForDisplay == null)
        checkpoint("cleared")
    }

    private suspend fun restoreAndResync(metadata: PhaseOneSessionMetadata) {
        val catalog = checkNotNull(store.loadCatalog(namespace, fixture.from))
        val snapshot = checkNotNull(store.loadEpg(namespace, fixture.from))
        check(metadata.seedRetainedSnapshots(catalog, snapshot))
        check(metadata.observation.value.currentSession == null)
        check(snapshot.snapshot.events.size == channelCount * 49)
        retain(snapshot.snapshot)
        checkpoint("restored")
        fixture.readyMetadata(metadata) { checkpoint("resyncing") }
        fixture.validate(metadata)
        check(metadata.observation.value.epgSnapshotForDisplay !== snapshot.snapshot)
        // Keep this reference local to the helper so it cannot retain the guide after clear.
    }

    private fun retain(snapshot: EpgSnapshot) {
        if (retainedSnapshots == 0 || held.lastOrNull() === snapshot) return
        held.addLast(snapshot)
        while (held.size > retainedSnapshots) held.removeFirst()
    }

    private fun checkpoint(phase: String) {
        if (!heapCheckpoints) return
        // Opt-in diagnostic runs only. Timings and GC allocation scores in these runs are invalid.
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        val before = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
        System.gc()
        check(collectors.sumOf { it.collectionCount.coerceAtLeast(0) } > before) {
            "Heap checkpoints require synchronous explicit GC"
        }
        CacheHeapCheckpoint().also {
            it.phase = phase
            it.cycle = cycle
            it.heldSnapshots = held.size
            it.usedBytes = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            it.commit()
        }
    }

    @TearDown(Level.Trial)
    public fun teardown() {
        check(held.size == retainedSnapshots)
        held.forEach { check(it.events.size == channelCount * 49) }
        held.clear()
        check(root.deleteRecursively()) { "Synthetic cache cleanup failed" }
    }
}

/** JDK Flight Recorder owns recording and export; only bounded synthetic checkpoint data is emitted. */
@Name("tvheadend.benchmark.CacheHeapCheckpoint")
@Label("SDK synthetic cache lifecycle heap")
public class CacheHeapCheckpoint : Event() {
    @JvmField public var phase: String = ""
    @JvmField public var cycle: Int = 0
    @JvmField public var heldSnapshots: Int = 0
    @JvmField public var usedBytes: Long = 0
}

/** Warm-file decode only: 33,000 synthetic events over roughly seven days on 143 channels. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
public open class CacheRestoreBenchmarks {
    private lateinit var store: FileMetadataCacheStore
    private lateinit var root: java.io.File
    private val namespace = cacheNamespace("benchmark.invalid", 9982, "")
    private val now = kotlin.time.Instant.fromEpochSeconds(1_700_000_000)

    @Setup(Level.Trial)
    public fun setup(): Unit = runBlocking {
        root = Files.createTempDirectory("sdk-restore-benchmark-").toFile()
        store = FileMetadataCacheStore(root, object : kotlin.time.Clock {
            override fun now(): kotlin.time.Instant = now
        })
        val snapshot = EpgSnapshot.create(events = List(33_000) { index ->
            val start = now.epochSeconds + index / 143 * 2700L
            at.bernhardberger.tvheadend.sdk.core.EpgEvent.create(
                at.bernhardberger.tvheadend.sdk.core.EventId(index.toLong()),
                at.bernhardberger.tvheadend.sdk.core.ChannelId((index % 143).toLong()),
                kotlin.time.Instant.fromEpochSeconds(start), kotlin.time.Instant.fromEpochSeconds(start + 2700),
                title = "Programme $index", description = "Synthetic programme description. ".repeat(8),
            )
        })
        store.storeEpg(namespace, CachedEpg(snapshot), now)
        check(checkNotNull(store.loadEpg(namespace, now)).snapshot.events.size == 4_719)
    }

    @Benchmark
    public fun decodeGuide(): Int = runBlocking {
        checkNotNull(store.loadEpg(namespace, now)).snapshot.events.size
    }

    @TearDown(Level.Trial)
    public fun teardown() {
        check(root.deleteRecursively()) { "Synthetic cache cleanup failed" }
    }
}
