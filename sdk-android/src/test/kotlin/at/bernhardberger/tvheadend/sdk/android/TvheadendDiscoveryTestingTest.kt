@file:OptIn(at.bernhardberger.tvheadend.sdk.core.TvheadendTestingApi::class)

package at.bernhardberger.tvheadend.sdk.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class TvheadendDiscoveryTestingTest {
    @Test
    fun `factory defaults to an empty snapshot`() {
        assertEquals(emptyList<DiscoveredTvheadendServer>(), TvheadendDiscoveryTestFactory.discovering().servers)
    }

    @Test
    fun `factory preserves supplied order and duplicate content`() {
        val first = DiscoveredTvheadendServer("Zulu", "192.0.2.2", 9982)
        val second = DiscoveredTvheadendServer("Alpha", "192.0.2.1", 9983)
        val servers = listOf(first, second, first)

        assertEquals(servers, TvheadendDiscoveryTestFactory.discovering(servers).servers)
    }

    @Test
    fun `caller mutations do not change the snapshot`() {
        val server = DiscoveredTvheadendServer("Fixture", "192.0.2.1", 9982)
        val servers = mutableListOf(server)
        val snapshot = TvheadendDiscoveryTestFactory.discovering(servers)

        servers.clear()

        assertEquals(listOf(server), snapshot.servers)
    }

    @Test
    fun `empty and populated snapshots reject list mutations`() {
        val server = DiscoveredTvheadendServer("Fixture", "192.0.2.1", 9982)
        for (servers in listOf(emptyList(), listOf(server))) {
            val snapshot = TvheadendDiscoveryTestFactory.discovering(servers)

            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.servers as MutableList<DiscoveredTvheadendServer>).add(server)
            }
        }
    }

    @Test
    fun `factory snapshot rendering stays redacted`() {
        val server = DiscoveredTvheadendServer("Fixture", "192.0.2.1", 9982)

        assertEquals(
            "Discovering(servers=[DiscoveredTvheadendServer(<redacted>)])",
            TvheadendDiscoveryTestFactory.discovering(listOf(server)).toString(),
        )
    }
}
