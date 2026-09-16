package net.duhowpi.nobita.capture

import android.content.Context

data class CaptureSession(val startedAt: Long, val target: String) {
    fun save(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        .putLong(KEY_STARTED, startedAt).putString(KEY_TARGET, target).apply()

    companion object {
        private const val PREFERENCES = "capture_session"
        private const val KEY_STARTED = "started_at"
        private const val KEY_TARGET = "target"
        fun load(context: Context): CaptureSession? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val startedAt = preferences.getLong(KEY_STARTED, 0)
            return if (startedAt == 0L) null else CaptureSession(startedAt, preferences.getString(KEY_TARGET, "") ?: "")
        }
        fun clear(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
