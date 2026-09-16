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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.rikka.shizuku.Shizuku
import net.duhowpi.nobita.capture.CaptureForegroundService
import net.duhowpi.nobita.shizuku.CaptureUserService
import net.duhowpi.nobita.shizuku.ICaptureUserService

class MainActivity : AppCompatActivity() {
    private var userService: ICaptureUserService? = null
    private lateinit var status: TextView
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
        findViewById<Button>(R.id.start_capture).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.stop_export).setOnClickListener { stopAndExport() }
        if (Shizuku.pingBinder()) bindUserService() else status.text = getString(R.string.shizuku_not_ready)
    }

    override fun onDestroy() {
        if (userService != null) Shizuku.unbindUserService(userServiceArgs(), connection, true)
        super.onDestroy()
    }

    private fun startCapture() {
        if (!Shizuku.pingBinder()) { status.text = getString(R.string.shizuku_not_ready); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQUEST); return
        }
        Thread {
            try {
                val result = userService?.prepareCapture() ?: error("Shizuku UserService is not connected")
                runOnUiThread {
                    status.text = "Capture active: $result"
                    ContextCompat.startForegroundService(this, Intent(this, CaptureForegroundService::class.java))
                    findViewById<Button>(R.id.start_capture).visibility = android.view.View.GONE
                    findViewById<Button>(R.id.stop_export).visibility = android.view.View.VISIBLE
                }
            } catch (error: Exception) { runOnUiThread { status.text = error.message ?: "Capture preparation failed" } }
        }.start()
    }

    private fun stopAndExport() {
        Thread {
            try {
                val target = findViewById<android.widget.EditText>(R.id.target).text.toString()
                val path = userService?.exportPcapng(target) ?: error("Shizuku UserService is not connected")
                runOnUiThread { status.text = "BTSnoop extracted: $path"; stopService(Intent(this, CaptureForegroundService::class.java)); resetButtons() }
            } catch (error: Exception) { runOnUiThread { status.text = error.message ?: "Export failed"; resetButtons() } }
        }.start()
    }

    private fun resetButtons() {
        findViewById<Button>(R.id.start_capture).visibility = android.view.View.VISIBLE
        findViewById<Button>(R.id.stop_export).visibility = android.view.View.GONE
    }

    private fun bindUserService() { Shizuku.bindUserService(userServiceArgs(), connection) }
    private fun userServiceArgs() = Shizuku.UserServiceArgs(ComponentName(this, CaptureUserService::class.java)).daemon(true).tag("bluetooth-capture").version(1)
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), 20)
        }
    }

    companion object { private const val SHIZUKU_REQUEST = 100 }
}
