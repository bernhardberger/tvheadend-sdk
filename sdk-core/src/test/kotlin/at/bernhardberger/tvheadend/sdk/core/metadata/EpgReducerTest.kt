package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.EpgEpisodeId
import at.bernhardberger.tvheadend.sdk.core.EpgEvent
import at.bernhardberger.tvheadend.sdk.core.EpgSeriesLinkId
import at.bernhardberger.tvheadend.sdk.core.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgUpdate
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class EpgReducerTest {
    private val generation = GatewayGeneration()

    @Test
    fun `complete add replaces nullable values while update preserves omissions`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(
            MetadataEvent.EventAdded(
                generation,
                event(
                    id = 10,
                    channelId = 1,
                    start = 10,
                    stop = 20,
                    title = "old",
                    categories = listOf("private-category"),
                    keywords = listOf("private-keyword"),
                    ageRating = 12,
                    episodeId = EpgEpisodeId(30),
                    seriesLinkId = EpgSeriesLinkId(31),
                ),
            ),
        )
        reducer.accept(
            MetadataEvent.EventUpdated(
                generation,
                update(id = 10, title = "new", categories = emptyList()),
            ),
        )

        var event = reducer.snapshot().events.single()
        assertEquals("new", event.title)
        assertEquals(emptyList<String>(), event.categories)
        assertEquals(listOf("private-keyword"), event.keywords)
        assertEquals(12L, event.rating?.age)
        assertEquals(EpgEpisodeId(30), event.episode?.id)

        reducer.accept(MetadataEvent.EventAdded(generation, event(10, 1, 30, 40)))

        event = reducer.snapshot().events.single()
        assertEquals(instant(30), event.start)
        assertEquals(null, event.title)
        assertEquals(null, event.categories)
        assertEquals(null, event.keywords)
        assertEquals(null, event.rating)
        assertEquals(null, event.episode)
    }

    @Test
    fun `unknown updates remain private drafts until timing is complete`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, title = "draft")))
        assertEquals(emptyList<Any>(), reducer.snapshot().events)

        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, channelId = 1, start = 10)))
        assertEquals(emptyList<Any>(), reducer.snapshot().events)

        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, stop = 20)))
        val event = reducer.snapshot().events.single()
        assertEquals(null, event.title)
        assertEquals(ChannelId(1), event.channelId)
        assertEquals(instant(10), event.start)
        assertEquals(instant(20), event.stop)
    }

    @Test
    fun `retention preserves incomplete drafts for later updates`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, channelId = 1, start = 10)))

        reducer.retainOverlapping(instant(0), instant(20))
        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, stop = 20)))

        val event = reducer.snapshot().events.single()
        assertEquals(instant(10), event.start)
        assertEquals(instant(20), event.stop)
    }

    @Test
    fun `untimed unknown updates are not retained as drafts`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, title = "draft")))
        reducer.retainOverlapping(instant(0), instant(20))
        reducer.accept(
            MetadataEvent.EventUpdated(
                generation,
                update(10, channelId = 1, start = 10, stop = 20),
            ),
        )

        val event = reducer.snapshot().events.single()
        assertEquals(null, event.title)
        assertEquals(instant(10), event.start)
        assertEquals(instant(20), event.stop)
    }

    @Test
    fun `invalid temporal candidates never replace valid state`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(MetadataEvent.EventAdded(generation, event(10, 1, 10, 20, title = "valid")))
        val original = reducer.snapshot().events.single()

        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, stop = 9, title = "invalid")))
        assertEquals(original, reducer.snapshot().events.single())

        reducer.accept(MetadataEvent.EventAdded(generation, event(10, 1, 30, 29, title = "invalid-add")))
        assertEquals(original, reducer.snapshot().events.single())
    }

    @Test
    fun `invalid first update leaves no insertion-order placeholder`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(
            MetadataEvent.EventUpdated(
                generation,
                update(id = 2, channelId = 1, start = 20, stop = 10),
            ),
        )
        reducer.accept(MetadataEvent.EventAdded(generation, event(3, 1, 30, 40)))

        reducer.accept(
            MetadataEvent.EventUpdated(
                generation,
                update(id = 2, channelId = 1, start = 50, stop = 60),
            ),
        )

        assertEquals(listOf(3L, 2L), reducer.snapshot().events.map { it.id.value })
    }

    @Test
    fun `channel migration and deletion update events and actual coverage`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        addChannel(reducer, 2)
        reducer.accept(MetadataEvent.EventAdded(generation, event(10, 1, 10, 20)))

        var snapshot = reducer.snapshot()
        assertFalse(snapshot.coverages.first { it.channelId == ChannelId(1) }.isEmpty)
        assertTrue(snapshot.coverages.first { it.channelId == ChannelId(2) }.isEmpty)

        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, channelId = 2)))
        snapshot = reducer.snapshot()
        assertEquals(ChannelId(2), snapshot.events.single().channelId)
        assertTrue(snapshot.coverages.first { it.channelId == ChannelId(1) }.isEmpty)
        assertFalse(snapshot.coverages.first { it.channelId == ChannelId(2) }.isEmpty)

        reducer.accept(MetadataEvent.ChannelDeleted(generation, ChannelId(2)))
        snapshot = reducer.snapshot()
        assertEquals(emptyList<Any>(), snapshot.events)
        assertEquals(listOf(ChannelId(1)), snapshot.coverages.map { it.channelId })
    }

    @Test
    fun `successful query replaces represented fields while preserving async-only metadata`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        addChannel(reducer, 2)
        reducer.accept(
            MetadataEvent.EventAdded(
                generation,
                event(
                    id = 10,
                    channelId = 1,
                    start = 10,
                    stop = 20,
                    title = "old",
                    categories = listOf("old"),
                    episodeId = EpgEpisodeId(30),
                    seriesLinkId = EpgSeriesLinkId(31),
                ),
            ),
        )

        reducer.acceptSuccessfulQuery(
            channelId = ChannelId(2),
            queriedTo = instant(100),
            queriedEvents = listOf(
                GatewayEpgQueryEvent(
                    id = EventId(10),
                    channelId = ChannelId(2),
                    start = instant(30),
                    stop = instant(40),
                    title = null,
                    categories = null,
                ),
                GatewayEpgQueryEvent(
                    id = EventId(11),
                    channelId = ChannelId(1),
                    start = instant(30),
                    stop = instant(40),
                ),
                GatewayEpgQueryEvent(
                    id = EventId(12),
                    channelId = ChannelId(2),
                    start = instant(50),
                    stop = instant(49),
                ),
            ),
        )

        val snapshot = reducer.snapshot()
        val event = snapshot.events.single()
        assertEquals(EventId(10), event.id)
        assertEquals(ChannelId(2), event.channelId)
        assertEquals(instant(30), event.start)
        assertEquals(null, event.title)
        assertEquals(null, event.categories)
        assertEquals(EpgEpisodeId(30), event.episode?.id)
        assertEquals(EpgSeriesLinkId(31), event.episode?.seriesLinkId)
        assertTrue(snapshot.coverages.first { it.channelId == ChannelId(1) }.isEmpty)
        assertEquals(
            instant(100),
            snapshot.coverages.first { it.channelId == ChannelId(2) }.queriedTo,
        )
    }

    @Test
    fun `reconciliation removes incomplete and orphaned events`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(MetadataEvent.EventUpdated(generation, update(10, title = "incomplete")))
        reducer.accept(MetadataEvent.EventAdded(generation, event(11, 2, 10, 20)))
        reducer.accept(MetadataEvent.EventAdded(generation, event(12, null, 20, 30)))

        reducer.reconcileChannels(listOf(ChannelId(1)))

        val snapshot = reducer.snapshot()
        assertEquals(listOf(12L), snapshot.events.map { it.id.value })
        assertEquals(listOf(ChannelId(1)), snapshot.coverages.map { it.channelId })
        assertTrue(snapshot.coverages.single().isEmpty)
    }

    @Test
    fun `overlap retention is inclusive while queried horizon stays monotonic`() {
        val reducer = EpgReducer()
        addChannel(reducer, 1)
        reducer.accept(MetadataEvent.EventAdded(generation, event(1, 1, 0, 10)))
        reducer.accept(MetadataEvent.EventAdded(generation, event(2, 1, 20, 30)))
        reducer.accept(MetadataEvent.EventAdded(generation, event(3, 1, -5, 35)))
        reducer.accept(MetadataEvent.EventAdded(generation, event(4, 1, 31, 40)))
        reducer.recordSuccessfulQuery(ChannelId(1), instant(50))
        reducer.recordSuccessfulQuery(ChannelId(1), instant(45))

        reducer.retainOverlapping(instant(10), instant(20))

        var snapshot = reducer.snapshot()
        assertEquals(listOf(1L, 2L, 3L), snapshot.events.map { it.id.value })
        var coverage = snapshot.coverages.single()
        assertEquals(instant(-5), coverage.coveredFrom)
        assertEquals(instant(35), coverage.coveredTo)
        assertEquals(instant(50), coverage.queriedTo)
        assertEquals(instant(50), coverage.knownTo)

        reducer.accept(MetadataEvent.EventDeleted(generation, EventId(3)))
        snapshot = reducer.snapshot()
        coverage = snapshot.coverages.single()
        assertEquals(instant(0), coverage.coveredFrom)
        assertEquals(instant(30), coverage.coveredTo)
        assertEquals(instant(50), coverage.knownTo)

        reducer.accept(MetadataEvent.EventDeleted(generation, EventId(1)))
        reducer.accept(MetadataEvent.EventDeleted(generation, EventId(2)))
        coverage = reducer.snapshot().coverages.single()
        assertTrue(coverage.isEmpty)
        assertEquals(instant(50), coverage.knownTo)
    }

    @Test
    fun `gateway collection inputs and historical snapshots remain isolated`() {
        val reducer = EpgReducer()
        val categories = mutableListOf("one")
        addChannel(reducer, 1)
        reducer.accept(
            MetadataEvent.EventAdded(
                generation,
                event(1, 1, 0, 10, categories = categories),
            ),
        )
        val historical = reducer.snapshot()
        categories.clear()
        reducer.accept(MetadataEvent.EventUpdated(generation, update(1, categories = listOf("two"))))

        assertEquals(listOf("one"), historical.events.single().categories)
        assertEquals(listOf("two"), reducer.snapshot().events.single().categories)
        assertFalse(
            listOf(historical, historical.events.single()).joinToString().contains("one"),
            "EPG rendering exposed event values",
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (historical.events as MutableList<EpgEvent>).clear()
        }
    }

    private fun addChannel(reducer: EpgReducer, id: Long) {
        reducer.accept(
            MetadataEvent.ChannelAdded(
                generation,
                GatewayChannelMetadata(
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
                ),
            ),
        )
    }

    private fun event(
        id: Long,
        channelId: Long?,
        start: Long,
        stop: Long,
        title: String? = null,
        categories: List<String>? = null,
        keywords: List<String>? = null,
        ageRating: Long? = null,
        episodeId: EpgEpisodeId? = null,
        seriesLinkId: EpgSeriesLinkId? = null,
    ): GatewayEpgEvent = GatewayEpgEvent(
        id = EventId(id),
        channelId = channelId?.let(::ChannelId),
        start = instant(start),
        stop = instant(stop),
        title = title,
        categories = categories,
        keywords = keywords,
        ageRating = ageRating,
        episodeId = episodeId,
        seriesLinkId = seriesLinkId,
    )

    private fun update(
        id: Long,
        channelId: Long? = null,
        start: Long? = null,
        stop: Long? = null,
        title: String? = null,
        categories: List<String>? = null,
    ): GatewayEpgUpdate = GatewayEpgUpdate(
        id = EventId(id),
        channelId = channelId?.let(::ChannelId),
        start = start?.let(::instant),
        stop = stop?.let(::instant),
        title = title,
        categories = categories,
    )

    private fun instant(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
}
