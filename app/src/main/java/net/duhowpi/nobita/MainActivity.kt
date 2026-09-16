package net.duhowpi.nobita

import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.content.pm.PackageManager
import org.json.JSONObject
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageButton
import android.net.Uri
import android.os.PowerManager
import android.util.TypedValue
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.app.AlertDialog
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import net.duhowpi.nobita.capture.CaptureForegroundService
import net.duhowpi.nobita.capture.CaptureSession
import net.duhowpi.nobita.capture.CaptureStage
import net.duhowpi.nobita.capture.CaptureState
import net.duhowpi.nobita.shizuku.CaptureUserService
import net.duhowpi.nobita.shizuku.ICaptureUserService
import net.duhowpi.nobita.export.CaptureFileStore
import net.duhowpi.nobita.export.CaptureDirectory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.io.File

class MainActivity : AppCompatActivity() {
    @Volatile private var userService: ICaptureUserService? = null
    private val userServiceLock = ReentrantLock()
    private val userServiceChanged = userServiceLock.newCondition()
    @Volatile private var userServiceBinding = false
    private var exportedUri: Uri? = null
    private var activeScan: Pair<BluetoothLeScanner, ScanCallback>? = null
    private var scanGeneration = 0
    private lateinit var status: TextView
    private lateinit var captureTopBar: LinearLayout
    private lateinit var captureTopStatus: TextView
    private val captureStatusHandler = Handler(Looper.getMainLooper())
    private val captureStatusUpdater = object : Runnable {
        override fun run() {
            val session = CaptureSession.load(this@MainActivity)
            val service = userService
            if (session?.stage == CaptureStage.CAPTURING && service != null) {
                val captureStatus = runCatching { service.getCaptureStatus() }.getOrNull()?.let(::parseCaptureStatus)
                if (captureStatus?.state == "CAPTURING" || captureStatus?.state == "INTERRUPTED") {
                    CaptureSession.load(this@MainActivity)?.copy(
                        bytesReceived = captureStatus.bytes,
                        lastDataAt = captureStatus.lastDataAt,
                    )?.save(this@MainActivity)
                    status.text = captureStatusText(captureStatus)
                }
                captureStatusHandler.postDelayed(this, 1_000)
            }
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { if (!isFinishing) bindUserService() }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { status.text = getString(R.string.capture_degraded) } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { status.text = "Shizuku authorized; ready to capture" }
            bindUserService()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userServiceLock.withLock {
                userService = ICaptureUserService.Stub.asInterface(service)
                userServiceBinding = false
                userServiceChanged.signalAll()
            }
            status.text = "Shizuku connected (uid checked on start)"
            Thread {
                val captureStatus = runCatching { userService?.getCaptureStatus() }.getOrNull()?.let(::parseCaptureStatus)
                if (captureStatus?.state == "CAPTURING" || captureStatus?.state == "INTERRUPTED") {
                    runOnUiThread {
                        status.text = captureStatusText(captureStatus)
                        if (CaptureSession.load(this@MainActivity) == null && captureStatus.captureId != null && captureStatus.startedAt > 0) {
                            CaptureSession(
                                captureStatus.startedAt,
                                captureStatus.target.orEmpty(),
                                captureStatus.captureId,
                                captureStatus.saveRaw,
                                bytesReceived = captureStatus.bytes,
                                lastDataAt = captureStatus.lastDataAt,
                            ).save(this@MainActivity)
                            showCaptureActive()
                            findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                            findViewById<Button>(R.id.stop_export).apply {
                                visibility = android.view.View.VISIBLE
                                isEnabled = true
                            }
                            startCaptureStatusPolling()
                        }
                    }
            }
            }.start()
            runCatching { CaptureDirectory.path(this@MainActivity)?.let { userService?.setCaptureDirectory(it) } }
            reconcilePendingSession()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            userServiceLock.withLock {
                userService = null
                userServiceBinding = false
                userServiceChanged.signalAll()
            }
            status.text = getString(R.string.shizuku_not_ready)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        captureTopBar = findViewById(R.id.capture_top_bar)
        captureTopStatus = findViewById(R.id.capture_top_status)
        requestNotifications()
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        findViewById<Button>(R.id.start_capture).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.stop_export).setOnClickListener { stopAndExport() }
        findViewById<Button>(R.id.export_full).setOnClickListener { exportFullCapture() }
        findViewById<ImageButton>(R.id.choose_paired).setOnClickListener { choosePairedDevice() }
        findViewById<ImageButton>(R.id.scan_nearby).setOnClickListener { scanNearby() }
        findViewById<ImageButton>(R.id.open_capture).setOnClickListener { openOrShare(false) }
        findViewById<ImageButton>(R.id.share_capture).setOnClickListener { openOrShare(true) }
        findViewById<ImageButton>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        CaptureSession.load(this)?.let { session ->
            findViewById<android.widget.EditText>(R.id.target).setText(session.target)
            val pending = session.exportPending || session.state() is CaptureState.ExportPending
            status.text = if (pending) "Capture export pending: choose full export" else "Capture active (${elapsed(session.startedAt)})"
            if (pending) showCaptureIdle(getString(R.string.capture_exporting_top)) else showCaptureActive()
            findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
            findViewById<Button>(R.id.stop_export).visibility = if (pending) android.view.View.GONE else android.view.View.VISIBLE
            findViewById<Button>(R.id.export_full).visibility = if (pending) android.view.View.VISIBLE else android.view.View.GONE
            if (!pending) startCaptureStatusPolling()
        }
        if (Shizuku.pingBinder()) bindUserService() else status.text = getString(R.string.shizuku_not_ready)
    }

