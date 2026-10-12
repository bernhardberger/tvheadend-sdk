package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelService
import at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi

/** TV, radio and unknown catalog metadata for application fakes, without a protocol connection. */
@ExperimentalTvheadendApi
public object ChannelCatalogFixture {
    /** Creates three channels with configurable identifiers and synthetic service metadata. */
    public fun create(
        tvChannelId: ChannelId = ChannelId(1),
        radioChannelId: ChannelId = ChannelId(2),
        unknownChannelId: ChannelId = ChannelId(3),
    ): ChannelCatalog = ChannelCatalog.create(
        channels = listOf(
            Channel.create(tvChannelId, name = "TV", services = listOf(service(1))),
            Channel.create(radioChannelId, name = "Radio", services = listOf(service(2))),
            Channel.create(unknownChannelId, name = "Unknown"),
        ),
    )

    private fun service(content: Long): ChannelService = ChannelService(
        name = "Fixture service",
        type = "",
        content = content,
        conditionalAccessId = null,
        conditionalAccessName = null,
        providerName = null,
    )
}
