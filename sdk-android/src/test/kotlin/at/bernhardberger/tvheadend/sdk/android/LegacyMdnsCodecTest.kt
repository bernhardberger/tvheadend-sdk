package at.bernhardberger.tvheadend.sdk.android

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LegacyMdnsCodecTest {
    private val service = LegacyMdnsCodec.serviceName("Télé.vision")
    private val target = dnsName("receiver", "local")
    private val address = InetAddress.getByName("192.0.2.1")

    @Test
    fun `instance dots and UTF8 stay inside one literal DNS label`() {
        val query = LegacyMdnsCodec.query(42, service, LegacyMdnsCodec.SRV)
        val label = "Télé.vision".toByteArray(Charsets.UTF_8)
        assertEquals(label.size, query[12].toInt())
        assertArrayEquals(label, query.copyOfRange(13, 13 + label.size))
        assertEquals(0, query[2].toInt())
        assertEquals(0, query[3].toInt())
        assertEquals(0, query[query.lastIndex - 1].toInt()) // No QU bit.
        assertEquals(1, query.last().toInt())
    }

    @Test
    fun `compressed owner and target decode with correlated positive SRV and A`() {
        val records = decode(srvResponse())
        assertEquals(2, records.size)
        assertEquals(service, records[0].owner)
        assertEquals(target, records[0].target)
        assertEquals(9_982, records[0].port)
        assertEquals(target, records[1].owner)
        assertEquals(address, records[1].address)
    }

    @Test
    fun `matching additional A avoids another exchange but unrelated A is ignored`() {
        var queries = 0
        val recovery = LegacyMdnsRecovery { name, type ->
            queries += 1
            assertEquals(service, name)
            assertEquals(LegacyMdnsCodec.SRV, type)
            decode(srvResponse()) + MdnsRecord(dnsName("unrelated", "local"), 1, null, null, address)
        }
        assertEquals(listOf(address), recovery.recover("Télé.vision", 9_982))
        assertEquals(1, queries)
    }

    @Test
    fun `AAAA-only SRV response still causes explicit correlated A query`() {
        val queries = mutableListOf<Int>()
        val recovery = LegacyMdnsRecovery { name, type ->
            queries += type
            when (type) {
                LegacyMdnsCodec.SRV -> decode(srvResponse(includeA = false))
                else -> {
                    assertEquals(target, name)
                    LegacyMdnsCodec.response(aResponse(), 43, target, LegacyMdnsCodec.A)
                }
            }
        }
        assertEquals(listOf(address), recovery.recover("Télé.vision", 9_982))
        assertEquals(listOf(33, 1), queries)
    }

    @Test
    fun `production recovery filters unusable A records before bounding additional candidates`() {
        val recovery = LegacyMdnsRecovery { _, _ ->
            decode(srvResponse(includeA = false)) +
                (1..8).map { MdnsRecord(target, 1, null, null, InetAddress.getByName("127.0.0.$it")) } +
                MdnsRecord(target, 1, null, null, address)
        }
        assertEquals(listOf(address), recovery.recover("Télé.vision", 9_982))
    }

    @Test
    fun `unusable additional A cannot suppress explicit A query`() {
        val queries = mutableListOf<Int>()
        val recovery = LegacyMdnsRecovery { _, type ->
            queries += type
            if (type == 33) {
                decode(srvResponse(includeA = false)) +
                    MdnsRecord(target, 1, null, null, InetAddress.getByName("127.0.0.1"))
            } else {
                (1..8).map { MdnsRecord(target, 1, null, null, InetAddress.getByName("127.0.0.$it")) } +
                    MdnsRecord(target, 1, null, null, address)
            }
        }
        assertEquals(listOf(address), recovery.recover("Télé.vision", 9_982))
        assertEquals(listOf(33, 1), queries)
    }

    @Test
    fun `different service owner or port cannot authorize IPv4 recovery`() {
        val variants = listOf(
            listOf(MdnsRecord(dnsName("other", "_htsp", "_tcp", "local"), 33, target, 9_982, null)),
            decode(srvResponse(port = 9_983)),
            decode(srvResponse(ttl = 0)),
            decode(srvResponse(clazz = 3)),
        )
        variants.forEach { records ->
            var queries = 0
            val recovery = LegacyMdnsRecovery { _, _ -> queries += 1; records }
            assertTrue(recovery.recover("Télé.vision", 9_982).isEmpty())
            assertEquals(1, queries)
        }
    }

    @Test
    fun `following A response must match verified SRV target`() {
        val recovery = LegacyMdnsRecovery { _, type ->
            if (type == 33) decode(srvResponse(includeA = false))
            else listOf(MdnsRecord(dnsName("other", "local"), 1, null, null, address))
        }
        assertTrue(recovery.recover("Télé.vision", 9_982).isEmpty())
    }

    @Test
    fun `transaction question flags and class mismatches are rejected`() {
        val packet = srvResponse()
        val queryEnd = LegacyMdnsCodec.query(42, service, 33).size
        val variants = listOf(
            packet.changed(1, 41), // ID
            packet.changed(2, 0), // not a response
            packet.changed(2, 0x82), // truncated
            packet.changed(2, 0x88), // nonstandard opcode
            packet.changed(3, 3), // NXDOMAIN
            packet.changed(5, 0), // missing echoed question
            packet.changed(13, 'X'.code), // question name
            packet.changed(queryEnd - 3, 1), // question type
            packet.changed(queryEnd - 1, 3), // question class
            packet.changed(7, 65), // count bound
        )
        variants.forEachIndexed { index, bytes -> assertTrue(decode(bytes).isEmpty(), "variant $index") }
    }

    @Test
    fun `truncation every byte malformed lengths and compression cycles are rejected`() {
        val packet = srvResponse()
        for (length in packet.indices) assertTrue(decode(packet.copyOf(length)).isEmpty(), "length $length")
        val owner = LegacyMdnsCodec.query(42, service, 33).size
        val variants = listOf(
            packet.changed(owner + 1, owner), // self pointer
            packet.changed(owner + 1, owner + 2), // forward pointer
            packet.changed(owner + 1, 0), // header pointer
            packet.changed(owner, 0xFF), // out of bounds pointer
            packet.changed(12, 64), // invalid label kind
            packet.changed(owner + 11, 1), // too-short SRV RDLENGTH
            packet.changed(owner + 11, 255), // oversized SRV RDLENGTH
            packet + byteArrayOf(0), // trailing unparsed data
            ByteArray(4_097),
        )
        variants.forEachIndexed { index, bytes -> assertTrue(decode(bytes).isEmpty(), "variant $index") }
    }

    @Test
    fun `nonpositive TTL wrong class and malformed A length are not accepted`() {
        val packet = aResponse()
        val owner = LegacyMdnsCodec.query(43, target, 1).size
        val variants = listOf(
            packet.changed(owner + 9, 0), // TTL zero
            packet.changed(owner + 5, 3), // CH class
            packet.changed(owner + 11, 3), // A must have exactly four bytes
            packet.changed(owner + 11, 5),
        )
        variants.forEach { assertTrue(LegacyMdnsCodec.response(it, 43, target, 1).isEmpty()) }
    }

    @Test
    fun `DNS names compare ASCII case-insensitively without splitting UTF8 labels`() {
        assertEquals(service, LegacyMdnsCodec.serviceName("TéLé.VISION"))
        assertEquals(service.hashCode(), LegacyMdnsCodec.serviceName("TéLé.VISION").hashCode())
        assertEquals("MdnsName(<redacted>)", service.toString())
        assertEquals("MdnsRecord(<redacted>)", decode(srvResponse()).first().toString())
        assertThrows(IllegalArgumentException::class.java) { LegacyMdnsCodec.serviceName("é".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { LegacyMdnsCodec.serviceName("") }
    }

    private fun decode(packet: ByteArray): List<MdnsRecord> = LegacyMdnsCodec.response(packet, 42, service, 33)

    private fun srvResponse(
        includeA: Boolean = true,
        port: Int = 9_982,
        ttl: Int = 120,
        clazz: Int = 1,
    ): ByteArray {
        val question = LegacyMdnsCodec.query(42, service, 33)
        val targetBytes = bytes { target.write(it) }
        return bytes { out ->
            header(out, 42, 1, 1)
            out.write(question.copyOfRange(12, question.size))
            out.writeShort(0xC00C)
            recordHeader(out, 33, clazz, ttl, 6 + targetBytes.size)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(port)
            out.write(targetBytes)
            out.writeShort(0xC000 or (question.size + 18))
            recordHeader(out, if (includeA) 1 else 28, 0x8001, 120, if (includeA) 4 else 16)
            out.write(if (includeA) address.address else InetAddress.getByName("fe80::1").address)
        }
    }

    private fun aResponse(): ByteArray = bytes { out ->
        val question = LegacyMdnsCodec.query(43, target, 1)
        header(out, 43, 1, 0)
        out.write(question.copyOfRange(12, question.size))
        out.writeShort(0xC00C)
        recordHeader(out, 1, 1, 120, 4)
        out.write(address.address)
    }

    private fun header(out: DataOutputStream, id: Int, answers: Int, additional: Int) {
        listOf(id, 0x8400, 1, answers, 0, additional).forEach(out::writeShort)
    }

    private fun recordHeader(out: DataOutputStream, type: Int, clazz: Int, ttl: Int, size: Int) {
        out.writeShort(type)
        out.writeShort(clazz)
        out.writeInt(ttl)
        out.writeShort(size)
    }

    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(write)
    }.toByteArray()

    private fun dnsName(vararg labels: String): MdnsName = MdnsName(labels.map { it.toByteArray(Charsets.UTF_8) })

    private fun ByteArray.changed(index: Int, value: Int): ByteArray = copyOf().also { it[index] = value.toByte() }
}
