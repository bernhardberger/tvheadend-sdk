@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.session

import at.bernhardberger.tvheadend.sdk.core.MetadataRefreshTimes
import at.bernhardberger.tvheadend.sdk.core.cache.CachedCatalog
import at.bernhardberger.tvheadend.sdk.core.cache.CachedEpg
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgReducer
import at.bernhardberger.tvheadend.sdk.core.metadata.EpgQueryAcceptance
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ChannelIcon
import at.bernhardberger.tvheadend.sdk.core.CapabilityAccess
import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ChannelService
import at.bernhardberger.tvheadend.sdk.core.DvrConfigId
import at.bernhardberger.tvheadend.sdk.core.DvrConfiguration
import at.bernhardberger.tvheadend.sdk.core.DvrConfigurationsState
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpace
import at.bernhardberger.tvheadend.sdk.core.DvrDiskSpaceState
import at.bernhardberger.tvheadend.sdk.core.DvrEntryUpdate
import at.bernhardberger.tvheadend.sdk.core.DvrMutationResult
import at.bernhardberger.tvheadend.sdk.core.DvrRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgCoverageAcquisitionResult
import at.bernhardberger.tvheadend.sdk.core.EpgCoveragePolicy
import at.bernhardberger.tvheadend.sdk.core.EpgRepositoryState
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.RecordingProgressCapability
import at.bernhardberger.tvheadend.sdk.core.ServerCapabilities
import at.bernhardberger.tvheadend.sdk.core.SessionState
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrStats
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayResult
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayServerFacts
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class PhaseOneSessionMetadataTest {
    @Test
    fun `reentrant observation retirement fences the rest of a post-sync batch`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        val replacement = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        var retired = false
        val observer = launch(context = kotlinx.coroutines.Dispatchers.Unconfined) {
            metadata.observation.collect { observation ->
                if (!retired && observation.channelCatalogForDisplay?.channels?.any { it.id == ChannelId(1) } == true) {
                    retired = true
                    metadata.resetWorkingStateRetainingPublishedSnapshot()
                    metadata.bindGeneration(replacement)
                }
            }
        }
        metadata.acceptMetadataBatch(listOf(
            MetadataEvent.ChannelAdded(generation, channel(1)),
            MetadataEvent.ChannelAdded(generation, channel(2)),
            MetadataEvent.EventAdded(generation, epgEvent(2, 2, 0, 10)),
        ))
        observer.cancel()
        assertTrue(retired)
        assertNull(metadata.currentEpgSnapshot(generation))
        // Finish the replacement sync to expose any old tail admitted into its working state.
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(replacement))
        assertTrue(metadata.currentSnapshot().channels.isEmpty())
        assertTrue(metadata.currentEpgSnapshot().events.isEmpty())
    }

    @Test
    fun `batch longer than lock chunk accepts its entire ordered tail`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadataBatch(buildList {
            add(MetadataEvent.ChannelAdded(generation, channel(1)))
            (1L..1100L).forEach { add(MetadataEvent.EventAdded(generation, epgEvent(it, 1, 0, 10))) }
            add(MetadataEvent.EventUpdated(generation, epgEvent(1100, 1, 0, 10, "Tail")))
            add(MetadataEvent.InitialSyncCompleted(generation))
        })
        assertEquals(1100, metadata.currentEpgSnapshot().events.size)
        assertEquals("Tail", metadata.currentEpgSnapshot().events.single { it.id == EventId(1100) }.title)
    }

    @Test
    fun `post-sync batch releases lock after at most 64 non-EPG publications`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        var checked = false
        val events = object : AbstractList<MetadataEvent>() {
            override val size: Int = 65
            override fun get(index: Int): MetadataEvent {
                if (index == 64 && !checked) {
                    checked = true
                    // Borrowed-list access between chunks must allow another thread's metadata read.
                    worker.submit { metadata.currentEpgSnapshot(generation) }
                        .get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                return MetadataEvent.ChannelAdded(generation, channel(index.toLong() + 1))
            }
        }
        try {
            metadata.acceptMetadataBatch(events)
            assertTrue(checked)
            assertEquals(65, metadata.currentSnapshot().channels.size)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `batch fences mixed generations and preserves sync then deferred publication`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val old = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.bindGeneration(old)
        metadata.bindGeneration(current)
        var titleAtSync: String? = null
        val waiter = launch(start = CoroutineStart.UNDISPATCHED, context = kotlinx.coroutines.Dispatchers.Unconfined) {
            metadata.awaitMetadataCurrent(current)
            titleAtSync = metadata.currentEpgSnapshot().events.single().title
        }
        metadata.acceptMetadataBatch(listOf(
            MetadataEvent.ChannelAdded(old, channel(99)),
            MetadataEvent.InitialSyncCompleted(old),
            MetadataEvent.ChannelAdded(current, channel(1)),
            MetadataEvent.EventAdded(current, epgEvent(1, 1, 0, 10, "Before")),
            MetadataEvent.InitialSyncCompleted(current),
            MetadataEvent.EventUpdated(current, epgEvent(1, 1, 0, 10, "After")),
            MetadataEvent.EventAdded(old, epgEvent(99, 1, 0, 10)),
        ))
        waiter.join()
        assertEquals("Before", titleAtSync)
        assertEquals("Before", metadata.currentEpgSnapshot().events.single().title)
        metadata.flushDeferredMetadata()
        assertEquals("After", metadata.currentEpgSnapshot().events.single().title)
        assertEquals(listOf(ChannelId(1)), metadata.currentSnapshot().channels.map { it.id })
    }

    @Test
    fun `batch DVR callbacks are outside lock ordered and fence the remaining events on retirement`() {
        val first = GatewayGeneration()
        val second = GatewayGeneration()
        val accepted = mutableListOf<MetadataEvent>()
        lateinit var metadata: PhaseOneSessionMetadata
        metadata = PhaseOneSessionMetadata(onDvrMetadataAccepted = { event ->
            accepted += event
            // A different thread must be able to acquire the metadata lock before callback returns.
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                worker.submit { metadata.bindGeneration(second) }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                worker.shutdownNow()
            }
        })
        metadata.bindGeneration(first)
        val confirmation = MetadataEvent.DvrEntryAdded(first, GatewayDvrEntry(DvrEntryId(1)))
        metadata.acceptMetadataBatch(listOf(
            confirmation,
            MetadataEvent.DvrEntryDeleted(first, DvrEntryId(1)),
            MetadataEvent.ChannelAdded(first, channel(99)),
            MetadataEvent.InitialSyncCompleted(first),
            MetadataEvent.ChannelAdded(second, channel(2)),
            MetadataEvent.InitialSyncCompleted(second),
        ))
        assertEquals(listOf(confirmation), accepted)
        assertEquals(listOf(ChannelId(2)), metadata.currentSnapshot().channels.map { it.id })
        assertTrue(metadata.currentDvrSnapshot().entries.isEmpty())
    }

    @Test
    fun `cached fields are display only and neither sparse patches nor fresh sync resurrect them`() {
        val metadata = PhaseOneSessionMetadata()
        val cachedChannel = Channel.create(ChannelId(1), name = "Cached", icon = ChannelIcon.ImageCache(ArtworkId(1)))
        val cachedEvent = EpgEvent.create(EventId(1), ChannelId(1), Instant.fromEpochSeconds(0),
            Instant.fromEpochSeconds(10), title = "Cached")
        val cachedCatalog = ChannelCatalog.create(channels = listOf(cachedChannel))
        val cachedEpg = EpgSnapshot.create(events = listOf(cachedEvent))
        assertTrue(metadata.seedRetainedSnapshots(CachedCatalog(cachedCatalog), CachedEpg(cachedEpg)))
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        val synchronizing = metadata.observation.value
        metadata.acceptMetadata(MetadataEvent.ChannelNowNextUpdated(generation, ChannelId(1), EventId(9), null))
        metadata.acceptMetadata(MetadataEvent.DvrEntryStatsUpdated(generation,
            GatewayDvrStats(DvrEntryId(1), null, DvrEntryState.RECORDING)))
        assertSame(synchronizing, metadata.observation.value)
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(1, name = "Fresh")))
        metadata.acceptMetadata(MetadataEvent.EventUpdated(generation, epgEvent(1, 1, 0, 10, "Fresh")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        assertEquals("Fresh", metadata.currentSnapshot().channels.single().name)
        assertNull(metadata.currentSnapshot().channels.single().icon)
        assertNull(metadata.currentSnapshot().channels.single().currentEventId)
        assertEquals("Fresh", metadata.currentEpgSnapshot().events.single().title)
        assertTrue(metadata.currentDvrSnapshot().entries.isEmpty())
        assertEquals("Cached", cachedEvent.title)
        assertEquals(ChannelIcon.ImageCache(ArtworkId(1)), cachedChannel.icon)
        val current = metadata.observation.value
        metadata.acceptMetadata(MetadataEvent.ChannelNowNextUpdated(generation, ChannelId(99), EventId(1), null))
        metadata.acceptMetadata(MetadataEvent.DvrEntryStatsUpdated(generation,
            GatewayDvrStats(DvrEntryId(99), null, DvrEntryState.RECORDING)))
        assertSame(current, metadata.observation.value)
    }

    @Test
    fun `DVR replacement removes stale ranked links while stats retain them and snapshots stay immutable`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
            DvrEntryId(1), eventId = EventId(1), state = DvrEntryState.RECORDING, owner = "owner",
        )))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
            DvrEntryId(2), eventId = EventId(1), state = DvrEntryState.SCHEDULED,
        )))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val retained = metadata.observation.value
        assertEquals(DvrEntryId(1), retained.dvrEntryForEvent(EventId(1))?.id)
        metadata.acceptMetadata(MetadataEvent.DvrEntryStatsUpdated(generation,
            GatewayDvrStats(DvrEntryId(1), null, DvrEntryState.RECORDING, dataSizeBytes = 42)))
        assertEquals(DvrEntryId(1), metadata.observation.value.dvrEntryForEvent(EventId(1))?.id)
        assertEquals("owner", metadata.currentDvrSnapshot().entries.first().owner)
        metadata.acceptMetadata(MetadataEvent.DvrEntryUpdated(generation,
            GatewayDvrEntry(DvrEntryId(1), state = DvrEntryState.RECORDING)))
        assertEquals(DvrEntryId(2), metadata.observation.value.dvrEntryForEvent(EventId(1))?.id)
        assertNull(metadata.currentDvrSnapshot().entries.first().owner)
        assertEquals(DvrEntryId(1), retained.dvrEntryForEvent(EventId(1))?.id)
    }

    @Test
    fun `capacity rejected EPG pushes do not refresh but accepted no-ops do`() {
        var now = Instant.fromEpochSeconds(100)
        val metadata = PhaseOneSessionMetadata(
            clock = object : Clock { override fun now() = now },
            epgCoveragePolicy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1),
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 100, 110)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val original = metadata.observation.value
        now += 1.seconds
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(2, 1, 120, 130)))
        assertSame(original, metadata.observation.value)
        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventUpdated(generation,
            GatewayEpgEvent(id = EventId(2), start = now, stop = now + 10.seconds)))
        metadata.flushDeferredMetadata()
        assertSame(original, metadata.observation.value)
        assertEquals(original.epgRefreshTimes, metadata.publishedSnapshots.value?.epgRefreshTimes)
        for (event in listOf(
            MetadataEvent.EventAdded(generation, epgEvent(1, 1, 100, 110)),
            MetadataEvent.EventUpdated(generation, GatewayEpgEvent(id = EventId(1), channelId = ChannelId(1),
                start = Instant.fromEpochSeconds(100), stop = Instant.fromEpochSeconds(110))),
            MetadataEvent.EventDeleted(generation, EventId(99)),
        )) {
            now += 1.seconds
            metadata.acceptMetadata(event)
            assertEquals(now, metadata.observation.value.epgRefreshTimes.lastUpdatedAt)
            assertEquals(original.epgSnapshotForDisplay, metadata.observation.value.epgSnapshotForDisplay)
        }
    }

    @Test
    fun `invalid timing EPG pushes do not replace pending accepted refresh time`() {
        var now = Instant.fromEpochSeconds(100)
        val metadata = PhaseOneSessionMetadata(clock = object : Clock { override fun now() = now })
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 100, 110)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val original = metadata.observation.value
        now += 1.seconds
        val invalid = MetadataEvent.EventUpdated(generation,
            GatewayEpgEvent(id = EventId(1), channelId = ChannelId(1), start = Instant.fromEpochSeconds(100),
                stop = Instant.fromEpochSeconds(99)))
        metadata.acceptMetadata(invalid)
        assertSame(original, metadata.observation.value)
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(2, 1, 120, 119)))
        assertSame(original, metadata.observation.value)
        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventUpdated(generation,
            GatewayEpgEvent(id = EventId(1), channelId = ChannelId(1), start = Instant.fromEpochSeconds(100),
                stop = Instant.fromEpochSeconds(110), title = "Updated")))
        val acceptedAt = now
        now += 1.seconds
        metadata.acceptMetadataDeferringEpg(invalid)
        metadata.flushDeferredMetadata()
        assertEquals(acceptedAt, metadata.observation.value.epgRefreshTimes.lastUpdatedAt)
        assertEquals(Instant.fromEpochSeconds(110), metadata.currentEpgSnapshot().events.single().stop)
    }

    @Test
    fun `direct rebind discards pending EPG push time before new sync`() {
        var now = Instant.fromEpochSeconds(100)
        val metadata = PhaseOneSessionMetadata(clock = object : Clock { override fun now() = now })
        val first = GatewayGeneration()
        metadata.bindGeneration(first)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        val retained = metadata.observation.value
        now += 1.seconds
        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventAdded(first, epgEvent(1, 1, 100, 110)))
        val second = GatewayGeneration()
        metadata.bindGeneration(second)
        metadata.flushDeferredMetadata()
        assertEquals(retained.epgRefreshTimes, metadata.observation.value.epgRefreshTimes)
        assertSame(retained.epgSnapshotForDisplay, metadata.observation.value.epgSnapshotForDisplay)
        now += 1.seconds
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second))
        assertEquals(MetadataRefreshTimes(now, now), metadata.observation.value.epgRefreshTimes)
        assertTrue(metadata.currentEpgSnapshot().events.isEmpty())
    }

    @Test
    fun `channel deletion publishes removal of only its accepted query time`() {
        val now = Instant.fromEpochSeconds(100)
        val metadata = PhaseOneSessionMetadata(clock = object : Clock { override fun now() = now })
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        for (id in 1L..2L) metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        for (id in 1L..2L) {
            val query = checkNotNull(metadata.beginEpgQuery(generation, ChannelId(id)))
            assertEquals(EpgQueryAcceptance.APPLIED,
                metadata.applySuccessfulEpgQuery(generation, query, now + 10.seconds, emptyList()))
        }
        val retained = metadata.observation.value
        assertEquals(mapOf(ChannelId(1) to now, ChannelId(2) to now), retained.epgLastQueriedAt)
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
        val expected = mapOf(ChannelId(2) to now)
        assertEquals(expected, metadata.observation.value.epgLastQueriedAt)
        assertEquals(expected, metadata.publishedSnapshots.value?.epgLastQueriedAt)
        assertEquals(2, retained.epgLastQueriedAt.size)
    }

    @Test
    fun `rejected and abandoned queries preserve times and deferred push stamps at acceptance`() {
        var now = Instant.fromEpochSeconds(100)
        val metadata = PhaseOneSessionMetadata(
            clock = object : Clock { override fun now() = now },
            epgCoveragePolicy = EpgCoveragePolicy.create(futureHorizon = 24.hours, maximumRetainedEvents = 1),
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 100, 110)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val original = metadata.observation.value
        val abandoned = checkNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        metadata.abandonEpgQuery(generation, abandoned)
        now += 1.seconds
        assertEquals(EpgQueryAcceptance.STALE,
            metadata.applySuccessfulEpgQuery(generation, abandoned, now, emptyList()))
        val query = checkNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        assertEquals(EpgQueryAcceptance.CAPACITY_REJECTED, metadata.applySuccessfulEpgQuery(
            generation, query, now, listOf(GatewayEpgQueryEvent(
                id = EventId(2), channelId = ChannelId(1), start = now, stop = now + 10.seconds,
            )),
        ))
        assertSame(original, metadata.observation.value)
        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventUpdated(generation,
            GatewayEpgEvent(id = EventId(1), channelId = ChannelId(1), start = Instant.fromEpochSeconds(100),
                stop = Instant.fromEpochSeconds(110), title = "Updated")))
        assertSame(original, metadata.observation.value)
        val acceptedAt = now
        now += 1.seconds
        metadata.flushDeferredMetadata()
        assertEquals(acceptedAt, metadata.observation.value.epgRefreshTimes.lastUpdatedAt)
        assertEquals(original.channelRefreshTimes, metadata.observation.value.channelRefreshTimes)
        assertEquals(original.dvrRefreshTimes, metadata.observation.value.dvrRefreshTimes)
    }

    @Test
    fun `empty sync stamps local millisecond times and retirement retains until profile clear`() {
        var now = Instant.parse("2026-01-01T00:00:00.123456Z")
        val metadata = PhaseOneSessionMetadata(clock = object : Clock { override fun now() = now })
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        assertEquals(MetadataRefreshTimes(), metadata.observation.value.epgRefreshTimes)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val expected = MetadataRefreshTimes(Instant.fromEpochMilliseconds(now.toEpochMilliseconds()),
            Instant.fromEpochMilliseconds(now.toEpochMilliseconds()))
        val synced = metadata.observation.value
        assertEquals(expected, synced.channelRefreshTimes)
        assertEquals(expected, synced.epgRefreshTimes)
        assertEquals(expected, synced.dvrRefreshTimes)
        now += 10.seconds
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        assertEquals(expected, metadata.observation.value.epgRefreshTimes)
        assertEquals(expected, metadata.observation.value.dvrRefreshTimes)
        assertEquals(Instant.fromEpochMilliseconds(now.toEpochMilliseconds()),
            metadata.observation.value.channelRefreshTimes.lastUpdatedAt)
        val retained = metadata.observation.value
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(2)))
        assertEquals(retained.channelRefreshTimes, metadata.observation.value.channelRefreshTimes)
        assertSame(retained.epgSnapshotForDisplay, metadata.observation.value.epgSnapshotForDisplay)
        metadata.bindGeneration(GatewayGeneration())
        assertEquals(retained.epgRefreshTimes, metadata.observation.value.epgRefreshTimes)
        metadata.clearAllState()
        assertEquals(MetadataRefreshTimes(), metadata.observation.value.channelRefreshTimes)
        assertEquals(MetadataRefreshTimes(), metadata.observation.value.epgRefreshTimes)
        assertEquals(MetadataRefreshTimes(), metadata.observation.value.dvrRefreshTimes)
        assertTrue(metadata.observation.value.epgLastQueriedAt.isEmpty())
    }

    @Test
    fun `unchanged and empty queries emit freshness without rebuilding reducer snapshot`() {
        var now = Instant.fromEpochSeconds(100)
        val metadata = PhaseOneSessionMetadata(clock = object : Clock { override fun now() = now })
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val event = GatewayEpgQueryEvent(id = EventId(1), channelId = ChannelId(1),
            start = now, stop = now + 10.seconds)
        val reducer = EpgReducer()
        reducer.accept(MetadataEvent.ChannelAdded(generation, channel(1)))
        for (events in listOf(emptyList(), listOf(event))) {
            val horizon = now + 30.seconds
            reducer.acceptSuccessfulQuery(checkNotNull(reducer.beginQuery(ChannelId(1))), horizon, events)
            val original = reducer.snapshot()
            metadata.applySuccessfulEpgQuery(generation, checkNotNull(metadata.beginEpgQuery(generation, ChannelId(1))),
                horizon, events)
            val before = metadata.observation.value
            val query = checkNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
            assertSame(before, metadata.observation.value)
            now += 1.seconds
            assertEquals(EpgQueryAcceptance.APPLIED, metadata.applySuccessfulEpgQuery(generation, query, horizon, events))
            reducer.acceptSuccessfulQuery(checkNotNull(reducer.beginQuery(ChannelId(1))), horizon, events)
            assertSame(original, reducer.snapshot())
            assertEquals(original.hashCode(), reducer.snapshot().hashCode())
            val after = metadata.observation.value
            assertNotSame(before, after)
            assertFalse(before == after)
            assertSame(before.epgSnapshotForDisplay, after.epgSnapshotForDisplay)
            assertEquals(now, after.epgRefreshTimes.lastUpdatedAt)
            assertEquals(now, after.epgLastQueriedAt[ChannelId(1)])
            assertEquals(before.channelRefreshTimes, after.channelRefreshTimes)
            assertEquals(before.dvrRefreshTimes, after.dvrRefreshTimes)
            metadata.retainEpgEvents(generation, retentionBounds(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE))
            assertEquals(after.epgRefreshTimes, metadata.observation.value.epgRefreshTimes)
        }
        val query = checkNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        val before = metadata.observation.value
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        now += 1.seconds
        assertEquals(EpgQueryAcceptance.STALE, metadata.applySuccessfulEpgQuery(generation, query, now, emptyList()))
        assertEquals(before.epgLastQueriedAt, metadata.observation.value.epgLastQueriedAt)
    }

    @Test
    fun `complete channel add and update clear omitted current link and successor evidence`() {
        for (completeAdd in listOf(false, true)) {
            val metadata = PhaseOneSessionMetadata(estimatedServerTime = { Instant.fromEpochSeconds(19) })
            val generation = GatewayGeneration()
            metadata.bindGeneration(generation)
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1, currentEventId = 11)))
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(10, 1, 10, 20)))
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(11, 1, 20, 30)))
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
            metadata.acceptMetadata(if (completeAdd) {
                MetadataEvent.ChannelAdded(generation, channel(1))
            } else {
                MetadataEvent.ChannelUpdated(generation, channel(1))
            })
            metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(10)))
            assertEquals(0,
                metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.size)
        }
    }

    @Test
    fun `deleted current link cannot become successor evidence when event id reappears`() {
        val metadata = PhaseOneSessionMetadata(estimatedServerTime = { Instant.fromEpochSeconds(19) })
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1, currentEventId = 11)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(10, 1, 10, 20)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(11, 1, 20, 30)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(11)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(11, 1, 20, 30)))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(10)))
        assertTrue(metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.isEmpty())
    }

    @Test
    fun `expired programme survives channel advance and deletion only as display history`() {
        var now = Instant.fromEpochSeconds(19, 500_000_000)
        val metadata = PhaseOneSessionMetadata(estimatedServerTime = { now })
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1, currentEventId = 10)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(10, 1, 10, 20, "A")))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(11, 1, 20, 30, "B")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(1, currentEventId = 11)))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(10)))
        val observed = metadata.observation.value
        assertEquals("A", observed.eventAt(ChannelId(1), Instant.fromEpochSeconds(10))?.title)
        assertNull(observed.eventAt(ChannelId(1), Instant.fromEpochSeconds(9)))
        assertEquals("B", observed.eventAt(ChannelId(1), Instant.fromEpochSeconds(20))?.title)
        assertNull(observed.eventAt(ChannelId(1), Instant.fromEpochSeconds(30)))
        assertNull(observed.event(EventId(10)))
        assertEquals(listOf(EventId(10)), observed.epgSnapshotForDisplay?.historicalEvents?.map { it.id })
        assertEquals(Instant.fromEpochSeconds(20), observed.coverage(ChannelId(1))?.coveredFrom)
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(11)))
        assertNull(metadata.observation.value.eventAt(ChannelId(1), Instant.fromEpochSeconds(25)))
        now = Instant.fromEpochSeconds(20) + 6.hours
        metadata.retainEpgEvents(generation, retentionBounds(now - 6.hours, now + 24.hours))
        assertTrue(metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.isEmpty())
        assertEquals("A", observed.eventAt(ChannelId(1), Instant.fromEpochSeconds(10))?.title)
    }

    @Test
    fun `capacity refused event still publishes history expiry`() {
        var now = Instant.fromEpochSeconds(20)
        val metadata = PhaseOneSessionMetadata(
            epgCoveragePolicy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1),
            estimatedServerTime = { now },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(10, 1, 10, 20)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(10)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(11, 1, 30, 30_000)))
        assertEquals(1, metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.size)
        now += 6.hours
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(12, 1, 30_000, 40_000)))
        assertEquals(listOf(EventId(11)), metadata.observation.value.epgSnapshotForDisplay!!.events.map { it.id })
        assertTrue(metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.isEmpty())
    }

    @Test
    fun `channel and DVR metadata do not refresh EPG retention bounds`() {
        val now = Instant.fromEpochSeconds(20)
        var reads = 0
        val clock = object : Clock {
            override fun now(): Instant { reads += 1; return now }
        }
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.retainEpgEvents(generation, EpgRetentionBounds(clock, 6.hours, 24.hours))
        reads = 0
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(1)))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, dvrEntry(1, "recording")))
        assertEquals(0, reads)
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(10, 1, 20, 30)))
        assertEquals(1, reads)
    }

    @Test
    fun `history is removed by channel deletion and never seeded into replacement generation`() {
        val metadata = PhaseOneSessionMetadata(estimatedServerTime = { Instant.fromEpochSeconds(20) })
        val first = GatewayGeneration()
        fun populate(generation: GatewayGeneration) {
            metadata.bindGeneration(generation)
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(1)))
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(10, 1, 10, 20)))
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
            metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(10)))
        }
        populate(first)
        assertEquals(1, metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.size)
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(first, ChannelId(1)))
        assertTrue(metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.isEmpty())
        populate(first)
        val second = GatewayGeneration()
        metadata.bindGeneration(second)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(second, channel(1)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(first, EventId(10)))
        assertTrue(metadata.observation.value.epgSnapshotForDisplay!!.historicalEvents.isEmpty())
        populate(second)
        metadata.clearAllState()
        assertNull(metadata.observation.value.epgSnapshotForDisplay)
    }

    @Test
    fun `mutation confirmation is delivered only after authoritative DVR publication`() = runTest {
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> null })
        val metadata = PhaseOneSessionMetadata(
            mutationCommands = coordinator,
            onDvrMetadataAccepted = coordinator::acceptMetadata,
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        coordinator.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, dvrEntry(1, "old")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }

        val result = async {
            val mutation = metadata.dvrRepository.updateEntry(
                currentSession,
                DvrEntryId(1),
                DvrEntryUpdate(title = "new"),
            )
            assertEquals("new", metadata.currentDvrSnapshot().entries.single().title)
            mutation
        }
        runCurrent()
        assertFalse(result.isCompleted)

        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(1, "new"),
            ),
        )
        runCurrent()

        assertTrue(result.await() is DvrMutationResult.Confirmed)
    }

    @Test
    fun `reducer-rejected DVR metadata cannot confirm a mutation`() = runTest {
        val gateway = MutationGateway()
        val coordinator = DvrMutationCoordinator(gateway, currentEntry = { _, _ -> null })
        val metadata = PhaseOneSessionMetadata(
            mutationCommands = coordinator,
            onDvrMetadataAccepted = coordinator::acceptMetadata,
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        coordinator.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryAdded(generation, dvrEntry(1, "old", start = 0, stop = 10)),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        coordinator.startAdmission(generation)
        gateway.updateBehavior = { _, _, _ -> GatewayResult.Ok(Unit) }

        val result = async {
            metadata.dvrRepository.updateEntry(
                currentSession,
                DvrEntryId(1),
                DvrEntryUpdate(title = "new"),
            )
        }
        runCurrent()
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(1, "invalid", start = 20, stop = 10),
            ),
        )
        runCurrent()

        assertFalse(result.isCompleted)
        assertEquals("old", metadata.currentDvrSnapshot().entries.single().title)

        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(1, "new"),
            ),
        )
        runCurrent()
        assertTrue(result.await() is DvrMutationResult.Confirmed)
    }

    @Test
    fun `matching fence publishes the complete working catalog before readiness`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(stale, true)
        metadata.applyDvrAccess(current, false)
        metadata.publishServerFacts(stale, serverFacts(streaming = false))
        metadata.publishServerFacts(current, serverFacts(streaming = true))
        val awaiting = async {
            metadata.awaitMetadataCurrent(current)
            assertTrue(
                metadata.channelsAndTags.value is ChannelRepositoryState.Current,
                "The metadata fence completed before catalog publication",
            )
        }
        runCurrent()

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(stale, channel(id = 99)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(stale, epgEvent(99, 99, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(stale))
        runCurrent()
        assertFalse(awaiting.isCompleted)
        assertEquals(
            ChannelRepositoryState.Synchronizing(staleCatalog = null),
            metadata.channelsAndTags.value,
        )

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1, name = "one")))
        metadata.acceptMetadata(MetadataEvent.TagAdded(current, tag(id = 2, channelIds = listOf(1))))
        metadata.acceptMetadata(MetadataEvent.EventAdded(current, epgEvent(4, 1, -1, 10)))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(current, dvrEntry(8, "one")))
        assertTrue(metadata.channelsAndTags.value is ChannelRepositoryState.Synchronizing)
        assertTrue(metadata.observation.value.epgState is EpgRepositoryState.Synchronizing)
        assertTrue(metadata.observation.value.dvrState is DvrRepositoryState.Synchronizing)

        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        runCurrent()
        assertTrue(awaiting.isCompleted)
        val snapshot = metadata.currentSnapshot()
        assertEquals(listOf(1L), snapshot.channels.map { it.id.value })
        assertEquals(listOf(2L), snapshot.tags.map { it.id.value })
        val epg = metadata.currentEpgSnapshot()
        assertEquals(listOf(4L), epg.events.map { it.id.value })
        assertEquals(listOf(8L), metadata.currentDvrSnapshot().entries.map { it.id.value })
        assertEquals(Instant.fromEpochSeconds(-1), epg.coverages.single().coveredFrom)
        assertEquals(Instant.fromEpochSeconds(10), epg.coverages.single().coveredTo)
        assertEquals(CapabilityAccess.ALLOWED, metadata.capabilities(current).streaming)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
        assertEquals(CapabilityAccess.UNKNOWN, metadata.capabilities(current).protocolDvr)
    }

    @Test
    fun `server facts project every observation while dvr write stays independently latched`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        val features = mutableListOf("htsp", "satip")
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(current, false)
        metadata.publishServerFacts(
            stale,
            serverFacts(
                streaming = false,
                dvr = false,
                serverName = "stale-server",
            ),
        )
        metadata.publishServerFacts(
            current,
            serverFacts(
                streaming = true,
                dvr = true,
                failedDvr = false,
                admin = true,
                anonymous = false,
                apiVersion = 42,
                limitAll = 0,
                limitDvr = 9,
                limitStreaming = 8,
                uiLevel = 0,
                serverCapabilities = features,
                serverName = "private-server",
                serverVersion = "private-version",
                webRoot = "/secret",
                language = "eng",
                uiLanguage = "deu",
            ),
        )
        features.clear()
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        metadata.awaitMetadataCurrent(current)

        val capabilities = metadata.capabilities(current)
        assertEquals(
            ServerCapabilities.create(
                streaming = CapabilityAccess.ALLOWED,
                dvrWrite = CapabilityAccess.DENIED,
                protocolDvr = CapabilityAccess.ALLOWED,
                failedDvr = CapabilityAccess.DENIED,
                admin = CapabilityAccess.ALLOWED,
                anonymous = CapabilityAccess.DENIED,
                apiVersion = 42,
                allLimit = 0,
                dvrLimit = 9,
                streamingLimit = 8,
                uiLevel = 0,
                features = listOf("htsp", "satip"),
                serverName = "private-server",
                serverVersion = "private-version",
                webRoot = "/secret",
                language = "eng",
                uiLanguage = "deu",
            ),
            capabilities,
        )
        assertEquals(listOf("htsp", "satip"), capabilities.features)
        assertThrows(UnsupportedOperationException::class.java) {
            (capabilities.features as MutableList<String>).clear()
        }
        assertFalse(
            capabilities.toString().contains("private"),
            "Capability rendering exposed server identity",
        )
        assertFalse(
            capabilities.toString().contains("/secret"),
            "Capability rendering exposed a path",
        )
    }

    @Test
    fun `dvr write latch uses later proof and ignores non proof failures`() {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        val configuration = DvrConfiguration(DvrConfigId("private-config"), "Default", "comment")
        metadata.bindGeneration(current)
        metadata.applyDvrAccess(current, null)
        metadata.publishServerFacts(current, serverFacts(streaming = true))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))

        assertEquals(CapabilityAccess.UNKNOWN, metadata.capabilities(current).dvrWrite)
        assertTrue(
            metadata.observation.value.dvrConfigurationsState is DvrConfigurationsState.Synchronizing,
        )

        metadata.applyDvrConfigurations(current, GatewayResult.Timeout)
        metadata.applyDvrConfigurations(current, GatewayResult.ServerRejected())
        metadata.applyDvrConfigurations(current, GatewayResult.ConnectionLimit)
        metadata.applyDvrConfigurations(current, GatewayResult.TransportUnavailable)
        metadata.applyDvrConfigurations(current, GatewayResult.NotSupported)
        metadata.applyDvrConfigurations(stale, GatewayResult.Ok(listOf(configuration)))
        assertEquals(CapabilityAccess.UNKNOWN, metadata.capabilities(current).dvrWrite)
        assertEquals(DvrConfigurationsState.Unknown, metadata.observation.value.dvrConfigurationsState)

        metadata.applyDvrAccess(current, false)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
        metadata.applyDvrConfigurations(current, GatewayResult.Ok(listOf(configuration)))
        assertEquals(CapabilityAccess.ALLOWED, metadata.capabilities(current).dvrWrite)
        val currentConfigs =
            metadata.observation.value.dvrConfigurationsState as DvrConfigurationsState.Current
        assertEquals(listOf(configuration), currentConfigs.configurations)
        assertThrows(UnsupportedOperationException::class.java) {
            (currentConfigs.configurations as MutableList<DvrConfiguration>).clear()
        }
        assertFalse(
            currentConfigs.toString().contains("private"),
            "Configuration rendering exposed a configuration identifier",
        )

        metadata.applyDvrConfigurations(current, GatewayResult.AccessDenied)
        assertEquals(CapabilityAccess.DENIED, metadata.capabilities(current).dvrWrite)
        assertEquals(DvrConfigurationsState.Denied, metadata.observation.value.dvrConfigurationsState)
    }

    @Test
    fun `disk space and configurations retain stale snapshots across reconnect`() {
        val metadata = PhaseOneSessionMetadata()
        val first = GatewayGeneration()
        val second = GatewayGeneration()
        val configuration = DvrConfiguration(DvrConfigId("config"), "Default", "")
        val diskSpace = DvrDiskSpace(freeBytes = 8, usedBytes = null, totalBytes = 16)
        metadata.bindGeneration(first)
        metadata.applyDvrAccess(first, true)
        metadata.publishServerFacts(first, serverFacts(streaming = true))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        metadata.applyDvrConfigurations(first, GatewayResult.Ok(listOf(configuration)))
        metadata.applyDvrDiskSpace(first, GatewayResult.Ok(diskSpace))

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        assertEquals(
            listOf(configuration),
            (metadata.observation.value.dvrConfigurationsState as DvrConfigurationsState.Stale)
                .configurations,
        )
        assertEquals(
            diskSpace,
            (metadata.observation.value.dvrDiskSpaceState as DvrDiskSpaceState.Stale).diskSpace,
        )

        metadata.bindGeneration(second)
        assertEquals(
            listOf(configuration),
            (metadata.observation.value.dvrConfigurationsState as DvrConfigurationsState.Synchronizing)
                .staleConfigurations,
        )
        assertEquals(
            diskSpace,
            (metadata.observation.value.dvrDiskSpaceState as DvrDiskSpaceState.Synchronizing)
                .staleDiskSpace,
        )

        metadata.applyDvrDiskSpace(first, GatewayResult.Ok(DvrDiskSpace(1, 1, 2)))
        assertEquals(
            diskSpace,
            (metadata.observation.value.dvrDiskSpaceState as DvrDiskSpaceState.Synchronizing)
                .staleDiskSpace,
        )
        metadata.applyDvrDiskSpace(second, GatewayResult.Timeout)
        assertEquals(
            DvrDiskSpaceState.Stale(diskSpace),
            metadata.observation.value.dvrDiskSpaceState,
        )
        assertEquals(
            diskSpace,
            (metadata.observation.value.dvrDiskSpaceState as DvrDiskSpaceState.Stale).diskSpace,
        )
        metadata.applyDvrDiskSpace(second, GatewayResult.Ok(DvrDiskSpace(3, 1, 4)))
        assertEquals(
            DvrDiskSpace(3, 1, 4),
            (metadata.observation.value.dvrDiskSpaceState as DvrDiskSpaceState.Current).diskSpace,
        )

        metadata.clearAllState()
        assertEquals(DvrConfigurationsState.Unknown, metadata.observation.value.dvrConfigurationsState)
        assertEquals(DvrDiskSpaceState.Unknown, metadata.observation.value.dvrDiskSpaceState)
    }

    @Test
    fun `full updates clear omissions except tag members and empty collections replace prior values`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                channel(
                    id = 1,
                    name = "name",
                    uuid = "uuid",
                    number = 7,
                    numberMinor = 8,
                    icon = "imagecache/12",
                    currentEventId = 4,
                    nextEventId = 5,
                    services = listOf(service("service")),
                    tagIds = listOf(2),
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(
                generation,
                tag(
                    id = 2,
                    name = "tag",
                    uuid = "tag-uuid",
                    index = 9,
                    icon = "/imagecache/34",
                    titledIcon = true,
                    channelIds = listOf(1),
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.ChannelUpdated(
                generation,
                channel(id = 1, name = "", number = 0),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagUpdated(
                generation,
                tag(id = 2, name = "", index = 0),
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        var snapshot = metadata.currentSnapshot()
        var channel = snapshot.channels.single()
        var tag = snapshot.tags.single()
        assertEquals("", channel.name)
        assertNull(channel.uuid)
        assertEquals(0L, channel.number)
        assertNull(channel.numberMinor)
        assertNull(channel.icon)
        assertNull(channel.currentEventId)
        assertNull(channel.nextEventId)
        assertNull(channel.services)
        assertNull(channel.tagIds)
        assertEquals("", tag.name)
        assertNull(tag.uuid)
        assertEquals(0L, tag.index)
        assertNull(tag.icon)
        assertNull(tag.titledIcon)
        assertEquals(listOf(1L), tag.channelIds?.map { it.value })

        metadata.acceptMetadata(
            MetadataEvent.ChannelUpdated(
                generation,
                channel(id = 1, services = emptyList(), tagIds = emptyList()),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagUpdated(generation, tag(id = 2, channelIds = emptyList())),
        )
        snapshot = metadata.currentSnapshot()
        channel = snapshot.channels.single()
        tag = snapshot.tags.single()
        assertEquals(emptyList<Any>(), channel.services)
        assertEquals(emptyList<Any>(), channel.tagIds)
        assertEquals(emptyList<Any>(), tag.channelIds)
        assertNull(channel.currentEventId)
        assertNull(channel.nextEventId)

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.TagAdded(generation, tag(id = 2)))
        snapshot = metadata.currentSnapshot()
        channel = snapshot.channels.single()
        tag = snapshot.tags.single()
        assertEquals(null, channel.currentEventId)
        assertEquals(null, channel.nextEventId)
        assertNull(channel.name)
        assertNull(channel.number)
        assertNull(channel.services)
        assertNull(channel.tagIds)
        assertNull(tag.name)
        assertNull(tag.index)
        assertEquals(emptyList<Any>(), tag.channelIds)
    }

    @Test
    fun `entity deletes clean opposite references and event delete clears every channel link`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                channel(
                    id = 1,
                    currentEventId = 4,
                    nextEventId = 4,
                    tagIds = listOf(10, 20, 20),
                ),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                generation,
                channel(id = 2, currentEventId = 4, nextEventId = 5, tagIds = listOf(20)),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(generation, tag(id = 10, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(generation, tag(id = 20, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        metadata.acceptMetadata(
            MetadataEvent.TagUpdated(generation, tag(id = 10, channelIds = listOf(99, 1, 1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(99)))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(2)))
        metadata.acceptMetadata(MetadataEvent.TagDeleted(generation, TagId(20)))
        metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(4)))

        val snapshot = metadata.currentSnapshot()
        val channel = snapshot.channels.single()
        val tag = snapshot.tags.single()
        assertEquals(1L, channel.id.value)
        assertEquals(listOf(10L), channel.tagIds?.map { it.value })
        assertEquals(null, channel.currentEventId)
        assertEquals(null, channel.nextEventId)
        assertEquals(10L, tag.id.value)
        assertEquals(listOf(1L, 1L), tag.channelIds?.map { it.value })
        assertEquals(emptyList<Any>(), metadata.currentEpgSnapshot().events)
    }

    @Test
    fun `reconnect exposes stale data and replaces it with only the new synchronization set`() {
        val metadata = PhaseOneSessionMetadata()
        val first = GatewayGeneration()
        val second = GatewayGeneration()
        metadata.bindGeneration(first)
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(
                first,
                channel(id = 1, name = "old", icon = "imagecache/5", tagIds = listOf(10)),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.ChannelAdded(first, channel(id = 2, name = "evicted", tagIds = listOf(10))),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(first, tag(id = 10, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, epgEvent(4, 1, 10, 20, "old-event")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(first, dvrEntry(8, "old-dvr")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        val staleSnapshot = metadata.currentSnapshot()
        val staleEpgSnapshot = metadata.currentEpgSnapshot()
        val staleDvrSnapshot = metadata.currentDvrSnapshot()

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        val staleState = metadata.channelsAndTags.value as ChannelRepositoryState.Stale
        assertSame(staleSnapshot, staleState.catalog)
        assertSame(
            staleEpgSnapshot,
            (metadata.observation.value.epgState as EpgRepositoryState.Stale).snapshot,
        )
        assertSame(
            staleDvrSnapshot,
            (metadata.observation.value.dvrState as DvrRepositoryState.Stale).snapshot,
        )
        metadata.bindGeneration(second)
        val synchronizing = metadata.channelsAndTags.value as ChannelRepositoryState.Synchronizing
        assertSame(staleSnapshot, synchronizing.staleCatalog)
        assertSame(
            staleEpgSnapshot,
            (metadata.observation.value.epgState as EpgRepositoryState.Synchronizing).staleSnapshot,
        )
        assertSame(
            staleDvrSnapshot,
            (metadata.observation.value.dvrState as DvrRepositoryState.Synchronizing).staleSnapshot,
        )

        metadata.acceptMetadata(
            MetadataEvent.ChannelUpdated(
                second,
                channel(id = 1, name = "new", tagIds = listOf(10, 11)),
            ),
        )
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(second, tag(id = 11, channelIds = listOf(1, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.EventAdded(second, epgEvent(6, 1, 30, 40, "new-event")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(second, dvrEntry(9, "new-dvr")))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(first, channel(id = 3, name = "stale")))
        metadata.acceptMetadata(MetadataEvent.EventAdded(first, epgEvent(7, 1, 50, 60, "stale-event")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(first, dvrEntry(10, "stale-dvr")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(first))
        assertSame(staleSnapshot, synchronizing.staleCatalog)
        assertEquals(listOf(1L, 2L), staleSnapshot.channels.map { it.id.value })

        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second))
        val current = metadata.currentSnapshot()
        val channel = current.channels.single()
        val tag = current.tags.single()
        assertEquals(1L, channel.id.value)
        assertEquals("new", channel.name)
        assertEquals(null, channel.icon)
        assertEquals(listOf(11L), channel.tagIds?.map { it.value })
        assertEquals(11L, tag.id.value)
        assertEquals(listOf(1L), tag.channelIds?.map { it.value })
        assertEquals(listOf(1L, 2L), staleSnapshot.channels.map { it.id.value })
        assertEquals(listOf(6L), metadata.currentEpgSnapshot().events.map { it.id.value })
        assertEquals(listOf(4L), staleEpgSnapshot.events.map { it.id.value })
        assertEquals(listOf(9L), metadata.currentDvrSnapshot().entries.map { it.id.value })
        assertEquals(listOf(8L), staleDvrSnapshot.entries.map { it.id.value })
    }

    @Test
    fun `coverage requester is current generation bound and cleared by identity or reset`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        val channelId = ChannelId(7)
        val through = Instant.fromEpochSeconds(20_000)
        var observed: Pair<ChannelId, Instant>? = null
        val requester = EpgCoverageRequester { _, requestedChannelId, requestedThrough ->
            observed = requestedChannelId to requestedThrough
            EpgCoverageAcquisitionResult.Ineligible
        }
        val expired = at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation.create(
            Any(),
            Any(),
            at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity.create(),
        )

        assertSame(
            EpgCoverageAcquisitionResult.ObservationExpired,
            metadata.epgRepository.acquireCoverage(expired, channelId, through),
        )
        metadata.bindGeneration(generation)
        assertFalse(metadata.bindEpgCoverageRequester(generation, requester))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(metadata, generation)
        val currentSession = requireNotNull(metadata.observation.value.currentSession)
        assertTrue(metadata.bindEpgCoverageRequester(generation, requester))
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            metadata.epgRepository.acquireCoverage(currentSession, channelId, through),
        )
        assertEquals(channelId to through, observed)

        metadata.clearEpgCoverageRequester(
            generation,
            EpgCoverageRequester { _, _, _ -> EpgCoverageAcquisitionResult.ObservationExpired },
        )
        assertSame(
            EpgCoverageAcquisitionResult.Ineligible,
            metadata.epgRepository.acquireCoverage(currentSession, channelId, through),
        )
        metadata.clearEpgCoverageRequester(generation, requester)
        assertSame(
            EpgCoverageAcquisitionResult.ObservationExpired,
            metadata.epgRepository.acquireCoverage(currentSession, channelId, through),
        )

        assertTrue(metadata.bindEpgCoverageRequester(generation, requester))
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        assertSame(
            EpgCoverageAcquisitionResult.ObservationExpired,
            metadata.epgRepository.acquireCoverage(currentSession, channelId, through),
        )
    }

    @Test
    fun `coverage fast paths propagate pre-existing caller cancellation`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val expired = at.bernhardberger.tvheadend.sdk.core.CurrentSessionObservation.create(
            Any(),
            Any(),
            at.bernhardberger.tvheadend.sdk.core.SessionGenerationIdentity.create(),
        )
        var kotlinReturned = false
        var javaBridgeReturned = false
        var expiredSingularReturned = false
        var unboundSingularReturned = false
        val kotlinCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("fixed caller cancellation"))
            metadata.epgRepository.acquireCoverageBatch(
                expired,
                listOf(ChannelId(1)),
                Instant.fromEpochSeconds(1),
            )
            kotlinReturned = true
        }
        val javaBridgeCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("fixed caller cancellation"))
            metadata.epgRepository.acquireCoverageBatchFromIds(
                expired,
                longArrayOf(1),
                Instant.fromEpochSeconds(1),
            )
            javaBridgeReturned = true
        }
        val expiredSingularCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("fixed caller cancellation"))
            metadata.epgRepository.acquireCoverage(
                expired,
                ChannelId(1),
                Instant.fromEpochSeconds(1),
            )
            expiredSingularReturned = true
        }
        val unboundMetadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        unboundMetadata.bindGeneration(generation)
        unboundMetadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        publishReady(unboundMetadata, generation)
        val currentSession = requireNotNull(unboundMetadata.observation.value.currentSession)
        val unboundSingularCaller = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("fixed caller cancellation"))
            unboundMetadata.epgRepository.acquireCoverage(
                currentSession,
                ChannelId(1),
                Instant.fromEpochSeconds(1),
            )
            unboundSingularReturned = true
        }

        kotlinCaller.join()
        javaBridgeCaller.join()
        expiredSingularCaller.join()
        unboundSingularCaller.join()

        assertTrue(kotlinCaller.isCancelled)
        assertTrue(javaBridgeCaller.isCancelled)
        assertTrue(expiredSingularCaller.isCancelled)
        assertTrue(unboundSingularCaller.isCancelled)
        assertFalse(kotlinReturned)
        assertFalse(javaBridgeReturned)
        assertFalse(expiredSingularReturned)
        assertFalse(unboundSingularReturned)
    }

    @Test
    fun `coverage and retention mutations are generation fenced and preserve successful horizon`() {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.bindGeneration(current)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(current, epgEvent(1, 1, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(current, epgEvent(2, 1, 20, 30)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))

        val query = checkNotNull(metadata.beginEpgQuery(current, ChannelId(1)))
        assertEquals(EpgQueryAcceptance.STALE,
            metadata.applySuccessfulEpgQuery(stale, query, Instant.fromEpochSeconds(100), emptyList()))
        metadata.retainEpgEvents(stale, retentionBounds(Instant.fromEpochSeconds(20), Instant.fromEpochSeconds(10)))
        assertEquals(EpgQueryAcceptance.APPLIED,
            metadata.applySuccessfulEpgQuery(current, query, Instant.fromEpochSeconds(50), emptyList()))
        val nextQuery = checkNotNull(metadata.beginEpgQuery(current, ChannelId(1)))
        assertEquals(EpgQueryAcceptance.APPLIED,
            metadata.applySuccessfulEpgQuery(current, nextQuery, Instant.fromEpochSeconds(40), emptyList()))
        metadata.retainEpgEvents(current, retentionBounds(Instant.fromEpochSeconds(10), Instant.fromEpochSeconds(20)))

        var snapshot = metadata.currentEpgSnapshot()
        assertEquals(listOf(1L, 2L), snapshot.events.map { it.id.value })
        var coverage = snapshot.coverages.single()
        assertEquals(Instant.fromEpochSeconds(0), coverage.coveredFrom)
        assertEquals(Instant.fromEpochSeconds(30), coverage.coveredTo)
        assertEquals(Instant.fromEpochSeconds(50), coverage.queriedTo)

        metadata.retainEpgEvents(current, retentionBounds(Instant.fromEpochSeconds(11), Instant.fromEpochSeconds(19)))
        snapshot = metadata.currentEpgSnapshot()
        coverage = snapshot.coverages.single()
        assertEquals(emptyList<Any>(), snapshot.events)
        assertTrue(coverage.isEmpty)
        assertEquals(Instant.fromEpochSeconds(50), coverage.knownTo)
    }

    @Test
    fun `configured retained event limit bounds async and query ingestion`() {
        val metadata = PhaseOneSessionMetadata(
            epgCoveragePolicy = EpgCoveragePolicy.create(24.hours, maximumRetainedEvents = 1),
            // Both programmes are upcoming and protected, so neither can be evicted for room.
            clock = object : Clock { override fun now() = Instant.fromEpochSeconds(0) },
        )
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(2, 1, 10, 20)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        var snapshot = metadata.currentEpgSnapshot(generation)
        assertEquals(listOf(1L), snapshot?.events?.map { event -> event.id.value })

        val query = requireNotNull(metadata.beginEpgQuery(generation, ChannelId(1)))
        metadata.applySuccessfulEpgQuery(
            generation = generation,
            query = query,
            queriedTo = Instant.fromEpochSeconds(100),
            events = listOf(
                GatewayEpgQueryEvent(
                    id = EventId(2),
                    channelId = ChannelId(1),
                    start = Instant.fromEpochSeconds(10),
                    stop = Instant.fromEpochSeconds(20),
                ),
            ),
        )

        snapshot = metadata.currentEpgSnapshot(generation)
        assertEquals(listOf(1L), snapshot?.events?.map { event -> event.id.value })
        assertNull(snapshot?.coverages?.single()?.queriedTo)
    }

    @Test
    fun `full store evicts a far tail for an async addition and pulls back every proof`() {
        val metadata = PhaseOneSessionMetadata(
            epgCoveragePolicy = EpgCoveragePolicy.create(48.hours, maximumRetainedEvents = 2),
            clock = object : Clock { override fun now() = Instant.fromEpochSeconds(0) },
        )
        val generation = GatewayGeneration()
        val tailStart = 30.hours.inWholeSeconds
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 2)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(2, 2, tailStart, tailStart + 3_600)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, Instant.fromEpochSeconds(0) + 40.hours))
        assertEquals(Instant.fromEpochSeconds(0) + 40.hours, metadata.initialEpgCoverage(generation)[ChannelId(2)])

        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(3, 1, 10, 20)))

        // The published snapshot is what the persistent cache stores, so it never claims the cut.
        val snapshot = requireNotNull(metadata.currentEpgSnapshot(generation))
        assertEquals(listOf(1L, 3L), snapshot.events.map { event -> event.id.value })
        assertNull(snapshot.coverages.first { it.channelId == ChannelId(2) }.knownTo)
        assertEquals(Instant.fromEpochSeconds(tailStart - 1), metadata.initialEpgCoverage(generation)[ChannelId(2)])
    }

    @Test
    fun `explicit request protection ends with its generation`() {
        val metadata = PhaseOneSessionMetadata(
            epgCoveragePolicy = EpgCoveragePolicy.create(48.hours, maximumRetainedEvents = 2),
            clock = object : Clock { override fun now() = Instant.fromEpochSeconds(0) },
        )
        val tailStart = 30.hours.inWholeSeconds
        val through = Instant.fromEpochSeconds(tailStart + 3_600)
        fun fullStore(generation: GatewayGeneration) {
            metadata.bindGeneration(generation)
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
            metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 2)))
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 0, 10)))
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(2, 2, tailStart, tailStart + 3_600)))
            metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        }
        fun addNearProgramme(generation: GatewayGeneration): List<Long>? {
            metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(3, 1, 10, 20)))
            return metadata.currentEpgSnapshot(generation)?.events?.map { event -> event.id.value }
        }

        val first = GatewayGeneration()
        fullStore(first)
        assertNotNull(metadata.rememberEpgCoverageRequest(first, listOf(ChannelId(2)), through))
        // The request protects channel 2's far tail, and nothing else may yield.
        assertEquals(listOf(1L, 2L), addNearProgramme(first))

        val second = GatewayGeneration()
        fullStore(second)
        assertNull(metadata.rememberEpgCoverageRequest(first, listOf(ChannelId(2)), through))
        assertEquals(listOf(1L, 3L), addNearProgramme(second))

        assertNotNull(metadata.rememberEpgCoverageRequest(second, listOf(ChannelId(2)), through))
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        assertNull(metadata.rememberEpgCoverageRequest(second, listOf(ChannelId(2)), through))
    }

    @Test
    fun `successful query applies events and horizon together only to a current channel lifetime`() {
        val metadata = PhaseOneSessionMetadata()
        val stale = GatewayGeneration()
        val current = GatewayGeneration()
        metadata.bindGeneration(current)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1)))
        assertNull(metadata.beginEpgQuery(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(current))
        val original = metadata.observation.value.epgState
        val event = GatewayEpgQueryEvent(
            id = EventId(10),
            channelId = ChannelId(1),
            start = Instant.fromEpochSeconds(10),
            stop = Instant.fromEpochSeconds(20),
            title = "private-title",
        )

        assertNull(metadata.beginEpgQuery(stale, ChannelId(1)))
        assertSame(original, metadata.observation.value.epgState)

        val currentQuery = requireNotNull(metadata.beginEpgQuery(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(current, channel(id = 1)))
        metadata.applySuccessfulEpgQuery(
            current,
            currentQuery,
            Instant.fromEpochSeconds(100),
            listOf(event),
        )
        var snapshot = metadata.currentEpgSnapshot(current)
        assertEquals(listOf(10L), snapshot?.events?.map { it.id.value })
        assertIndexesPrepared(requireNotNull(snapshot))
        assertEquals(Instant.fromEpochSeconds(100), snapshot.coverages.single().queriedTo)

        val overtakenQuery = requireNotNull(metadata.beginEpgQuery(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(current, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(current, channel(id = 1)))
        metadata.applySuccessfulEpgQuery(
            current,
            overtakenQuery,
            Instant.fromEpochSeconds(200),
            listOf(event),
        )
        snapshot = metadata.currentEpgSnapshot(current)
        assertEquals(emptyList<Any>(), snapshot?.events)
        assertTrue(requireNotNull(snapshot).coverages.single().isEmpty)
        assertNull(snapshot.coverages.single().queriedTo)
        assertFalse(
            metadata.observation.value.epgState.toString().contains("private"),
            "Query publication rendering exposed programme data",
        )
    }

    private fun assertIndexesPrepared(snapshot: EpgSnapshot) {
        for ((holderName, indexNames) in listOf(
            "eventIndexes" to listOf("byId", "byChannel"),
            "historyIndexes" to listOf("byChannel"),
        )) {
            val holderField = EpgSnapshot::class.java.getDeclaredField(holderName)
            holderField.isAccessible = true
            val holder = holderField.get(snapshot)
            for (name in indexNames) {
                val field = holder.javaClass.getDeclaredField("$name\$delegate")
                field.isAccessible = true
                assertTrue((field.get(holder) as Lazy<*>).isInitialized(),
                    "Published $holderName.$name must not defer a full-guide allocation to its first UI consumer")
            }
        }
    }

    @Test
    fun `seeded snapshots publish stale states and are replaced by the first sync`() {
        val metadata = PhaseOneSessionMetadata()
        val seededCatalog = ChannelCatalog.create(
            channels = listOf(Channel.create(id = ChannelId(9), name = "Cached")),
        )
        val seededEpg = EpgSnapshot.create()

        assertNull(metadata.publishedSnapshots.value)
        assertTrue(metadata.seedRetainedSnapshots(CachedCatalog(seededCatalog), CachedEpg(seededEpg)))
        assertFalse(metadata.seedRetainedSnapshots(CachedCatalog(seededCatalog), CachedEpg(seededEpg)),
            "A second seed must be refused")

        val staleChannels = metadata.channelsAndTags.value as ChannelRepositoryState.Stale
        assertSame(seededCatalog, staleChannels.catalog)
        val staleEpg = metadata.observation.value.epgState as EpgRepositoryState.Stale
        assertSame(seededEpg, staleEpg.snapshot)
        assertIndexesPrepared(staleEpg.snapshot)
        assertNull(metadata.publishedSnapshots.value, "Seeds are not publications")

        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        val synchronizing = metadata.channelsAndTags.value as ChannelRepositoryState.Synchronizing
        assertSame(seededCatalog, synchronizing.staleCatalog)
        assertFalse(metadata.seedRetainedSnapshots(CachedCatalog(seededCatalog), CachedEpg(seededEpg)),
            "A bound generation refuses seeds")

        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        val current = metadata.channelsAndTags.value as ChannelRepositoryState.Current
        assertEquals(listOf(1L), current.catalog.channels.map { it.id.value })
        val published = requireNotNull(metadata.publishedSnapshots.value)
        assertSame(current.catalog, published.catalog)
        assertSame((metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot, published.epgSnapshot)
        assertIndexesPrepared(published.epgSnapshot)

        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 10, 20)))
        val updated = requireNotNull(metadata.publishedSnapshots.value).epgSnapshot
        assertIndexesPrepared(updated)
        assertSame(updated.events.single(), updated.eventsById[EventId(1)])
        assertSame(updated.events.single(), updated.eventsByChannel[ChannelId(1)]?.single())

        repeat(10) {
            metadata.acceptMetadata(MetadataEvent.EventDeleted(generation, EventId(999)))
            assertSame(updated, (metadata.observation.value.epgState as EpgRepositoryState.Current).snapshot)
            assertSame(updated, metadata.publishedSnapshots.value?.epgSnapshot,
                "An equal, suppressed candidate must not retain a second set of prepared indexes")
        }

        metadata.clearAllState()
        assertTrue(metadata.seedRetainedSnapshots(CachedCatalog(seededCatalog), null), "A cleared metadata accepts a seed again")
    }

    @Test
    fun `partial cache seeds cannot replace live snapshots retained after reset`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 10, 20)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val published = requireNotNull(metadata.publishedSnapshots.value)
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        val observation = metadata.observation.value

        assertFalse(metadata.seedRetainedSnapshots(null, CachedEpg(EpgSnapshot.create())))
        assertFalse(metadata.seedRetainedSnapshots(CachedCatalog(ChannelCatalog.create()), null))
        assertSame(observation, metadata.observation.value)
        assertSame(published, metadata.publishedSnapshots.value)
        assertSame(published.catalog, observation.channelCatalogForDisplay)
        assertSame(published.epgSnapshot, observation.epgSnapshotForDisplay)
    }

    @Test
    fun `a generation-fenced retained seed preserves live sync and never lends cached query proof`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        val cachedCatalog = ChannelCatalog.create(channels = listOf(Channel.create(ChannelId(9), "Cached")))
        val cachedEpg = EpgSnapshot.create()
        val received = Instant.fromEpochSeconds(5)
        val times = MetadataRefreshTimes(received, received)
        val catalog = CachedCatalog(cachedCatalog, times)
        val guide = CachedEpg(cachedEpg, times, mapOf(ChannelId(1) to received, ChannelId(9) to received))
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(1, 1, 10, 20)))

        assertFalse(metadata.seedRetainedSnapshots(catalog, guide, GatewayGeneration()))
        assertFalse(metadata.seedRetainedSnapshots(catalog, guide), "An unfenced seed cannot target a bound generation")
        assertTrue(metadata.seedRetainedSnapshots(catalog, guide, generation))
        assertSame(cachedCatalog, (metadata.observation.value.channelState as ChannelRepositoryState.Synchronizing).staleCatalog)
        assertSame(cachedEpg, (metadata.observation.value.epgState as EpgRepositoryState.Synchronizing).staleSnapshot)
        assertEquals(times, metadata.observation.value.channelRefreshTimes)
        assertEquals(times, metadata.observation.value.epgRefreshTimes)
        assertNull(metadata.publishedSnapshots.value)
        assertTrue(metadata.initialEpgCoverage(generation).isEmpty())

        val through = Instant.fromEpochSeconds(100)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation, through))
        val published = requireNotNull(metadata.publishedSnapshots.value)
        assertEquals(listOf(1L), published.catalog.channels.map { it.id.value })
        assertEquals(listOf(1L), published.epgSnapshot.events.map { it.id.value })
        assertTrue(published.epgLastQueriedAt.isEmpty(), "The marker discards cached scheduling timestamps")
        assertEquals(mapOf(ChannelId(1) to through), metadata.initialEpgCoverage(generation))
        assertFalse(metadata.seedRetainedSnapshots(catalog, guide, generation))
        assertSame(published, metadata.publishedSnapshots.value)
    }

    @Test
    fun `published snapshots retain insertion order are immutable conflated and redacted`() {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        val mutableServices = mutableListOf(service("private-service"), service("private-service"))
        val mutableTags = mutableListOf(7L, 7L)
        val firstChannel = channel(
            id = 2,
            name = "private-channel",
            services = mutableServices,
            tagIds = mutableTags,
        )
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, firstChannel))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, firstChannel))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1)))
        metadata.acceptMetadata(
            MetadataEvent.TagAdded(generation, tag(id = 7, name = "private-tag", channelIds = listOf(2, 2))),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        mutableServices.clear()
        mutableTags.clear()

        val state = metadata.channelsAndTags.value as ChannelRepositoryState.Current
        val snapshot = state.catalog
        assertEquals(listOf(2L, 1L), snapshot.channels.map { it.id.value })
        assertEquals(2, snapshot.channels.first().services?.size)
        assertEquals(listOf(7L, 7L), snapshot.channels.first().tagIds?.map { it.value })
        assertEquals(listOf(2L, 2L), snapshot.tags.single().channelIds?.map { it.value })
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.channels as MutableList<Channel>).add(snapshot.channels.first())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.channels.first().services as MutableList<ChannelService>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.channels.first().tagIds as MutableList<TagId>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.tags.single().channelIds as MutableList<ChannelId>).clear()
        }

        metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(
            id = 2, name = "private-channel", services = listOf(service("private-service"), service("private-service")),
            tagIds = listOf(7, 7),
        )))
        metadata.acceptMetadata(
            MetadataEvent.DvrEntryUpdated(
                generation,
                dvrEntry(8, "private-dvr"),
            ),
        )
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        metadata.acceptMetadata(MetadataEvent.ChannelDeleted(GatewayGeneration(), ChannelId(2)))
        assertSame(state, metadata.channelsAndTags.value)
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        val stale = metadata.channelsAndTags.value as ChannelRepositoryState.Stale
        assertSame(snapshot, stale.catalog)

        val rendering = listOf(
            state,
            snapshot,
            snapshot.channels.first(),
            snapshot.channels.first().services?.first(),
            snapshot.tags.first(),
            metadata.observation.value.dvrState,
        ).joinToString()
        assertFalse(rendering.contains("private"), "Catalog rendering exposed metadata")
    }

    @Test
    fun `reset invalidates old waiters and empty synchronization is current`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val first = GatewayGeneration()
        metadata.bindGeneration(first)
        val retired = async { metadata.awaitMetadataCurrent(first) }
        runCurrent()

        metadata.resetWorkingStateRetainingPublishedSnapshot()
        runCurrent()
        assertTrue(retired.isCancelled, "Reset did not invalidate the retired generation fence")

        val second = GatewayGeneration()
        metadata.bindGeneration(second)
        val raced = async {
            runCatching { metadata.awaitMetadataCurrent(second) }
        }
        runCurrent()
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(second))
        metadata.resetWorkingStateRetainingPublishedSnapshot()
        runCurrent()
        assertTrue(raced.await().isFailure, "A retired generation passed its post-fence check")

        val third = GatewayGeneration()
        metadata.bindGeneration(third)
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(third))
        metadata.awaitMetadataCurrent(third)
        val snapshot = metadata.currentSnapshot()
        assertTrue(snapshot.channels.isEmpty(), "Empty synchronized channels were not current")
        assertTrue(snapshot.tags.isEmpty(), "Empty synchronized tags were not current")
        assertTrue(metadata.currentEpgSnapshot().events.isEmpty(), "Empty synchronized EPG was not current")
        assertTrue(metadata.currentDvrSnapshot().entries.isEmpty(), "Empty synchronized DVR was not current")
    }

    private fun PhaseOneSessionMetadata.currentSnapshot() =
        (channelsAndTags.value as ChannelRepositoryState.Current).catalog

    @Test
    fun `deferred EPG traffic publishes once when flushed`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1, name = "one")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val synced = metadata.observation.value
        val syncedEpg = metadata.currentEpgSnapshot()

        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventAdded(generation, epgEvent(4, 1, 0, 10)))
        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventAdded(generation, epgEvent(5, 1, 10, 20)))
        assertSame(synced, metadata.observation.value)
        assertSame(syncedEpg, metadata.currentEpgSnapshot())

        metadata.flushDeferredMetadata()
        val flushed = metadata.observation.value
        assertNotSame(synced, flushed)
        assertEquals(listOf(4L, 5L), metadata.currentEpgSnapshot().events.map { it.id.value })
        assertEquals(Instant.fromEpochSeconds(0), metadata.currentEpgSnapshot().coverages.single().coveredFrom)
        assertEquals(Instant.fromEpochSeconds(20), metadata.currentEpgSnapshot().coverages.single().coveredTo)

        metadata.flushDeferredMetadata()
        assertSame(flushed, metadata.observation.value)
    }

    @Test
    fun `a non-EPG event carries deferred EPG changes immediately`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1, name = "one")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))

        metadata.acceptMetadataDeferringEpg(MetadataEvent.EventAdded(generation, epgEvent(4, 1, 0, 10)))
        metadata.acceptMetadataDeferringEpg(MetadataEvent.DvrEntryAdded(generation, dvrEntry(8, "one")))
        assertEquals(listOf(4L), metadata.currentEpgSnapshot().events.map { it.id.value })
        assertEquals(listOf(8L), metadata.currentDvrSnapshot().entries.map { it.id.value })

        val published = metadata.observation.value
        metadata.flushDeferredMetadata()
        assertSame(published, metadata.observation.value)
    }

    @Test
    fun `unrelated metadata republishes the identical EPG snapshot instance`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1, name = "one")))
        metadata.acceptMetadata(MetadataEvent.EventAdded(generation, epgEvent(4, 1, 0, 10)))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val epg = metadata.currentEpgSnapshot()

        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, dvrEntry(8, "one")))
        assertSame(epg, metadata.currentEpgSnapshot())

        metadata.acceptMetadata(MetadataEvent.EventUpdated(generation, epgEvent(4, 1, 0, 10, "changed")))
        assertNotSame(epg, metadata.currentEpgSnapshot())
        assertEquals("changed", metadata.currentEpgSnapshot().events.single().title)
    }

    private fun PhaseOneSessionMetadata.currentEpgSnapshot() =
        (observation.value.epgState as EpgRepositoryState.Current).snapshot

    @Test
    fun `mixed metadata bursts reuse DVR and pointer updates preserve EPG snapshots`() = runTest {
        val metadata = PhaseOneSessionMetadata()
        val generation = GatewayGeneration()
        metadata.bindGeneration(generation)
        metadata.acceptMetadata(MetadataEvent.ChannelAdded(generation, channel(id = 1, name = "one")))
        metadata.acceptMetadata(MetadataEvent.DvrEntryAdded(generation, dvrEntry(8, "old")))
        metadata.acceptMetadata(MetadataEvent.InitialSyncCompleted(generation))
        val retainedDvr = metadata.currentDvrSnapshot()
        repeat(1_000) { index ->
            val id = index.toLong() + 1
            metadata.acceptMetadataDeferringEpg(MetadataEvent.EventAdded(generation, epgEvent(id, 1, id, id + 1)))
        }
        metadata.flushDeferredMetadata()
        assertSame(retainedDvr, metadata.currentDvrSnapshot())
        val epg = metadata.currentEpgSnapshot()
        assertEquals(1_000, epg.events.size)
        repeat(20) { index ->
            metadata.acceptMetadata(MetadataEvent.ChannelUpdated(generation, channel(1, currentEventId = index.toLong() + 1)))
            assertSame(epg, metadata.currentEpgSnapshot())
            assertSame(retainedDvr, metadata.currentDvrSnapshot())
        }
        metadata.acceptMetadata(MetadataEvent.DvrEntryUpdated(generation, dvrEntry(8, "new")))
        assertSame(epg, metadata.currentEpgSnapshot())
        assertEquals("old", retainedDvr.entries.single().title)
        assertEquals("new", metadata.currentDvrSnapshot().entries.single().title)
    }

    private fun PhaseOneSessionMetadata.currentDvrSnapshot() =
        (observation.value.dvrState as DvrRepositoryState.Current).snapshot

    private fun dvrEntry(
        id: Long,
        title: String? = null,
        start: Long? = null,
        stop: Long? = null,
    ): GatewayDvrEntry = GatewayDvrEntry(
        id = DvrEntryId(id),
        title = title,
        start = start?.let(Instant::fromEpochSeconds),
        stop = stop?.let(Instant::fromEpochSeconds),
    )

    private fun channel(
        id: Long,
        name: String? = null,
        uuid: String? = null,
        number: Long? = null,
        numberMinor: Long? = null,
        icon: String? = null,
        currentEventId: Long? = null,
        nextEventId: Long? = null,
        services: List<GatewayChannelService>? = null,
        tagIds: List<Long>? = null,
    ): GatewayChannelMetadata = GatewayChannelMetadata(
        id = ChannelId(id),
        name = name,
        uuid = uuid,
        number = number,
        numberMinor = numberMinor,
        icon = icon,
        currentEventId = currentEventId?.let(::EventId),
        nextEventId = nextEventId?.let(::EventId),
        services = services,
        tagIds = tagIds?.map(::TagId),
    )

    private fun tag(
        id: Long,
        name: String? = null,
        uuid: String? = null,
        index: Long? = null,
        icon: String? = null,
        titledIcon: Boolean? = null,
        channelIds: List<Long>? = null,
    ): GatewayTagMetadata = GatewayTagMetadata(
        id = TagId(id),
        name = name,
        uuid = uuid,
        index = index,
        icon = icon,
        titledIcon = titledIcon,
        channelIds = channelIds?.map(::ChannelId),
    )

    private fun epgEvent(
        id: Long,
        channelId: Long?,
        start: Long,
        stop: Long,
        title: String? = null,
    ): GatewayEpgEvent = GatewayEpgEvent(
        id = EventId(id),
        channelId = channelId?.let(::ChannelId),
        start = Instant.fromEpochSeconds(start),
        stop = Instant.fromEpochSeconds(stop),
        title = title,
    )

    private fun service(name: String): GatewayChannelService = GatewayChannelService(
        name = name,
        type = "type",
        content = 1,
        conditionalAccessId = 2,
        conditionalAccessName = "private-ca",
        providerName = "private-provider",
    )

    private fun serverFacts(
        streaming: Boolean? = null,
        dvr: Boolean? = null,
        failedDvr: Boolean? = null,
        admin: Boolean? = null,
        anonymous: Boolean? = null,
        apiVersion: Long? = null,
        limitAll: Long? = null,
        limitDvr: Long? = null,
        limitStreaming: Long? = null,
        uiLevel: Long? = null,
        serverCapabilities: List<String>? = null,
        serverName: String? = null,
        serverVersion: String? = null,
        webRoot: String? = null,
        language: String? = null,
        uiLanguage: String? = null,
    ): GatewayServerFacts = GatewayServerFacts(
        serverName = serverName,
        serverVersion = serverVersion,
        webRoot = webRoot,
        language = language,
        serverCapabilities = serverCapabilities,
        apiVersion = apiVersion,
        admin = admin,
        streaming = streaming,
        dvr = dvr,
        failedDvr = failedDvr,
        anonymous = anonymous,
        limitAll = limitAll,
        limitDvr = limitDvr,
        limitStreaming = limitStreaming,
        uiLevel = uiLevel,
        uiLanguage = uiLanguage,
    )

    private fun publishReady(
        metadata: PhaseOneSessionMetadata,
        generation: GatewayGeneration,
    ) {
        metadata.publishSessionState(
            state = SessionState.Ready(
                ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN),
            ),
            progressCapability = RecordingProgressCapability.UNKNOWN,
            generation = generation,
        )
    }
}
