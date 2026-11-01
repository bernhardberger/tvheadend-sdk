package at.bernhardberger.tvheadend.sdk.android

import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DiscoveryEndpointSelectionMultilanTest {
    private val ipv4 = InetAddress.getByName("192.0.2.1")
    private val ipv6 = InetAddress.getByName("2001:db8::1")

    @Test
    fun `legacy snapshot tries second LAN after first fails with IPv4 first on each`() {
        val first = FakeDiscoveryNetwork().apply { reachable = { false }; recovered = listOf(ipv4) }
        val second = FakeDiscoveryNetwork().apply { recovered = listOf(ipv4) }
        val fixture = Fixture(listOf(first, second))
        fixture.resolve(ipv6)
        assertEquals(listOf(ipv4, ipv6), first.probes)
        assertEquals(listOf(ipv4), second.probes)
        assertEquals(ipv4.hostAddress, fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `all distinct LANs fail without publishing and duplicate LANs run only once`() {
        val lans = List(4) { FakeDiscoveryNetwork().apply { reachable = { false } } }
        val fixture = Fixture(lans + lans)
        fixture.resolve(ipv6, ipv4)
        lans.forEach { assertEquals(listOf(ipv4, ipv6), it.probes) }
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `explicit source is never expanded even if another LAN would succeed`() {
        val first = FakeDiscoveryNetwork().apply { reachable = { false } }
        val other = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, other), pinned = first)
        fixture.resolve(ipv4)
        assertEquals(listOf(ipv4), first.probes)
        assertTrue(other.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `explicit source retiring after successful TCP does not switch to other LANs`() {
        var alive = true
        val pinned = FakeDiscoveryNetwork().apply {
            current = { alive }; reachable = { alive = false; true }
        }
        val other = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(pinned, other), pinned = pinned)
        fixture.resolve(ipv4)
        assertTrue(other.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `ineligible explicit source does not enumerate fallback LANs`() {
        val pinned = FakeDiscoveryNetwork().apply { current = { false } }
        val policy = DiscoveryNetworkSnapshot<DiscoveryNetwork>(
            available = { error("Pinned source must not enumerate") },
            eligible = { it.isCurrent() }, capture = { network, _ -> network },
        )
        assertTrue(policy.networks(pinned).isEmpty())
    }

    @Test
    fun `retired first LAN is skipped while the remaining captured LAN succeeds`() {
        val retired = FakeDiscoveryNetwork().apply { current = { false } }
        val current = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(retired, current))
        fixture.resolve(ipv4)
        assertTrue(retired.probes.isEmpty())
        assertEquals(listOf(ipv4), current.probes)
        assertEquals(1, fixture.events.resolvedServers.size)
    }

    @Test
    fun `retirement during successful probe continues other LANs without publishing stale address`() {
        var alive = true
        val first = FakeDiscoveryNetwork().apply {
            current = { alive }; reachable = { alive = false; true }
        }
        val second = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, second))
        fixture.resolve(ipv4)
        assertEquals(listOf(ipv4), second.probes)
        assertEquals(1, fixture.events.resolvedServers.size)
    }

    @Test
    fun `captured snapshot does not follow newly arriving replacement LAN`() {
        val replacement = FakeDiscoveryNetwork()
        var available: List<DiscoveryNetwork> = emptyList()
        var alive = true
        val first = FakeDiscoveryNetwork().apply {
            current = { alive }
            reachable = { alive = false; available = listOf(replacement); true }
        }
        available = listOf(first)
        val policy = DiscoveryNetworkSnapshot(
            available = { available }, eligible = { it.isCurrent() },
            capture = { network, _ -> network },
        )
        val selector = DiscoveryEndpointSelector { policy.networks(null) }
        assertEquals(null, selector.select(ResolvedNsdService("Server", listOf(ipv4), 9_982), DiscoveryCancellation()))
        assertTrue(replacement.probes.isEmpty())
    }

    @Test
    fun `loss and close halt remaining LANs and close active socket ownership`() {
        for (lose in listOf(true, false)) {
            val second = FakeDiscoveryNetwork()
            var resourceClosed = false
            lateinit var fixture: Fixture
            val first = FakeDiscoveryNetwork().apply {
                reachable = {
                    if (lose) fixture.platform.lost(fixture.record) else fixture.registration.close()
                    true
                }
            }
            val owned = object : DiscoveryNetwork by first {
                override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean =
                    cancellation.use(Closeable { resourceClosed = true }) {
                        val connected = first.connects(address, port, cancellation)
                        assertTrue(resourceClosed, "Loss or close must close the resource before the operation returns")
                        connected
                    } == true
            }
            fixture = Fixture(listOf(owned, second))
            fixture.resolve(ipv4)
            assertTrue(resourceClosed)
            assertTrue(second.probes.isEmpty())
            assertTrue(fixture.events.resolvedServers.isEmpty())
        }
    }

    @Test
    fun `per LAN platform exception in recovery probe or eligibility does not stop later LANs`() {
        for (phase in listOf("eligibility", "recovery", "probe")) {
            val first = object : DiscoveryNetwork {
                override fun isCurrent(): Boolean {
                    if (phase == "eligibility") error("Private detail")
                    return true
                }
                override fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation): List<InetAddress> {
                    if (phase == "recovery") error("Private detail")
                    return listOf(ipv4)
                }
                override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean =
                    error("Private detail")
            }
            val second = FakeDiscoveryNetwork().apply { recovered = listOf(ipv4) }
            val fixture = Fixture(listOf(first, second))
            fixture.resolve(ipv6)
            assertEquals(listOf(ipv4), second.probes)
            assertEquals(1, fixture.events.resolvedServers.size)
        }
    }

    @Test
    fun `per LAN cancellation propagates without continuing to another LAN`() {
        val first = FakeDiscoveryNetwork().apply { reachable = { throw CancellationException() } }
        val second = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, second))
        assertThrows(CancellationException::class.java) { fixture.resolve(ipv4) }
        assertTrue(second.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `eligibility cancellation propagates instead of omitting LAN and continuing`() {
        val first = FakeDiscoveryNetwork().apply { current = { throw CancellationException() } }
        val second = FakeDiscoveryNetwork()
        val fixture = Fixture(listOf(first, second))
        assertThrows(CancellationException::class.java) { fixture.resolve(ipv4) }
        assertTrue(second.probes.isEmpty())
    }

    @Test
    fun `missing prefix opens no UDP socket but independently published IPv4 and IPv6 can connect`() {
        val scoped = java.net.Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        for (address in listOf(ipv4, ipv6, scoped)) {
            val sockets = RecordingSockets()
            val network = AndroidDiscoveryNetwork(sockets, { true }, { null })
            val fixture = Fixture(listOf(network))
            fixture.resolve(address)
            assertEquals(0, sockets.datagrams)
            assertEquals(listOf(address), sockets.probes)
            assertTrue(sockets.closed)
            assertEquals(address.hostAddress, fixture.events.resolvedServers.single().host)
        }
    }

    @Test
    fun `missing prefix on first LAN does not prevent IPv4 recovery on second LAN`() {
        val sockets = RecordingSockets().apply { succeeds = false }
        val first = AndroidDiscoveryNetwork(sockets, { true }, { null })
        val second = FakeDiscoveryNetwork().apply { recovered = listOf(ipv4) }
        val fixture = Fixture(listOf(first, second))
        fixture.resolve(ipv6)
        assertEquals(0, sockets.datagrams)
        assertEquals(listOf(ipv6), sockets.probes)
        assertEquals(1, second.recoveries)
        assertEquals(listOf(ipv4), second.probes)
        assertEquals(ipv4.hostAddress, fixture.events.resolvedServers.single().host)
    }

    private class Fixture(networks: List<DiscoveryNetwork>, pinned: DiscoveryNetwork? = null) {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val record = serviceRecord("Server")
        private val snapshot = DiscoveryNetworkSnapshot(
            available = { networks }, eligible = { it.isCurrent() },
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
        fun resolve(vararg addresses: InetAddress) =
            platform.resolutions.last().listener.onServiceResolved(ResolvedNsdService("Server", addresses.toList(), 9_982))
    }

    private class RecordingSockets : DiscoverySockets {
        var datagrams = 0
        var succeeds = true
        var closed = false
        val probes = mutableListOf<InetAddress>()
        override fun datagram(): DatagramSocket {
            datagrams += 1
            error("Missing prefix must not open UDP")
        }
        override fun tcp(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val target = endpoint as java.net.InetSocketAddress
                assertEquals(350, timeout)
                assertEquals(9_982, target.port)
                probes += target.address
                if (!succeeds) throw java.io.IOException()
            }
            override fun close() { closed = true }
        }
        override fun bind(socket: DatagramSocket) = Unit
        override fun bind(socket: Socket) = Unit
    }
}
