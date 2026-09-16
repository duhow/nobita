package net.duhowpi.nobita

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<SwitchCompat>(R.id.save_raw_switch).apply {
            isChecked = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(SAVE_RAW, false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(SAVE_RAW, checked).apply()
            }
        }
    }

    companion object {
        const val PREFERENCES = "capture_settings"
        const val SAVE_RAW = "save_raw"
    }
}
