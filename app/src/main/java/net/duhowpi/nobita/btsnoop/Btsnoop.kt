package net.duhowpi.nobita.btsnoop

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

data class BtsnoopRecord(
    val originalLength: Int,
    val flags: Int,
    val timestampMicros: Long,
    val packet: ByteArray,
) {
    val controllerToHost: Boolean get() = flags and 1 != 0
}

class InvalidBtsnoopException(message: String) : Exception(message)

object BtsnoopReader {
    private const val HEADER_LENGTH = 16
    private const val ANDROID_DATALINK_H4 = 1002
    private const val BTSNOOP_EPOCH_OFFSET = 0x00dcddb30f2f8000L

    fun read(input: InputStream): Sequence<BtsnoopRecord> = sequence {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(8)
        data.readFully(magic)
        if (!magic.contentEquals("btsnoop\u0000".toByteArray(Charsets.US_ASCII))) {
            throw InvalidBtsnoopException("Invalid BTSnoop magic")
        }
        val version = data.readInt()
        val datalink = data.readInt()
        if (version != 1 || datalink != ANDROID_DATALINK_H4) {
            throw InvalidBtsnoopException("Unsupported BTSnoop header: version=$version datalink=$datalink")
        }
        while (true) {
            val originalLength: Int
            try {
                originalLength = data.readInt()
            } catch (_: EOFException) {
                break
            }
            val includedLength = data.readInt()
            val flags = data.readInt()
            data.readInt() // cumulative drops
            val timestamp = data.readLong() - BTSNOOP_EPOCH_OFFSET
            if (originalLength < 0 || includedLength < 0 || includedLength > originalLength || includedLength > 16 * 1024 * 1024) {
                throw InvalidBtsnoopException("Invalid BTSnoop record lengths")
            }
            val packet = ByteArray(includedLength)
            data.readFully(packet)
            yield(BtsnoopRecord(originalLength, flags, timestamp, packet))
        }
    }
}
