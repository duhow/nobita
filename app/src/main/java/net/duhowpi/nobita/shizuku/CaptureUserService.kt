package net.duhowpi.nobita.shizuku

import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

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
        return "uid=${Process.myUid()} mode=full"
    }

    override fun exportBtsnoop(): String {
        val lines = commandLines("/system/bin/bugreportz", "-p")
        val path = lines.firstOrNull { it.startsWith("OK:") }?.removePrefix("OK:")?.trim()
            ?: error(lines.lastOrNull { it.startsWith("FAIL:") } ?: "bugreportz did not complete")
        val output = File.createTempFile("nobita-", ".btsnoop", File("/data/local/tmp"))
        ZipFile(path).use { zip ->
            val entry = zip.entries().asSequence().filter { !it.isDirectory }
                .map { it to score(it.name) }.filter { it.second > 0 }
                .maxByOrNull { it.second }?.first ?: error("No BTSnoop file found")
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        restoreCaptureEnvironment()
        File(path).delete()
        return output.absolutePath
    }

    override fun restoreCaptureEnvironment() {
        previousMode?.let { command("setprop", "persist.bluetooth.btsnooplogmode", it) }
        if (bluetoothInitiallyEnabled) restartBluetooth() else command("cmd", "bluetooth_manager", "disable")
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
