package net.duhowpi.nobita.hci

object HciPacketClassifier {
    /** Returns true when an unfragmented ACL packet carries the ATT L2CAP channel. */
    fun isAttPacket(packet: ByteArray): Boolean {
        if (packet.size < 9 || packet[0].toInt() and 0xff != 0x02) return false
        val payloadLength = (packet[3].toInt() and 0xff) or ((packet[4].toInt() and 0xff) shl 8)
        if (payloadLength < 4 || packet.size < payloadLength + 5) return false
        val channel = (packet[7].toInt() and 0xff) or ((packet[8].toInt() and 0xff) shl 8)
        return channel == 0x0004
    }
}
