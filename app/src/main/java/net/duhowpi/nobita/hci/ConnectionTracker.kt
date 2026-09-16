package net.duhowpi.nobita.hci

import net.duhowpi.nobita.btsnoop.BtsnoopRecord

data class Connection(val handle: Int, val address: String, var connected: Boolean = true)

class ConnectionTracker {
    private val connections = mutableMapOf<Int, Connection>()
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
            0x03 -> if (packet.size >= 9) connections[readLe16(packet, 3)] = Connection(readLe16(packet, 3), address(packet, 5))
            0x05 -> if (packet.size >= 5) connections.remove(readLe16(packet, 2) and 0x0fff)
            0x3e -> if (packet.size >= 4 && ((packet[3].toInt() and 0xff == 0x01) || (packet[3].toInt() and 0xff == 0x0a))) {
                val handleOffset = 4
                val addressOffset = 8
                if (packet.size >= addressOffset + 6) connections[readLe16(packet, handleOffset)] = Connection(readLe16(packet, handleOffset), address(packet, addressOffset))
            }
        }
    }
    private fun address(packet: ByteArray, offset: Int) = (0..5).joinToString(":") { "%02X".format(packet[offset + 5 - it].toInt() and 0xff) }
    private fun readLe16(packet: ByteArray, offset: Int) = (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
}

object PacketFilter {
    fun matches(record: BtsnoopRecord, connection: Connection?, target: String?): Boolean {
        if (target.isNullOrBlank()) return true
        val normalized = target.replace(":", "").replace("-", "").lowercase()
        return connection?.address?.replace(":", "")?.lowercase()?.contains(normalized) == true
    }
}
