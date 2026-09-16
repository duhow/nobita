package net.duhowpi.nobita

import net.duhowpi.nobita.btsnoop.BtsnoopReader
import net.duhowpi.nobita.btsnoop.BtsnoopRecord
import net.duhowpi.nobita.pcapng.PcapngValidator
import net.duhowpi.nobita.pcapng.PcapngWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.DeflaterOutputStream

class BtsnoopPcapngTest {
    @Test fun readsAndroidBtsnoopAndPreservesDirection() {
        val packet = byteArrayOf(0x04, 0x0e, 0x00)
        val records = BtsnoopReader.read(btsnoop(packet, flags = 1).inputStream()).toList()
        assertEquals(1, records.size)
        assertEquals(true, records.single().controllerToHost)
        assertEquals(packet.toList(), records.single().packet.toList())
    }

    @Test fun writesAValidatedBluetoothPcapng() {
        val file = Files.createTempFile("nobita-test", ".pcapng").toFile()
        PcapngWriter(file.outputStream()).use { it.write(BtsnoopRecord(3, 0, 1_000_000, byteArrayOf(0x04, 0x0e, 0x00))) }
        PcapngValidator.validate(file)
        file.delete()
    }

    @Test fun decodesCompressedBtsnoozV1() {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { output ->
            output.write(byteArrayOf(2, 0, 10, 0, 0, 0, 0x10, 0x0e))
        }
        val snooz = ByteArrayOutputStream().apply {
            write(1)
            writeLongLe(1_000)
            write(compressed.toByteArray())
        }
        val output = ByteArrayOutputStream()
        net.duhowpi.nobita.btsnoop.BtsnoozDecoder.decode(snooz.toByteArray().inputStream(), output)
        val record = BtsnoopReader.read(output.toByteArray().inputStream()).single()
        assertEquals(listOf<Byte>(0x04, 0x0e), record.packet.toList())
        assertEquals(true, record.controllerToHost)
    }

    private fun btsnoop(packet: ByteArray, flags: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write("btsnoop\u0000".toByteArray())
            output.writeInt(1); output.writeInt(1002)
            output.writeInt(packet.size); output.writeInt(packet.size)
            output.writeInt(flags); output.writeInt(0)
            output.writeLong(0x00dcddb30f2f8000L + 1_000_000)
            output.write(packet)
        }
        return bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLongLe(value: Long) {
        repeat(8) { write((value ushr (it * 8)).toInt()) }
    }
}
