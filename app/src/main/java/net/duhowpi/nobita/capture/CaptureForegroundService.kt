package net.duhowpi.nobita.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import net.duhowpi.nobita.shizuku.CaptureUserService
import net.duhowpi.nobita.shizuku.ICaptureUserService
import androidx.core.app.NotificationCompat
import net.duhowpi.nobita.MainActivity
import net.duhowpi.nobita.R
import net.duhowpi.nobita.export.CaptureFileStore
import java.util.concurrent.atomic.AtomicBoolean

class CaptureForegroundService : Service() {
    private val binderDead = Shizuku.OnBinderDeadListener {
        updateNotification(getString(R.string.capture_degraded))
    }
    @Volatile private var exportStarted = false
    @Volatile private var exportInProgress = false
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationUpdater = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(ID, notification())
            notificationHandler.postDelayed(this, 1_000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bluetooth capture", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(ID, notification())
        Shizuku.addBinderDeadListener(binderDead)
        notificationHandler.postDelayed(notificationUpdater, 1_000)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> startExport()
            ACTION_EXPORTING -> {
                exportInProgress = true
                notificationHandler.removeCallbacks(notificationUpdater)
                updateNotification(getString(R.string.capture_exporting_top))
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        notificationHandler.removeCallbacks(notificationUpdater)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }
    private fun startExport() {
        synchronized(this) {
            if (exportStarted) return
            exportStarted = true
        }
        exportInProgress = true
        notificationHandler.removeCallbacks(notificationUpdater)
        val session = CaptureSession.load(this) ?: run { stopSelf(); return }
        session.copy(stage = CaptureStage.EXPORTING).save(this)
        val target = session.target
        if (!Shizuku.pingBinder()) { stopSelf(); return }
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nobita:export").apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
        try { Shizuku.bindUserService(userServiceArgs(), object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Thread {
                    var success = false
                    var service: ICaptureUserService? = null
                    try {
                        val boundService = ICaptureUserService.Stub.asInterface(binder)
                        service = boundService
                        val exportCompleted = AtomicBoolean(false)
                        val progress = Thread {
                            while (!exportCompleted.get()) {
                                updateNotification(boundService.getExportProgress())
                                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                            }
                        }
                        progress.start()
                        val path = try {
                            boundService.stopAndExport()
                        } finally {
                            exportCompleted.set(true)
                            progress.interrupt()
                        }
                        CaptureFileStore.importPcapng(this@CaptureForegroundService, path)
                        success = true
                        updateNotification("${boundService.getLastExportSummary()} — exported to Downloads/BluetoothCaptures")
                    } catch (error: Exception) {
                        if (runCatching { service?.hasPendingCapture() == true }.getOrDefault(false)) {
                            CaptureSession.load(this@CaptureForegroundService)?.copy(exportPending = true, stage = CaptureStage.EXPORT_PENDING)?.save(this@CaptureForegroundService)
                        } else {
                            CaptureSession.clear(this@CaptureForegroundService)
                        }
                        updateNotification("Capture export failed: ${error.message ?: "unknown error"}")
                    }
                    finally {
                        if (wakeLock.isHeld) wakeLock.release()
                        Shizuku.unbindUserService(userServiceArgs(), this, true)
                        if (success) CaptureSession.clear(this@CaptureForegroundService)
                        stopSelf()
                    }
                }.start()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (wakeLock.isHeld) wakeLock.release()
                stopSelf()
            }
        }) } catch (error: RuntimeException) {
            if (wakeLock.isHeld) wakeLock.release()
            CaptureSession.clear(this)
            stopSelf()
        }
    }
    private fun userServiceArgs() = Shizuku.UserServiceArgs(ComponentName(this, CaptureUserService::class.java))
        .daemon(true).tag("bluetooth-capture").version(USER_SERVICE_VERSION).processNameSuffix("capture")
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(getString(if (exportInProgress) R.string.capture_exporting_top else R.string.capture_active))
            .setContentText(text).setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
    }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(getString(R.string.capture_active))
        .setContentText(captureText()).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .apply {
            if (!exportInProgress) addAction(0, getString(R.string.stop_export), PendingIntent.getService(this@CaptureForegroundService, 1, Intent(this@CaptureForegroundService, CaptureForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        }.build()
    private fun captureText(): String {
        val session = CaptureSession.load(this) ?: return getString(R.string.capture_notification_text)
        val target = session.target.ifBlank { "All devices" }
        val seconds = ((System.currentTimeMillis() - session.startedAt) / 1000).coerceAtLeast(0)
        return "Target: $target • ${seconds / 60}m ${seconds % 60}s"
    }
    companion object { private const val CHANNEL = "capture"; private const val ID = 42; private const val USER_SERVICE_VERSION = 5; const val ACTION_STOP = "net.duhowpi.nobita.STOP"; const val ACTION_EXPORTING = "net.duhowpi.nobita.EXPORTING" }
}
