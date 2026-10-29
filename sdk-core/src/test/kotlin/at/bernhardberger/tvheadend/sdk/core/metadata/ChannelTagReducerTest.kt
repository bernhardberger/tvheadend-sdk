@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.core.metadata

import at.bernhardberger.tvheadend.sdk.core.ArtworkId
import at.bernhardberger.tvheadend.sdk.core.ChannelIcon
import at.bernhardberger.tvheadend.sdk.core.ChannelMediaKind
import at.bernhardberger.tvheadend.sdk.core.mediaKind
import at.bernhardberger.tvheadend.sdk.core.gateway.ChannelId
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayChannelService
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayGeneration
import at.bernhardberger.tvheadend.sdk.core.gateway.GatewayTagMetadata
import at.bernhardberger.tvheadend.sdk.core.gateway.MetadataEvent
import at.bernhardberger.tvheadend.sdk.core.gateway.TagId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ChannelTagReducerTest {
    @Test
    fun `channel and tag icons distinguish image cache IDs and remote URLs`() {
        val cases = listOf(
            "imagecache/12" to ChannelIcon.ImageCache(ArtworkId(12)),
            "/imagecache/12" to ChannelIcon.ImageCache(ArtworkId(12)),
            "http://example.invalid/imagecache/12" to ChannelIcon.parse("http://example.invalid/imagecache/12"),
            "https://example.invalid/logo.png" to ChannelIcon.parse("https://example.invalid/logo.png"),
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
    fun `full updates replace remote icons with image cache IDs and clear absent icons`() {
        val generation = GatewayGeneration()
        val reducer = ChannelTagReducer()
        reducer.accept(MetadataEvent.ChannelAdded(generation, channel(1, "https://example.invalid/logo.png")))
        reducer.accept(MetadataEvent.TagAdded(generation, tag(1, "http://example.invalid/logo.png")))
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, "imagecache/7")))
        reducer.accept(MetadataEvent.TagUpdated(generation, tag(1, "imagecache/8")))
        assertEquals(ChannelIcon.ImageCache(ArtworkId(7)), reducer.snapshot().channels.single().icon)
        assertEquals(ChannelIcon.ImageCache(ArtworkId(8)), reducer.snapshot().tags.single().icon)
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null)))
        reducer.accept(MetadataEvent.TagUpdated(generation, tag(1, null)))

        assertEquals(null, reducer.snapshot().channels.single().icon)
        assertEquals(null, reducer.snapshot().tags.single().icon)
    }

    @Test
    fun `full updates clear absent services and reclassify immutable snapshots`() {
        val generation = GatewayGeneration()
        val reducer = ChannelTagReducer()
        reducer.accept(MetadataEvent.ChannelAdded(generation, channel(1, null)))
        assertEquals(ChannelMediaKind.UNKNOWN, reducer.snapshot().channels.single().mediaKind)

        val services = mutableListOf(service(2), service(1))
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null, services = services)))
        val television = reducer.snapshot()
        services.clear()
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null, name = "Updated")))
        assertEquals(ChannelMediaKind.UNKNOWN, reducer.snapshot().channels.single().mediaKind)

        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null, services = listOf(service(2)))))
        assertEquals(ChannelMediaKind.RADIO, reducer.snapshot().channels.single().mediaKind)
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null, services = listOf(service(0)))))
        assertEquals(ChannelMediaKind.OTHER, reducer.snapshot().channels.single().mediaKind)
        reducer.accept(MetadataEvent.ChannelUpdated(generation, channel(1, null, services = emptyList())))
        assertEquals(ChannelMediaKind.UNKNOWN, reducer.snapshot().channels.single().mediaKind)
        assertEquals(ChannelMediaKind.TV, television.channels.single().mediaKind)
        assertEquals(listOf(2L, 1L), television.channels.single().services?.map { it.content })
    }

    private fun service(content: Long): GatewayChannelService = GatewayChannelService(
        name = "Service",
        type = "",
        content = content,
        conditionalAccessId = null,
        conditionalAccessName = null,
        providerName = null,
    )

    private fun channel(
        id: Long,
        icon: String?,
        name: String? = null,
        services: List<GatewayChannelService>? = null,
    ): GatewayChannelMetadata = GatewayChannelMetadata(
        id = ChannelId(id),
        name = name,
        uuid = null,
        number = null,
        numberMinor = null,
        icon = icon,
        currentEventId = null,
        nextEventId = null,
        services = services,
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
