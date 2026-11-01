package at.bernhardberger.tvheadend.sdk.android

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

/**
 * Only the bounded legacy-unicast SRV → A exchange needed after Android's single-host NSD API.
 * Android still owns browse/loss. JmDNS 3.6.5 stops A queries once IPv6 exists and has endpoint
 * logging and a long-lived socket/timer lifecycle. dnsjava 3.7.1 logs DNS names in ordinary
 * Name/Compression TRACE paths. MiniDNS 1.1.3's ASCII dotted names cannot preserve arbitrary
 * UTF-8 DNS-SD instance labels (including literal dots). None fits this privacy/one-shot boundary.
 */
internal object LegacyMdnsCodec {
    const val A: Int = 1
    const val SRV: Int = 33
    const val MAX_PACKET: Int = 4_096

    fun serviceName(instance: String): MdnsName = MdnsName(
        listOf(instance, "_htsp", "_tcp", "local").map { it.toByteArray(Charsets.UTF_8) },
    )

    fun query(id: Int, name: MdnsName, type: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id)
            out.writeShort(0)
            out.writeShort(1)
            repeat(3) { out.writeShort(0) }
            name.write(out)
            out.writeShort(type)
            out.writeShort(1) // Legacy query from an ephemeral port: ordinary IN, no QU bit.
        }
        return bytes.toByteArray()
    }

    fun response(packet: ByteArray, id: Int, name: MdnsName, type: Int): List<MdnsRecord> = try {
        val reader = MdnsReader(packet)
        require(reader.word() == id)
        val flags = reader.word()
        require(flags and 0xFA0F == 0x8000) // Response, standard opcode, complete, successful.
        require(reader.word() == 1) // RFC 6762 §6.7: legacy response echoes the question.
        val answers = reader.word()
        val authority = reader.word()
        val additional = reader.word()
        require(answers + authority + additional <= 64)
        require(reader.name() == name && reader.word() == type && reader.word() == 1)
        val records = List(answers + authority + additional) { index ->
            reader.record().takeUnless { index in answers until answers + authority }
        }.filterNotNull()
        require(reader.finished())
        records
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}

internal class MdnsName(private val labels: List<ByteArray>) {
    init {
        require(labels.isNotEmpty() && labels.all { it.size in 1..63 }) { "Invalid DNS name" }
        require(labels.sumOf { it.size + 1 } + 1 <= 255) { "Invalid DNS name" }
    }

    fun write(out: DataOutputStream) {
        labels.forEach { out.writeByte(it.size); out.write(it) }
        out.writeByte(0)
    }

    override fun equals(other: Any?): Boolean = other is MdnsName &&
        labels.size == other.labels.size && labels.indices.all { index ->
            val left = labels[index]
            val right = other.labels[index]
            left.size == right.size && left.indices.all { asciiLower(left[it]) == asciiLower(right[it]) }
        }

    override fun hashCode(): Int = labels.fold(1) { hash, label ->
        31 * hash + label.fold(1) { part, byte -> 31 * part + asciiLower(byte) }
    }

    override fun toString(): String = "MdnsName(<redacted>)"
}

internal class MdnsRecord(
    val owner: MdnsName,
    val type: Int,
    val target: MdnsName?,
    val port: Int?,
    val address: InetAddress?,
) {
    override fun toString(): String = "MdnsRecord(<redacted>)"
}

private class MdnsReader(private val packet: ByteArray) {
    private var position = 0

    init {
        require(packet.size in 12..LegacyMdnsCodec.MAX_PACKET)
    }

    fun word(): Int = (byte() shl 8) or byte()

    private fun byte(): Int {
        require(position < packet.size)
        return packet[position++].toInt() and 0xFF
    }

    fun finished(): Boolean = position == packet.size

    fun name(): MdnsName {
        val labels = mutableListOf<ByteArray>()
        var cursor = position
        var end: Int? = null
        var length = 1
        repeat(128) {
            require(cursor < packet.size)
            val size = packet[cursor].toInt() and 0xFF
            when {
                size == 0 -> {
                    position = end ?: (cursor + 1)
                    return MdnsName(labels)
                }
                size and 0xC0 == 0xC0 -> {
                    require(cursor + 1 < packet.size)
                    val pointer = ((size and 0x3F) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
                    require(pointer in 12 until cursor) // Only backward pointers; bounds and cycles are rejected.
                    if (end == null) end = cursor + 2
                    cursor = pointer
                }
                else -> {
                    require(size in 1..63 && cursor + 1 + size <= packet.size)
                    length += size + 1
                    require(length <= 255)
                    labels += packet.copyOfRange(cursor + 1, cursor + 1 + size)
                    cursor += size + 1
                }
            }
        }
        throw IllegalArgumentException("Invalid DNS name")
    }

    fun record(): MdnsRecord? {
        val owner = name()
        val type = word()
        val clazz = word()
        val ttl = (word().toLong() shl 16) or word().toLong()
        val size = word()
        val end = position + size
        require(end <= packet.size)
        var target: MdnsName? = null
        var port: Int? = null
        var address: InetAddress? = null
        when (type) {
            LegacyMdnsCodec.SRV -> {
                require(end - position >= 7)
                word() // priority
                word() // weight
                port = word()
                target = name()
                require(position == end)
            }
            LegacyMdnsCodec.A -> {
                require(end - position == 4)
                address = InetAddress.getByAddress(packet.copyOfRange(position, end))
            }
        }
        position = end
        return if (clazz and 0x7FFF == 1 && ttl > 0) MdnsRecord(owner, type, target, port, address) else null
    }
}

private fun asciiLower(byte: Byte): Int {
    val value = byte.toInt() and 0xFF
    return if (value in 65..90) value + 32 else value
}
