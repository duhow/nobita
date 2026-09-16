package net.duhowpi.nobita

import net.duhowpi.nobita.btsnoop.BtsnoopRecord
import net.duhowpi.nobita.hci.ConnectionTracker
import net.duhowpi.nobita.hci.PacketFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTrackerTest {
    @Test fun mapsLeConnectionHandleToPeerAddress() {
        val tracker = ConnectionTracker()
        val event = BtsnoopRecord(19, 1, 0, byteArrayOf(
            0x04, 0x3e, 0x13, 0x01, 0x42, 0x00, 0x00, 0x01,
            0xa6.toByte(), 0xb5.toByte(), 0xc4.toByte(), 0xd3.toByte(), 0xe2.toByte(), 0xf1.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0,
        ))
        tracker.connectionFor(event)
        val connection = tracker.connectionFor(BtsnoopRecord(3, 0, 1, byteArrayOf(0x02, 0x42, 0x00)))
        assertEquals("F1:E2:D3:C4:B5:A6", connection?.address)
        assertTrue(PacketFilter.matches(event, connection, "F1:E2"))
    }
}
