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
import dev.rikka.shizuku.Shizuku
import net.duhowpi.nobita.shizuku.CaptureUserService
import net.duhowpi.nobita.shizuku.ICaptureUserService
import androidx.core.app.NotificationCompat
import net.duhowpi.nobita.MainActivity
import net.duhowpi.nobita.R

class CaptureForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bluetooth capture", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(ID, notification())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) startExport()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun startExport() {
        val target = CaptureSession.load(this)?.target.orEmpty()
        if (!Shizuku.pingBinder()) { stopSelf(); return }
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nobita:export").apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
        try { Shizuku.bindUserService(userServiceArgs(), object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Thread {
                    try { ICaptureUserService.Stub.asInterface(binder).exportPcapng(target) }
                    finally {
                        if (wakeLock.isHeld) wakeLock.release()
                        Shizuku.unbindUserService(userServiceArgs(), this, true)
                        CaptureSession.clear(this@CaptureForegroundService)
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
            stopSelf()
        }
    }
    private fun userServiceArgs() = Shizuku.UserServiceArgs(ComponentName(this, CaptureUserService::class.java)).daemon(true).tag("bluetooth-capture").version(1)
    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(getString(R.string.capture_active))
        .setContentText(getString(R.string.capture_notification_text)).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, getString(R.string.stop_export), PendingIntent.getService(this, 1, Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build()
    companion object { private const val CHANNEL = "capture"; private const val ID = 42; const val ACTION_STOP = "net.duhowpi.nobita.STOP" }
}
