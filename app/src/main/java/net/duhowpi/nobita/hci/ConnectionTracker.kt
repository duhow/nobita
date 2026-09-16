package net.duhowpi.nobita.hci

import net.duhowpi.nobita.btsnoop.BtsnoopRecord

enum class Transport { CLASSIC, LE, UNKNOWN }

data class Connection(
    val handle: Int,
    val address: String,
    var name: String? = null,
    val transport: Transport = Transport.UNKNOWN,
    var connected: Boolean = true,
)

class ConnectionTracker {
    private val connections = mutableMapOf<Int, Connection>()
    private val seenHandles = mutableSetOf<Int>()
    private val pendingClassicAddresses = mutableSetOf<String>()
    private val names = mutableMapOf<String, String>()
    fun connectionFor(record: BtsnoopRecord): Connection? {
        val packet = record.packet
        if (packet.isEmpty()) return null
        if (packet[0].toInt() and 0xff == 0x04) {
            val event = if (packet.size > 1) packet[1].toInt() and 0xff else return null
            if (event == 0x05 && packet.size >= 6) {
                return connections.remove(readLe16(packet, 4) and 0x0fff)
            }
            parseEvent(packet)
            return when (event) {
                0x03 -> if (packet.size >= 6) connections[readLe16(packet, 4) and 0x0fff] else null
                0x07 -> if (packet.size >= 10) {
                    val address = address(packet, 4)
                    connections.values.firstOrNull { it.address == address }
                } else null
                0x3e -> if (packet.size >= 6 && packet[3].toInt() and 0xff in intArrayOf(0x01, 0x0a)) {
                    connections[readLe16(packet, 5)]
                } else null
                else -> null
            }
        }
        when (packet[0].toInt() and 0xff) {
            0x02, 0x03, 0x05 -> if (packet.size >= 3) return connections[readLe16(packet, 1) and 0x0fff]
        }
        return null
    }
    private fun parseEvent(packet: ByteArray) {
        if (packet.size < 3) return
        when (packet[1].toInt() and 0xff) {
            0x03 -> if (packet.size >= 12 && packet[3].toInt() and 0xff == 0) {
                val address = address(packet, 6)
                val handle = readLe16(packet, 4) and 0x0fff
                pendingClassicAddresses.remove(address)
                seenHandles += handle
                connections[handle] = Connection(handle, address, names[address], Transport.CLASSIC)
            }
            0x04 -> if (packet.size >= 13) pendingClassicAddresses += address(packet, 3)
            0x07 -> if (packet.size >= 11) {
                val address = address(packet, 4)
                rememberName(address, packet.copyOfRange(10, packet.size).takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8))
            }
            0x3e -> if (packet.size >= 4) when (packet[3].toInt() and 0xff) {
                0x01, 0x0a -> if (packet.size >= 15 && packet[4].toInt() and 0xff == 0) {
                    val handle = readLe16(packet, 5)
                    val addressOffset = 9
                    if (packet.size >= addressOffset + 6) {
                        val address = address(packet, addressOffset)
                        seenHandles += handle
                        connections[handle] = Connection(handle, address, names[address], Transport.LE)
                    }
                }
                0x02 -> parseAdvertising(packet, extended = false)
                0x0d -> parseAdvertising(packet, extended = true)
            }
        }
    }
    private fun parseAdvertising(packet: ByteArray, extended: Boolean) {
        if (packet.size < 5) return
        val reportCount = packet[4].toInt() and 0xff
        var reportOffset = 5
        repeat(reportCount) {
            val minimumHeader = if (extended) 24 else 9
            if (reportOffset + minimumHeader > packet.size) return@repeat
            val addressOffset = reportOffset + if (extended) 3 else 2
            val dataLengthOffset = reportOffset + if (extended) 23 else 8
            val address = address(packet, addressOffset)
            val dataLength = packet[dataLengthOffset].toInt() and 0xff
            val dataStart = dataLengthOffset + 1
            val dataEnd = dataStart + dataLength
            if (dataEnd > packet.size) return@repeat
            var offset = dataStart
            while (offset + 1 < dataEnd) {
                val length = packet[offset].toInt() and 0xff
                if (length == 0 || offset + 1 + length > dataEnd) break
                val type = packet[offset + 1].toInt() and 0xff
                if (type == 0x08 || type == 0x09) rememberName(address, packet.copyOfRange(offset + 2, offset + 1 + length).toString(Charsets.UTF_8))
                offset += length + 1
            }
            reportOffset = if (extended) dataEnd else dataEnd + 1 // Legacy reports have RSSI after the data.
        }
    }
    private fun rememberName(address: String, name: String) {
        names[address] = name
        connections.values.filter { it.address == address }.forEach { it.name = name }
    }
    fun connectionCount(): Int = seenHandles.size
    fun pendingClassicAddresses(): Set<String> = pendingClassicAddresses.toSet()
    private fun address(packet: ByteArray, offset: Int) = (0..5).joinToString(":") { "%02X".format(packet[offset + 5 - it].toInt() and 0xff) }
    private fun readLe16(packet: ByteArray, offset: Int) = (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
}

object PacketFilter {
    fun matches(record: BtsnoopRecord, connection: Connection?, target: String?): Boolean {
        if (target.isNullOrBlank()) return true
        return DeviceTarget.fromQuery(target).matches(connection)
    }
}
