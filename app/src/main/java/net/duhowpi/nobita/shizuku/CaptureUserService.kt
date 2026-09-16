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
import net.duhowpi.nobita.hci.ConnectionTracker
import net.duhowpi.nobita.hci.PacketFilter
import net.duhowpi.nobita.pcapng.PcapngWriter
import net.duhowpi.nobita.pcapng.PcapngValidator

@Keep
class CaptureUserService : ICaptureUserService.Stub() {
    private var previousMode: String? = null
    private var bluetoothInitiallyEnabled = false

    @Keep
    fun destroy() {
        System.exit(0)
    }

    override fun prepareCapture(): String {
        check(Process.myUid() == 2000 || Process.myUid() == 0) { "Unexpected UserService UID: ${Process.myUid()}" }
        previousMode = command("getprop", "persist.bluetooth.btsnooplogmode").trim().ifEmpty { "disabled" }
        bluetoothInitiallyEnabled = command("settings", "get", "global", "bluetooth_on").trim() == "1"
        command("setprop", "persist.bluetooth.btsnooplogmode", "full")
        check(command("getprop", "persist.bluetooth.btsnooplogmode").trim() == "full") { "Bluetooth snoop mode was rejected" }
        if (bluetoothInitiallyEnabled) restartBluetooth()
        return "uid=${Process.myUid()} mode=full previous=$previousMode initialBluetooth=$bluetoothInitiallyEnabled"
    }

    override fun exportPcapng(target: String, saveRaw: Boolean, previousMode: String, bluetoothInitiallyEnabled: Boolean): String {
        this.previousMode = previousMode
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
                zip.getInputStream(entry).use { input -> rawFile.outputStream().use { input.copyTo(it) } }
            }
            extracted = true
            val directory = captureDirectory()
            val base = (target.ifBlank { "Bluetooth" }).replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val generated = File(directory, "${base}_$stamp.pcapng")
            output = generated
            var written = 0
            rawFile.inputStream().use { input -> PcapngWriter(generated.outputStream()).use { writer ->
                val tracker = ConnectionTracker()
                for (record in BtsnoopReader.read(input)) {
                    val connection = tracker.connectionFor(record)
                    if (PacketFilter.matches(record, connection, target)) { writer.write(record); written++ }
                }
            } }
            if (target.isNotBlank() && written == 0) error("No packets matched target; raw capture preserved for full export")
            PcapngValidator.validate(generated)
            if (saveRaw) rawFile.copyTo(File(directory, "${base}_$stamp.btsnoop"), overwrite = true)
            converted = true
            return generated.absolutePath
        } finally {
            if (extracted && !converted) raw?.copyTo(File(captureDirectory(), ".pending-${System.currentTimeMillis()}.btsnoop"), overwrite = true)
            if (!converted) output?.delete()
            raw?.delete()
            bugreport?.delete()
            restoreCaptureEnvironment(previousMode, bluetoothInitiallyEnabled)
        }
    }

    override fun exportFullCapture(previousMode: String, bluetoothInitiallyEnabled: Boolean): String {
        this.previousMode = previousMode
        this.bluetoothInitiallyEnabled = bluetoothInitiallyEnabled
        val directory = captureDirectory()
        val raw = directory.listFiles { file -> file.name.startsWith(".pending-") && file.name.endsWith(".btsnoop") }
            ?.maxByOrNull { it.lastModified() } ?: error("No pending raw capture is available")
        val output = File(directory, "Bluetooth_${System.currentTimeMillis()}.pcapng")
        try {
            raw.inputStream().use { input -> PcapngWriter(output.outputStream()).use { writer ->
                BtsnoopReader.read(input).forEach(writer::write)
            } }
            PcapngValidator.validate(output)
            raw.delete()
            return output.absolutePath
        } finally {
            if (!output.isFile) output.delete()
            restoreCaptureEnvironment(previousMode, bluetoothInitiallyEnabled)
        }
    }

    private fun captureDirectory() = File("/sdcard/Download/BluetoothCaptures").apply { mkdirs() }

    override fun restoreCaptureEnvironment(previousMode: String, bluetoothInitiallyEnabled: Boolean) {
        command("setprop", "persist.bluetooth.btsnooplogmode", previousMode)
        if (bluetoothInitiallyEnabled) restartBluetooth() else command("cmd", "bluetooth_manager", "disable")
    }

    override fun abortCapture() {
        val mode = previousMode ?: return
        restoreCaptureEnvironment(mode, bluetoothInitiallyEnabled)
    }

    private fun restartBluetooth() {
        command("cmd", "bluetooth_manager", "disable")
        command("cmd", "bluetooth_manager", "wait-for-state:STATE_OFF")
        command("cmd", "bluetooth_manager", "enable")
        command("cmd", "bluetooth_manager", "wait-for-state:STATE_ON")
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
