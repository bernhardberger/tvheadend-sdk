package at.bernhardberger.tvheadend.sdk.android

import java.net.SocketTimeoutException
import java.net.DatagramPacket
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LegacyMdnsSocketTest {
    private val name = LegacyMdnsCodec.serviceName("Server")

    @Test
    fun `malformed or unrelated packet flood stops at the shared packet budget`() {
        val datagrams = FakeDatagrams()
        val socket = LegacyMdnsSocket(datagrams, { 0 }, { 42 })
        assertTrue(socket.query(name, 33).isEmpty())
        assertTrue(socket.query(name, 1).isEmpty())
        assertEquals(16, datagrams.receives)
        assertEquals(1, datagrams.sent.size)
        assertTrue(datagrams.timeouts.all { it == 600 })
    }

    @Test
    fun `query count and timeout are bounded even without clock advancement`() {
        val datagrams = FakeDatagrams().apply { receivePacket = { throw SocketTimeoutException() } }
        val socket = LegacyMdnsSocket(datagrams, { 0 }, { 42 })
        repeat(10) { assertTrue(socket.query(name, 33).isEmpty()) }
        assertEquals(3, datagrams.sent.size)
        assertEquals(3, datagrams.receives)
    }

    @Test
    fun `deadlines bound each exchange and the whole recovery`() {
        var now = 0L
        val datagrams = FakeDatagrams().apply {
            receivePacket = { now += 600_000_000; byteArrayOf() }
        }
        val socket = LegacyMdnsSocket(datagrams, { now }, { 42 })
        repeat(5) { assertTrue(socket.query(name, 33).isEmpty()) }
        assertEquals(3, datagrams.sent.size)
        assertEquals(listOf(600, 600, 300), datagrams.timeouts)
    }

    @Test
    fun `matching response succeeds after a mismatched transaction`() {
        val datagrams = FakeDatagrams()
        var first = true
        datagrams.receivePacket = {
            val query = datagrams.sent.last()
            // Echoed question plus a compressed positive SRV answer, port 9982, target node.local.
            val answer = byteArrayOf(
                0xC0.toByte(), 12, 0, 33, 0, 1, 0, 0, 0, 120, 0, 18,
                0, 0, 0, 0, 0x26, 0xFE.toByte(),
                4, 110, 111, 100, 101, 5, 108, 111, 99, 97, 108, 0,
            )
            (query + answer).also {
                it[2] = 0x84.toByte()
                it[7] = 1
                if (first) { it[1] = 41; first = false }
            }
        }
        val socket = LegacyMdnsSocket(datagrams, { 0 }, { 42 })
        val records = socket.query(name, 33)
        assertEquals(1, records.size)
        assertEquals(9_982, records.single().port)
        assertEquals(2, datagrams.receives)
        assertEquals(1, datagrams.sent.size)
    }

    @Test
    fun `otherwise valid replies are rejected off subnet but accepted on subnet without restricting advertised A`() {
        val localSender = InetAddress.getByName("192.0.2.2")
        val routedSender = InetAddress.getByName("198.51.100.2")
        val advertised = InetAddress.getByName("203.0.113.9")
        val query = LegacyMdnsCodec.query(42, name, 1)
        val answer = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 120, 0, 4) + advertised.address
        val response = (query + answer).also { it[2] = 0x84.toByte(); it[7] = 1 }
        for (sender in listOf(routedSender, localSender)) {
            var receives = 0
            val transport = object : MdnsPacketSocket {
                override fun send(packet: DatagramPacket) = Unit
                override fun receive(packet: DatagramPacket, timeoutMillis: Int) {
                    receives += 1
                    packet.data = response
                    packet.address = sender
                    packet.port = 5_353
                }
            }
            val datagrams = AndroidMdnsDatagrams(transport) { it == localSender }
            val socket = LegacyMdnsSocket(datagrams, { 0 }, { 42 })
            val records = socket.query(name, 1)
            if (sender == localSender) {
                assertEquals(advertised, records.single().address)
                assertEquals(1, receives)
            } else {
                assertTrue(records.isEmpty())
                assertEquals(16, receives)
            }
        }
    }

    private class FakeDatagrams : MdnsDatagrams {
        val sent = mutableListOf<ByteArray>()
        val timeouts = mutableListOf<Int>()
        var receives = 0
        var receivePacket: () -> ByteArray = { byteArrayOf() }
        override fun send(query: ByteArray) { sent += query }
        override fun receive(timeoutMillis: Int): ByteArray {
            receives += 1
            timeouts += timeoutMillis
            return receivePacket()
        }
    }
}
