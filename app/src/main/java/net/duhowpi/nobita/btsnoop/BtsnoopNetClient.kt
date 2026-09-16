package net.duhowpi.nobita.btsnoop

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class BtsnoopNetTerminalReason { STOPPED, EOF, SIZE_LIMIT, STORAGE_ERROR, READ_ERROR }

data class BtsnoopNetStatus(
    val bytesReceived: Long,
    val lastDataAt: Long,
    val terminalReason: BtsnoopNetTerminalReason?,
    val error: String?,
)

class BtsnoopNetClient(
    private val output: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val freeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    private val socketFactory: () -> Socket = { Socket() },
) {
    private val stopped = AtomicBoolean(false)
    private val terminalReason = AtomicReference<BtsnoopNetTerminalReason?>(null)
    private val firstError = AtomicReference<String?>(null)
    @Volatile private var bytesReceived = 0L
    @Volatile private var lastDataAt = 0L
    private var socket: Socket? = null
    private var reader: Thread? = null

    @Synchronized
    fun start(): BtsnoopNetStatus {
        check(reader == null) { "btsnoop_net capture already started" }
        output.parentFile?.mkdirs()
        val connection = socketFactory()
        try {
            connection.connect(InetSocketAddress(LOOPBACK, PORT), CONNECT_TIMEOUT_MS)
            connection.soTimeout = HEADER_TIMEOUT_MS
            val header = ByteArray(BtsnoopFormat.HEADER_LENGTH.toInt())
            readFully(connection.getInputStream(), header)
            validateHeader(header)
            FileOutputStream(output).use { it.write(header); it.fd.sync() }
            connection.soTimeout = 0
            socket = connection
            reader = Thread({ drain(connection, header.size.toLong()) }, "btsnoop-net-reader").also { it.start() }
            return status()
        } catch (error: Exception) {
            connection.closeQuietly()
            output.delete()
            firstError.compareAndSet(null, error.message ?: error.javaClass.simpleName)
            terminalReason.set(BtsnoopNetTerminalReason.READ_ERROR)
            throw IllegalStateException("btsnoop_net unavailable: ${error.message ?: "invalid endpoint"}", error)
        }
    }

    fun stop(): BtsnoopNetStatus {
        if (stopped.compareAndSet(false, true)) {
            terminalReason.compareAndSet(null, BtsnoopNetTerminalReason.STOPPED)
            socket.closeQuietly()
            reader?.let { thread ->
                if (thread !== Thread.currentThread()) thread.join(JOIN_TIMEOUT_MS)
            }
            FileOutputStream(output, true).use { it.fd.sync() }
        }
        return status()
    }

    fun status() = BtsnoopNetStatus(bytesReceived, lastDataAt, terminalReason.get(), firstError.get())

    private fun drain(connection: Socket, initialBytes: Long) {
        var total = initialBytes
        try {
            connection.getInputStream().use { input -> FileOutputStream(output, true).use { file ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (!stopped.get()) {
                    if (output.parentFile?.usableSpace ?: 0L <= freeSpaceReserveBytes) {
                        terminalReason.set(BtsnoopNetTerminalReason.STORAGE_ERROR)
                        break
                    }
                    val count = input.read(buffer)
                    if (count < 0) {
                        terminalReason.compareAndSet(null, BtsnoopNetTerminalReason.EOF)
                        break
                    }
                    if (total + count > maxBytes) {
                        terminalReason.set(BtsnoopNetTerminalReason.SIZE_LIMIT)
                        break
                    }
                    file.write(buffer, 0, count)
                    total += count
                    bytesReceived = total
                    lastDataAt = System.currentTimeMillis()
                }
                file.flush()
                file.fd.sync()
            } }
        } catch (error: Exception) {
            if (!stopped.get()) {
                terminalReason.set(BtsnoopNetTerminalReason.READ_ERROR)
                firstError.compareAndSet(null, error.message ?: error.javaClass.simpleName)
            }
        } finally {
            socket.closeQuietly()
        }
    }

    private fun validateHeader(header: ByteArray) {
        check(header.size == BtsnoopFormat.HEADER_LENGTH.toInt() && header.copyOfRange(0, 8).contentEquals(BtsnoopFormat.MAGIC)) { "Invalid BTSnoop header" }
        val version = readInt(header, 8)
        val datalink = readInt(header, 12)
        check(version == BtsnoopFormat.VERSION && datalink == BtsnoopFormat.ANDROID_DATALINK_H4) { "Unsupported BTSnoop header" }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            check(count >= 0) { "Short BTSnoop header" }
            offset += count
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

    private fun Socket?.closeQuietly() = runCatching { this?.close() }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val PORT = 8872
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HEADER_TIMEOUT_MS = 3_000
        private const val JOIN_TIMEOUT_MS = 5_000L
        private const val BUFFER_SIZE = 32 * 1024
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        const val DEFAULT_FREE_SPACE_RESERVE_BYTES = 64L * 1024 * 1024
    }
}
