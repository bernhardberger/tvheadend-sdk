@file:OptIn(at.bernhardberger.tvheadend.sdk.playback.ExperimentalTvheadendApi::class)

package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelMediaKind
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.SessionObservation
import at.bernhardberger.tvheadend.sdk.core.mediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ChannelCatalogFixtureTest {
    @Test
    fun `catalog fixtures publish classifications through the application fake`() {
        val fake = FakeTvheadendSession()
        for (catalog in listOf(
            ChannelCatalogFixture.create(),
            ChannelCatalogFixture.create(ChannelId(4), ChannelId(5), ChannelId(6)),
        )) {
            fake.publish(SessionObservation.create(channelState = ChannelRepositoryState.Current(catalog)))
            val published = (fake.observation.value.channelState as ChannelRepositoryState.Current).catalog
            assertEquals(catalog.channels.map { it.id }, published.channels.map { it.id })
            assertEquals(
                listOf(ChannelMediaKind.TV, ChannelMediaKind.RADIO, ChannelMediaKind.UNKNOWN),
                published.channels.map { it.mediaKind },
            )
        }
    }
}
