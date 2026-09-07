package at.bernhardberger.tvheadend.sdk.core.cache

import at.bernhardberger.tvheadend.sdk.core.ArtworkContent
import at.bernhardberger.tvheadend.sdk.core.ArtworkFailure
import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ArtworkLoadResult
import at.bernhardberger.tvheadend.sdk.core.CacheStatistics
import at.bernhardberger.tvheadend.sdk.core.MetadataCachePolicy
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
        val unavailable = ArtworkLoadResult.Unavailable(ArtworkFailure.FILE_UNAVAILABLE)
        assertSame(unavailable, runtime.loadArtwork(namespace, id, { true }) { unavailable })
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)

        runtime.loadArtwork(namespace, id, { true }) { available() }
        val directory = File(root, "tvheadend-sdk/${namespace.value}/artwork")
        File(directory, "1").writeBytes(byteArrayOf(9, 9, 9))
        assertSame(unavailable, runtime.loadArtwork(namespace, id, { true }) { unavailable })
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        runtime.loadArtwork(namespace, id, { true }) { available() }
        File(directory, "index.bin").writeBytes(byteArrayOf(0))
        assertSame(unavailable, runtime.loadArtwork(namespace, id, { true }) { unavailable })
        assertEquals(CacheStatistics.EMPTY, runtime.statistics.value)
        runtime.shutdown()
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
        assertEquals(ArtworkFailure.CONNECTION_CHANGED, (result as ArtworkLoadResult.Unavailable).failure)
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
            assertNull(store.load(namespace, id, now, now - 10.seconds))
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
                ArtworkLoadResult.Unavailable(ArtworkFailure.FILE_UNAVAILABLE)
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

    private fun available(): ArtworkLoadResult.Available = ArtworkLoadResult.Available(ArtworkContent.create(bytes))

    private fun TestScope.runtime(): MetadataCacheRuntime = MetadataCacheRuntime(
        FileMetadataCacheStore(root), MetadataCachePolicy.create(root, artworkRetention = 10.seconds),
        StandardTestDispatcher(testScheduler), clock,
    )
}
