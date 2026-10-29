@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgCoverage
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSnapshot
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class EpgSnapshotReuseTest {
    private val generation = GatewayGeneration()
    private val channel = ChannelId(1)

    @Test
    fun `coverage only reducer publication reuses indexes and retains old coverage`() {
        val reducer = EpgReducer()
        addChannel(reducer)
        reducer.accept(MetadataEvent.EventAdded(generation, gatewayEvent(1)))
        val before = reducer.snapshot().also { it.prepareForObservation() }
        reducer.recordSuccessfulQuery(channel, instant(100))
        val after = reducer.snapshot().also { it.prepareForObservation() }
        assertNotSame(before, after)
        assertSame(before.events, after.events)
        assertSame(before.eventsById, after.eventsById)
        assertSame(before.eventsByChannel, after.eventsByChannel)
        assertSame(before.historyByChannel, after.historyByChannel)
        assertNull(before.coverages.single().queriedTo)
        assertEquals(instant(100), after.coverages.single().queriedTo)
        assertNotEquals(before, after)
        assertSame(after, reducer.snapshot())
        assertContentAndHash(after)
    }

    @Test
    fun `changed replacement reordered and removed events rebuild indexes without mutating old snapshot`() {
        val original = listOf(event(1, "Aa"), event(2, "Second"))
        val before = EpgSnapshot.create(original).also { it.prepareForObservation() }
        // Deliberate String and event hash collision: a hash match cannot establish reuse.
        assertEquals(original.first().hashCode(), event(1, "BB").hashCode())
        val changes = listOf(
            listOf(event(1, "BB"), original[1]),
            listOf(event(3, "Replacement"), original[1]),
            original.reversed(),
            original.drop(1),
            listOf(event(1, "Aa", ChannelId(2)), original[1]),
        )
        for (events in changes) {
            val after = before.withUpdatedContent(events, emptyList(), emptyList())
            after.prepareForObservation()
            assertNotSame(before.events, after.events)
            assertNotSame(before.eventsById, after.eventsById)
            assertNotSame(before.eventsByChannel, after.eventsByChannel)
            assertEquals(events.associateBy { it.id }, after.eventsById)
            assertEquals(events.groupBy { it.channelId }, after.eventsByChannel)
            assertEquals(original, before.events)
            assertEquals(original.associateBy { it.id }, before.eventsById)
            assertContentAndHash(after)
        }
    }

    @Test
    fun `live and history storage invalidate independently and reused lazy indexes work before preparation`() {
        val live = event(1, "Live")
        val history = event(2, "History")
        val before = EpgSnapshot.create(listOf(live), historicalEvents = listOf(history))
        val coverage = listOf(EpgCoverage.empty(channel, instant(100)))
        val coverageOnly = before.withUpdatedContent(listOf(live), coverage, listOf(history))
        assertSame(before.eventsById, coverageOnly.eventsById)
        assertSame(before.eventsByChannel, coverageOnly.eventsByChannel)
        assertSame(before.historyByChannel, coverageOnly.historyByChannel)
        val changedHistory = coverageOnly.withUpdatedContent(listOf(live), coverage, listOf(event(3, "New history")))
        assertSame(coverageOnly.eventsById, changedHistory.eventsById)
        assertNotSame(coverageOnly.historyByChannel, changedHistory.historyByChannel)
        val changedLive = coverageOnly.withUpdatedContent(listOf(event(4, "New live")), coverage, listOf(history))
        assertNotSame(coverageOnly.eventsById, changedLive.eventsById)
        assertSame(coverageOnly.historyByChannel, changedLive.historyByChannel)
        assertEquals(listOf(history), before.historyByChannel[channel])
        assertContentAndHash(changedHistory)
        assertContentAndHash(changedLive)
    }

    @Test
    fun `input lists remain defensively owned for new and reused snapshot content`() {
        val events = mutableListOf(event(1, "Original"))
        val history = mutableListOf(event(2, "History"))
        val coverages = mutableListOf(EpgCoverage.empty(channel))
        val before = EpgSnapshot.create(events, coverages, history)
        val same = before.withUpdatedContent(events, coverages, history)
        events += event(3, "Added")
        history += event(4, "Added history")
        val changed = before.withUpdatedContent(events, coverages, history)
        events.clear()
        history.clear()
        coverages.clear()
        assertEquals(1, before.events.size)
        assertEquals(1, same.events.size)
        assertEquals(2, changed.events.size)
        assertEquals(2, changed.historicalEvents.size)
        assertEquals(1, changed.coverages.size)
        for (snapshot in listOf(before, same, changed)) {
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.events as MutableList).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.historicalEvents as MutableList).clear() }
            assertThrows(UnsupportedOperationException::class.java) { (snapshot.coverages as MutableList).clear() }
            assertContentAndHash(snapshot)
        }
    }

    @Test
    fun `duplicate IDs remain ambiguous after reuse reorder and removal`() {
        val first = event(1, "First")
        val second = event(1, "Second")
        val third = event(1, "Third")
        val before = EpgSnapshot.create(listOf(first, second, third))
        val reused = before.withUpdatedContent(before.events.toList(), listOf(EpgCoverage.empty(channel)), emptyList())
        assertSame(before.eventsById, reused.eventsById)
        assertNull(reused.eventsById[EventId(1)])
        val reordered = reused.withUpdatedContent(reused.events.reversed(), emptyList(), emptyList())
        assertNotSame(reused.eventsByChannel, reordered.eventsByChannel)
        assertEquals(listOf(third, second, first), reordered.eventsByChannel[channel])
        assertNull(reordered.eventsById[EventId(1)])
        val removed = reordered.withUpdatedContent(listOf(first), emptyList(), emptyList())
        assertEquals(first, removed.eventsById[EventId(1)])
        assertNull(before.eventsById[EventId(1)])
    }

    @Test
    fun `reducer updates deletes readds and clear invalidate visible storage`() {
        val reducer = EpgReducer()
        addChannel(reducer)
        reducer.accept(MetadataEvent.EventAdded(generation, gatewayEvent(1)))
        reducer.accept(MetadataEvent.EventAdded(generation, gatewayEvent(2)))
        val before = reducer.snapshot().also { it.prepareForObservation() }
        reducer.accept(MetadataEvent.EventUpdated(generation, gatewayEvent(1, "Changed")))
        val changed = reducer.snapshot()
        assertNotSame(before.eventsById, changed.eventsById)
        assertEquals("Changed", changed.eventsById[EventId(1)]?.title)
        reducer.accept(MetadataEvent.EventDeleted(generation, EventId(1)))
        val removed = reducer.snapshot()
        assertEquals(listOf(EventId(2)), removed.events.map { it.id })
        assertNull(removed.eventsById[EventId(1)])
        reducer.accept(MetadataEvent.EventAdded(generation, gatewayEvent(1)))
        val reordered = reducer.snapshot()
        assertEquals(listOf(EventId(2), EventId(1)), reordered.eventsByChannel[channel]?.map { it.id })
        reducer.clear()
        addChannel(reducer)
        reducer.accept(MetadataEvent.EventAdded(generation, gatewayEvent(2)))
        reducer.accept(MetadataEvent.EventAdded(generation, gatewayEvent(1)))
        val reset = reducer.snapshot()
        assertEquals(reordered, reset)
        assertNotSame(reordered.events, reset.events)
        assertNotSame(reordered.eventsById, reset.eventsById)
        assertEquals(listOf(EventId(1), EventId(2)), before.events.map { it.id })
        assertEquals("Original", before.eventsById[EventId(1)]?.title)
    }

    private fun assertContentAndHash(snapshot: EpgSnapshot) {
        assertEquals(EpgSnapshot.create(snapshot.events, snapshot.coverages, snapshot.historicalEvents), snapshot)
        assertEquals(31 * (31 * snapshot.events.hashCode() + snapshot.coverages.hashCode()) +
            snapshot.historicalEvents.hashCode(), snapshot.hashCode())
    }

    private fun addChannel(reducer: EpgReducer) {
        reducer.accept(MetadataEvent.ChannelAdded(generation, GatewayChannelMetadata(
            channel, name = null, uuid = null, number = null, numberMinor = null,
            icon = null, currentEventId = null, nextEventId = null, services = null, tagIds = null,
        )))
    }

    private fun gatewayEvent(id: Long, title: String = "Original"): GatewayEpgEvent = GatewayEpgEvent(
        EventId(id), channelId = channel, start = instant(id * 10), stop = instant(id * 10 + 10), title = title,
    )

    private fun event(id: Long, title: String, channelId: ChannelId = channel): EpgEvent =
        EpgEvent.create(EventId(id), channelId, instant(id * 10), instant(id * 10 + 10), title = title)

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
