@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

internal class EpgHistoryTest {
    private val generation = GatewayGeneration()

    @Test
    fun `expiry is inclusive at six hours and newly inserted earlier ends survive clock rewind`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        addChannel(reducer, 2)
        remember(reducer, event(1, 1, 10, 20))
        remember(reducer, event(2, 1, 20, 30))
        val before = reducer.snapshot()
        reducer.pruneHistory(instant(20) + 6.hours - 1.nanoseconds)
        assertSame(before, reducer.snapshot())
        reducer.pruneHistory(instant(20) + 6.hours)
        assertHistory(reducer, 2)

        reducer.pruneHistory(instant(10))
        remember(reducer, event(3, 2, 5, 15))
        assertHistory(reducer, 3, 2)
        reducer.pruneHistory(instant(15) + 6.hours)
        assertHistory(reducer, 2)
        reducer.pruneHistory(instant(30) + 6.hours)
        assertHistory(reducer)
        assertEquals(2, before.historicalEvents.size)
    }

    @Test
    fun `history bound trims by stop then id independently of start and arrival order`() {
        for (order in listOf(listOf(9L, 5L, 7L), listOf(7L, 5L, 9L))) {
            val reducer = EpgReducer(maximumRetainedEvents = 2)
            order.forEach { id ->
                addChannel(reducer, id)
                remember(reducer, event(id, id, if (id == 9L) 10 else 20, 50))
            }
            assertHistory(reducer, 9, 7)
            // Reusing a trimmed id must not leave its former deadline behind.
            remember(reducer, event(5, 5, 50, 60))
            assertHistory(reducer, 9, 5)
            reducer.pruneHistory(instant(50) + 6.hours)
            assertHistory(reducer, 5)
        }
    }

    @Test
    fun `same id supersedes across channels and without a channel for async and query corrections`() {
        for (query in listOf(false, true)) {
            for (channel in listOf(2L, null)) {
                val reducer = EpgReducer()
                addChannel(reducer, 1)
                addChannel(reducer, 2)
                remember(reducer, event(1, 1, 10, 20))
                remember(reducer, event(2, 2, 30, 40))
                correct(reducer, event(1, channel, 30, 40), query)
                assertHistory(reducer, *if (channel == null) longArrayOf(2) else longArrayOf())
                reducer.pruneHistory(instant(40) + 6.hours)
                assertHistory(reducer)
            }
        }
    }

    @Test
    fun `overlap supersession handles long enclosing events multiple hits touching edges and zero duration`() {
        for (query in listOf(false, true)) {
            for ((start, stop, remaining) in listOf(
                Triple(900L, 1150L, emptyList<Long>()),
                Triple(1000L, 1100L, listOf(1L, 3L)),
                Triple(500L, 500L, listOf(2L, 3L)),
                Triple(500L, 499L, listOf(1L, 2L, 3L)),
            )) {
                val reducer = EpgReducer()
                addChannel(reducer, 1)
                addChannel(reducer, 2)
                remember(reducer, event(1, 1, 0, 1000))
                remember(reducer, event(2, 1, 1000, 1100))
                remember(reducer, event(3, 1, 1100, 1200))
                remember(reducer, event(4, 2, 0, 1200))
                correct(reducer, event(5, 1, start, stop), query)
                assertEquals((remaining + 4L).toSet(), reducer.snapshot().historicalEvents.map { it.id.value }.toSet())
            }
        }
    }

    @Test
    fun `all history removal paths allow id reuse with a new channel and deadline`() {
        for (removal in listOf("clear", "channel", "reconcile", "expiry", "trim", "async", "query")) {
            val reducer = EpgReducer(maximumRetainedEvents = 1)
            addChannel(reducer, 1)
            addChannel(reducer, 2)
            remember(reducer, event(1, 1, 10, 20))
            when (removal) {
                "clear" -> reducer.clear()
                "channel" -> reducer.accept(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
                "reconcile" -> reducer.reconcileChannels(listOf(ChannelId(2)))
                "expiry" -> reducer.pruneHistory(instant(20) + 6.hours)
                "trim" -> {
                    remember(reducer, event(2, 2, 20, 30))
                    correct(reducer, event(2, 2, 20, 30), query = false)
                }
                "async" -> correct(reducer, event(1, 1, 10, 20), query = false)
                "query" -> correct(reducer, event(1, 1, 10, 20), query = true)
            }
            assertHistory(reducer)
            addChannel(reducer, 2)
            // Live retention does not prune history; here it just clears any correction.
            reducer.retainOverlapping(instant(31), instant(40))
            remember(reducer, event(1, 2, 30, 40))
            reducer.accept(MetadataEvent.ChannelDeleted(generation, ChannelId(1)))
            reducer.pruneHistory(instant(20) + 6.hours)
            assertHistory(reducer, 1)
            reducer.pruneHistory(instant(40) + 6.hours)
            assertHistory(reducer)
        }
    }

    @Test
    fun `carryover installation and live retention leave historical expiry independent`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        remember(reducer, event(1, 1, 10, 20))
        val seed = requireNotNull(ReducedEpgEvent.fromEvent(event(2, 1, 50, 60))?.toPublicOrNull())
        reducer.installCarryover(listOf(seed), mapOf(ChannelId(1) to instant(40)))
        reducer.retainOverlapping(instant(50), instant(60))
        assertHistory(reducer, 1)
        reducer.pruneHistory(instant(20) + 6.hours)
        assertHistory(reducer)
        assertEquals(listOf(EventId(2)), reducer.snapshot().events.map { it.id })
    }

    @Test
    fun `mixed history workload matches linear reference through corrections trimming and clock changes`() {
        val limit = 24
        val reducer = EpgReducer(maximumRetainedEvents = limit)
        (1L..8L).forEach { addChannel(reducer, it) }
        val expected = linkedMapOf<EventId, GatewayEpgEvent>()
        val random = Random(6)
        repeat(2_000) { step ->
            val now = instant(random.nextLong(0, 40_000))
            reducer.pruneHistory(now)
            expected.values.removeIf { it.stop <= now - 6.hours }
            val start = random.nextLong(0, 40_000)
            val candidate = event(random.nextLong(1, 80), random.nextLong(1, 9), start, start + random.nextLong(1, 500))
            expected.values.removeIf {
                it.id == candidate.id || it.channelId == candidate.channelId &&
                    it.start < candidate.stop && candidate.start < it.stop
            }
            correct(reducer, candidate, query = step % 3 == 0)
            // Keep the live pool empty; independently retain eligible ended deletions in the oracle.
            reducer.accept(MetadataEvent.EventDeleted(generation, candidate.id), now)
            if (candidate.stop <= now && candidate.stop > now - 6.hours) expected[candidate.id] = candidate
            while (expected.size > limit) {
                expected.remove(expected.values.minWith(compareBy({ it.stop }, { it.id.value })).id)
            }
            val actual = reducer.snapshot().historicalEvents
            val ordered = expected.values.sortedWith(compareBy({ it.start }, { it.stop }, { it.id.value }))
            assertEquals(ordered.map { it.id }, actual.map { it.id }, "history ids at step $step")
            assertEquals(ordered.map { it.channelId }, actual.map { it.channelId }, "history channels at step $step")
            assertEquals(ordered.map { it.stop }, actual.map { it.stop }, "history ends at step $step")
            assertTrue(reducer.snapshot().events.isEmpty())
        }
    }

    private fun remember(reducer: EpgReducer, event: GatewayEpgEvent) {
        reducer.accept(MetadataEvent.EventAdded(generation, event))
        reducer.accept(MetadataEvent.EventDeleted(generation, event.id), event.stop)
    }

    private fun correct(reducer: EpgReducer, event: GatewayEpgEvent, query: Boolean) {
        if (query) {
            assertEquals(EpgQueryAcceptance.APPLIED, reducer.acceptSuccessfulQuery(
                requireNotNull(reducer.beginQuery(event.channelId ?: ChannelId(1))), event.stop,
                listOf(GatewayEpgQueryEvent(event.id, event.channelId, event.start, event.stop)),
            ))
        } else {
            reducer.accept(MetadataEvent.EventUpdated(generation, event))
        }
    }

    private fun addChannel(reducer: EpgReducer, id: Long) {
        reducer.accept(MetadataEvent.ChannelAdded(generation, GatewayChannelMetadata(
            ChannelId(id), null, null, null, null, null, null, null, null, null,
        )))
    }

    private fun assertHistory(reducer: EpgReducer, vararg ids: Long) {
        assertEquals(ids.toList(), reducer.snapshot().historicalEvents.map { it.id.value })
    }

    private fun event(id: Long, channel: Long?, start: Long, stop: Long): GatewayEpgEvent =
        GatewayEpgEvent(EventId(id), channel?.let(::ChannelId), instant(start), instant(stop))

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
