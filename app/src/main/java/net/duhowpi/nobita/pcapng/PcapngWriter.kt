package net.duhowpi.nobita.pcapng

import net.duhowpi.nobita.btsnoop.BtsnoopRecord
import java.io.OutputStream

class PcapngWriter(private val output: OutputStream) : AutoCloseable {
    init {
        writeSectionHeader()
        writeInterfaceDescription()
    }

    fun write(record: BtsnoopRecord) {
        val pseudoHeader = byteArrayOf(
            0, 0, 0, (if (record.controllerToHost) 1 else 0).toByte(),
        )
        val packet = pseudoHeader + record.packet
        val paddedLength = (packet.size + 3) and -4
        val totalLength = 32 + paddedLength
        writeInt(0x00000006)
        writeInt(totalLength)
        writeInt(0) // interface id
        writeInt((record.timestampMicros ushr 32).toInt())
        writeInt(record.timestampMicros.toInt())
        writeInt(packet.size)
        writeInt(record.originalLength + 4)
        output.write(packet)
        repeat(paddedLength - packet.size) { output.write(0) }
        writeInt(totalLength)
    }

    override fun close() = output.close()

    private fun writeSectionHeader() {
        writeInt(0x0A0D0D0A)
        writeInt(28)
        writeInt(0x1A2B3C4D)
        writeShort(1); writeShort(0)
        writeLong(-1L)
        writeInt(28)
    }

    private fun writeInterfaceDescription() {
        val name = "Android Bluetooth HCI".toByteArray()
        val description = "Android BTSnoop capture".toByteArray()
        val options = option(2, name) + option(3, description) + byteArrayOf(0, 0, 0, 0)
        val totalLength = 20 + options.size
        writeInt(0x00000001); writeInt(totalLength)
        writeShort(201); writeShort(0); writeInt(0xFFFFFFFF.toInt())
        output.write(options)
        writeInt(totalLength)
    }

    private fun option(code: Int, value: ByteArray): ByteArray {
        val padded = (value.size + 3) and -4
        return byteArrayOf((code ushr 8).toByte(), code.toByte(), (value.size ushr 8).toByte(), value.size.toByte()) +
            value + ByteArray(padded - value.size)
    }

    private fun writeShort(value: Int) { output.write(value ushr 8); output.write(value) }
    private fun writeInt(value: Int) { output.write(value ushr 24); output.write(value ushr 16); output.write(value ushr 8); output.write(value) }
    private fun writeLong(value: Long) { writeInt((value ushr 32).toInt()); writeInt(value.toInt()) }
}
