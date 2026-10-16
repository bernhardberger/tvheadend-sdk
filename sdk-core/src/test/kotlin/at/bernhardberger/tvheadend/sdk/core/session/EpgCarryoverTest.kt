@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.cache.CachedEpg
import at.bernhardberger.tvheadend.sdk.core.cache.FileMetadataCacheStore
import at.bernhardberger.tvheadend.sdk.core.cache.cacheNamespace
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Cached or retained programmes beyond live coverage stay visible until live coverage replaces them. */
internal class EpgCarryoverTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock = object : Clock { override fun now(): Instant = now }

    @TempDir
    lateinit var root: File

    @Test
    fun `warm start keeps cached programmes beyond the initial live window after ready`() {
        val metadata = PhaseOneSessionMetadata(clock = clock)
        val cached = EpgSnapshot.create(
            listOf(
                cachedEvent(1, 0.hours, 2.hours, "cached"),
                cachedEvent(2, 2.hours, 4.hours),
                cachedEvent(3, 7.hours, 9.hours),
                cachedEvent(4, 20.hours, 22.hours),
            ),
            listOf(EpgCoverage.create(ChannelId(1), now, now + 22.hours)),
        )
        metadata.seedRetainedSnapshots(null, CachedEpg(cached))
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(1, 0.hours, 2.hours, "live")))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(5, 2.hours, 6.hours + 30.minutes)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 6.hours))

        val snapshot = metadata.currentEpg()
        assertEquals(listOf(1L, 5L, 3L, 4L), snapshot.events.map { it.id.value })
        assertEquals("live", snapshot.events.single { it.id == EventId(1) }.title)
        val coverage = snapshot.coverages.single()
        assertEquals(now + 6.hours + 30.minutes, coverage.coveredTo, "Carryover never extends live coverage")
        assertNull(coverage.queriedTo)
        assertEquals(mapOf(ChannelId(1) to now + 6.hours), metadata.initialEpgCoverage(generation))
        assertEquals(
            now + 6.hours + 30.minutes + 4.hours,
            epgQueryTarget(coverage, now, EpgWorkerSettings(), now + 6.hours),
            "The worker still queries the range shown from carryover",
        )
    }

    @Test
    fun `reconnect keeps the previous generation's guide beyond the new live window`() {
        val metadata = PhaseOneSessionMetadata(clock = clock)
        val first = GatewayGeneration()
        metadata.bindGeneration(first)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(first, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, liveEvent(1, 0.hours, 2.hours)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, liveEvent(2, 2.hours, 6.hours)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first, now + 5.hours))
        query(metadata, first, 20.hours, queryEvent(3, 6.hours, 12.hours), queryEvent(4, 12.hours, 20.hours))
        assertEquals(listOf(1L, 2L, 3L, 4L), metadata.currentEpg().events.map { it.id.value })

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        val second = GatewayGeneration()
        metadata.bindGeneration(second)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(second, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(second, liveEvent(1, 0.hours, 2.hours)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(second, liveEvent(2, 2.hours, 6.hours)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second, now + 5.hours))

        val snapshot = metadata.currentEpg()
        assertEquals(listOf(1L, 2L, 3L, 4L), snapshot.events.map { it.id.value })
        assertEquals(now + 6.hours, snapshot.coverages.single().coveredTo)
        assertNull(snapshot.coverages.single().queriedTo, "Carryover never inherits query proof")
    }

    @Test
    fun `live version wins for every carryover event id`() {
        val metadata = PhaseOneSessionMetadata(clock = clock)
        metadata.seedRetainedSnapshots(null, CachedEpg(EpgSnapshot.create(listOf(
            cachedEvent(7, 15.hours, 16.hours, "cached"),
            cachedEvent(8, 17.hours, 18.hours, "cached"),
            cachedEvent(9, 19.hours, 20.hours, "cached"),
        ))))
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(7, 1.hours, 2.hours, "moved")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 6.hours))
        assertEquals(listOf(7L, 8L, 9L), metadata.currentEpg().events.map { it.id.value })
        assertEquals("moved", metadata.currentEpg().events.single { it.id == EventId(7) }.title)

        metadata.acceptMetadata(MetadataEvent.EventUpdated(generation, liveEvent(9, 19.hours, 20.hours, "live")))
        val snapshot = metadata.currentEpg()
        assertEquals(listOf(7L, 9L), snapshot.events.map { it.id.value }, "Live coverage now reaches beyond 8")
        assertEquals("live", snapshot.events.single { it.id == EventId(9) }.title)
    }

    @Test
    fun `coverage advance drops carryover in the newly covered range`() {
        val (metadata, generation) = syncedWithCarryover(
            cachedEvent(3, 8.hours, 9.hours),
            cachedEvent(4, 9.hours, 10.hours),
            cachedEvent(6, 14.hours, 15.hours),
        )
        assertEquals(listOf(1L, 3L, 4L, 6L), metadata.currentEpg().events.map { it.id.value })

        query(metadata, generation, 12.hours, queryEvent(10, 8.hours, 12.hours))
        assertEquals(listOf(1L, 10L, 6L), metadata.currentEpg().events.map { it.id.value })

        query(metadata, generation, 16.hours)
        assertEquals(listOf(1L, 10L), metadata.currentEpg().events.map { it.id.value })
        assertEquals(now + 16.hours, metadata.currentEpg().coverages.single().queriedTo)
    }

    @Test
    fun `server deletion removes a carryover programme`() {
        val (metadata, generation) = syncedWithCarryover(cachedEvent(3, 8.hours, 9.hours), cachedEvent(4, 9.hours, 10.hours))
        assertEquals(listOf(1L, 3L, 4L), metadata.currentEpg().events.map { it.id.value })

        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(3)))
        assertEquals(listOf(1L, 4L), metadata.currentEpg().events.map { it.id.value })
    }

    @Test
    fun `retention pruning applies to carryover`() {
        val (metadata, generation) = syncedWithCarryover(cachedEvent(3, 8.hours, 9.hours), cachedEvent(4, 30.hours, 31.hours))
        assertEquals(listOf(1L, 3L, 4L), metadata.currentEpg().events.map { it.id.value })

        metadata.retainEpgEvents(generation, retentionBounds(now - 6.hours, now + 24.hours))
        assertEquals(listOf(1L, 3L), metadata.currentEpg().events.map { it.id.value })
    }

    @Test
    fun `capacity evicts carryover before live events and never refuses live data for it`() {
        val (metadata, generation) = syncedWithCarryover(
            cachedEvent(10, 7.hours, 8.hours),
            cachedEvent(11, 8.hours, 9.hours),
            cachedEvent(12, 9.hours, 10.hours),
            policy = EpgCoveragePolicy.create(24.hours, 3),
            liveEvents = listOf(liveEvent(1, 0.hours, 3.hours), liveEvent(2, 3.hours, 6.hours)),
        )
        assertEquals(listOf(1L, 2L, 10L), metadata.currentEpg().events.map { it.id.value })

        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(13, 6.hours, 7.hours)))
        assertEquals(listOf(1L, 2L, 13L), metadata.currentEpg().events.map { it.id.value })
        assertEquals(mapOf(ChannelId(1) to now + 6.hours), metadata.initialEpgCoverage(generation),
            "Carryover must not cause a live capacity refusal")
    }

    @Test
    fun `profile switch never carries over the previous profile's guide`() {
        val metadata = PhaseOneSessionMetadata(clock = clock)
        val first = GatewayGeneration()
        metadata.bindGeneration(first)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(first, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, liveEvent(1, 0.hours, 6.hours)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first, now + 5.hours))
        query(metadata, first, 20.hours, queryEvent(3, 6.hours, 20.hours))

        metadata.clearAllState()
        val second = GatewayGeneration()
        metadata.bindGeneration(second)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(second, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(second, liveEvent(1, 0.hours, 6.hours)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second, now + 5.hours))
        assertEquals(listOf(1L), metadata.currentEpg().events.map { it.id.value })

        metadata.clearAllState()
        metadata.seedRetainedSnapshots(null, CachedEpg(EpgSnapshot.create(listOf(cachedEvent(50, 10.hours, 11.hours)))))
        val third = GatewayGeneration()
        metadata.bindGeneration(third)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(third, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(third, liveEvent(1, 0.hours, 6.hours)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(third, now + 5.hours))
        assertEquals(listOf(1L, 50L), metadata.currentEpg().events.map { it.id.value },
            "Only the selected profile's own restored cache carries over")
    }

    @Test
    fun `persisted carryover survives a short session for the next warm start`() = runTest {
        val store = FileMetadataCacheStore(root, clock)
        val namespace = cacheNamespace("host", 9982, "user")
        val (first) = syncedWithCarryover(cachedEvent(3, 8.hours, 9.hours), cachedEvent(4, 20.hours, 21.hours))
        val published = checkNotNull(first.publishedSnapshots.value)
        assertEquals(listOf(1L, 3L, 4L), published.epgSnapshot.events.map { it.id.value })
        assertEquals(now + 6.hours, published.epgSnapshot.coverages.single().coveredTo)
        store.storeEpg(namespace, CachedEpg(published.epgSnapshot, published.epgRefreshTimes), now)

        val restored = checkNotNull(store.loadEpg(namespace, now - 1.hours))
        assertEquals(now + 6.hours, restored.snapshot.coverages.single().coveredTo)
        val (second) = syncedWithCarryover(seed = restored)
        assertEquals(listOf(1L, 3L, 4L), second.currentEpg().events.map { it.id.value })
    }

    @Test
    fun `a deletion received before installation excludes the id from carryover`() {
        for (lateSeed in listOf(false, true)) {
            val metadata = PhaseOneSessionMetadata(clock = clock)
            val seed = CachedEpg(EpgSnapshot.create(listOf(cachedEvent(3, 8.hours, 9.hours), cachedEvent(4, 10.hours, 11.hours))))
            if (!lateSeed) metadata.seedRetainedSnapshots(null, seed)
            val generation = GatewayGeneration()
            metadata.bindGeneration(generation)
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(1, 0.hours, 6.hours)))
            metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(3)))
            if (lateSeed) metadata.seedRetainedSnapshots(null, seed, generation)
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 6.hours))

            assertEquals(listOf(1L, 4L), metadata.currentEpg().events.map { it.id.value }, "late seed: $lateSeed")
            assertEquals(listOf(1L, 4L), checkNotNull(metadata.publishedSnapshots.value).epgSnapshot.events.map { it.id.value },
                "A deleted programme is not persisted either (late seed: $lateSeed)")
        }
    }

    @Test
    fun `a batched live add then delete drops carryover as individual messages do`() {
        assertBatchingIndependent(listOf(1L, 4L)) { generation ->
            listOf(
                MetadataEvent.EventAdded(generation, liveEvent(20, 7.hours, 10.hours)),
                MetadataEvent.EventDeleted(generation, EventId(20)),
            )
        }
    }

    @Test
    fun `carryover stays dropped when an update shortens the live event that reached it`() {
        assertBatchingIndependent(listOf(1L, 20L, 4L)) { generation ->
            listOf(
                MetadataEvent.EventAdded(generation, liveEvent(20, 6.hours, 10.hours)),
                MetadataEvent.EventUpdated(generation, liveEvent(20, 6.hours, 7.hours)),
            )
        }
    }

    @Test
    fun `a channel without initial sync proof gets no carryover`() {
        val metadata = PhaseOneSessionMetadata(clock = clock)
        metadata.seedRetainedSnapshots(null, CachedEpg(EpgSnapshot.create(listOf(
            cachedEvent(3, 3.hours, 4.hours, channelId = 2),
            cachedEvent(4, 8.hours, 9.hours, channelId = 2),
        ))))
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(2)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(1, 0.hours, 2.hours, channelId = 2)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, epgThrough = null))

        assertEquals(emptyMap<ChannelId, Instant>(), metadata.initialEpgCoverage(generation))
        assertEquals(listOf(1L), metadata.currentEpg().events.map { it.id.value },
            "An unproven channel must not show cached programmes as CURRENT")
    }

    @Test
    fun `install keeps only carryover beyond the proven window even when live data ends earlier`() {
        val (metadata) = syncedWithCarryover(
            cachedEvent(3, 5.hours, 6.hours),
            cachedEvent(4, 7.hours, 8.hours),
            liveEvents = listOf(liveEvent(1, 0.hours, 4.hours)),
        )
        assertEquals(listOf(1L, 4L), metadata.currentEpg().events.map { it.id.value })
    }

    @Test
    fun `a proven channel without live events shows only carryover beyond its proof`() {
        val (metadata) = syncedWithCarryover(cachedEvent(3, 3.hours, 4.hours), cachedEvent(4, 7.hours, 8.hours), liveEvents = emptyList())
        val snapshot = metadata.currentEpg()
        assertEquals(listOf(4L), snapshot.events.map { it.id.value })
        assertEquals(true, snapshot.coverages.single().isEmpty, "Carryover never creates coverage")
    }

    @Test
    fun `observation lookups return carryover programmes`() {
        val (metadata) = syncedWithCarryover(cachedEvent(3, 8.hours, 9.hours, "cached"))
        val observation = metadata.observation.value
        assertEquals("cached", observation.event(EventId(3))?.title)
        assertEquals(EventId(3), observation.eventAt(ChannelId(1), now + 8.hours + 30.minutes)?.id)
    }

    @Test
    fun `channel deletion removes that channel's carryover`() {
        val metadata = PhaseOneSessionMetadata(clock = clock)
        metadata.seedRetainedSnapshots(null, CachedEpg(EpgSnapshot.create(listOf(
            cachedEvent(3, 8.hours, 9.hours),
            cachedEvent(4, 8.hours, 9.hours, channelId = 2),
        ))))
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(2)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 6.hours))
        assertEquals(listOf(3L, 4L), metadata.currentEpg().events.map { it.id.value })

        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(2)))
        assertEquals(listOf(3L), metadata.currentEpg().events.map { it.id.value })
    }

    @Test
    fun `installation applies production retention bounds`() {
        val metadata = PhaseOneSessionMetadata(clock = clock, epgWorkerSettings = EpgWorkerSettings())
        metadata.seedRetainedSnapshots(null, CachedEpg(EpgSnapshot.create(listOf(
            cachedEvent(3, 8.hours, 9.hours),
            cachedEvent(4, 25.hours, 26.hours),
        ))))
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, liveEvent(1, 0.hours, 6.hours)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 6.hours))
        assertEquals(listOf(1L, 3L), metadata.currentEpg().events.map { it.id.value })
    }

    private fun assertBatchingIndependent(expected: List<Long>, messages: (GatewayGeneration) -> List<MetadataEvent>) {
        val cached = arrayOf(cachedEvent(3, 8.hours, 9.hours), cachedEvent(4, 12.hours, 13.hours))
        val (individual, individualGeneration) = syncedWithCarryover(*cached)
        messages(individualGeneration).forEach(individual::acceptMetadata)
        assertEquals(expected, individual.currentEpg().events.map { it.id.value })

        val (batched, batchedGeneration) = syncedWithCarryover(*cached)
        batched.acceptMetadataBatch(messages(batchedGeneration))
        batched.flushDeferredMetadata()
        assertEquals(expected, batched.currentEpg().events.map { it.id.value },
            "Carryover reached by live data stays dropped when live data later recedes")
    }

    private data class Synced(val metadata: PhaseOneSessionMetadata, val generation: GatewayGeneration)

    private fun syncedWithCarryover(
        vararg cached: EpgEvent,
        policy: EpgCoveragePolicy = EpgCoveragePolicy.create(),
        liveEvents: List<GatewayEpgEvent> = listOf(liveEvent(1, 0.hours, 6.hours)),
        seed: CachedEpg = CachedEpg(EpgSnapshot.create(cached.toList())),
    ): Synced {
        val metadata = PhaseOneSessionMetadata(epgCoveragePolicy = policy, clock = clock)
        metadata.seedRetainedSnapshots(null, seed)
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        liveEvents.forEach { metadata.acceptMetadata(MetadataEvent.EventAdded(generation, it)) }
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, now + 6.hours))
        return Synced(metadata, generation)
    }

    private fun query(
        metadata: PhaseOneSessionMetadata,
        generation: GatewayGeneration,
        through: Duration,
        vararg events: GatewayEpgQueryEvent,
    ) {
        val fence = checkNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        assertEquals(
            EpgQueryAcceptance.APPLIED,
            metadata.applySuccessfulEpgQuery(generation, fence, now + through, events.toList()),
        )
    }

    private fun PhaseOneSessionMetadata.currentEpg(): EpgSnapshot =
        (observation.value.epgState as EpgRepositoryState.Current).snapshot

    private fun channel(id: Long): GatewayChannelMetadata = GatewayChannelMetadata(
        id = ChannelId(id),
        name = null,
        uuid = null,
        number = null,
        numberMinor = null,
        icon = null,
        currentEventId = null,
        nextEventId = null,
        services = null,
        tagIds = null,
    )

    private fun cachedEvent(id: Long, start: Duration, stop: Duration, title: String? = null, channelId: Long = 1): EpgEvent =
        EpgEvent.create(id = EventId(id), channelId = ChannelId(channelId), start = now + start, stop = now + stop, title = title)

    private fun liveEvent(id: Long, start: Duration, stop: Duration, title: String? = null, channelId: Long = 1): GatewayEpgEvent =
        GatewayEpgEvent(
            id = EventId(id),
            channelId = ChannelId(channelId),
            start = now + start,
            stop = now + stop,
            title = title,
        )

    private fun queryEvent(id: Long, start: Duration, stop: Duration): GatewayEpgQueryEvent = GatewayEpgQueryEvent(
        id = EventId(id),
        channelId = ChannelId(1),
        start = now + start,
        stop = now + stop,
    )
}
