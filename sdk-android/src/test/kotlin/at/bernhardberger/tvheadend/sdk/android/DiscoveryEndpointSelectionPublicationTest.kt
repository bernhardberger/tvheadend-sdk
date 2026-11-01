package at.bernhardberger.tvheadend.sdk.android

import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DiscoveryEndpointSelectionPublicationTest {
    private val ipv4 = InetAddress.getByName("192.0.2.1")

    @Test
    fun `final publication rejection continues original LAN snapshot and publishes second LAN once`() {
        val first = finalValidationNetwork { false }
        val second = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, second))
        fixture.resolve()
        assertEquals(listOf(ipv4), first.probes)
        assertEquals(listOf(ipv4), second.probes)
        assertEquals(1, fixture.events.resolvedServers.size)
        assertEquals(1, fixture.snapshots)
    }

    @Test
    fun `runtime failure exactly at final publication validation continues original LAN snapshot`() {
        val first = finalValidationNetwork { error("Private platform detail") }
        val second = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, second))
        fixture.resolve()
        assertEquals(listOf(ipv4), first.probes)
        assertEquals(listOf(ipv4), second.probes)
        assertEquals(1, fixture.events.resolvedServers.size)
        assertEquals(1, fixture.snapshots)
    }

    @Test
    fun `cancellation exactly at final publication validation propagates without later LAN work`() {
        val first = finalValidationNetwork { throw CancellationException() }
        val second = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, second))
        assertThrows(CancellationException::class.java) { fixture.resolve() }
        assertEquals(listOf(ipv4), first.probes)
        assertTrue(second.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
        assertEquals(1, fixture.snapshots)
    }

    @Test
    fun `explicit source rejected at final publication validation never expands to fallback LAN`() {
        for (platformFailure in listOf(false, true)) {
            val first = finalValidationNetwork {
                if (platformFailure) error("Private platform detail")
                false
            }
            val second = FakeDiscoveryNetwork()
            val fixture = Fixture(listOf(first, second), pinned = first)
            fixture.resolve()
            assertEquals(listOf(ipv4), first.probes)
            assertTrue(second.probes.isEmpty())
            assertTrue(fixture.events.resolvedServers.isEmpty())
            assertEquals(0, fixture.snapshots)
        }
    }

    @Test
    fun `loss or close during final validation stops handoff even if validation returns true`() {
        for (lose in listOf(true, false)) {
            lateinit var fixture: Fixture
            val first = finalValidationNetwork {
                if (lose) fixture.platform.lost(fixture.record) else fixture.registration.close()
                true
            }
            val second = FakeDiscoveryNetwork()
            fixture = Fixture(listOf(first, second))
            fixture.resolve()
            assertTrue(second.probes.isEmpty())
            assertTrue(fixture.events.resolvedServers.isEmpty())
            assertEquals(1, fixture.snapshots)
        }
    }

    @Test
    fun `same name replacement during final validation terminates old snapshot handoff`() {
        lateinit var fixture: Fixture
        val first = finalValidationNetwork {
            fixture.platform.lost(fixture.record)
            fixture.platform.found(fixture.record)
            false
        }
        val second = FakeDiscoveryNetwork()
        fixture = Fixture(listOf(first, second))
        fixture.resolve()
        assertTrue(second.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
        assertEquals(2, fixture.platform.resolutions.size)
        assertEquals(1, fixture.snapshots)
    }

    /** Initial selection, pre-probe and post-probe checks succeed; only publication invokes action. */
    private fun finalValidationNetwork(action: () -> Boolean): FakeDiscoveryNetwork {
        var probed = false
        var checksAfterProbe = 0
        return FakeDiscoveryNetwork().apply {
            reachable = { probed = true; true }
            current = {
                if (probed) checksAfterProbe += 1
                if (checksAfterProbe == 2) action() else true
            }
        }
    }

    private class Fixture(networks: List<DiscoveryNetwork>, pinned: DiscoveryNetwork? = null) {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val record = serviceRecord("Server")
        var snapshots = 0
        private val snapshot = DiscoveryNetworkSnapshot(
            available = { snapshots += 1; networks }, eligible = { it.isCurrent() },
            capture = { network, current ->
                object : DiscoveryNetwork by network {
                    override fun isCurrent(): Boolean = current()
                }
            },
        )
        val registration = NsdDiscoveryRegistration(
            platform, NsdResolutionCoordinator(), HTSP_SERVICE_TYPE, events,
            DiscoveryEndpointSelector { snapshot.networks(pinned) }, ImmediateDiscoveryWork(),
        )
        init {
            registration.start()
            platform.started()
            platform.found(record)
        }
        fun resolve() = platform.resolutions.last().listener.onServiceResolved(resolvedService("Server", "192.0.2.1"))
    }
}
