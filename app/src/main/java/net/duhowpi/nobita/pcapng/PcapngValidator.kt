package net.duhowpi.nobita.pcapng

import java.io.DataInputStream
import java.io.File

object PcapngValidator {
    fun validate(file: File) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 0x0A0D0D0A) { "Invalid PCAPNG section header" }
            check(input.readInt() >= 28) { "Invalid PCAPNG section length" }
            check(input.readInt() == 0x1A2B3C4D) { "Unsupported PCAPNG byte order" }
            input.skipBytes(12)
            check(input.readInt() == 28) { "Invalid PCAPNG section trailer" }
            var interfaceFound = false
            var packets = 0
            var lastTimestamp = Long.MIN_VALUE
            while (input.available() > 0) {
                val type = input.readInt()
                val length = input.readInt()
                check(length >= 12 && length % 4 == 0) { "Invalid PCAPNG block length" }
                val payload = ByteArray(length - 12)
                input.readFully(payload)
                check(input.readInt() == length) { "PCAPNG block length mismatch" }
                when (type) {
                    1 -> { check(payload.size >= 8 && ((payload[0].toInt() and 0xff) shl 8 or (payload[1].toInt() and 0xff)) == 201) { "Unexpected link type" }; interfaceFound = true }
                    6 -> {
                        check(payload.size >= 20) { "Invalid enhanced packet block" }
                        val timestamp = (readInt(payload, 4).toLong() shl 32) or (readInt(payload, 8).toLong() and 0xffffffffL)
                        check(timestamp >= lastTimestamp) { "Non-monotonic packet timestamps" }
                        lastTimestamp = timestamp
                        val captured = readInt(payload, 12)
                        check(captured >= 4 && payload.size >= 20 + ((captured + 3) and -4)) { "Invalid packet length" }
                        val packet = payload.copyOfRange(20, 20 + captured)
                        check(readInt(packet, 0) == 0 || readInt(packet, 0) == 1) { "Invalid Bluetooth direction" }
                        check(packet.size > 4 && packet[4].toInt() and 0xff in 1..5) { "Invalid H4 packet type" }
                        packets++
                    }
                }
            }
            check(interfaceFound) { "Missing Bluetooth interface" }
            check(packets > 0) { "PCAPNG contains no packets" }
        }
    }
    private fun readInt(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
}
