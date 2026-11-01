package at.bernhardberger.tvheadend.sdk.android

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ThreadLocalRandom

internal class AndroidDiscoveryNetworks(private val connectivity: ConnectivityManager) : DiscoveryNetworkProvider {
    private val snapshot = DiscoveryNetworkSnapshot(
        available = { allNetworks() },
        eligible = ::isLan,
        capture = { network, current ->
            AndroidDiscoveryNetwork(
                sockets = AndroidDiscoverySockets(network),
                current = current,
                replySource = { localReplySource(network) },
            )
        },
    )

    override fun networks(service: ResolvedNsdService): List<DiscoveryNetwork> = try {
        snapshot.networks(service.network)
    } catch (_: SecurityException) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun allNetworks(): List<Network> = connectivity.allNetworks.toList()

    private fun isLan(network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun localReplySource(network: Network): ((InetAddress) -> Boolean)? {
        val properties = connectivity.getLinkProperties(network) ?: return null
        val links = properties.linkAddresses.filter {
            it.address is Inet4Address && usableDiscoveryAddress(it.address) && it.prefixLength in 1..32
        }
        // IpPrefix's public constructor requires API33. Reuse platform route prefixes matching
        // the interface's LinkAddresses instead, retaining Android's maintained subnet comparison.
        val prefixes = properties.routes.map { it.destination }.distinct().filter { prefix ->
            links.any { it.prefixLength == prefix.prefixLength && prefix.contains(it.address) }
        }
        if (prefixes.isEmpty()) return null
        return { address -> prefixes.any { it.contains(address) } }
    }
}

internal interface DiscoverySockets {
    fun datagram(): DatagramSocket
    fun tcp(): Socket
    fun bind(socket: DatagramSocket)
    fun bind(socket: Socket)
}

private class AndroidDiscoverySockets(private val network: Network) : DiscoverySockets {
    override fun datagram(): DatagramSocket = DatagramSocket(null)
    override fun tcp(): Socket = Socket()
    override fun bind(socket: DatagramSocket) = network.bindSocket(socket)
    override fun bind(socket: Socket) = network.bindSocket(socket)
}

internal class AndroidDiscoveryNetwork(
    private val sockets: DiscoverySockets,
    private val current: () -> Boolean,
    private val replySource: () -> ((InetAddress) -> Boolean)?,
) : DiscoveryNetwork {
    override fun isCurrent(): Boolean = current()

    override fun recoverIpv4(name: String, port: Int, cancellation: DiscoveryCancellation): List<InetAddress> {
        return try {
            val source = replySource() ?: return emptyList()
            cancellation.use(sockets.datagram()) { socket ->
                sockets.bind(socket)
                socket.bind(InetSocketAddress(0))
                require(socket.localPort != 5_353) { "Legacy DNS query requires an ephemeral port" }
                val datagrams = AndroidMdnsDatagrams(AndroidMdnsPacketSocket(socket), source)
                LegacyMdnsRecovery(LegacyMdnsSocket(datagrams)).recover(name, port)
            }.orEmpty()
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    override fun connects(address: InetAddress, port: Int, cancellation: DiscoveryCancellation): Boolean = try {
        cancellation.use(sockets.tcp()) { socket ->
            sockets.bind(socket)
            socket.connect(InetSocketAddress(address, port), 350)
            true
        } == true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

internal fun interface MdnsExchange {
    fun query(name: MdnsName, type: Int): List<MdnsRecord>
}

internal class LegacyMdnsRecovery(private val exchange: MdnsExchange) {
    fun recover(instance: String, port: Int): List<InetAddress> {
        val name = LegacyMdnsCodec.serviceName(instance)
        val records = exchange.query(name, LegacyMdnsCodec.SRV)
        val targets = records.filter {
            it.owner == name && it.type == LegacyMdnsCodec.SRV && it.port == port
        }.mapNotNull { it.target }.distinct().take(2)
        return targets.flatMap { target ->
            val additional = records.addressesFor(target)
            additional.ifEmpty { exchange.query(target, LegacyMdnsCodec.A).addressesFor(target) }
        }.distinctBy { it.hostAddress }.take(MAX_DISCOVERY_ADDRESSES)
    }

    private fun List<MdnsRecord>.addressesFor(target: MdnsName): List<InetAddress> =
        filter { it.type == LegacyMdnsCodec.A && it.owner == target }.mapNotNull { it.address }
            .filter { it is Inet4Address && usableDiscoveryAddress(it) }.distinctBy { it.hostAddress }
}

internal interface MdnsDatagrams {
    fun send(query: ByteArray)

    fun receive(timeoutMillis: Int): ByteArray
}

internal interface MdnsPacketSocket {
    fun send(packet: DatagramPacket)
    fun receive(packet: DatagramPacket, timeoutMillis: Int)
}

private class AndroidMdnsPacketSocket(private val socket: DatagramSocket) : MdnsPacketSocket {
    override fun send(packet: DatagramPacket) = socket.send(packet)
    override fun receive(packet: DatagramPacket, timeoutMillis: Int) {
        socket.soTimeout = timeoutMillis
        socket.receive(packet)
    }
}

internal class AndroidMdnsDatagrams(
    private val socket: MdnsPacketSocket,
    private val isLocalSource: (InetAddress) -> Boolean,
) : MdnsDatagrams {
    override fun send(query: ByteArray) {
        val destination = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))
        socket.send(DatagramPacket(query, query.size, destination, 5_353))
    }

    override fun receive(timeoutMillis: Int): ByteArray {
        val packet = DatagramPacket(ByteArray(LegacyMdnsCodec.MAX_PACKET + 1), LegacyMdnsCodec.MAX_PACKET + 1)
        socket.receive(packet, timeoutMillis)
        return if (packet.port == 5_353 && usableDiscoveryAddress(packet.address) && isLocalSource(packet.address)) {
            packet.data.copyOf(packet.length)
        } else {
            byteArrayOf()
        }
    }
}

/** RFC 6762 §§5.1/6.7: ephemeral source port, unicast replies, no multicast receive membership. */
internal class LegacyMdnsSocket(
    private val datagrams: MdnsDatagrams,
    private val nanoTime: () -> Long = System::nanoTime,
    private val nextId: () -> Int = { ThreadLocalRandom.current().nextInt(1, 65_536) },
) : MdnsExchange {
    private val deadline = nanoTime() + 1_500_000_000L
    private var packetsRemaining = 16
    private var queriesRemaining = 3

    override fun query(name: MdnsName, type: Int): List<MdnsRecord> {
        if (nanoTime() >= deadline || packetsRemaining == 0 || queriesRemaining == 0) return emptyList()
        queriesRemaining -= 1
        val id = nextId()
        val query = LegacyMdnsCodec.query(id, name, type)
        datagrams.send(query)
        val queryDeadline = minOf(deadline, nanoTime() + 600_000_000L)
        while (packetsRemaining > 0 && nanoTime() < queryDeadline) {
            val timeout = ((queryDeadline - nanoTime()) / 1_000_000L).toInt().coerceAtLeast(1)
            val packet = try {
                datagrams.receive(timeout)
            } catch (_: SocketTimeoutException) {
                return emptyList()
            }
            packetsRemaining -= 1
            val records = LegacyMdnsCodec.response(packet, id, name, type)
            if (records.any { it.owner == name && it.type == type }) return records
        }
        return emptyList()
    }
}
