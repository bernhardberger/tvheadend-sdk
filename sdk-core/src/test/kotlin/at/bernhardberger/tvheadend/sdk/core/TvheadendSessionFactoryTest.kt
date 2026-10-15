package at.bernhardberger.tvheadend.sdk.core

import at.bernhardberger.tvheadend.sdk.core.gateway.htsp.HtspProtocolGateway
import at.bernhardberger.tvheadend.sdk.core.session.PlaybackSessionChildren
import at.bernhardberger.tvheadend.sdk.core.session.SessionChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir

internal class TvheadendSessionFactoryTest {
    @TempDir
    lateinit var cacheRoot: File

    @Test
    fun `fresh equal policies and policy-free lookup reuse configured owner`() = runTest {
        val first = createTvheadendSession(
            EpgCoveragePolicy.create(7.days, 123),
            MetadataCachePolicy.create(cacheRoot),
        )
        try {
            assertSame(first, createTvheadendSession())
            assertSame(
                first,
                createTvheadendSession(
                    EpgCoveragePolicy.create(7.days, 123),
                    MetadataCachePolicy.create(File(cacheRoot, "unused/..")),
                ),
            )
        } finally {
            first.shutdown()
        }
    }

    @Test
    fun `each conflicting policy field throws without exposing configuration`() = runTest {
        val policy = EpgCoveragePolicy.create(7.days, 123)
        val cache = MetadataCachePolicy.create(cacheRoot)
        val first = createTvheadendSession(policy, cache)
        try {
            val conflictingCalls = listOf<() -> TvheadendSession>(
                { createTvheadendSession(EpgCoveragePolicy.create(6.days, 123), cache) },
                { createTvheadendSession(EpgCoveragePolicy.create(7.days, 124), cache) },
                { createTvheadendSession(policy) },
                { createTvheadendSession(policy, MetadataCachePolicy.create(File(cacheRoot, "different"))) },
                { createTvheadendSession(policy, MetadataCachePolicy.create(cacheRoot, metadataRetention = 8.days)) },
                { createTvheadendSession(policy, MetadataCachePolicy.create(cacheRoot, artworkRetention = 31.days)) },
                { createTvheadendSession(policy, MetadataCachePolicy.create(cacheRoot, artworkMaxBytes = 1)) },
            )
            conflictingCalls.forEach { acquire ->
                val error = assertThrows(IllegalStateException::class.java) { acquire() }
                assertEquals("The active Tvheadend session has different policies", error.message)
                assertFalse(error.message.orEmpty().contains(cacheRoot.path))
                assertSame(first, createTvheadendSession())
            }
        } finally {
            first.shutdown()
        }
    }

    @Test
    fun `shutdown releases configuration and permits different policies`() = runTest {
        val first = createTvheadendSession(EpgCoveragePolicy.create(7.days))
        try {
            assertThrows(IllegalStateException::class.java) {
                createTvheadendSession(EpgCoveragePolicy.create(7.days), MetadataCachePolicy.create(cacheRoot))
            }
        } finally {
            first.shutdown()
        }
        val replacement = createTvheadendSession(
            EpgCoveragePolicy.create(2.days),
            MetadataCachePolicy.create(cacheRoot),
        )
        try {
            assertNotSame(first, replacement)
            assertSame(replacement, createTvheadendSession())
        } finally {
            replacement.shutdown()
        }
    }

