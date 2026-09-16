package net.duhowpi.nobita

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import rikka.shizuku.Shizuku

class OnboardingActivity : AppCompatActivity() {
    private lateinit var next: Button
    private lateinit var shizukuStatus: TextView
    private lateinit var bluetoothStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var batteryStatus: TextView
    private val shizukuPermissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == SHIZUKU_REQUEST) runOnUiThread { refresh() }
    }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getPreferences(MODE_PRIVATE).getBoolean(ONBOARDING_COMPLETE, false) && requiredAccessAvailable()) {
            openMain()
            return
        }
        setContentView(R.layout.activity_onboarding)
        next = findViewById(R.id.onboarding_next)
        shizukuStatus = findViewById(R.id.onboarding_shizuku_status)
        bluetoothStatus = findViewById(R.id.onboarding_bluetooth_status)
        notificationStatus = findViewById(R.id.onboarding_notification_status)
        batteryStatus = findViewById(R.id.onboarding_battery_status)
        findViewById<Button>(R.id.onboarding_shizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.onboarding_bluetooth).setOnClickListener { requestBluetooth() }
        findViewById<Button>(R.id.onboarding_notifications).setOnClickListener { requestNotifications() }
        findViewById<Button>(R.id.onboarding_battery).setOnClickListener { openBatterySettings() }
        next.setOnClickListener { getPreferences(MODE_PRIVATE).edit().putBoolean(ONBOARDING_COMPLETE, true).apply(); openMain() }
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResult)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::next.isInitialized) refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionResult)
        super.onDestroy()
    }

    private fun refresh() {
        val shizukuReady = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        val bluetoothReady = bluetoothPermissionsGranted()
        shizukuStatus.text = getString(if (shizukuReady) R.string.onboarding_available else R.string.onboarding_required)
        bluetoothStatus.text = getString(if (bluetoothReady) R.string.onboarding_available else R.string.onboarding_required)
        notificationStatus.text = getString(if (notificationPermissionGranted()) R.string.onboarding_available else R.string.onboarding_optional)
        batteryStatus.text = getString(if (batteryExempt()) R.string.onboarding_available else R.string.onboarding_optional)
        next.isEnabled = shizukuReady && bluetoothReady
    }

    private fun requestShizuku() {
        if (!Shizuku.pingBinder()) {
            try {
                startActivity(Intent("moe.shizuku.manager.action.MAIN_SETTINGS"))
            } catch (_: ActivityNotFoundException) {
                shizukuStatus.text = getString(R.string.onboarding_shizuku_missing)
            }
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(SHIZUKU_REQUEST)
        else refresh()
    }

    private fun requestBluetooth() {
        if (Build.VERSION.SDK_INT >= 31) ActivityCompat.requestPermissions(this, BLUETOOTH_PERMISSIONS, BLUETOOTH_REQUEST)
        else refresh()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (_: SecurityException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requiredAccessAvailable() = Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && bluetoothPermissionsGranted()

    private fun bluetoothPermissionsGranted() = Build.VERSION.SDK_INT < 31 ||
        BLUETOOTH_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun notificationPermissionGranted() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        private const val ONBOARDING_COMPLETE = "onboarding_complete"
        private const val SHIZUKU_REQUEST = 100
        private const val BLUETOOTH_REQUEST = 101
        private const val NOTIFICATION_REQUEST = 102
        private val BLUETOOTH_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
