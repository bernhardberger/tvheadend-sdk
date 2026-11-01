package at.bernhardberger.tvheadend.sdk.android

import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DiscoveryEndpointSelectionTest {
    @Test
    fun `API31 single unscoped IPv6 resolves a reachable IPv4 through recovery`() {
        val network = FakeDiscoveryNetwork().apply { recovered = listOf(ipv4) }
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "fe80::1"))
        assertEquals(1, network.recoveries)
        assertEquals(listOf(ipv4), network.probes)
        assertEquals(ipv4.hostAddress, fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `API34 all-address list prefers IPv4 without recovery even when IPv6 appears first`() {
        val network = FakeDiscoveryNetwork()
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "2001:db8::1", "192.0.2.1"))
        assertEquals(0, network.recoveries)
        assertEquals(listOf(ipv4), network.probes)
        assertEquals(ipv4.hostAddress, fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `unscoped link local alone with failed recovery produces no result or probe`() {
        val network = FakeDiscoveryNetwork()
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "fe80::1"))
        assertEquals(1, network.recoveries)
        assertTrue(network.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `scoped IPv6 survives only a successful TCP probe and preserves its zone`() {
        val address = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        val network = FakeDiscoveryNetwork()
        val fixture = Fixture(network)
        fixture.resolve(ResolvedNsdService("Server", listOf(address), 9_982))
        assertEquals(address.hostAddress, fixture.events.resolvedServers.single().host)
        assertTrue(fixture.events.resolvedServers.single().host.endsWith("%7"))
        assertEquals(listOf(address), network.probes)
    }

    @Test
    fun `global and scoped IPv6 with closed TCP port produce no result`() {
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        val network = FakeDiscoveryNetwork().apply { reachable = { false } }
        val fixture = Fixture(network)
        fixture.resolve(ResolvedNsdService("Server", listOf(scoped, InetAddress.getByName("2001:db8::1")), 9_982))
        assertEquals(2, network.probes.size)
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `multiple IPv4 candidates are tried until one succeeds`() {
        val second = InetAddress.getByName("192.0.2.2")
        val network = FakeDiscoveryNetwork().apply { reachable = { it == second } }
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "192.0.2.1", "192.0.2.2", "192.0.2.3"))
        assertEquals(listOf(ipv4, second), network.probes)
        assertEquals(second.hostAddress, fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `IPv4 preference is applied before the candidate probe bound`() {
        val network = FakeDiscoveryNetwork()
        val fixture = Fixture(network)
        val addresses = (1..10).map { InetAddress.getByName("2001:db8::$it") } + ipv4
        fixture.resolve(ResolvedNsdService("Server", addresses, 9_982))
        assertEquals(listOf(ipv4), network.probes)
        assertEquals(0, network.recoveries)
    }

    @Test
    fun `unreachable recovered IPv4 falls through to reachable published IPv6`() {
        val v6 = InetAddress.getByName("2001:db8::1")
        val network = FakeDiscoveryNetwork().apply {
            recovered = listOf(ipv4)
            reachable = { it == v6 }
        }
        val fixture = Fixture(network)
        fixture.resolve(ResolvedNsdService("Server", listOf(v6), 9_982))
        assertEquals(listOf(ipv4, v6), network.probes)
        assertEquals(v6.hostAddress, fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `invalid address classes are never probed from either source`() {
        val invalid = listOf("0.0.0.0", "127.0.0.1", "224.0.0.251", "::", "::1", "ff02::1", "fe80::1")
            .map(InetAddress::getByName)
        val network = FakeDiscoveryNetwork().apply { recovered = invalid }
        val fixture = Fixture(network)
        fixture.resolve(ResolvedNsdService("Server", invalid, 9_982))
        assertTrue(network.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `unusable recovered A values before the ninth reachable value do not consume candidate capacity`() {
        val network = FakeDiscoveryNetwork().apply {
            recovered = (1..8).map { InetAddress.getByName("127.0.0.$it") } + ipv4
        }
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "fe80::1"))
        assertEquals(listOf(ipv4), network.probes)
        assertEquals(ipv4.hostAddress, fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `coordinator admission cannot deadlock a publication callback that reentrantly closes`() {
        val publishing = CountDownLatch(1)
        val admitting = CountDownLatch(1)
        val admissionReady = CountDownLatch(1)
        var gateAdmission = false
        val coordinator = object : NsdResolutionCoordinator() {
            override fun resolve(owner: Any, isCancelled: () -> Boolean, start: (CallbackRegistration) -> Unit) {
                if (gateAdmission) {
                    admissionReady.countDown()
                    assertTrue(publishing.await(2, TimeUnit.SECONDS))
                }
                super.resolve(owner, {
                    if (gateAdmission) admitting.countDown()
                    isCancelled()
                }, start)
            }
        }
        val platform = FakeNsdPlatform()
        val work = QueuedDiscoveryWork()
        lateinit var registration: NsdDiscoveryRegistration
        val listener = object : NsdDiscoveryListener {
            override fun onResolved(key: String, server: DiscoveredTvheadendServer) {
                publishing.countDown()
                assertTrue(admitting.await(2, TimeUnit.SECONDS))
                registration.close()
            }
            override fun onLost(key: String) = Unit
            override fun onFailed(failure: TvheadendDiscoveryFailure) = Unit
        }
        registration = registration(platform, listener, coordinator, workers = work)
        registration.start()
        platform.found(serviceRecord("First"))
        platform.resolutions.first().listener.onServiceResolved(resolvedService("First", "192.0.2.1"))
        gateAdmission = true
        val failures = ConcurrentLinkedQueue<Throwable>()
        val admit = Thread {
            try { platform.found(serviceRecord("Second")) } catch (failure: Throwable) { failures += failure }
        }.apply { isDaemon = true }
        val publish = Thread {
            try { work.work.removeFirst().invoke() } catch (failure: Throwable) { failures += failure }
        }.apply { isDaemon = true }
        admit.start()
        assertTrue(admissionReady.await(2, TimeUnit.SECONDS))
        publish.start()
        admit.join(3_000)
        publish.join(3_000)
        assertFalse(admit.isAlive, "NSD admission deadlocked")
        assertFalse(publish.isAlive, "Reentrant publication cleanup deadlocked")
        assertTrue(failures.isEmpty(), "Concurrent discovery operation failed")
        assertTrue(work.closed)
    }

    @Test
    fun `late work after loss or same-name rediscovery cannot publish`() {
        val queue = QueuedDiscoveryWork()
        val fixture = Fixture(workers = queue)
        fixture.resolve(resolvedService("Old", "192.0.2.1"))
        fixture.platform.lost(fixture.record)
        fixture.platform.found(fixture.record)
        fixture.resolve(resolvedService("New", "192.0.2.2"))
        queue.work.removeFirst().invoke()
        assertTrue(fixture.events.resolvedServers.isEmpty())
        queue.work.removeFirst().invoke()
        assertEquals("New", fixture.events.resolvedServers.single().name)
    }

    @Test
    fun `loss during a successful probe fences its result`() {
        val network = FakeDiscoveryNetwork()
        val fixture = Fixture(network)
        network.reachable = { fixture.platform.lost(fixture.record); true }
        fixture.resolve(resolvedService("Server", "192.0.2.1"))
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `network retired during a successful probe cannot publish and later announcement can retry`() {
        var valid = true
        val network = FakeDiscoveryNetwork().apply {
            current = { valid }
            reachable = { valid = false; true }
        }
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "192.0.2.1"))
        assertEquals(listOf(ipv4), network.probes)
        assertTrue(fixture.events.resolvedServers.isEmpty())
        valid = true
        network.reachable = { true }
        fixture.platform.found(fixture.record)
        fixture.resolve(resolvedService("Server", "192.0.2.2"))
        assertEquals("192.0.2.2", fixture.events.resolvedServers.single().host)
    }

    @Test
    fun `unexpected platform failure during publication validation is contained`() {
        var probed = false
        val network = FakeDiscoveryNetwork().apply {
            reachable = { probed = true; true }
            current = { check(!probed) { "private platform detail" }; true }
        }
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "192.0.2.1"))
        assertTrue(probed)
        assertTrue(fixture.events.resolvedServers.isEmpty())
        fixture.platform.found(fixture.record)
        assertEquals(2, fixture.platform.resolutions.size)
    }

    @Test
    fun `cancellation during publication validation still propagates`() {
        var probed = false
        val network = FakeDiscoveryNetwork().apply {
            reachable = { probed = true; true }
            current = { if (probed) throw CancellationException(); true }
        }
        val fixture = Fixture(network)
        assertThrows(CancellationException::class.java) {
            fixture.resolve(resolvedService("Server", "192.0.2.1"))
        }
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `close fences queued and late NSD work and closes worker ownership`() {
        val queue = QueuedDiscoveryWork()
        val network = FakeDiscoveryNetwork()
        val fixture = Fixture(network, queue)
        fixture.resolve(resolvedService("Server", "192.0.2.1"))
        fixture.registration.close()
        queue.work.removeFirst().invoke()
        fixture.resolve(resolvedService("Late", "192.0.2.2"))
        assertTrue(queue.closed)
        assertTrue(network.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `NSD global slot is released before deferred endpoint probes`() {
        val platform = FakeNsdPlatform()
        val coordinator = NsdResolutionCoordinator()
        val work = QueuedDiscoveryWork()
        val first = registration(platform, RecordingNsdDiscoveryListener(), coordinator, workers = work)
        val second = registration(platform, RecordingNsdDiscoveryListener(), coordinator, workers = work)
        first.start()
        second.start()
        platform.found(serviceRecord("First"), 0)
        platform.found(serviceRecord("Second"), 1)
        platform.resolutions.first().listener.onServiceResolved(resolvedService("First", "192.0.2.1"))
        assertEquals(2, platform.resolutions.size)
        assertEquals(1, work.work.size)
    }

    @Test
    fun `fallback and TCP checks both run after another registration acquires the NSD slot`() {
        val platform = FakeNsdPlatform()
        val coordinator = NsdResolutionCoordinator()
        var recovered = false
        var probed = false
        val network = object : DiscoveryNetwork {
            override fun isCurrent(): Boolean = true
            override fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation): List<InetAddress> {
                assertEquals(2, platform.resolutions.size)
                recovered = true
                return listOf(ipv4)
            }
            override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean {
                assertEquals(2, platform.resolutions.size)
                probed = true
                return true
            }
        }
        val first = registration(platform, RecordingNsdDiscoveryListener(), coordinator, network)
        val second = registration(platform, RecordingNsdDiscoveryListener(), coordinator)
        first.start()
        second.start()
        platform.found(serviceRecord("First"), 0)
        platform.found(serviceRecord("Second"), 1)
        platform.resolutions.first().listener.onServiceResolved(resolvedService("First", "fe80::1"))
        assertTrue(recovered)
        assertTrue(probed)
        first.close()
        second.close()
    }

    @Test
    fun `source network disappearing before selection produces no endpoint`() {
        var available: DiscoveryNetwork? = FakeDiscoveryNetwork()
        val selector = DiscoveryEndpointSelector { listOfNotNull(available) }
        available = null
        assertEquals(null, selector.select(resolvedService("Server", "192.0.2.1"), DiscoveryCancellation()))
    }

    @Test
    fun `pinned network no longer eligible for LAN discovery cannot start recovery or TCP work`() {
        val network = FakeDiscoveryNetwork().apply {
            current = { false }
            recovered = listOf(ipv4)
        }
        val fixture = Fixture(network)
        fixture.resolve(resolvedService("Server", "fe80::1"))
        assertEquals(0, network.recoveries)
        assertTrue(network.probes.isEmpty())
        assertTrue(fixture.events.resolvedServers.isEmpty())
    }

    @Test
    fun `source network replacement cannot redirect an in-flight recovery or probe`() {
        val replacement = FakeDiscoveryNetwork().apply { recovered = listOf(ipv4) }
        var selected: DiscoveryNetwork = replacement
        var oldProbes = 0
        val lost = object : DiscoveryNetwork {
            override fun isCurrent(): Boolean = selected === this
            override fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation): List<InetAddress> {
                selected = replacement
                return listOf(ipv4)
            }
            override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean {
                oldProbes += 1
                return false
            }
        }
        selected = lost
        val selector = DiscoveryEndpointSelector { listOf(selected) }
        val service = resolvedService("Server", "fe80::1")
        assertEquals(null, selector.select(service, DiscoveryCancellation()))
        assertEquals(0, oldProbes)
        assertEquals(0, replacement.recoveries)
        assertTrue(replacement.probes.isEmpty())
        assertEquals(ipv4, selector.select(service, DiscoveryCancellation())?.address)
        assertEquals(listOf(ipv4), replacement.probes)
    }

    @Test
    fun `admission bounds services and rejected work allows later announcement`() {
        val platform = FakeNsdPlatform()
        val registration = registration(platform, RecordingNsdDiscoveryListener())
        registration.start()
        repeat(100) { platform.found(serviceRecord("Server $it")) }
        repeat(64) { index ->
            platform.resolutions[index].listener.onServiceResolved(resolvedService("Server $index", "192.0.2.1"))
        }
        assertEquals(64, platform.resolutions.size)

        val queue = QueuedDiscoveryWork().apply { accept = false }
        val fixture = Fixture(workers = queue)
        fixture.resolve(resolvedService("Server", "192.0.2.1"))
        fixture.platform.found(fixture.record)
        assertEquals(2, fixture.platform.resolutions.size)
    }

    @Test
    fun `cancellation closes blocked resource and rejects later resource acquisition`() {
        val cancellation = DiscoveryCancellation()
        val closed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val worker = Thread {
            cancellation.use(Closeable { closed.countDown() }) {
                entered.countDown()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            cancellation.close()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            var acquired = false
            var rejectedClosed = false
            cancellation.use(Closeable { rejectedClosed = true }) { acquired = true }
            assertFalse(acquired)
            assertTrue(rejectedClosed)
        } finally {
            cancellation.close()
            worker.join(2_000)
        }
        assertFalse(worker.isAlive)
    }

    @Test
    fun `registration close immediately closes in-flight probe resource`() {
        val closed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val network = object : DiscoveryNetwork {
            override fun isCurrent(): Boolean = true
            override fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation) = emptyList<InetAddress>()
            override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean =
                cancellation.use(Closeable { closed.countDown() }) {
                    entered.countDown()
                    closed.await(2, TimeUnit.SECONDS)
                } == true
        }
        val workers = DiscoveryWorkers().also { it.start() }
        val fixture = Fixture(network, workers)
        try {
            fixture.resolve(resolvedService("Server", "192.0.2.1"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.registration.close()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.events.resolvedServers.isEmpty())
        } finally {
            fixture.registration.close()
        }
    }

    @Test
    fun `loss immediately closes in-flight fallback resource and suppresses its result`() {
        val closed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val network = object : DiscoveryNetwork {
            override fun isCurrent(): Boolean = true
            override fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation): List<InetAddress> =
                cancellation.use(Closeable { closed.countDown() }) {
                    entered.countDown()
                    closed.await(2, TimeUnit.SECONDS)
                    listOf(ipv4)
                }.orEmpty()
            override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean =
                error("Cancelled fallback must not probe")
        }
        val workers = DiscoveryWorkers().also { it.start() }
        val fixture = Fixture(network, workers)
        try {
            fixture.resolve(resolvedService("Server", "fe80::1"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            fixture.platform.lost(fixture.record)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.events.resolvedServers.isEmpty())
        } finally {
            fixture.registration.close()
        }
    }

    @Test
    fun `workers admit only two active tasks and thirty-two queued tasks`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val workers = DiscoveryWorkers().also { it.start() }
        try {
            repeat(2) {
                assertTrue(workers.offer { entered.countDown(); release.await(2, TimeUnit.SECONDS) })
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(32) { assertTrue(workers.offer {}) }
            assertFalse(workers.offer {})
            workers.close()
            assertFalse(workers.offer {})
        } finally {
            release.countDown()
            workers.close()
        }
    }

    private class Fixture(
        network: DiscoveryNetwork = FakeDiscoveryNetwork(),
        workers: DiscoveryWorkQueue = ImmediateDiscoveryWork(),
        networks: DiscoveryNetworkProvider = DiscoveryNetworkProvider { listOf(network) },
    ) {
        val platform = FakeNsdPlatform()
        val events = RecordingNsdDiscoveryListener()
        val record = serviceRecord("Server")
        val registration = NsdDiscoveryRegistration(
            platform, NsdResolutionCoordinator(), HTSP_SERVICE_TYPE, events,
            DiscoveryEndpointSelector(networks), workers,
        )

        init {
            registration.start()
            platform.started()
            platform.found(record)
        }

        fun resolve(service: ResolvedNsdService) = platform.resolutions.last().listener.onServiceResolved(service)
    }

    private val ipv4 = InetAddress.getByName("192.0.2.1")
}

private class QueuedDiscoveryWork : DiscoveryWorkQueue {
    val work = ArrayDeque<() -> Unit>()
    var closed = false
    var accept = true
    override fun offer(work: () -> Unit): Boolean {
        if (accept) this.work += work
        return accept
    }
    override fun close() { closed = true }
}
