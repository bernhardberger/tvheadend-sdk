package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
import at.bernhardberger.tvheadend.sdk.playback.TvheadendFailure
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ArtworkCacheTest {
    @TempDir lateinit var root: File
    private val namespace = cacheNamespace("fixture", 9982, "user")
    private val other = cacheNamespace("other", 9982, "user")
    private val id = ArtworkId(1)
    private val bytes = byteArrayOf(1, 2, 3)
    private var now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock = object : Clock { override fun now(): Instant = now }

    @Test
    fun `formerly ambiguous profiles never share artwork bytes`() = runTest {
        val runtime = runtime()
        val first = cacheNamespace("::1", 2, "3:4:u")
        val second = cacheNamespace("::1:2:3", 4, "u")
        runtime.loadArtwork(first, id, { true }) { available() }
        val replacement = byteArrayOf(4, 5)
        val loaded = runtime.loadArtwork(second, id, { true }) {
            ArtworkLoadResult.Available(ArtworkContent.create(replacement))
        }
        assertArrayEquals(replacement, (loaded as ArtworkLoadResult.Available).content.openStream().readBytes())
        val retained = runtime.loadArtwork(first, id, { true }) { error("Unexpected fetch") }
        assertArrayEquals(bytes, (retained as ArtworkLoadResult.Available).content.openStream().readBytes())
        runtime.shutdown()
    }

    @Test
    fun `cached reads only read and recheck authority after the read`() = runTest {
        val runtime = runtime()
        val miss = runtime.loadCachedArtwork(namespace, id) { true }
        assertEquals(TvheadendFailure.NOT_READY, (miss as ArtworkLoadResult.Unavailable).failure)
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        runtime.loadArtwork(namespace, id, { true }) { available() }
        val hit = runtime.loadCachedArtwork(namespace, id) { true }
        assertArrayEquals(bytes, (hit as ArtworkLoadResult.Available).content.openStream().readBytes())
        var checks = 0
        val raced = runtime.loadCachedArtwork(namespace, id) { ++checks < 2 }
        assertEquals(TvheadendFailure.CONNECTION_CHANGED, (raced as ArtworkLoadResult.Unavailable).failure)
        assertEquals(2, checks)
        now += 11.seconds
        val expired = runtime.loadCachedArtwork(namespace, id) { true }
        assertEquals(TvheadendFailure.NOT_READY, (expired as ArtworkLoadResult.Unavailable).failure)
        runtime.shutdown()
    }

    @Test
    fun `restart hits avoid fetch and clear removes metadata and artwork in every namespace`() = runTest {
        val first = runtime()
        first.restore(namespace)
        first.loadArtwork(namespace, id, { true }) { available() }
        first.loadArtwork(other, id, { true }) { available() }
        assertEquals(CacheStatistics(0, 6, 2), first.statistics.value)
        first.shutdown()

        val restarted = runtime()
        restarted.restore(namespace)
        val hit = restarted.loadArtwork(namespace, id, { true }) { error("Unexpected fetch") }
        assertArrayEquals(bytes, (hit as ArtworkLoadResult.Available).content.openStream().readBytes())
        assertEquals(CacheStatistics(0, 6, 2), restarted.statistics.value)
        restarted.clear()
        assertEquals(CacheStatistics.EMPTY, restarted.statistics.value)
        assertEquals(0, root.walkTopDown().count { it.isFile })
        restarted.shutdown()
    }

    @Test
    fun `LRU budget spans namespaces and touch does not extend retention`() = runTest {
        val store = FileArtworkCacheStore(root)
        store.store(namespace, id, bytes, now, now - 10.seconds, 6)
        now += 1.seconds
        store.store(other, id, bytes, now, now - 10.seconds, 6)
        now += 1.seconds
        assertArrayEquals(bytes, store.load(namespace, id, now, now - 10.seconds))
        now += 1.seconds
        store.store(namespace, ArtworkId(2), bytes, now, now - 10.seconds, 6)
        assertNull(store.load(other, id, now, now - 10.seconds))
        assertEquals(CacheStatistics(0, 6, 2), FileMetadataCacheStore(root).statistics())
        now += 8.seconds
        store.prune(now - 10.seconds, 6)
        assertNull(store.load(namespace, id, now, now - 10.seconds))
        assertArrayEquals(bytes, store.load(namespace, ArtworkId(2), now, now - 10.seconds))
        store.store(namespace, ArtworkId(3), ByteArray(7), now, now - 10.seconds, 6)
        assertEquals(CacheStatistics(0, 3, 1), FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `expired unavailable and corrupt bytes or index are removed`() = runTest {
        val runtime = runtime()
        runtime.loadArtwork(namespace, id, { true }) { available() }
        now += 11.seconds
        val unavailable = ArtworkLoadResult.Unavailable(TvheadendFailure.FILE_UNAVAILABLE)
        assertSame(unavailable, runtime.loadArtwork(namespace, id, { true }) { unavailable })
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)

        runtime.loadArtwork(namespace, id, { true }) { available() }
        val directory = File(root, "tvheadend-sdk/${namespace.value}/artwork")
        File(directory, "1").writeBytes(byteArrayOf(9, 9, 9))
        assertSame(unavailable, runtime.loadArtwork(namespace, id, { true }) { unavailable })
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        runtime.loadArtwork(namespace, id, { true }) { available() }
        runtime.shutdown()
        File(directory, "index.bin").writeBytes(byteArrayOf(0))
        // The index is disposable startup state, held in memory for the lifetime of one runtime.
        val restarted = runtime()
        assertSame(unavailable, restarted.loadArtwork(namespace, id, { true }) { unavailable })
        assertEquals(CacheStatistics.EMPTY, restarted.statistics.value)
        restarted.shutdown()
    }

    @Test
    fun `clear and retired authority fence delayed fetch writes and cancellation propagates`() = runTest {
        val runtime = runtime()
        val completion = CompletableDeferred<Unit>()
        var current = true
        val pending = async {
            runtime.loadArtwork(namespace, id, { current }) { completion.await(); available() }
        }
        runCurrent()
        runtime.clear()
        completion.complete(Unit)
        pending.await()
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)

        val result = runtime.loadArtwork(namespace, id, { current }) { current = false; available() }
        assertEquals(TvheadendFailure.CONNECTION_CHANGED, (result as ArtworkLoadResult.Unavailable).failure)
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        val cancellation = CancellationException("cancelled")
        try {
            runtime.loadArtwork(namespace, id, { true }) { throw cancellation }
            error("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        runtime.shutdown()
    }

    @Test
    fun `truncated indexes are disposable and replacing an entry resets retention`() = runTest {
        val store = FileArtworkCacheStore(root)
        store.store(namespace, id, bytes, now, now - 10.seconds, 6)
        val index = File(root, "tvheadend-sdk/${namespace.value}/artwork/index.bin")
        val encoded = index.readBytes()
        for (length in encoded.indices) {
            index.writeBytes(encoded.copyOf(length))
            assertNull(FileArtworkCacheStore(root).load(namespace, id, now, now - 10.seconds))
            store.store(namespace, id, bytes, now, now - 10.seconds, 6)
        }
        now += 9.seconds
        val replacement = byteArrayOf(4, 5)
        store.store(namespace, id, replacement, now, now - 10.seconds, 6)
        now += 2.seconds
        assertArrayEquals(replacement, store.load(namespace, id, now, now - 10.seconds))
        assertEquals(CacheStatistics(0, 2, 1), FileMetadataCacheStore(root).statistics())
    }

    @Test
    fun `concurrent stores retain distinct IDs and file unavailable removes a raced fresh entry`() = runTest {
        val runtime = runtime()
        val completion = CompletableDeferred<Unit>()
        val missing = async {
            runtime.loadArtwork(namespace, id, { true }) {
                completion.await()
                ArtworkLoadResult.Unavailable(TvheadendFailure.FILE_UNAVAILABLE)
            }
        }
        runCurrent()
        val first = async { runtime.loadArtwork(namespace, id, { true }) { available() } }
        val second = async { runtime.loadArtwork(namespace, ArtworkId(2), { true }) { available() } }
        first.await()
        second.await()
        assertEquals(CacheStatistics(0, 6, 2), runtime.statistics.value)
        completion.complete(Unit)
        missing.await()
        assertEquals(CacheStatistics(0, 3, 1), runtime.statistics.value)
        runtime.loadArtwork(namespace, ArtworkId(2), { true }) { error("Unexpected fetch") }
        runtime.shutdown()
    }

    @Test
    fun `unwritable cache never replaces successful fetched content with failure`() = runTest {
        File(root, "tvheadend-sdk").writeText("not a directory")
        val runtime = runtime()
        val content = available()
        assertSame(content, runtime.loadArtwork(namespace, id, { true }) { content })
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        runtime.shutdown()
    }

    @Test
    fun `artwork reads do not wait for a blocked guide restore`() = runTest {
        runtime().apply { loadArtwork(namespace, id, { true }) { available() } }.shutdown()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = runtime(gatedEpgStore(loading, release))
        val restore = async { runtime.restore(namespace) }
        loading.await()
        val cached = async { runtime.loadCachedArtwork(namespace, id) { true } }
        val fetched = async { runtime.loadArtwork(namespace, ArtworkId(2), { true }) { available() } }
        runCurrent()
        assertTrue(cached.isCompleted && fetched.isCompleted, "Artwork reads must not queue behind the guide decode")
        assertArrayEquals(bytes, (cached.await() as ArtworkLoadResult.Available).content.openStream().readBytes())
        assertFalse(restore.isCompleted)
        release.complete(Unit)
        restore.await()
        runtime.shutdown()
    }

    @Test
    fun `restore prunes expired artwork of every namespace after accepting the guide`() = runTest {
        runtime().apply { loadArtwork(other, id, { true }) { available() } }.shutdown()
        now += 11.seconds
        val file = File(root, "tvheadend-sdk/${other.value}/artwork/1")
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = runtime(gatedEpgStore(loading, release))
        val retainedAtAcceptance = mutableListOf<Boolean>()
        val restore = async { runtime.restore(namespace) { retainedAtAcceptance += file.isFile } }
        loading.await()
        runCurrent()
        assertTrue(file.isFile)
        release.complete(Unit)
        restore.await()
        runCurrent() // The prune runs in its own runtime job.
        assertEquals(listOf(true, true), retainedAtAcceptance)
        assertFalse(file.exists())
        runtime.shutdown()
    }

    @Test
    fun `prune leaves legacy namespaces to catalog restore`() {
        val legacy = File(root, "tvheadend-sdk/${"0".repeat(32)}/artwork").apply { mkdirs() }
        File(legacy, "1").writeBytes(bytes)
        FileArtworkCacheStore(root).prune(now, 0)
        assertTrue(File(legacy, "1").isFile)
    }

    @Test
    fun `clear queued behind a blocked guide restore still invalidates artwork`() = runTest {
        runtime().apply { loadArtwork(namespace, id, { true }) { available() } }.shutdown()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = runtime(gatedEpgStore(loading, release))
        val restore = async { runtime.restore(namespace) }
        loading.await()
        val completion = CompletableDeferred<Unit>()
        val pending = async {
            runtime.loadArtwork(namespace, ArtworkId(2), { true }) { completion.await(); available() }
        }
        runCurrent()
        val clear = async { runtime.clear() }
        runCurrent()
        assertFalse(clear.isCompleted)
        release.complete(Unit)
        clear.await()
        val cleared = runtime.loadCachedArtwork(namespace, id) { true }
        assertEquals(TvheadendFailure.NOT_READY, (cleared as ArtworkLoadResult.Unavailable).failure)
        completion.complete(Unit)
        pending.await()
        assertFalse(restore.await().isCurrent())
        runCurrent() // A prune after clear must not recreate the deleted tree.
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        assertEquals(0, root.walkTopDown().count { it.isFile })
        runtime.shutdown()
    }

    private fun gatedEpgStore(loading: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>): MetadataCacheStore {
        val store = FileMetadataCacheStore(root)
        return object : MetadataCacheStore by store {
            override suspend fun loadEpg(namespace: CacheNamespace, notBefore: Instant): CachedEpg? {
                loading.complete(Unit)
                release.await()
                return store.loadEpg(namespace, notBefore)
            }
        }
    }

    private fun available(): ArtworkLoadResult.Available = ArtworkLoadResult.Available(ArtworkContent.create(bytes))

    private fun TestScope.runtime(store: MetadataCacheStore = FileMetadataCacheStore(root)) = MetadataCacheRuntime(
        store, MetadataCachePolicy.create(root, artworkRetention = 10.seconds),
        StandardTestDispatcher(testScheduler), clock,
    )
}
