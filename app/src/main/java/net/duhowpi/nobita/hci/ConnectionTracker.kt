package net.duhowpi.nobita.hci

import net.duhowpi.nobita.btsnoop.BtsnoopRecord

data class Connection(val handle: Int, val address: String, val name: String? = null, var connected: Boolean = true)

class ConnectionTracker {
    private val connections = mutableMapOf<Int, Connection>()
    private val names = mutableMapOf<String, String>()
    fun connectionFor(record: BtsnoopRecord): Connection? {
        val packet = record.packet
        if (packet.isEmpty()) return null
        if (packet[0].toInt() and 0xff == 0x04) parseEvent(packet)
        if (packet[0].toInt() and 0xff == 0x02) return connections[readLe16(packet, 1) and 0x0fff]
        return null
    }
    private fun parseEvent(packet: ByteArray) {
        if (packet.size < 3) return
        when (packet[1].toInt() and 0xff) {
            0x03 -> if (packet.size >= 9) {
                val address = address(packet, 5)
                connections[readLe16(packet, 3)] = Connection(readLe16(packet, 3), address, names[address])
            }
            0x07 -> if (packet.size >= 10) {
                val address = address(packet, 3)
                names[address] = packet.copyOfRange(9, packet.size).takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8)
            }
            0x05 -> if (packet.size >= 5) connections.remove(readLe16(packet, 2) and 0x0fff)
            0x3e -> if (packet.size >= 4 && ((packet[3].toInt() and 0xff == 0x01) || (packet[3].toInt() and 0xff == 0x0a))) {
                val handleOffset = 4
                val addressOffset = 8
                if (packet.size >= addressOffset + 6) {
                    val address = address(packet, addressOffset)
                    connections[readLe16(packet, handleOffset)] = Connection(readLe16(packet, handleOffset), address, names[address])
                }
            } else if (packet[3].toInt() and 0xff == 0x02) parseAdvertising(packet)
        }
    }
    private fun parseAdvertising(packet: ByteArray) {
        if (packet.size < 15) return
        val address = address(packet, 7)
        val dataLength = packet[13].toInt() and 0xff
        val end = (14 + dataLength).coerceAtMost(packet.size)
        var offset = 14
        while (offset + 2 <= end) {
            val length = packet[offset].toInt() and 0xff
            if (length == 0 || offset + length >= end) break
            val type = packet[offset + 1].toInt() and 0xff
            if (type == 0x08 || type == 0x09) names[address] = packet.copyOfRange(offset + 2, offset + 1 + length).toString(Charsets.UTF_8)
            offset += length + 1
        }
    }
    private fun address(packet: ByteArray, offset: Int) = (0..5).joinToString(":") { "%02X".format(packet[offset + 5 - it].toInt() and 0xff) }
    private fun readLe16(packet: ByteArray, offset: Int) = (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
}

object PacketFilter {
    fun matches(record: BtsnoopRecord, connection: Connection?, target: String?): Boolean {
        if (target.isNullOrBlank()) return true
        val normalized = target.replace(":", "").replace("-", "").lowercase()
        return connection?.address?.replace(":", "")?.lowercase()?.contains(normalized) == true ||
            connection?.name?.lowercase()?.contains(target.lowercase()) == true
    }
}
