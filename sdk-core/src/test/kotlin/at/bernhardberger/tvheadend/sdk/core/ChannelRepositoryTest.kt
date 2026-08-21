package at.bernhardberger.tvheadend.sdk.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChannelRepositoryTest {
    @Test
    fun `identifier values enforce the unsigned wire domain`() {
        assertEquals(0L, ChannelId(0).value)
        assertEquals(0xffff_ffffL, ChannelTagId(0xffff_ffffL).value)
        assertThrows(IllegalArgumentException::class.java) { EventId(-1) }
        assertThrows(IllegalArgumentException::class.java) { ChannelId(0x1_0000_0000L) }
        assertThrows(IllegalArgumentException::class.java) {
            Channel.create(ChannelId(1), number = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Channel.create(ChannelId(1), numberMinor = 0x1_0000_0000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelTag.create(ChannelTagId(1), index = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelService(
                name = "service",
                type = "type",
                content = 0x1_0000_0000L,
                conditionalAccessId = null,
                conditionalAccessName = null,
                providerName = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelService(
                name = "service",
                type = "type",
                content = 0,
                conditionalAccessId = -1,
                conditionalAccessName = null,
                providerName = null,
            )
        }
        assertEquals(0L, Channel.create(ChannelId(1), number = 0, numberMinor = 0xffff_ffffL).number)
        assertEquals(true, ChannelTag.create(ChannelTagId(1), titledIcon = true).titledIcon)
        assertEquals(false, ChannelTag.create(ChannelTagId(1), titledIcon = false).titledIcon)
    }

    @Test
    fun `server capabilities freeze features and distinguish empty from unknown`() {
        val features = mutableListOf("htsp")
        val capabilities = ServerCapabilities.create(
            streaming = CapabilityAccess.ALLOWED,
            dvrWrite = CapabilityAccess.UNKNOWN,
            features = features,
            serverName = "private-server",
            webRoot = "/secret",
        )
        features.clear()
        assertEquals(listOf("htsp"), capabilities.features)
        assertEquals(
            null,
            ServerCapabilities.create(CapabilityAccess.UNKNOWN, CapabilityAccess.UNKNOWN).features,
        )
        assertEquals(
            emptyList<String>(),
            ServerCapabilities.create(
                streaming = CapabilityAccess.UNKNOWN,
                dvrWrite = CapabilityAccess.UNKNOWN,
                features = emptyList(),
            ).features,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (capabilities.features as MutableList<String>).clear()
        }
        assertFalse(
            capabilities.toString().contains("private"),
            "Capability rendering exposed server identity",
        )
        assertFalse(
            capabilities.toString().contains("/secret"),
            "Capability rendering exposed a path",
        )
    }

    @Test
    fun `catalog factories freeze nested collections and redact rendering`() {
        val services = mutableListOf(service("private-service"))
        val tagIds = mutableListOf(ChannelTagId(2))
        val channelIds = mutableListOf(ChannelId(1))
        val channel = Channel.create(
            id = ChannelId(1),
            name = "private-channel",
            services = services,
            tagIds = tagIds,
        )
        val tag = ChannelTag.create(
            id = ChannelTagId(2),
            name = "private-tag",
            channelIds = channelIds,
        )
        val channels = mutableListOf(channel)
        val tags = mutableListOf(tag)
        val catalog = ChannelCatalog.create(channels, tags)

        services.clear()
        tagIds.clear()
        channelIds.clear()
        channels.clear()
        tags.clear()

        assertEquals(1, catalog.channels.single().services?.size)
        assertEquals(listOf(ChannelTagId(2)), catalog.channels.single().tagIds)
        assertEquals(listOf(ChannelId(1)), catalog.tags.single().channelIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (catalog.channels as MutableList<Channel>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (catalog.channels.single().services as MutableList<ChannelService>).clear()
        }
        assertFalse(
            listOf(channel, tag, catalog, service("private-service")).joinToString().contains("private"),
            "Public metadata rendering exposed private values",
        )
    }

    @Test
    fun `all projections derive atomically from one freshness state`() = runTest {
        val repository = TestChannelRepository()
        val channel = Channel.create(ChannelId(1), name = "one")
        val tag = ChannelTag.create(ChannelTagId(2), name = "two", titledIcon = false)
        val catalog = ChannelCatalog.create(channels = listOf(channel), tags = listOf(tag))

        assertEquals(ChannelRepositoryState.Empty, repository.state.value)
        assertEquals(emptyList<Channel>(), repository.channels.value)
        assertEquals(emptyList<ChannelTag>(), repository.tags.value)
        assertEquals(null, repository.channel(ChannelId(1)).first())
        assertEquals(null, repository.tag(ChannelTagId(2)).first())

        repository.set(ChannelRepositoryState.Synchronizing(catalog))
        assertSame(catalog.channels, repository.channels.value)
        assertSame(catalog.tags, repository.tags.value)
        assertSame(channel, repository.channel(ChannelId(1)).first())
        assertSame(tag, repository.tag(ChannelTagId(2)).first())

        val replayed = async { repository.channels.first() }
        runCurrent()
        assertSame(catalog.channels, replayed.await())

        repository.set(ChannelRepositoryState.Current(ChannelCatalog.create()))
        assertEquals(emptyList<Channel>(), repository.channels.value)
        assertEquals(emptyList<ChannelTag>(), repository.tags.value)
        assertEquals(null, repository.channel(ChannelId(1)).first())
        assertEquals(null, repository.tag(ChannelTagId(2)).first())
        assertEquals(
            ChannelRepositoryState.Current(ChannelCatalog.create()),
            repository.state.value,
        )
    }

    private class TestChannelRepository : StateBackedChannelRepository() {
        private val mutableState = MutableStateFlow<ChannelRepositoryState>(ChannelRepositoryState.Empty)
        override val state: StateFlow<ChannelRepositoryState> = mutableState.asStateFlow()

        fun set(state: ChannelRepositoryState) {
            mutableState.value = state
        }
    }

    private fun service(name: String): ChannelService = ChannelService(
        name = name,
        type = "type",
        content = 1,
        conditionalAccessId = 2,
        conditionalAccessName = "private-ca",
        providerName = "private-provider",
    )
}
