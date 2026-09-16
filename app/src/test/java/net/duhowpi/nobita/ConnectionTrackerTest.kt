package net.duhowpi.nobita

import net.duhowpi.nobita.btsnoop.BtsnoopRecord
import net.duhowpi.nobita.hci.ConnectionTracker
import net.duhowpi.nobita.hci.PacketFilter
import net.duhowpi.nobita.hci.DeviceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTrackerTest {
    @Test fun mapsLeConnectionHandleToPeerAddress() {
        val tracker = ConnectionTracker()
        val event = BtsnoopRecord(19, 1, 0, byteArrayOf(
            0x04, 0x3e, 0x13, 0x01, 0x00, 0x42, 0x00, 0x00, 0x01,
            0xa6.toByte(), 0xb5.toByte(), 0xc4.toByte(), 0xd3.toByte(), 0xe2.toByte(), 0xf1.toByte(),
            0, 0, 0, 0, 0, 0, 0,
        ))
        tracker.connectionFor(event)
        val connection = tracker.connectionFor(BtsnoopRecord(3, 0, 1, byteArrayOf(0x02, 0x42, 0x00)))
        assertEquals("F1:E2:D3:C4:B5:A6", connection?.address)
        assertTrue(PacketFilter.matches(event, connection, "F1:E2"))
        assertTrue(DeviceTarget.fromQuery("F1:E2:D3:C4:B5:A6").matches(connection))
        assertEquals("F1:E2:D3:C4:B5:A6", tracker.connectionFor(event)?.address)
        tracker.connectionFor(BtsnoopRecord(4, 1, 3, byteArrayOf(0x04, 0x05, 0x04, 0, 0x42, 0)))
        assertNull(tracker.connectionFor(BtsnoopRecord(3, 0, 4, byteArrayOf(0x02, 0x42, 0))))
    }

    @Test fun associatesClassicRemoteNameBeforeConnection() {
        val tracker = ConnectionTracker()
        val name = "Headset".toByteArray()
        tracker.connectionFor(BtsnoopRecord(0, 1, 0, byteArrayOf(
            0x04, 0x07, (7 + name.size).toByte(), 0,
            0xa6.toByte(), 0xb5.toByte(), 0xc4.toByte(), 0xd3.toByte(), 0xe2.toByte(), 0xf1.toByte(),
            *name, 0,
        )))
        tracker.connectionFor(BtsnoopRecord(0, 1, 1, byteArrayOf(
            0x04, 0x03, 0x0b, 0x00, 0x2a, 0x00,
            0xa6.toByte(), 0xb5.toByte(), 0xc4.toByte(), 0xd3.toByte(), 0xe2.toByte(), 0xf1.toByte(), 0,
            0,
        )))
        val connection = tracker.connectionFor(BtsnoopRecord(3, 0, 2, byteArrayOf(0x02, 0x2a, 0x00)))
        assertEquals("Headset", connection?.name)
    }

    @Test fun learnsNamesFromEachAdvertisingReport() {
        val tracker = ConnectionTracker()
        val reportOne = byteArrayOf(
            0, 0, 6, 5, 4, 3, 2, 1, 4, 3, 9, 'A'.code.toByte(), '1'.code.toByte(), 0xc0.toByte(),
        )
        val reportTwo = byteArrayOf(
            0, 0, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11, 4, 3, 9, 'B'.code.toByte(), '2'.code.toByte(), 0xc0.toByte(),
        )
        tracker.connectionFor(BtsnoopRecord(0, 0, 0, byteArrayOf(
            0x04, 0x3e, 0x1d, 0x02, 0x02, *reportOne, *reportTwo,
        )))
        tracker.connectionFor(BtsnoopRecord(0, 1, 1, byteArrayOf(
            0x04, 0x3e, 0x13, 0x01, 0x00, 0x42, 0x00, 0x00, 0x01,
            0x16, 0x15, 0x14, 0x13, 0x12, 0x11,
            0, 0, 0, 0, 0, 0, 0,
        )))

        val connection = tracker.connectionFor(BtsnoopRecord(3, 0, 2, byteArrayOf(0x02, 0x42, 0x00)))
        assertEquals("B2", connection?.name)
    }

    @Test fun updatesConnectedDeviceWhenRemoteNameArrives() {
        val tracker = ConnectionTracker()
        tracker.connectionFor(BtsnoopRecord(0, 1, 0, byteArrayOf(
            0x04, 0x03, 0x0b, 0, 0x42, 0,
            0xa6.toByte(), 0xb5.toByte(), 0xc4.toByte(), 0xd3.toByte(), 0xe2.toByte(), 0xf1.toByte(), 0, 0,
        )))
        tracker.connectionFor(BtsnoopRecord(0, 1, 1, byteArrayOf(
            0x04, 0x07, 14, 0,
            0xa6.toByte(), 0xb5.toByte(), 0xc4.toByte(), 0xd3.toByte(), 0xe2.toByte(), 0xf1.toByte(),
            *"Headset".toByteArray(), 0,
        )))

        val connection = tracker.connectionFor(BtsnoopRecord(3, 0, 2, byteArrayOf(0x02, 0x42, 0x00)))
        assertEquals("Headset", connection?.name)
        assertTrue(DeviceTarget.fromQuery("head").matches(connection))
    }

    @Test fun learnsNameFromExtendedAdvertisingReport() {
        val tracker = ConnectionTracker()
        val report = byteArrayOf(
            0, 0, 0, 6, 5, 4, 3, 2, 1,
            1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            4, 3, 9, 'E'.code.toByte(), 'X'.code.toByte(),
        )
        tracker.connectionFor(BtsnoopRecord(0, 0, 0, byteArrayOf(
            0x04, 0x3e, 0, 0x0d, 1, *report,
        )))

        tracker.connectionFor(BtsnoopRecord(0, 1, 1, byteArrayOf(
            0x04, 0x3e, 0x13, 0x01, 0, 0x42, 0, 0, 0,
            6, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0,
        )))
        val connection = tracker.connectionFor(BtsnoopRecord(3, 0, 2, byteArrayOf(0x02, 0x42, 0x00)))
        assertEquals("EX", connection?.name)
    }
}
