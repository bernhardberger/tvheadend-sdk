@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.gateway.AutorecRuleId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryId
import at.bernhardberger.tvheadend.sdk.core.gateway.DvrEntryState
import at.bernhardberger.tvheadend.sdk.core.gateway.EventId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayAutorecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrEntry
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrFailure
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayDvrStats
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayEpgQueryEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTimerecRule
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import at.bernhardberger.tvheadend.sdk.core.gateway.TimerecRuleId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

internal class EntityReplacementTest {
    private val generation = GatewayGeneration()
    private val start = Instant.fromEpochSeconds(100)
    private val stop = Instant.fromEpochSeconds(200)

    @Test
    fun `full channel add and update clear omitted values without mutating retained snapshots`() {
        for (add in listOf(false, true)) {
            val reducer = ChannelTagReducer()
            reducer.accept(MetadataEvent.ChannelAdded(generation, channel(icon = "imagecache/1")))
            val retained = reducer.snapshot()
            reducer.accept(if (add) MetadataEvent.ChannelAdded(generation, channel())
                else MetadataEvent.ChannelUpdated(generation, channel()))
            assertNull(reducer.snapshot().channels.single().icon)
            assertNotNull(retained.channels.single().icon)
        }
    }

    @Test
    fun `tag replacements clear optional scalars but omitted membership alone is preserved`() {
        val reducer = ChannelTagReducer()
        reducer.accept(MetadataEvent.TagAdded(generation, tag(icon = "imagecache/1", members = listOf(ChannelId(1)))))
        reducer.accept(MetadataEvent.TagAdded(generation, tag()))
        assertNull(reducer.snapshot().tags.single().icon)
        assertEquals(listOf(ChannelId(1)), reducer.snapshot().tags.single().channelIds)
        reducer.accept(MetadataEvent.TagUpdated(generation, tag(icon = "imagecache/2")))
        reducer.accept(MetadataEvent.TagUpdated(generation, tag(members = emptyList())))
        assertNull(reducer.snapshot().tags.single().icon)
        assertEquals(emptyList<ChannelId>(), reducer.snapshot().tags.single().channelIds)
        reducer.accept(MetadataEvent.InitialSyncCompleted(generation))
        reducer.accept(MetadataEvent.TagUpdated(generation, tag(id = 2)))
        assertEquals(listOf(TagId(1), TagId(2)), reducer.snapshot().tags.map { it.id })
    }

    @Test
    fun `full EPG update clears optional values and supersedes an older query`() {
        val reducer = EpgReducer()
        reducer.accept(MetadataEvent.ChannelAdded(generation, channel()))
        reducer.accept(MetadataEvent.EventAdded(generation, GatewayEpgEvent(
            EventId(1), ChannelId(1), start, stop, subtitle = "old",
        )))
        val retained = reducer.snapshot()
        val query = checkNotNull(reducer.beginQuery(ChannelId(1)))
        reducer.accept(MetadataEvent.EventUpdated(generation, GatewayEpgEvent(
            EventId(1), ChannelId(1), start, stop,
        )))
        assertNull(reducer.snapshot().events.single().subtitle)
        reducer.acceptSuccessfulQuery(query, stop, listOf(GatewayEpgQueryEvent(
            EventId(1), ChannelId(1), start, stop, subtitle = "stale",
        )))
        assertNull(reducer.snapshot().events.single().subtitle)
        assertEquals("old", retained.events.single().subtitle)
    }

