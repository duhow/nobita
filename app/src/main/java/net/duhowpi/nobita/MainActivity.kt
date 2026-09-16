package net.duhowpi.nobita

import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import android.content.pm.PackageManager
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.net.Uri
import android.os.PowerManager
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.app.AlertDialog
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import net.duhowpi.nobita.capture.CaptureForegroundService
import net.duhowpi.nobita.capture.CaptureSession
import net.duhowpi.nobita.capture.TargetHistory
import net.duhowpi.nobita.shizuku.CaptureUserService
import net.duhowpi.nobita.shizuku.ICaptureUserService
import net.duhowpi.nobita.export.CaptureFileStore

class MainActivity : AppCompatActivity() {
    @Volatile private var userService: ICaptureUserService? = null
    private var exportedUri: Uri? = null
    private lateinit var status: TextView
    private val binderReceived = Shizuku.OnBinderReceivedListener { if (!isFinishing) bindUserService() }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { status.text = "Capture degraded: Shizuku stopped. Bluetooth logging may still be active; open Shizuku before exporting." } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { status.text = "Shizuku authorized; ready to capture" }
            bindUserService()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = ICaptureUserService.Stub.asInterface(service)
            status.text = "Shizuku connected (uid checked on start)"
        }
        override fun onServiceDisconnected(name: ComponentName?) { userService = null; status.text = getString(R.string.shizuku_not_ready) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        requestNotifications()
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        findViewById<Button>(R.id.start_capture).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.stop_export).setOnClickListener { stopAndExport() }
        findViewById<Button>(R.id.export_full).setOnClickListener { exportFullCapture() }
        findViewById<Button>(R.id.choose_paired).setOnClickListener { choosePairedDevice() }
        findViewById<Button>(R.id.scan_nearby).setOnClickListener { scanNearby() }
        findViewById<Button>(R.id.battery_settings).setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        findViewById<Button>(R.id.choose_recent).setOnClickListener { chooseRecentTarget() }
        findViewById<Button>(R.id.open_capture).setOnClickListener { openOrShare(false) }
        findViewById<Button>(R.id.share_capture).setOnClickListener { openOrShare(true) }
        CaptureSession.load(this)?.let { session ->
            findViewById<android.widget.EditText>(R.id.target).setText(session.target)
            status.text = if (session.exportPending) "Capture export pending: choose full export" else "Capture active (${elapsed(session.startedAt)})"
            findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
            findViewById<Button>(R.id.stop_export).visibility = if (session.exportPending) android.view.View.GONE else android.view.View.VISIBLE
            findViewById<Button>(R.id.export_full).visibility = if (session.exportPending) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (Shizuku.pingBinder()) bindUserService() else status.text = getString(R.string.shizuku_not_ready)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (userService != null) Shizuku.unbindUserService(userServiceArgs(), connection, false)
        super.onDestroy()
    }

    private fun startCapture() {
        if (!Shizuku.pingBinder()) { status.text = getString(R.string.shizuku_not_ready); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST); return
        }
        Thread {
            try {
                val result = withWakeLock { requireUserService().prepareCapture() }
                runOnUiThread {
                    val environment = result.substringAfter("previous=", "disabled").substringBefore(" initialBluetooth=")
                    val initialBluetooth = result.substringAfter("initialBluetooth=", "true").toBoolean()
                    val target = findViewById<android.widget.EditText>(R.id.target).text.toString()
                    TargetHistory.add(this, target)
                    CaptureSession(System.currentTimeMillis(), target, environment, initialBluetooth).save(this)
                    status.text = "Capture active: $result"
                    ContextCompat.startForegroundService(this, Intent(this, CaptureForegroundService::class.java))
                    findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                    findViewById<Button>(R.id.stop_export).visibility = android.view.View.VISIBLE
                }
            } catch (error: Exception) {
                runCatching { userService?.abortCapture() }
                runOnUiThread { CaptureSession.clear(this); status.text = error.message ?: "Capture preparation failed" }
            }
        }.start()
    }

    private fun stopAndExport() {
        val target = findViewById<android.widget.EditText>(R.id.target).text.toString()
        val saveRaw = findViewById<android.widget.CheckBox>(R.id.save_raw).isChecked
        Thread {
            try {
                val session = CaptureSession.load(this) ?: error("No active capture session")
                val exported = withWakeLock {
                    val service = requireUserService()
                    service.exportPcapng(target, saveRaw, session.previousMode, session.bluetoothInitiallyEnabled) to service.getLastExportSummary()
                }
                val uri = CaptureFileStore.importPcapng(this, exported.first)
                runOnUiThread {
                    CaptureSession.clear(this); exportedUri = uri
                    status.text = "Capture complete: ${exported.second}\nPCAPNG exported: ${exported.first}"; stopService(Intent(this, CaptureForegroundService::class.java)); resetButtons()
                    findViewById<LinearLayout>(R.id.export_actions).visibility = android.view.View.VISIBLE
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = error.message ?: "Export failed"
                    CaptureSession.load(this)?.copy(exportPending = true)?.save(this)
                    findViewById<Button>(R.id.export_full).visibility = android.view.View.VISIBLE
                    findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                    findViewById<Button>(R.id.stop_export).visibility = android.view.View.GONE
                    stopService(Intent(this, CaptureForegroundService::class.java))
                }
            }
        }.start()
    }

    private fun exportFullCapture() {
        Thread {
            try {
                val session = CaptureSession.load(this) ?: error("No pending capture session")
                val exported = withWakeLock {
                    val service = requireUserService()
                    service.exportFullCapture(session.previousMode, session.bluetoothInitiallyEnabled) to service.getLastExportSummary()
                }
                val uri = CaptureFileStore.importPcapng(this, exported.first)
                runOnUiThread { CaptureSession.clear(this); exportedUri = uri; status.text = "Full capture complete: ${exported.second}"; findViewById<Button>(R.id.export_full).visibility = android.view.View.GONE; findViewById<LinearLayout>(R.id.export_actions).visibility = android.view.View.VISIBLE; resetButtons() }
            } catch (error: Exception) { runOnUiThread { status.text = error.message ?: "Full export failed" } }
        }.start()
    }

    private fun resetButtons() {
        findViewById<Button>(R.id.start_capture).visibility = android.view.View.VISIBLE
        findViewById<Button>(R.id.stop_export).visibility = android.view.View.GONE
    }
    private fun openOrShare(share: Boolean) {
        val uri = exportedUri ?: return
        val intent = if (share) Intent(Intent.ACTION_SEND).apply { type = "application/vnd.tcpdump.pcap"; putExtra(Intent.EXTRA_STREAM, uri) }
            else Intent(Intent.ACTION_VIEW).apply { type = "application/vnd.tcpdump.pcap"; setData(uri) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, if (share) getString(R.string.share_capture) else getString(R.string.open_capture)))
    }

    private fun bindUserService() {
        if (!Shizuku.pingBinder()) {
            status.text = getString(R.string.shizuku_not_ready)
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            status.text = "Shizuku authorization is required before connecting"
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (error: SecurityException) {
            userService = null
            status.text = "Shizuku authorization is required before connecting"
        }
    }
    private fun requireUserService(): ICaptureUserService {
        userService?.let { return it }
        check(Shizuku.pingBinder()) { "Shizuku is not running" }
        bindUserService()
        repeat(20) {
            userService?.let { return it }
            Thread.sleep(250)
        }
        error("Shizuku UserService is not connected")
    }
    private fun userServiceArgs() = Shizuku.UserServiceArgs(ComponentName(this, CaptureUserService::class.java))
        .daemon(true).tag("bluetooth-capture").version(1).processNameSuffix("capture")
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 20)
        }
    }
    private fun choosePairedDevice() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 21)
            return
        }
        val devices = bluetoothAdapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty()
        if (devices.isEmpty()) { status.text = getString(R.string.no_paired_devices); return }
        val labels = devices.map { "${it.name ?: "Unknown device"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.choose_paired).setItems(labels) { _, which ->
            findViewById<android.widget.EditText>(R.id.target).setText(devices[which].address)
        }.show()
    }
    private fun chooseRecentTarget() {
        val targets = TargetHistory.list(this)
        if (targets.isEmpty()) { status.text = getString(R.string.no_recent_targets); return }
        AlertDialog.Builder(this).setTitle(R.string.choose_recent).setItems(targets.toTypedArray()) { _, which -> findViewById<android.widget.EditText>(R.id.target).setText(targets[which]) }.show()
    }
    private fun scanNearby() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 22)
            return
        }
        if (Build.VERSION.SDK_INT < 31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 23)
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run { status.text = getString(R.string.no_nearby_devices); return }
        val found = linkedMapOf<String, BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) { found[result.device.address] = result.device }
            override fun onScanFailed(errorCode: Int) { runOnUiThread { status.text = "Bluetooth scan failed: $errorCode" } }
        }
        status.text = "Scanning nearby devices..."
        scanner.startScan(callback)
        android.os.Handler(mainLooper).postDelayed({
            scanner.stopScan(callback)
            val devices = found.values.sortedBy { it.name ?: it.address }
            runOnUiThread {
                if (devices.isEmpty()) { status.text = getString(R.string.no_nearby_devices); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle(R.string.scan_nearby)
                    .setItems(devices.map { "${it.name ?: "Unknown device"}\n${it.address}" }.toTypedArray()) { _, which -> findViewById<android.widget.EditText>(R.id.target).setText(devices[which].address) }.show()
            }
        }, 8_000)
    }
    private fun elapsed(startedAt: Long): String = "${((System.currentTimeMillis() - startedAt) / 1000)}s"
    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter
    private fun <T> withWakeLock(block: () -> T): T {
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nobita:capture").apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
        return try { block() } finally { if (lock.isHeld) lock.release() }
    }

    companion object { private const val SHIZUKU_REQUEST = 100 }
}
