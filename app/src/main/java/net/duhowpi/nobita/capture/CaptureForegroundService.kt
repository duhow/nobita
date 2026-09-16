package net.duhowpi.nobita.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
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
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(getString(R.string.capture_active))
        .setContentText(getString(R.string.capture_notification_text)).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, getString(R.string.stop_export), PendingIntent.getService(this, 1, Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build()
    companion object { private const val CHANNEL = "capture"; private const val ID = 42; const val ACTION_STOP = "net.duhowpi.nobita.STOP" }
}