    @Test
    fun `full DVR add and update clear optional values and recompute derived folder`() {
        for (add in listOf(false, true)) {
            val reducer = DvrReducer()
            reducer.accept(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
                DvrEntryId(1), start = start, stop = stop, comment = "old", path = "shows/programme.ts",
            )))
            val retained = reducer.snapshot()
            val cleared = GatewayDvrEntry(DvrEntryId(1), start = start, stop = stop)
            reducer.accept(if (add) MetadataEvent.DvrEntryAdded(generation, cleared)
                else MetadataEvent.DvrEntryUpdated(generation, cleared))
            assertNull(reducer.snapshot().entries.single().comment)
            assertNull(reducer.snapshot().entries.single().folder)
            assertEquals("old", retained.entries.single().comment)
        }
    }

    @Test
    fun `full rule updates clear omitted values and insert unknown rules`() {
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.AutorecRuleAdded(generation, GatewayAutorecRule(AutorecRuleId("a"), comment = "old")))
        reducer.accept(MetadataEvent.TimerecRuleAdded(generation, GatewayTimerecRule(TimerecRuleId("t"), comment = "old")))
        for (id in listOf("a", "b")) reducer.accept(MetadataEvent.AutorecRuleUpdated(generation, GatewayAutorecRule(AutorecRuleId(id))))
        for (id in listOf("t", "u")) reducer.accept(MetadataEvent.TimerecRuleUpdated(generation, GatewayTimerecRule(TimerecRuleId(id))))
        assertEquals(2, reducer.snapshot().autorecRules.size)
        assertEquals(2, reducer.snapshot().timerecRules.size)
        assertTrue(reducer.snapshot().autorecRules.all { it.comment == null })
        assertTrue(reducer.snapshot().timerecRules.all { it.comment == null })
    }

    @Test
    fun `full unseen updates insert channels events and DVR entries`() {
        val channels = ChannelTagReducer()
        channels.accept(MetadataEvent.ChannelUpdated(generation, channel()))
        assertEquals(ChannelId(1), channels.snapshot().channels.single().id)
        val epg = EpgReducer()
        assertTrue(epg.accept(MetadataEvent.EventUpdated(generation, GatewayEpgEvent(EventId(1), start = start, stop = stop))))
        assertEquals(EventId(1), epg.snapshot().events.single().id)
        val dvr = DvrReducer()
        assertTrue(dvr.accept(MetadataEvent.DvrEntryUpdated(generation, GatewayDvrEntry(DvrEntryId(1)))))
        assertEquals(DvrEntryId(1), dvr.snapshot().entries.single().id)
    }

    @Test
    fun `now next patch clears event links but preserves channel metadata`() {
        val reducer = ChannelTagReducer()
        reducer.accept(MetadataEvent.ChannelAdded(generation, channel(icon = "imagecache/1", current = EventId(2))))
        reducer.accept(nowNext(1))
        val channel = reducer.snapshot().channels.single()
        assertNull(channel.currentEventId)
        assertNull(channel.nextEventId)
        assertEquals("Channel", channel.name)
        assertNotNull(channel.icon)
    }

    @Test
    fun `DVR stats patch clears omitted stats while preserving non stats metadata`() {
        val reducer = DvrReducer()
        reducer.accept(MetadataEvent.DvrEntryAdded(generation, GatewayDvrEntry(
            DvrEntryId(1), title = "Recording", comment = "keep", start = start, stop = stop,
            state = DvrEntryState.RECORDING, failure = GatewayDvrFailure.PRESENT, streamErrors = 4,
        )))
        reducer.accept(stats(1))
        val entry = reducer.snapshot().entries.single()
        assertEquals(DvrEntryState.RECORDING, entry.state)
        assertNull(entry.streamErrors)
        assertEquals(42L, entry.dataSizeBytes)
        assertEquals("Recording", entry.title)
        assertEquals("keep", entry.comment)
        assertEquals(start, entry.start)
    }

    @Test
    fun `unknown sparse patches never insert partial entities`() {
        val channels = ChannelTagReducer()
        channels.accept(nowNext(1))
        assertTrue(channels.snapshot().channels.isEmpty())
        val dvr = DvrReducer()
        assertFalse(dvr.accept(stats(1)))
        assertTrue(dvr.snapshot().entries.isEmpty())
    }

    private fun nowNext(id: Long): MetadataEvent = MetadataEvent.ChannelNowNextUpdated(generation, ChannelId(id), null, null)

    private fun stats(id: Long): MetadataEvent = MetadataEvent.DvrEntryStatsUpdated(generation,
        GatewayDvrStats(DvrEntryId(id), uuid = null, state = DvrEntryState.RECORDING, dataSizeBytes = 42))

    private fun channel(icon: String? = null, current: EventId? = null): GatewayChannelMetadata =
        GatewayChannelMetadata(ChannelId(1), "Channel", null, 1, null, icon, current, null, emptyList(), emptyList())

    private fun tag(id: Long = 1, icon: String? = null, members: List<ChannelId>? = null): GatewayTagMetadata =
        GatewayTagMetadata(TagId(id), "Tag", null, 0, icon, false, members)
}
