package net.duhowpi.nobita.shizuku

import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.annotation.Keep
import net.duhowpi.nobita.btsnoop.BtsnoopReader
import net.duhowpi.nobita.btsnoop.BtsnoozDecoder
import net.duhowpi.nobita.hci.ConnectionTracker
import net.duhowpi.nobita.hci.PacketFilter
import net.duhowpi.nobita.hci.HciPacketClassifier
import net.duhowpi.nobita.pcapng.PcapngWriter
import net.duhowpi.nobita.pcapng.PcapngValidator

@Keep
class CaptureUserService : ICaptureUserService.Stub() {
    private var previousMode: String? = null
    private var previousDefaultMode: String? = null
    private var propertyModeChanged = false
    private var bluetoothInitiallyEnabled = false
    private var lastExportSummary = ""

    @Keep
    fun destroy() {
        System.exit(0)
    }

    override fun prepareCapture(): String {
        check(Process.myUid() == 2000 || Process.myUid() == 0) { "Unexpected UserService UID: ${Process.myUid()}" }
        val mode = command("getprop", "persist.bluetooth.btsnooplogmode").trim().ifEmpty { "unknown" }
        previousMode = mode
        previousDefaultMode = "null"
        propertyModeChanged = false
        bluetoothInitiallyEnabled = true
        return "uid=${Process.myUid()} mode=$mode previous=$previousMode defaultMode=$previousDefaultMode propertyChanged=$propertyModeChanged initialBluetooth=$bluetoothInitiallyEnabled"
    }

    override fun exportPcapng(target: String, saveRaw: Boolean, previousMode: String, previousDefaultMode: String, propertyModeChanged: Boolean, bluetoothInitiallyEnabled: Boolean): String {
        this.previousMode = previousMode
        this.previousDefaultMode = previousDefaultMode
        this.propertyModeChanged = propertyModeChanged
        this.bluetoothInitiallyEnabled = bluetoothInitiallyEnabled
        var bugreport: File? = null
        var raw: File? = null
        var extracted = false
        var converted = false
        var output: File? = null
        try {
            val lines = commandLines("/system/bin/bugreportz", "-p")
            val path = lines.firstOrNull { it.startsWith("OK:") }?.removePrefix("OK:")?.trim()
                ?: error(lines.lastOrNull { it.startsWith("FAIL:") } ?: "bugreportz did not complete")
            bugreport = File(path)
            val rawFile = File.createTempFile("nobita-", ".btsnoop", File("/data/local/tmp"))
            raw = rawFile
            ZipFile(path).use { zip ->
                val entry = zip.entries().asSequence().filter { !it.isDirectory }
                    .map { it to score(it.name) }.filter { it.second > 0 }
                    .maxByOrNull { it.second }?.first ?: error("No BTSnoop file found")
                    zip.getInputStream(entry).use { input -> rawFile.outputStream().use { output ->
                        if (entry.name.substringAfterLast('/').startsWith("btsnooz_hci.log", true)) BtsnoozDecoder.decode(input, output)
                        else input.copyTo(output)
                    } }
            }
            extracted = true
            val directory = captureDirectory()
            val base = (target.ifBlank { "Bluetooth" }).replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val generated = File(directory, "${base}_$stamp.pcapng")
            output = generated
            var written = 0
            var total = 0
            val handles = mutableSetOf<Int>()
            var attPackets = 0
            rawFile.inputStream().use { input -> PcapngWriter(generated.outputStream()).use { writer ->
                val tracker = ConnectionTracker()
                for (record in BtsnoopReader.read(input)) {
                    val connection = tracker.connectionFor(record)
                    total++
                    connection?.let { handles += it.handle }
                    if (HciPacketClassifier.isAttPacket(record.packet)) attPackets++
                    if (PacketFilter.matches(record, connection, target)) { writer.write(record); written++ }
                }
            } }
            lastExportSummary = "Packets: $total; target packets: $written; connections: ${handles.size}; ATT packets: $attPackets"
            if (target.isNotBlank() && written == 0) error("No packets matched target; raw capture preserved for full export")
            PcapngValidator.validate(generated)
            if (saveRaw) rawFile.copyTo(File(directory, "${base}_$stamp.btsnoop"), overwrite = true)
            converted = true
            return generated.absolutePath
        } finally {
            if (extracted && !converted) raw?.copyTo(File(captureDirectory(), ".pending-${System.currentTimeMillis()}.btsnoop"), overwrite = true)
            if (converted) cleanupPendingCaptures()
            if (!converted) output?.delete()
            raw?.delete()
            bugreport?.delete()
            restoreCaptureEnvironment(previousMode, previousDefaultMode, propertyModeChanged, bluetoothInitiallyEnabled)
        }
    }

    override fun exportFullCapture(previousMode: String, previousDefaultMode: String, propertyModeChanged: Boolean, bluetoothInitiallyEnabled: Boolean): String {
        this.previousMode = previousMode
        this.previousDefaultMode = previousDefaultMode
        this.propertyModeChanged = propertyModeChanged
        this.bluetoothInitiallyEnabled = bluetoothInitiallyEnabled
        val directory = captureDirectory()
        val raw = directory.listFiles { file -> file.name.startsWith(".pending-") && file.name.endsWith(".btsnoop") }
            ?.maxByOrNull { it.lastModified() } ?: error("No pending raw capture is available")
        val output = File(directory, "Bluetooth_${System.currentTimeMillis()}.pcapng")
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
            lastExportSummary = "Packets: $total; target packets: $total; connections: ${tracker.connectionCount()}; ATT packets: $attPackets"
            PcapngValidator.validate(output)
            raw.delete()
            return output.absolutePath
        } finally {
            if (!output.isFile) output.delete()
            restoreCaptureEnvironment(previousMode, previousDefaultMode, propertyModeChanged, bluetoothInitiallyEnabled)
        }
    }

    override fun getLastExportSummary(): String = lastExportSummary

    private fun captureDirectory() = File("/sdcard/Download/BluetoothCaptures").apply { mkdirs() }
    private fun cleanupPendingCaptures() {
        captureDirectory().listFiles { file -> file.name.startsWith(".pending-") && file.name.endsWith(".btsnoop") }
            ?.forEach { it.delete() }
    }

    override fun restoreCaptureEnvironment(previousMode: String, previousDefaultMode: String, propertyModeChanged: Boolean, bluetoothInitiallyEnabled: Boolean) {
        // Capture assumes the user's Bluetooth snoop configuration is already enabled.
        // Never restart or otherwise change Bluetooth during cleanup.
    }

    override fun abortCapture() {
        val mode = previousMode ?: return
        restoreCaptureEnvironment(mode, previousDefaultMode ?: "null", propertyModeChanged, bluetoothInitiallyEnabled)
    }

    private fun score(name: String): Int = when {
        name.substringAfterLast('/').equals("btsnoop_hci.log", true) -> 1000
        name.substringAfterLast('/').startsWith("btsnoop_hci.log", true) -> 700
        name.substringAfterLast('/').startsWith("btsnooz_hci.log", true) -> 100
        else -> 0
    } + if ("/data/misc/bluetooth/logs/" in name || "/data/log/bt/" in name) 200 else 0

    private fun command(vararg args: String): String = commandLines(*args).joinToString("\n")
    private fun commandLines(vararg args: String): List<String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(process.inputStream)).readLines()
        check(process.waitFor() == 0) { "${args.first()} failed: ${lines.joinToString(" ")}" }
        return lines
    }
}
