package net.duhowpi.nobita.shizuku

import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.duhowpi.nobita.btsnoop.BtsnoopReader
import net.duhowpi.nobita.hci.ConnectionTracker
import net.duhowpi.nobita.hci.PacketFilter
import net.duhowpi.nobita.pcapng.PcapngWriter
import net.duhowpi.nobita.pcapng.PcapngValidator

class CaptureUserService : ICaptureUserService.Stub() {
    private var previousMode: String? = null
    private var bluetoothInitiallyEnabled = false

    override fun prepareCapture(): String {
        check(Process.myUid() == 2000 || Process.myUid() == 0) { "Unexpected UserService UID: ${Process.myUid()}" }
        previousMode = command("getprop", "persist.bluetooth.btsnooplogmode").trim().ifEmpty { "disabled" }
        bluetoothInitiallyEnabled = command("settings", "get", "global", "bluetooth_on").trim() == "1"
        command("setprop", "persist.bluetooth.btsnooplogmode", "full")
        check(command("getprop", "persist.bluetooth.btsnooplogmode").trim() == "full") { "Bluetooth snoop mode was rejected" }
        restartBluetooth()
        return "uid=${Process.myUid()} mode=full previous=$previousMode initialBluetooth=$bluetoothInitiallyEnabled"
    }

    override fun exportPcapng(target: String, saveRaw: Boolean, previousMode: String, bluetoothInitiallyEnabled: Boolean): String {
        this.previousMode = previousMode
        this.bluetoothInitiallyEnabled = bluetoothInitiallyEnabled
        val lines = commandLines("/system/bin/bugreportz", "-p")
        val path = lines.firstOrNull { it.startsWith("OK:") }?.removePrefix("OK:")?.trim()
            ?: error(lines.lastOrNull { it.startsWith("FAIL:") } ?: "bugreportz did not complete")
        val raw = File.createTempFile("nobita-", ".btsnoop", File("/data/local/tmp"))
        try {
            ZipFile(path).use { zip ->
                val entry = zip.entries().asSequence().filter { !it.isDirectory }
                    .map { it to score(it.name) }.filter { it.second > 0 }
                    .maxByOrNull { it.second }?.first ?: error("No BTSnoop file found")
                zip.getInputStream(entry).use { input -> raw.outputStream().use { input.copyTo(it) } }
            }
            File(path).delete()
            val directory = File("/sdcard/Download/BluetoothCaptures").apply { mkdirs() }
            val base = (target.ifBlank { "Bluetooth" }).replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val output = File(directory, "${base}_$stamp.pcapng")
            raw.inputStream().use { input -> PcapngWriter(output.outputStream()).use { writer ->
                val tracker = ConnectionTracker()
                for (record in BtsnoopReader.read(input)) {
                    val connection = tracker.connectionFor(record)
                    if (PacketFilter.matches(record, connection, target)) writer.write(record)
                }
            } }
            PcapngValidator.validate(output)
            if (saveRaw) raw.copyTo(File(directory, "${base}_$stamp.btsnoop"), overwrite = true)
            return output.absolutePath
        } finally {
            raw.delete()
            restoreCaptureEnvironment(previousMode, bluetoothInitiallyEnabled)
        }
    }

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