    override fun onResume() {
        super.onResume()
        if (CaptureSession.load(this) == null) resetButtons()
    }

    override fun onDestroy() {
        captureStatusHandler.removeCallbacks(captureStatusUpdater)
        scanGeneration++
        activeScan?.let { (scanner, callback) ->
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                runCatching { scanner.stopScan(callback) }
            }
        }
        activeScan = null
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (userService != null || userServiceBinding) Shizuku.unbindUserService(userServiceArgs(), connection, false)
        super.onDestroy()
    }

    private fun startCapture() {
        if (!Shizuku.pingBinder()) { status.text = getString(R.string.shizuku_not_ready); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST); return
        }
        val target = findViewById<android.widget.EditText>(R.id.target).text.toString()
        val saveRaw = getSharedPreferences(SettingsActivity.PREFERENCES, MODE_PRIVATE).getBoolean(SettingsActivity.SAVE_RAW, false)
        Thread {
            var startedCaptureId: String? = null
            try {
                val result = withWakeLock { requireUserService().startCapture(target, saveRaw) }
                val captureSessionId = captureId(result)
                startedCaptureId = captureSessionId
                runOnUiThread {
                    CaptureSession(System.currentTimeMillis(), target, captureSessionId, saveRaw = saveRaw).save(this)
                    status.text = getString(R.string.capture_active)
                    showCaptureActive()
                    ContextCompat.startForegroundService(this, Intent(this, CaptureForegroundService::class.java))
                    findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                    findViewById<Button>(R.id.stop_export).apply {
                        visibility = android.view.View.VISIBLE
                        isEnabled = true
                    }
                    startCaptureStatusPolling()
                }
            } catch (error: Exception) {
                startedCaptureId?.let { captureId -> runCatching { userService?.abortCapture(captureId) } }
                runOnUiThread { CaptureSession.clear(this); status.text = error.message ?: "Capture preparation failed" }
            }
        }.start()
    }

    private fun stopAndExport() {
        val currentSession = CaptureSession.load(this)
        if (currentSession == null || currentSession.stage != CaptureStage.CAPTURING) return
        findViewById<Button>(R.id.stop_export).isEnabled = false
        currentSession.copy(stage = CaptureStage.EXPORTING).save(this)
        showCaptureIdle(getString(R.string.capture_exporting_top))
        status.text = getString(R.string.capture_exporting_top)
        startService(Intent(this, CaptureForegroundService::class.java).setAction(CaptureForegroundService.ACTION_EXPORTING))
        Thread {
            try {
                val session = CaptureSession.load(this) ?: error("No active capture session")
                val exported = withWakeLock {
                    val service = requireUserService()
                    val finished = AtomicBoolean(false)
                    val progress = Thread {
                        while (!finished.get()) {
                            runOnUiThread { status.text = "${getString(R.string.capture_exporting_top)}\n${service.getExportProgress()}" }
                            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                        }
                    }
                    progress.start()
                    try {
                    service.stopAndExport(session.captureId) to service.getLastExportSummary()
                    } finally {
                        finished.set(true)
                        progress.interrupt()
                    }
                }
                val uri = CaptureFileStore.importPcapng(this, exported.first)
                runOnUiThread {
                    CaptureSession.clear(this); exportedUri = uri
                    showCaptureComplete(exported.second)
                    status.text = "Capture complete: ${exported.second}\n${getString(R.string.capture_saved_file, File(exported.first).name)}"; stopService(Intent(this, CaptureForegroundService::class.java)); resetButtons()
                    findViewById<LinearLayout>(R.id.export_actions).visibility = android.view.View.VISIBLE
                }
            } catch (error: Exception) {
                val pending = runCatching { userService?.hasPendingCapture(CaptureSession.load(this)?.captureId.orEmpty()) == true }.getOrDefault(false)
                runOnUiThread {
                    status.text = error.message ?: "Capture finalization failed"
                    if (pending) {
                        showCaptureIdle(getString(R.string.capture_exporting_top))
                        CaptureSession.load(this)?.copy(exportPending = true, stage = CaptureStage.EXPORT_PENDING)?.save(this)
                        findViewById<Button>(R.id.export_full).visibility = android.view.View.VISIBLE
                        findViewById<Button>(R.id.export_full).isEnabled = true
                        findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                        findViewById<Button>(R.id.stop_export).visibility = android.view.View.GONE
                    } else {
                        CaptureSession.clear(this)
                        resetButtons()
                    }
                    stopService(Intent(this, CaptureForegroundService::class.java))
                }
            }
        }.start()
    }

    private fun exportFullCapture() {
        findViewById<Button>(R.id.export_full).isEnabled = false
        Thread {
            try {
                val session = CaptureSession.load(this) ?: error("No pending capture session")
                val exported = withWakeLock {
                    val service = requireUserService()
                    val finished = AtomicBoolean(false)
                    val progress = Thread {
                        while (!finished.get()) {
                            runOnUiThread { status.text = "${getString(R.string.capture_exporting_top)}\n${service.getExportProgress()}" }
                            try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                        }
                    }
                    progress.start()
                    try {
                        service.exportFullCapture(session.captureId) to service.getLastExportSummary()
                    } finally {
                        finished.set(true)
                        progress.interrupt()
                    }
                }
                val uri = CaptureFileStore.importPcapng(this, exported.first)
                runOnUiThread { CaptureSession.clear(this); exportedUri = uri; showCaptureComplete(exported.second); status.text = "Full capture complete: ${exported.second}\n${getString(R.string.capture_saved_file, File(exported.first).name)}"; findViewById<Button>(R.id.export_full).visibility = android.view.View.GONE; findViewById<LinearLayout>(R.id.export_actions).visibility = android.view.View.VISIBLE; resetButtons() }
            } catch (error: Exception) {
                val pending = runCatching { userService?.hasPendingCapture(CaptureSession.load(this)?.captureId.orEmpty()) == true }.getOrDefault(false)
                runOnUiThread {
                    status.text = error.message ?: "Full capture finalization failed"
                    if (!pending) {
                        CaptureSession.clear(this)
                        resetButtons()
                    } else {
                        findViewById<Button>(R.id.export_full).isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun resetButtons() {
        captureStatusHandler.removeCallbacks(captureStatusUpdater)
        findViewById<Button>(R.id.start_capture).visibility = android.view.View.VISIBLE
        findViewById<Button>(R.id.stop_export).apply {
            visibility = android.view.View.GONE
            isEnabled = true
        }
        findViewById<Button>(R.id.export_full).apply {
            visibility = android.view.View.GONE
            isEnabled = true
        }
    }
    private fun startCaptureStatusPolling() {
        captureStatusHandler.removeCallbacks(captureStatusUpdater)
        captureStatusHandler.post(captureStatusUpdater)
    }
    private fun parseCaptureStatus(value: String) = runCatching {
        JSONObject(value).takeIf { it.optInt("version") == 1 }?.let {
            CaptureStatusView(
                it.optString("state"),
                it.optLong("bytes"),
                it.optLong("lastDataAt"),
                it.optString("reason").takeUnless { reason -> reason.isBlank() || reason == "null" },
                it.optString("captureId").takeUnless { id -> id.isBlank() || id == "null" },
                it.optLong("startedAt"),
                it.optString("target").takeUnless { target -> target.isBlank() || target == "null" },
                it.optBoolean("saveRaw"),
            )
        }
    }.getOrNull()
    private fun captureStatusText(value: CaptureStatusView): String = if (value.state == "INTERRUPTED") {
        getString(R.string.capture_interrupted, value.reason ?: "unknown", value.bytes)
    } else {
        getString(R.string.capture_active_progress, value.bytes, if (value.lastDataAt > 0) ", last data ${value.lastDataAt}" else "")
    }
    private data class CaptureStatusView(
        val state: String,
        val bytes: Long,
        val lastDataAt: Long,
        val reason: String?,
        val captureId: String?,
        val startedAt: Long,
        val target: String?,
        val saveRaw: Boolean,
    )
    private fun showCaptureActive() {
        captureTopBar.setBackgroundColor(ContextCompat.getColor(this, R.color.capture_active_blue))
        captureTopStatus.setTextColor(android.graphics.Color.WHITE)
        captureTopStatus.text = getString(R.string.capture_packets_pending)
    }
    private fun showCaptureIdle(message: String) {
        captureTopBar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val color = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, color, true)
        if (color.resourceId != 0) ContextCompat.getColorStateList(this, color.resourceId)?.let { captureTopStatus.setTextColor(it) }
        else captureTopStatus.setTextColor(color.data)
        captureTopStatus.text = message
    }
    private fun showCaptureComplete(summary: String) {
        val packets = Regex("packets: (\\d+)", RegexOption.IGNORE_CASE).find(summary)?.groupValues?.get(1)?.toIntOrNull()
        showCaptureIdle(getString(R.string.capture_complete_top) + if (packets != null) " — " + getString(R.string.capture_packets_count, packets) else "")
    }
    private fun openOrShare(share: Boolean) {
        val uri = exportedUri ?: return
        val intent = if (share) Intent(Intent.ACTION_SEND).apply { type = "application/vnd.tcpdump.pcap"; putExtra(Intent.EXTRA_STREAM, uri) }
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.tcpdump.pcap")
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
        userServiceLock.withLock {
            if (userService != null || userServiceBinding) return
            userServiceBinding = true
        }
        try {
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (error: SecurityException) {
            userServiceLock.withLock {
                userService = null
                userServiceBinding = false
                userServiceChanged.signalAll()
            }
            status.text = "Shizuku authorization is required before connecting"
        }
    }
    private fun requireUserService(): ICaptureUserService {
        userService?.let {
            it.setCaptureDirectory(CaptureDirectory.path(this@MainActivity) ?: error("Capture folder is not configured"))
            return it
        }
        check(Shizuku.pingBinder()) { "Shizuku is not running" }
        bindUserService()
        val deadline = System.nanoTime() + 15_000_000_000L
        userServiceLock.withLock {
            while (userService == null) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) break
                userServiceChanged.awaitNanos(remainingNanos)
            }
            userService?.let {
                it.setCaptureDirectory(CaptureDirectory.path(this@MainActivity) ?: error("Capture folder is not configured"))
                return it
            }
        }
        error("Shizuku UserService is not connected")
    }
    private fun reconcilePendingSession() {
        val session = CaptureSession.load(this) ?: return
        val service = userService ?: return
        Thread {
            val authoritative = runCatching { service.getCaptureStatus() }.getOrNull()?.let(::parseCaptureStatus)
            val pending = runCatching { service.hasPendingCapture(session.captureId) }.getOrDefault(true)
            when {
                session.stage == CaptureStage.CAPTURING && authoritative?.state == "CAPTURING" -> startCaptureStatusPolling()
                session.stage == CaptureStage.CAPTURING && authoritative?.state in setOf("IDLE", "COMPLETED") && pending -> runOnUiThread {
                    CaptureSession.clear(this)
                    session.copy(exportPending = true, stage = CaptureStage.EXPORT_PENDING).save(this)
                    showCaptureIdle(getString(R.string.capture_exporting_top))
                    status.text = getString(R.string.capture_recovered)
                    findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                    findViewById<Button>(R.id.stop_export).visibility = android.view.View.GONE
                    findViewById<Button>(R.id.export_full).visibility = android.view.View.VISIBLE
                }
                session.state() is CaptureState.ExportPending && !pending -> runOnUiThread {
                    CaptureSession.clear(this)
                    resetButtons()
                    showCaptureIdle(getString(R.string.capture_packets_pending))
                    status.text = getString(R.string.no_pending_capture)
                }
            }
        }.start()
    }
    private fun userServiceArgs() = Shizuku.UserServiceArgs(ComponentName(this, CaptureUserService::class.java))
        .daemon(true).tag("bluetooth-capture").version(USER_SERVICE_VERSION).processNameSuffix("capture")
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
    private fun scanNearby() {
        if (activeScan != null) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 22)
            return
        }
        if (Build.VERSION.SDK_INT < 31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 23)
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run { showScanComplete(0); return }
        val found = linkedMapOf<String, BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) { found[result.device.address] = result.device }
            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    if (activeScan?.second === this) {
                        activeScan = null
                        showScanFailed(errorCode)
                    }
                }
            }
        }
        val generation = ++scanGeneration
        val scanStatus = findViewById<TextView>(R.id.scan_status)
        val scanButton = findViewById<ImageButton>(R.id.scan_nearby)
        val scanIndicator = findViewById<android.widget.ImageView>(R.id.scan_indicator)
        scanStatus.text = getString(R.string.scan_in_progress)
        scanButton.isEnabled = false
        scanIndicator.visibility = android.view.View.VISIBLE
        scanIndicator.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate))
        activeScan = scanner to callback
        try {
            scanner.startScan(callback)
        } catch (_: SecurityException) {
            activeScan = null
            scanButton.isEnabled = true
            showScanFailed(-1)
            return
        } catch (_: IllegalStateException) {
            activeScan = null
            scanButton.isEnabled = true
            showScanFailed(-1)
            return
        }
        android.os.Handler(mainLooper).postDelayed({
            if (generation != scanGeneration || activeScan?.second !== callback) return@postDelayed
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                runCatching { scanner.stopScan(callback) }
            }
            activeScan = null
            val devices = found.values.sortedBy { it.name ?: it.address }
            runOnUiThread {
                showScanComplete(devices.size)
                if (devices.isEmpty()) return@runOnUiThread
                AlertDialog.Builder(this).setTitle(R.string.scan_nearby)
                    .setItems(devices.map { "${it.name ?: "Unknown device"}\n${it.address}" }.toTypedArray()) { _, which -> findViewById<android.widget.EditText>(R.id.target).setText(devices[which].address) }.show()
            }
        }, 8_000)
    }
    private fun showScanComplete(count: Int) {
        findViewById<ImageButton>(R.id.scan_nearby).isEnabled = true
        val indicator = findViewById<android.widget.ImageView>(R.id.scan_indicator)
        indicator.clearAnimation()
        indicator.visibility = android.view.View.GONE
        findViewById<TextView>(R.id.scan_status).text = resources.getQuantityString(R.plurals.nearby_devices_found, count, count)
    }
    private fun showScanFailed(errorCode: Int) {
        findViewById<ImageButton>(R.id.scan_nearby).isEnabled = true
        val indicator = findViewById<android.widget.ImageView>(R.id.scan_indicator)
        indicator.clearAnimation()
        indicator.visibility = android.view.View.GONE
        findViewById<TextView>(R.id.scan_status).text = getString(R.string.scan_failed, errorCode)
    }
    private fun elapsed(startedAt: Long): String = "${((System.currentTimeMillis() - startedAt) / 1000)}s"
    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter
    private fun <T> withWakeLock(block: () -> T): T {
        val power = getSystemService(PowerManager::class.java)
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nobita:capture").apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
        return try { block() } finally { if (lock.isHeld) lock.release() }
    }

    private fun captureId(result: String): String = JSONObject(result).let {
        check(it.optInt("version") == 1 && it.optString("state") == "CAPTURING") { "Invalid capture start response" }
        it.getString("captureId")
    }

    companion object { private const val SHIZUKU_REQUEST = 100; private const val USER_SERVICE_VERSION = 10 }
}
