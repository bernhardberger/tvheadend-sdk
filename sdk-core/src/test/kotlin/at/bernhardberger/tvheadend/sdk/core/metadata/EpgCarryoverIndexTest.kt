@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class EpgCarryoverIndexTest {
    private val generation = GatewayGeneration()
    private val channels = listOf(ChannelId(1), ChannelId(2), ChannelId(3))
    private val proof = channels.associateWith { instant(0) }

    @Test
    fun `duplicate seed ids move buckets without moving global order and exact bounds remain distinct`() {
        val reducer = reducer()
        val first = event(1, 1, 10)
        val second = event(2, 2, 20)
        val moved = event(1, 2, 30)
        val third = event(3, 1, 40)
        reducer.installCarryover(listOf(first, second, third, moved), proof)
        assertContents(reducer, listOf(moved, second, third))

        // The old bucket must not remove the same id now owned by another channel.
        reducer.recordSuccessfulQuery(ChannelId(1), instant(30))
        assertContents(reducer, listOf(moved, second, third))
        val live = event(90, 2, 0, stop = 20)
        acceptLive(reducer, live)
        assertContents(reducer, listOf(moved, second, third), listOf(live))
        reducer.recordSuccessfulQuery(ChannelId(2), instant(20))
        assertContents(reducer, listOf(moved, third), listOf(live))
        reducer.recordSuccessfulQuery(ChannelId(2), instant(30))
        assertContents(reducer, listOf(third), listOf(live))
        val receded = event(90, 2, 0, stop = 5)
        acceptLive(reducer, receded)
        assertContents(reducer, listOf(third), listOf(receded))
    }

    @Test
    fun `deletion live replacement retention and channel deletion release their index entries`() {
        val reducer = reducer()
        val seed = listOf(event(1, 1, 10), event(2, 2, 20), event(3, 1, 30), event(4, 2, 40), event(5, 3, 50))
        reducer.installCarryover(seed, proof)
        assertContents(reducer, seed)
        reducer.accept(MetadataEvent.EventDeleted(generation, EventId(1)))
        assertContents(reducer, seed.drop(1))
        val movedLive = event(2, 3, 1, stop = 2)
        acceptLive(reducer, movedLive)
        assertContents(reducer, seed.drop(2), listOf(movedLive))
        reducer.retainOverlapping(instant(0), instant(35))
        assertContents(reducer, listOf(seed[2]), listOf(movedLive))
        reducer.accept(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
        assertContents(reducer, emptyList(), listOf(movedLive))
        reducer.recordSuccessfulQuery(ChannelId(2), instant(100))
        assertContents(reducer, emptyList(), listOf(movedLive))
    }

    @Test
    fun `single and multiple capacity evictions release buckets in latest start then id order`() {
        val reducer = reducer(capacity = 3)
        val seed = listOf(event(1, 1, 10), event(2, 2, 20), event(3, 1, 20), event(4, 2, 20), event(5, 3, 30))
        reducer.installCarryover(seed, proof)
        assertContents(reducer, seed.take(3))
        val live = event(90, 3, 0, stop = 1)
        acceptLive(reducer, live)
        assertContents(reducer, seed.take(2), listOf(live))
        val otherLive = event(91, 3, 1, stop = 2)
        acceptLive(reducer, otherLive)
        assertContents(reducer, seed.take(1), listOf(live, otherLive))
        reducer.recordSuccessfulQuery(ChannelId(1), instant(10))
        assertContents(reducer, emptyList(), listOf(live, otherLive))
    }

    @Test
    fun `reinstallation clear late installation and reconciliation replace index ownership`() {
        val reducer = reducer()
        reducer.installCarryover(listOf(event(1, 1, 10), event(2, 2, 20)), proof)
        val replacement = event(1, 3, 30)
        reducer.installCarryover(listOf(replacement), proof)
        assertContents(reducer, listOf(replacement))
        reducer.recordSuccessfulQuery(ChannelId(1), instant(100))
        assertContents(reducer, listOf(replacement))
        reducer.clear()
        assertContents(reducer, emptyList())
        channels.forEach { addChannel(reducer, it) }
        reducer.installCarryover(emptyList(), proof, awaitLateSeed = true)
        val live = event(90, 1, 0, stop = 10)
        acceptLive(reducer, live)
        val seed = listOf(event(1, 1, 10), event(2, 2, 20), event(3, 3, 30))
        assertTrue(reducer.installLateCarryover(seed, proof))
        assertContents(reducer, seed, listOf(live))
        reducer.reconcileChannels(listOf(ChannelId(2)))
        assertContents(reducer, listOf(seed[1]))
        reducer.recordSuccessfulQuery(ChannelId(2), instant(20))
        assertContents(reducer, emptyList())
    }

    private fun reducer(capacity: Int = 20): EpgReducer = EpgReducer(capacity).also { reducer ->
        channels.forEach { addChannel(reducer, it) }
    }

    private fun addChannel(reducer: EpgReducer, id: ChannelId) {
        reducer.accept(MetadataEvent.ChannelAdded(generation, GatewayChannelMetadata(
            id, name = null, uuid = null, number = null, numberMinor = null, icon = null,
            currentEventId = null, nextEventId = null, services = null, tagIds = null,
        )))
    }

    private fun acceptLive(reducer: EpgReducer, event: EpgEvent) {
        reducer.accept(MetadataEvent.EventUpdated(generation, GatewayEpgEvent(
            event.id, event.channelId, event.start, event.stop,
        )))
    }

    private fun assertContents(reducer: EpgReducer, carryover: List<EpgEvent>, live: List<EpgEvent> = emptyList()) {
        assertEquals(live + carryover, reducer.snapshot().events)
        // Public output alone cannot expose orphaned entries retaining programmes in the index.
        // Inspect that private ownership invariant without adding a production test-only API.
        val field = EpgReducer::class.java.getDeclaredField("carryoverByChannel")
        field.isAccessible = true
        assertEquals(carryover.groupBy { it.channelId }.mapValues { (_, events) -> events.associateBy { it.id } },
            field.get(reducer))
    }

    private fun event(id: Long, channel: Long, start: Long, stop: Long = start + 5): EpgEvent =
        EpgEvent.create(EventId(id), ChannelId(channel), instant(start), instant(stop))

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