    @Test
    fun `shutdown in progress retains the owner and rejects conflicting policies`() = runTest {
        val shuttingDown = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val children = object : SessionChildren by SessionChildren.None {
            override suspend fun closeAndJoinSubscriptions() {
                shuttingDown.complete(Unit)
                release.await()
            }
        }
        val first = SessionRegistry.acquire(EpgCoveragePolicy.create(7.days)) { policy, _ ->
            SessionRegistry.createOwner(policy, childrenFactory = { _, _, _ -> children })
        }
        val shutdown = launch { first.shutdown() }
        try {
            shuttingDown.await()
            assertFalse(shutdown.isCompleted)
            assertSame(first, createTvheadendSession())
            assertSame(first, createTvheadendSession(EpgCoveragePolicy.create(7.days)))
            assertThrows(IllegalStateException::class.java) {
                createTvheadendSession(EpgCoveragePolicy.create(2.days))
            }
            assertThrows(IllegalStateException::class.java) {
                createTvheadendSession(EpgCoveragePolicy.create(7.days), MetadataCachePolicy.create(cacheRoot))
            }
        } finally {
            release.complete(Unit)
            shutdown.join()
        }
        val replacement = createTvheadendSession(EpgCoveragePolicy.create(2.days))
        try {
            assertNotSame(first, replacement)
        } finally {
            replacement.shutdown()
        }
    }

    @Test
    fun `concurrent equal policy requests acquire exactly one owner`() = runTest {
        val results = acquireConcurrently(List(8) { EpgCoveragePolicy.create(7.days, 123) })
        try {
            assertTrue(results.all { it.isSuccess })
            val first = results.first().getOrThrow()
            results.forEach { assertSame(first, it.getOrThrow()) }
        } finally {
            results.mapNotNull { it.getOrNull() }.distinct().forEach { it.shutdown() }
        }
    }

    @Test
    fun `concurrent conflicting policy requests have exactly one winner`() = runTest {
        val results = acquireConcurrently(List(8) { EpgCoveragePolicy.create(7.days, it + 1) })
        try {
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(7, results.count { it.exceptionOrNull() is IllegalStateException })
            assertSame(results.single { it.isSuccess }.getOrThrow(), createTvheadendSession())
        } finally {
            results.mapNotNull { it.getOrNull() }.distinct().forEach { it.shutdown() }
        }
    }

    private suspend fun acquireConcurrently(policies: List<EpgCoveragePolicy>): List<Result<TvheadendSession>> =
        coroutineScope {
            val ready = Channel<Unit>(policies.size)
            val start = CompletableDeferred<Unit>()
            val acquisitions = policies.map { policy ->
                async(Dispatchers.Default) {
                    ready.send(Unit)
                    start.await()
                    try {
                        Result.success(createTvheadendSession(policy))
                    } catch (failure: IllegalStateException) {
                        Result.failure(failure)
                    }
                }
            }
            repeat(policies.size) { ready.receive() }
            start.complete(Unit)
            acquisitions.awaitAll()
        }

    @Test
    fun `factory enforces one production owner and releases it after shutdown`() = runTest {
        val first = createTvheadendSession(EpgCoveragePolicy.create(7.days))
        val same = createTvheadendSession()
        assertSame(first, same)

        first.shutdown()
        val replacement = createTvheadendSession()
        try {
            assertNotSame(first, replacement)
        } finally {
            replacement.shutdown()
        }
    }

    @Test
    fun `owner factory fans the same policy out to gateway and worker`() = runTest {
        val policy = EpgCoveragePolicy.create(
            futureHorizon = 7.days,
            maximumRetainedEvents = 123,
        )
        var gatewayPolicy: EpgCoveragePolicy? = null
        var workerPolicy: EpgCoveragePolicy? = null
        val owner = SessionRegistry.createOwner(
            epgCoveragePolicy = policy,
            gatewayFactory = { suppliedPolicy ->
                gatewayPolicy = suppliedPolicy
                HtspProtocolGateway(Dispatchers.Unconfined, suppliedPolicy)
            },
            childrenFactory = { gateway, metadata, settings ->
                workerPolicy = settings.coveragePolicy
                PlaybackSessionChildren(
                    gateway = gateway,
                    metadata = metadata,
                    dispatcher = Dispatchers.Unconfined,
                    epgSettings = settings,
                )
            },
        )

        try {
            assertSame(policy, gatewayPolicy)
            assertSame(policy, workerPolicy)
        } finally {
            owner.shutdown()
        }
    }
}
