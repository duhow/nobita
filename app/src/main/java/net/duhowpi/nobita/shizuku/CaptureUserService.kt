package net.duhowpi.nobita.shizuku

import android.os.Process
import org.json.JSONObject
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

    init {
        recoverInterruptedParts()
    }

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
        val terminalReason: String?
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
            val streamStatus = activeClient.stop()
            terminalReason = streamStatus.terminalReason?.name
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
            result = convertRaw(finalized, id, captureTarget, captureSaveRaw, recovery.truncatedTail, "btsnoop_net", terminalReason)
            return result
        } finally {
            synchronized(lifecycleLock) {
                if (result != null) completedExport = result
                exportRunning = false
                lifecycleLock.notifyAll()
            }
        }
    }

    private fun convertRaw(raw: File, id: String, target: String, saveRaw: Boolean, truncatedTail: Boolean, source: String, terminalReason: String?): String {
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
            val warnings = listOfNotNull(
                terminalReason?.takeUnless { it == "STOPPED" }?.let { "stream ended: ${it.lowercase(Locale.US)}" },
                if (truncatedTail) "truncated incomplete tail" else null,
            )
            lastExportSummary = "Capture: $source; target: ${target.ifBlank { "All devices" }}; packets: $total; target packets: $written; connections: ${handles.size}; handles: ${formatHandles(handles)}; ATT packets: $attPackets; bytes: ${raw.length()}${if (warnings.isEmpty()) "" else "; warning: ${warnings.joinToString(", ")}"}"
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

    override fun exportFullCapture(captureId: String): String {
        require(captureId.matches(Regex("[0-9a-f]{32}"))) { "Invalid capture ID" }
        exportProgress = "Converting full capture…"
        val raw = File(captureDirectory(), ".pending-$captureId.btsnoop")
        check(raw.isFile) { "No pending raw capture is available for $captureId" }
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
                val state = if (status.terminalReason == null) "CAPTURING" else "INTERRUPTED"
                captureStatusJson(state, status.bytesReceived, status.lastDataAt, status.terminalReason?.name)
            }
            exportRunning -> captureStatusJson("EXPORTING", 0, 0, null)
            completedExport != null -> captureStatusJson("COMPLETED", 0, 0, null)
            else -> captureStatusJson("IDLE", 0, 0, null)
        }
    }
    override fun hasPendingCapture(captureId: String): Boolean {
        if (!captureId.matches(Regex("[0-9a-f]{32}"))) return false
        return File(captureDirectory(), ".pending-$captureId.btsnoop").isFile
    }

    override fun abortCapture() {
        synchronized(lifecycleLock) {
            client?.stop()
            preserveActiveCapture()
            client = null
            activeRaw = null
            captureId = null
            target = ""
            saveRaw = false
            lifecycleLock.notifyAll()
        }
    }

    private fun preserveActiveCapture() {
        val raw = activeRaw ?: return
        val id = captureId ?: return
        runCatching {
            val recovery = BtsnoopFileRecovery.truncateToCompleteRecords(raw)
            if (recovery.malformedRecord && recovery.completeLength < raw.length()) {
                java.io.RandomAccessFile(raw, "rw").use { it.setLength(recovery.completeLength) }
            }
            if (recovery.records == 0L) {
                raw.delete()
                return
            }
            val pending = File(captureDirectory(), ".pending-$id.btsnoop")
            if (!raw.renameTo(pending)) {
                raw.copyTo(pending, overwrite = true)
                raw.delete()
            }
        }.onFailure {
            val pending = File(captureDirectory(), ".pending-$id.btsnoop")
            if (!raw.renameTo(pending)) {
                raw.copyTo(pending, overwrite = true)
                raw.delete()
            }
        }
    }

    private fun captureDirectory() = File(
        "/sdcard/Android/data/net.duhowpi.nobita/files/BluetoothCaptures",
    ).apply { mkdirs() }

    private fun recoverInterruptedParts() {
        captureDirectory().listFiles { file ->
            file.name.matches(Regex("\\.active-[0-9a-f]{32}\\.btsnoop\\.part"))
        }?.forEach { raw ->
            runCatching {
                val recovery = BtsnoopFileRecovery.truncateToCompleteRecords(raw)
                if (recovery.malformedRecord && recovery.completeLength < raw.length()) {
                    java.io.RandomAccessFile(raw, "rw").use { it.setLength(recovery.completeLength) }
                }
                if (recovery.records == 0L) {
                    raw.delete()
                    return@runCatching
                }
                val id = raw.name.removePrefix(".active-").removeSuffix(".btsnoop.part")
                val pending = File(captureDirectory(), ".pending-$id.btsnoop")
                if (!raw.renameTo(pending)) {
                    raw.copyTo(pending, overwrite = true)
                    raw.delete()
                }
            }
        }
    }

    private fun captureStatusJson(state: String, bytes: Long, lastDataAt: Long, reason: String?): String = JSONObject()
        .put("version", 1)
        .put("state", state)
        .put("captureId", captureId ?: JSONObject.NULL)
        .put("bytes", bytes)
        .put("lastDataAt", lastDataAt)
        .put("reason", reason ?: JSONObject.NULL)
        .toString()

    private fun formatHandles(handles: Set<Int>) = handles.sorted().joinToString(",") { "0x%04X".format(Locale.US, it) }.ifEmpty { "none" }
    private fun command(vararg args: String): String = commandLines(*args).joinToString("\n")
    private fun commandLines(vararg args: String): List<String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(process.inputStream)).readLines()
        check(process.waitFor() == 0) { "${args.first()} failed: ${lines.joinToString(" ")}" }
        return lines
    }
}
