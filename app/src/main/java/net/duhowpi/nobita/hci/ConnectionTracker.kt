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
        when (packet[0].toInt() and 0xff) {
            0x02, 0x03, 0x05 -> if (packet.size >= 3) return connections[readLe16(packet, 1) and 0x0fff]
        }
        return null
    }
    private fun parseEvent(packet: ByteArray) {
        if (packet.size < 3) return
        when (packet[1].toInt() and 0xff) {
            0x03 -> if (packet.size >= 12) {
                val address = address(packet, 6)
                val handle = readLe16(packet, 4)
                connections[handle] = Connection(handle, address, names[address])
            }
            0x07 -> if (packet.size >= 11) {
                val address = address(packet, 4)
                names[address] = packet.copyOfRange(10, packet.size).takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8)
            }
            0x05 -> if (packet.size >= 6) connections.remove(readLe16(packet, 4) and 0x0fff)
            0x3e -> when (packet[3].toInt() and 0xff) {
                0x01, 0x0a -> if (packet.size >= 15) {
                    val handle = readLe16(packet, 5)
                    val addressOffset = 9
                    if (packet.size >= addressOffset + 6) {
                        val address = address(packet, addressOffset)
                        connections[handle] = Connection(handle, address, names[address])
                    }
                }
                0x02 -> parseAdvertising(packet)
            }
        }
    }
    private fun parseAdvertising(packet: ByteArray) {
        if (packet.size < 5) return
        val reportCount = packet[4].toInt() and 0xff
        var reportOffset = 5
        repeat(reportCount) {
            if (reportOffset + 9 > packet.size) return@repeat
            val address = address(packet, reportOffset + 2)
            val dataLength = packet[reportOffset + 8].toInt() and 0xff
            val dataStart = reportOffset + 9
            val dataEnd = dataStart + dataLength
            if (dataEnd + 1 > packet.size) return@repeat
            var offset = dataStart
            while (offset + 1 < dataEnd) {
                val length = packet[offset].toInt() and 0xff
                if (length == 0 || offset + 1 + length > dataEnd) break
                val type = packet[offset + 1].toInt() and 0xff
                if (type == 0x08 || type == 0x09) names[address] = packet.copyOfRange(offset + 2, offset + 1 + length).toString(Charsets.UTF_8)
                offset += length + 1
            }
            reportOffset = dataEnd + 1 // Skip RSSI.
        }
    }
    private fun address(packet: ByteArray, offset: Int) = (0..5).joinToString(":") { "%02X".format(packet[offset + 5 - it].toInt() and 0xff) }
    private fun readLe16(packet: ByteArray, offset: Int) = (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
}

object PacketFilter {
    fun matches(record: BtsnoopRecord, connection: Connection?, target: String?): Boolean {
        if (target.isNullOrBlank()) return true
        return DeviceTarget.fromQuery(target).matches(connection)
    }
}
