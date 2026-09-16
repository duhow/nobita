package net.duhowpi.nobita.shizuku

import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.annotation.Keep
import net.duhowpi.nobita.btsnoop.BtsnoopFileRecovery
import net.duhowpi.nobita.btsnoop.BtsnoopNetClient
import net.duhowpi.nobita.btsnoop.BtsnoopReader
import net.duhowpi.nobita.hci.ConnectionTracker
import net.duhowpi.nobita.hci.HciPacketClassifier
import net.duhowpi.nobita.hci.PacketFilter
import net.duhowpi.nobita.pcapng.PcapngValidator
import net.duhowpi.nobita.pcapng.PcapngWriter

@Keep
class CaptureUserService : ICaptureUserService.Stub() {
    private val lifecycleLock = Object()
    private var client: BtsnoopNetClient? = null
    private var captureId: String? = null
    private var activeRaw: File? = null
    private var target = ""
    private var saveRaw = false
    private var exportRunning = false
    private var completedExport: String? = null
    private var lastExportSummary = ""
    @Volatile private var exportProgress = "Ready"

    @Keep
    fun destroy() {
        abortCapture()
        System.exit(0)
    }

    override fun startCapture(target: String, saveRaw: Boolean): String {
        check(Process.myUid() == 2000 || Process.myUid() == 0) { "Unexpected UserService UID: ${Process.myUid()}" }
        synchronized(lifecycleLock) {
            check(client == null && !exportRunning) { "A Bluetooth capture is already active" }
            completedExport = null
            lastExportSummary = ""
            val mode = command("getprop", "persist.bluetooth.btsnooplogmode").trim().ifEmpty { "unknown" }
            val id = UUID.randomUUID().toString().replace("-", "")
            val raw = File(captureDirectory(), ".active-$id.btsnoop.part")
            val newClient = BtsnoopNetClient(raw)
            exportProgress = "Connecting to btsnoop_net…"
            newClient.start()
            client = newClient
            captureId = id
            activeRaw = raw
            this.target = target
            this.saveRaw = saveRaw
            return "uid=${Process.myUid()} mode=$mode source=btsnoop_net captureId=$id"
        }
    }

    override fun stopAndExport(): String {
        val captureTarget: String
        val captureSaveRaw: Boolean
        val raw: File
        val id: String
        synchronized(lifecycleLock) {
            while (exportRunning) lifecycleLock.wait()
            completedExport?.let { return it }
            exportRunning = true
            val activeClient = client ?: run {
                exportRunning = false
                lifecycleLock.notifyAll()
                error("No active btsnoop_net capture")
            }
            exportProgress = "Stopping live capture…"
            activeClient.stop()
            raw = activeRaw ?: error("No active capture file")
            id = captureId ?: error("No active capture ID")
            captureTarget = target
            captureSaveRaw = saveRaw
            client = null
            activeRaw = null
            captureId = null
        }

        var result: String? = null
        try {
            exportProgress = "Finalizing BTSnoop…"
            val recovery = BtsnoopFileRecovery.truncateToCompleteRecords(raw)
            check(!recovery.malformedRecord) { "Malformed BTSnoop record; raw capture preserved" }
            check(recovery.records > 0) { "No Bluetooth packets were received; raw capture preserved" }
            val finalized = File(captureDirectory(), ".capture-$id.btsnoop")
            if (!raw.renameTo(finalized)) {
                raw.copyTo(finalized, overwrite = true)
                raw.delete()
            }
            result = convertRaw(finalized, id, captureTarget, captureSaveRaw, recovery.truncatedTail, "btsnoop_net")
            return result
        } finally {
            synchronized(lifecycleLock) {
                if (result != null) completedExport = result
                exportRunning = false
                lifecycleLock.notifyAll()
            }
        }
    }

