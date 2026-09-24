package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ChannelTagReducerTest {
    @Test
    fun `channel and tag icons expose only positive image cache artwork IDs`() {
        val cases = listOf(
            "imagecache/12" to ArtworkId(12),
            "/imagecache/12" to ArtworkId(12),
            "http://example.invalid/imagecache/12" to null,
            "imagecache/0" to null,
            "picon/x" to null,
            null to null,
        )
        val generation = GatewayGeneration()
        val reducer = ChannelTagReducer()
        cases.forEachIndexed { index, (icon, _) ->
            reducer.accept(MetadataEvent.ChannelAdded(generation, channel(index.toLong(), icon)))
            reducer.accept(MetadataEvent.TagAdded(generation, tag(index.toLong(), icon)))
        }

        val catalog = reducer.snapshot()

        assertEquals(cases.map { it.second }, catalog.channels.map { it.icon })
        assertEquals(cases.map { it.second }, catalog.tags.map { it.icon })
    }

    @Test
    fun `an update without an icon keeps the previous artwork ID`() {
        val generation = GatewayGeneration()
        val reducer = ChannelTagReducer()
        reducer.accept(MetadataEvent.ChannelAdded(generation, channel(1, "imagecache/7")))
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null)))

        assertEquals(ArtworkId(7), reducer.snapshot().channels.single().icon)
    }

    private fun channel(id: Long, icon: String?): GatewayChannelMetadata = GatewayChannelMetadata(
        id = ChannelId(id),
        name = null,
        uuid = null,
        number = null,
        numberMinor = null,
        icon = icon,
        currentEventId = null,
        nextEventId = null,
        services = null,
        tagIds = null,
    )

    private fun tag(id: Long, icon: String?): GatewayTagMetadata = GatewayTagMetadata(
        id = TagId(id),
        name = null,
        uuid = null,
        index = null,
        icon = icon,
        titledIcon = null,
        channelIds = null,
    )
}
