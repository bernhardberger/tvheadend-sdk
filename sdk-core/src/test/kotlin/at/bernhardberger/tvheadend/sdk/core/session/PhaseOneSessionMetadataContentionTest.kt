package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageBatchSettlement
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant

internal class PhaseOneSessionMetadataContentionTest {
    @Test
    fun `readers and coverage cancellation proceed while query publication is paused`() {
        val generation = GatewayGeneration()
        val metadata = readyMetadata(generation, channelCount = 2, eventsPerChannel = 2)
        val before = metadata.observation.value
        val current = requireNotNull(before.currentSession)
        val worker = EpgWorker(generation, metadata, object : Clock {
            override fun now(): Instant = instant(0)
        }) { _, _, _ -> error("No query should be dispatched") }
        assertTrue(metadata.bindEpgCoverageRequester(generation, worker))
        val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(0)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit<EpgQueryAcceptance> {
                metadata.applySuccessfulEpgQuery(generation, query, instant(7200), gatedEvents(
                    listOf(queryEvent(0, "query")), entered, release,
                ))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reader = executor.submit {
                assertSame(generation, metadata.resolveGeneration(current))
                assertSame(before, metadata.currentObservation(generation, current))
                runBlocking {
                    val single = metadata.epgRepository.acquireCoverage(current, ChannelId(0), instant(100))
                        as EpgCoverageAcquisitionResult.CoveredWithData
                    assertSame(before, single.observation)
                    val batch = metadata.epgRepository.acquireCoverageBatch(
                        current, listOf(ChannelId(1), ChannelId(0), ChannelId(1), ChannelId(99)), instant(100),
                    )
                    assertEquals(listOf(ChannelId(1), ChannelId(0), ChannelId(99)), batch.settlements.map { it.channelId })
                    assertTrue(batch.settlements.last() is EpgCoverageBatchSettlement.TargetAbsent)
                    batch.settlements.take(2).forEach {
                        assertSame(before, (it as EpgCoverageBatchSettlement.CoveredWithData).observation)
                    }
                    val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                        metadata.epgRepository.acquireCoverageBatch(current, listOf(ChannelId(0)), instant(7200))
                        error("Uncovered request must suspend")
                    }
                    assertFalse(waiting.isCompleted)
                    waiting.cancelAndJoin()
                    assertTrue(waiting.isCancelled)
                }
            }
            reader.get(5, TimeUnit.SECONDS)
            assertFalse(writer.isDone)
            release.countDown()
            assertSame(EpgQueryAcceptance.APPLIED, writer.get(5, TimeUnit.SECONDS))
            assertEquals("initial", before.event(EventId(0))?.title)
            assertEquals("query", metadata.currentObservation(generation, current)?.event(EventId(0))?.title)
            metadata.acceptMetadata(MetadataEvent.EventUpdated(generation, GatewayEpgUpdate(EventId(0), title = "live")))
            assertEquals("live", metadata.currentObservation(generation, current)?.event(EventId(0))?.title)
            metadata.retainEpgEvents(generation, instant(1801), instant(10_000))
            val after = requireNotNull(metadata.currentObservation(generation, current))
            assertEquals(listOf(EventId(2), EventId(3)), after.epgSnapshotForDisplay?.events?.map { it.id })
            assertEquals(instant(7200), after.coverage(ChannelId(0))?.queriedTo)
            assertEquals("initial", before.event(EventId(0))?.title)

            metadata.resetWorkingStateRetainingPublishedSnapshot()
            assertNull(metadata.resolveGeneration(current))
            assertNull(metadata.currentObservation(generation, current))
            val replacement = GatewayGeneration()
            metadata.bindGeneration(replacement)
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(replacement, channel(0)))
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(replacement))
            publishReady(metadata, replacement)
            runBlocking {
                assertSame(EpgCoverageAcquisitionResult.ObservationExpired,
                    metadata.epgRepository.acquireCoverage(current, ChannelId(0), instant(100)))
            }
            assertNull(metadata.currentObservation(generation, current))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `representative contended publication reader workload`() {
        val generation = GatewayGeneration()
        val metadata = readyMetadata(generation, channelCount = 100, eventsPerChannel = 200)
        val current = requireNotNull(metadata.observation.value.currentSession)
        val executor = Executors.newSingleThreadExecutor()
        val latencies = ArrayList<Long>()
        try {
            repeat(40) { iteration ->
                val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(0)))
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                // Reproducible measurement only: release races the read, with no timing threshold.
                // The separate paused-query test gates progress before the writer is released.
                val writer = executor.submit {
                    metadata.applySuccessfulEpgQuery(generation, query, instant(400_000), gatedEvents(
                        listOf(queryEvent(0, "query-$iteration")), entered, release,
                    ))
                    metadata.retainEpgEvents(generation, instant(iteration * 1800L), instant(400_000))
                }
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    val start = System.nanoTime()
                    release.countDown()
                    assertSame(generation, metadata.resolveGeneration(current))
                    val observed = requireNotNull(metadata.currentObservation(generation, current))
                    latencies += System.nanoTime() - start
                    assertSame(current, observed.currentSession)
                    assertTrue(observed.epgState is EpgRepositoryState.Current)
                    writer.get(5, TimeUnit.SECONDS)
                } finally {
                    release.countDown()
                }
            }
            val sorted = latencies.sorted()
            println("EPG contention: samples=${sorted.size}, median-ns=${sorted[sorted.size / 2]}, max-ns=${sorted.last()}")
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent generation replacement never mixes published channel and EPG authority`() {
        val metadata = PhaseOneSessionMetadata()
        val executor = Executors.newSingleThreadExecutor()
        val start = CountDownLatch(1)
        val firstRead = CountDownLatch(1)
        try {
            val writer = executor.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(100) { revision ->
                    val generation = GatewayGeneration()
                    metadata.bindGeneration(generation)
                    metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation,
                        channel(revision.toLong())))
                    metadata.acceptMetadata(MetadataEvent.EventAdded(generation, GatewayEpgEvent(
                        EventId(revision.toLong()), ChannelId(revision.toLong()), instant(0), instant(1800),
                    )))
                    metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
                    publishReady(metadata, generation)
                    if (revision == 0) assertTrue(firstRead.await(5, TimeUnit.SECONDS))
                    metadata.resetWorkingStateRetainingPublishedSnapshot()
                }
            }
            start.countDown()
            var reads = 0
            while (!writer.isDone || reads < 1000) {
                val published = metadata.observation.value
                val current = published.currentSession
                if (current != null) {
                    val generation = current.generation as GatewayGeneration
                    metadata.currentObservation(generation, current)?.let { observed ->
                        assertSame(current, observed.currentSession)
                        assertSame(published.channelCatalogForDisplay, observed.channelCatalogForDisplay)
                        assertEquals(observed.channelCatalogForDisplay?.channels?.single()?.id,
                            observed.epgSnapshotForDisplay?.events?.single()?.channelId)
                        firstRead.countDown()
                    }
                }
                reads += 1
            }
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            firstRead.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun readyMetadata(generation: GatewayGeneration, channelCount: Int, eventsPerChannel: Int): PhaseOneSessionMetadata {
        val metadata = PhaseOneSessionMetadata()
        metadata.bindGeneration(generation)
        repeat(channelCount) {
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(it.toLong())))
        }
        repeat(channelCount * eventsPerChannel) {
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, GatewayEpgEvent(
                EventId(it.toLong()), ChannelId((it % channelCount).toLong()),
                instant(it / channelCount * 1800L), instant(it / channelCount * 1800L + 1800), title = "initial",
            )))
        }
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        return metadata
    }

    private fun publishReady(metadata: PhaseOneSessionMetadata, generation: GatewayGeneration) {
        metadata.publishSessionState(
            SessionState.Ready(ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN)),
            RecordingProgressCapability.UNKNOWN, generation,
        )
    }

    private fun queryEvent(id: Long, title: String): GatewayEpgQueryEvent =
        GatewayEpgQueryEvent(EventId(id), ChannelId(0), instant(0), instant(1800), title = title)

    private fun channel(id: Long): GatewayChannelMetadata = GatewayChannelMetadata(
        id = ChannelId(id), name = null, uuid = null, number = null, numberMinor = null,
        icon = null, currentEventId = null, nextEventId = null, services = null, tagIds = null,
    )

    private fun gatedEvents(events: List<GatewayEpgQueryEvent>, entered: CountDownLatch, release: CountDownLatch): List<GatewayEpgQueryEvent> =
        object : AbstractList<GatewayEpgQueryEvent>() {
            override val size: Int = events.size
            override fun get(index: Int): GatewayEpgQueryEvent {
                if (index == 0) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Query fixture was not released" }
                }
                return events[index]
            }
        }

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
