package at.bernhardberger.tvheadend.sdk.testing

import at.bernhardberger.tvheadend.sdk.core.Channel
import at.bernhardberger.tvheadend.sdk.core.ChannelCatalog
import at.bernhardberger.tvheadend.sdk.core.ChannelId
import at.bernhardberger.tvheadend.sdk.core.ChannelRepositoryState
import at.bernhardberger.tvheadend.sdk.core.ChannelTag
import at.bernhardberger.tvheadend.sdk.core.ChannelTagId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class FakeChannelRepositoryTest {
    @Test
    fun `fake scripts only complete public repository states`() = runTest {
        val channel = Channel.create(ChannelId(7), name = "channel")
        val tag = ChannelTag.create(ChannelTagId(8), name = "tag", titledIcon = true)
        val catalog = ChannelCatalog.create(channels = listOf(channel), tags = listOf(tag))
        val repository = FakeChannelRepository(
            ChannelRepositoryState.Synchronizing(staleCatalog = catalog),
        )

        assertSame(catalog.channels, repository.channels.value)
        assertSame(catalog.tags, repository.tags.value)
        assertSame(channel, repository.channel(ChannelId(7)).first())
        assertSame(tag, repository.tag(ChannelTagId(8)).first())

        repository.setState(ChannelRepositoryState.Current(ChannelCatalog.create()))

        assertEquals(emptyList<Channel>(), repository.channels.value)
        assertEquals(emptyList<ChannelTag>(), repository.tags.value)
        assertEquals(null, repository.channel(ChannelId(7)).first())
        assertEquals(null, repository.tag(ChannelTagId(8)).first())
        assertEquals(
            ChannelRepositoryState.Current(ChannelCatalog.create()),
            repository.state.value,
        )
    }
}
