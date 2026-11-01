package at.bernhardberger.tvheadend.sdk.android

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A collection-owned, bounded blocking-I/O boundary; never runs on an NSD callback. */
internal interface DiscoveryWorkQueue : CallbackRegistration {
    fun offer(work: () -> Unit): Boolean
}

internal class DiscoveryWorkers : DiscoveryWorkQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<() -> Unit>(32)

    fun start() {
        repeat(2) {
            scope.launch {
                for (work in queue) work()
            }
        }
    }

    override fun offer(work: () -> Unit): Boolean = queue.trySend(work).isSuccess

    override fun close() {
        queue.cancel()
        scope.cancel()
    }
}

/** Closing a generation interrupts its current socket and rejects future socket acquisition. */
internal class DiscoveryCancellation : CallbackRegistration {
    private val lock = Any()
    private var closed = false
    private var socket: Closeable? = null

    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun <T : Closeable, R> use(resource: T, operation: (T) -> R): R? = resource.use {
        val accepted = synchronized(lock) {
            if (closed) false else {
                check(socket == null) { "Discovery resource already active" }
                socket = resource
                true
            }
        }
        if (!accepted) return null
        try {
            operation(resource)
        } finally {
            synchronized(lock) { socket = null }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            try {
                socket?.close()
            } catch (_: IOException) {
                // Cancellation remains terminal even if the platform reports a close failure.
            }
            socket = null
        }
    }
}

internal fun interface DiscoveryNetworkProvider {
    fun networks(service: ResolvedNsdService): List<DiscoveryNetwork>
}

/** Captures every distinct eligible LAN once; an explicit source never expands to other LANs. */
internal class DiscoveryNetworkSnapshot<N : Any>(
    private val available: () -> List<N>,
    private val eligible: (N) -> Boolean,
    private val capture: (N, () -> Boolean) -> DiscoveryNetwork,
) {
    fun networks(pinned: N?): List<DiscoveryNetwork> =
        (pinned?.let(::listOf) ?: available()).distinct().mapNotNull { network ->
            discoveryPlatformOrNull {
                if (eligible(network)) capture(network) { eligible(network) } else null
            }
        }
}

private inline fun <T> discoveryPlatformOrNull(operation: () -> T): T? = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    // A failed platform operation on one LAN must not suppress other captured LANs.
    null
}

internal interface DiscoveryNetwork {
    fun isCurrent(): Boolean

    fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation): List<InetAddress>

    fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean
}

internal class DiscoveryEndpointSelector(private val networks: DiscoveryNetworkProvider) {
    fun select(
        service: ResolvedNsdService,
        cancellation: DiscoveryCancellation,
        accept: (DiscoveryEndpoint) -> Boolean = { true },
    ): DiscoveryEndpoint? {
        if (cancellation.isClosed) return null
        val snapshot = networks.networks(service).distinct()
        for (network in snapshot) {
            if (cancellation.isClosed) return null
            val endpoint = discoveryPlatformOrNull { selectOnNetwork(service, network, cancellation) }
            // Acceptance may reject a retired winner at the publication boundary. Resume only
            // this captured snapshot, never enumerate replacement networks or repeat a LAN.
            if (endpoint != null && accept(endpoint)) return endpoint
        }
        return null
    }

    private fun selectOnNetwork(
        service: ResolvedNsdService,
        network: DiscoveryNetwork,
        cancellation: DiscoveryCancellation,
    ): DiscoveryEndpoint? {
        if (cancellation.isClosed || !network.isCurrent()) return null
        val published = service.addresses.filter(::usableDiscoveryAddress)
            .distinctBy { it.hostAddress }.sortedBy { it !is Inet4Address }.take(MAX_DISCOVERY_ADDRESSES)
        val recovered = if (published.none { it is Inet4Address }) {
            network.recoverIpv4(service.name, service.port, cancellation)
                .filter { it is Inet4Address && usableDiscoveryAddress(it) }
                .distinctBy { it.hostAddress }.take(MAX_DISCOVERY_ADDRESSES)
        } else {
            emptyList()
        }
        val candidates = (recovered + published).distinctBy { it.hostAddress }
            .sortedBy { it !is Inet4Address }
            .take(MAX_DISCOVERY_ADDRESSES)
        for (address in candidates) {
            if (cancellation.isClosed || !network.isCurrent()) return null
            if (network.connects(address, service.port, cancellation)) {
                return if (!cancellation.isClosed && network.isCurrent()) DiscoveryEndpoint(address, network) else null
            }
        }
        return null
    }
}

/** Retains the probed network so publication cannot silently switch to a replacement LAN. */
internal class DiscoveryEndpoint(val address: InetAddress, private val network: DiscoveryNetwork) {
    fun isCurrent(): Boolean = discoveryPlatformOrNull { network.isCurrent() } == true

    override fun toString(): String = "DiscoveryEndpoint(<redacted>)"
}

internal fun usableDiscoveryAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
        !(address is Inet6Address && address.isLinkLocalAddress && address.scopeId == 0)

internal const val MAX_DISCOVERY_ADDRESSES: Int = 8
