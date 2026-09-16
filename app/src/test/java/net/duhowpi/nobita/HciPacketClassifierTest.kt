package net.duhowpi.nobita

import net.duhowpi.nobita.hci.HciPacketClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HciPacketClassifierTest {
    @Test fun identifiesAttL2capPackets() {
        assertTrue(HciPacketClassifier.isAttPacket(byteArrayOf(0x02, 0x42, 0, 5, 0, 1, 0, 4, 0, 2)))
        assertFalse(HciPacketClassifier.isAttPacket(byteArrayOf(0x02, 0x42, 0, 5, 0, 1, 0, 1, 0, 2)))
        assertFalse(HciPacketClassifier.isAttPacket(byteArrayOf(0x04, 0x0e, 0)))
    }
}
