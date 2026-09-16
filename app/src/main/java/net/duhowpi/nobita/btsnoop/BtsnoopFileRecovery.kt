package net.duhowpi.nobita.btsnoop

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

data class BtsnoopRecovery(
    val completeLength: Long,
    val records: Long,
    val truncatedTail: Boolean,
    val malformedRecord: Boolean,
)

object BtsnoopFileRecovery {
    fun inspect(file: File): BtsnoopRecovery {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = ByteArray(BtsnoopFormat.MAGIC.size)
            input.readFully(magic)
            check(magic.contentEquals(BtsnoopFormat.MAGIC)) { "Invalid BTSnoop magic" }
            check(input.readInt() == BtsnoopFormat.VERSION) { "Unsupported BTSnoop version" }
            check(input.readInt() == BtsnoopFormat.ANDROID_DATALINK_H4) { "Unsupported BTSnoop datalink" }
        }

        val length = file.length()
        var offset = BtsnoopFormat.HEADER_LENGTH
        var records = 0L
        var truncatedTail = false
        var malformedRecord = false
        java.io.RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            while (offset < length) {
                if (length - offset < BtsnoopFormat.RECORD_HEADER_LENGTH) {
                    truncatedTail = true
                    break
                }
                val original = input.readInt()
                val included = input.readInt()
                input.readInt()
                input.readInt()
                input.readLong()
                if (original < 0 || included < 0 || included > original || included > BtsnoopFormat.MAX_INCLUDED_LENGTH) {
                    malformedRecord = true
                    break
                }
                val end = offset + BtsnoopFormat.RECORD_HEADER_LENGTH + included
                if (end > length) {
                    truncatedTail = true
                    break
                }
                input.seek(end)
                offset = end
                records++
            }
        }
        return BtsnoopRecovery(offset, records, truncatedTail, malformedRecord)
    }

    fun truncateToCompleteRecords(file: File): BtsnoopRecovery {
        val recovery = inspect(file)
        if (recovery.truncatedTail && !recovery.malformedRecord) {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(recovery.completeLength) }
        }
        return recovery
    }
}
