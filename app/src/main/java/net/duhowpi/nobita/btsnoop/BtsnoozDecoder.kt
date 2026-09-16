package net.duhowpi.nobita.btsnoop

import java.io.ByteArrayInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.InflaterInputStream

/** Converts Android's compressed btsnooz v1/v2 format into a normal BTSnoop stream. */
object BtsnoozDecoder {
    private const val EPOCH_OFFSET = 0x00dcddb30f2f8000L
    private val inbound = setOf(0x10, 0x11, 0x12, 0x17)

    fun decode(input: InputStream, output: OutputStream) {
        var source = input.readBytes()
        if (source.isEmpty()) error("Empty btsnooz stream")
        if (source[0].toInt() !in 1..2) {
            source = Base64.getMimeDecoder().decode(source)
        }
        check(source.size >= 9) { "Truncated btsnooz header" }
        val version = source[0].toInt()
        check(version == 1 || version == 2) { "Unsupported btsnooz version: $version" }
        val header = ByteBuffer.wrap(source, 1, 8).order(ByteOrder.LITTLE_ENDIAN)
        val lastTimestamp = header.long
        val compressed = InflaterInputStream(ByteArrayInputStream(source, 9, source.size - 9)).readBytes()
        val records = if (version == 1) parseV1(compressed) else parseV2(compressed)
        var firstTimestamp = lastTimestamp + EPOCH_OFFSET
        records.forEach { firstTimestamp -= it.deltaMs }
        val data = DataOutputStream(output)
        data.write("btsnoop\u0000".toByteArray())
        data.writeInt(1); data.writeInt(1002)
        records.forEach { record ->
            firstTimestamp += record.deltaMs
            data.writeInt(record.originalLength)
            data.writeInt(record.includedLength)
            data.writeInt(if (record.type in inbound) 1 else 0)
            data.writeInt(0)
            data.writeLong(firstTimestamp)
            data.writeByte(hciType(record.type))
            data.write(record.payload)
        }
        data.flush()
    }

    private fun parseV1(bytes: ByteArray): List<Record> {
        val records = mutableListOf<Record>()
        var offset = 0
        while (offset < bytes.size) {
            check(offset + 7 <= bytes.size) { "Truncated btsnooz v1 record" }
            val length = u16(bytes, offset)
            val delta = u32(bytes, offset + 2)
            val type = bytes[offset + 6].toInt() and 0xff
            check(length >= 1 && offset + 7 + length - 1 <= bytes.size) { "Invalid btsnooz v1 record length" }
            records += Record(length, length, delta, type, bytes.copyOfRange(offset + 7, offset + 7 + length - 1))
            offset += 7 + length - 1
        }
        return records
    }

    private fun parseV2(bytes: ByteArray): List<Record> {
        val records = mutableListOf<Record>()
        var offset = 0
        while (offset < bytes.size) {
            check(offset + 9 <= bytes.size) { "Truncated btsnooz v2 record" }
            val length = u16(bytes, offset)
            val packetLength = u16(bytes, offset + 2)
            val delta = u32(bytes, offset + 4)
            val type = bytes[offset + 8].toInt() and 0xff
            check(length >= 1 && packetLength >= length && offset + 9 + length - 1 <= bytes.size) { "Invalid btsnooz v2 record length" }
            records += Record(packetLength, length, delta, type, bytes.copyOfRange(offset + 9, offset + 9 + length - 1))
            offset += 9 + length - 1
        }
        return records
    }

    private fun hciType(type: Int) = when (type) {
        0x20 -> 0x01
        0x11, 0x21 -> 0x02
        0x12, 0x22 -> 0x03
        0x10 -> 0x04
        0x17, 0x2d -> 0x05
        else -> error("Unknown btsnooz packet type: 0x${type.toString(16)}")
    }

    private fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun u32(bytes: ByteArray, offset: Int) = (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or ((bytes[offset + 2].toLong() and 0xff) shl 16) or ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private data class Record(val originalLength: Int, val includedLength: Int, val deltaMs: Long, val type: Int, val payload: ByteArray)
}