    private fun convertRaw(raw: File, id: String, target: String, saveRaw: Boolean, truncatedTail: Boolean, source: String): String {
        exportProgress = "Converting packets…"
        val directory = captureDirectory()
        val base = (target.ifBlank { "Bluetooth" }).replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val generated = File(directory, "${base}_$stamp.pcapng")
        var converted = false
        try {
            var written = 0
            var total = 0
            val handles = mutableSetOf<Int>()
            var attPackets = 0
            raw.inputStream().use { input -> PcapngWriter(generated.outputStream()).use { writer ->
                val tracker = ConnectionTracker()
                for (record in BtsnoopReader.read(input)) {
                    val connection = tracker.connectionFor(record)
                    total++
                    connection?.let { handles += it.handle }
                    if (HciPacketClassifier.isAttPacket(record.packet)) attPackets++
                    if (PacketFilter.matches(record, connection, target)) { writer.write(record); written++ }
                }
            } }
            lastExportSummary = "Capture: $source; target: ${target.ifBlank { "All devices" }}; packets: $total; target packets: $written; connections: ${handles.size}; handles: ${formatHandles(handles)}; ATT packets: $attPackets; bytes: ${raw.length()}${if (truncatedTail) "; warning: truncated incomplete tail" else ""}"
            if (target.isNotBlank() && written == 0) error("No packets matched target; raw capture preserved for full export")
            PcapngValidator.validate(generated)
            exportProgress = "Finishing export…"
            if (saveRaw) raw.copyTo(File(directory, "${base}_$stamp.btsnoop"), overwrite = true)
            converted = true
            return generated.absolutePath
        } finally {
            if (!converted) raw.copyTo(File(directory, ".pending-$id.btsnoop"), overwrite = true)
            if (converted) raw.delete()
            if (!converted) generated.delete()
        }
    }

    override fun exportFullCapture(): String {
        exportProgress = "Converting full capture…"
        val raw = captureDirectory().listFiles { file -> file.name.startsWith(".pending-") && file.name.endsWith(".btsnoop") }
            ?.maxByOrNull { it.lastModified() } ?: error("No pending raw capture is available")
        val output = File(captureDirectory(), "Bluetooth_${System.currentTimeMillis()}.pcapng")
        try {
            var total = 0
            var attPackets = 0
            val tracker = ConnectionTracker()
            raw.inputStream().use { input -> PcapngWriter(output.outputStream()).use { writer ->
                BtsnoopReader.read(input).forEach { record ->
                    tracker.connectionFor(record)
                    if (HciPacketClassifier.isAttPacket(record.packet)) attPackets++
                    writer.write(record)
                    total++
                }
            } }
            lastExportSummary = "Packets: $total; target packets: $total; connections: ${tracker.connectionCount()}; handles: ${formatHandles(tracker.connectionHandles())}; ATT packets: $attPackets; source: btsnoop_net"
            PcapngValidator.validate(output)
            exportProgress = "Finishing export…"
            raw.delete()
            return output.absolutePath
        } finally {
            if (!output.isFile) output.delete()
        }
    }

    override fun getLastExportSummary(): String = lastExportSummary
    override fun getExportProgress(): String = exportProgress
    override fun getCaptureStatus(): String = synchronized(lifecycleLock) {
        val active = client
        when {
            active != null -> {
                val status = active.status()
                "CAPTURING captureId=${captureId ?: "unknown"} bytes=${status.bytesReceived} lastDataAt=${status.lastDataAt}"
            }
            exportRunning -> "EXPORTING captureId=${captureId ?: "unknown"}"
            completedExport != null -> "COMPLETED"
            else -> "IDLE"
        }
    }
    override fun hasPendingCapture(): Boolean = captureDirectory()
        .listFiles { file -> file.name.startsWith(".pending-") && file.name.endsWith(".btsnoop") }
        ?.isNotEmpty() == true

    override fun abortCapture() {
        synchronized(lifecycleLock) {
            client?.stop()
            client = null
            activeRaw?.delete()
            activeRaw = null
            captureId = null
            target = ""
            saveRaw = false
            lifecycleLock.notifyAll()
        }
    }

    private fun captureDirectory() = File(
        "/sdcard/Android/data/net.duhowpi.nobita/files/BluetoothCaptures",
    ).apply { mkdirs() }

    private fun formatHandles(handles: Set<Int>) = handles.sorted().joinToString(",") { "0x%04X".format(Locale.US, it) }.ifEmpty { "none" }
    private fun command(vararg args: String): String = commandLines(*args).joinToString("\n")
    private fun commandLines(vararg args: String): List<String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(process.inputStream)).readLines()
        check(process.waitFor() == 0) { "${args.first()} failed: ${lines.joinToString(" ")}" }
        return lines
    }
}
