package net.duhowpi.nobita.capture

import android.content.Context

enum class CaptureStage { CAPTURING, EXPORTING, EXPORT_PENDING }

data class CaptureSession(
    val startedAt: Long,
    val target: String,
    val previousMode: String = "disabled",
    val bluetoothInitiallyEnabled: Boolean = true,
    val exportPending: Boolean = false,
    val stage: CaptureStage = CaptureStage.CAPTURING,
) {
    fun state(): CaptureState = when (stage) {
        CaptureStage.CAPTURING -> CaptureState.Capturing(startedAt, target)
        CaptureStage.EXPORTING -> CaptureState.GeneratingBugreport
        CaptureStage.EXPORT_PENDING -> CaptureState.ExportPending
    }

    fun save(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        .putLong(KEY_STARTED, startedAt).putString(KEY_TARGET, target).putString(KEY_MODE, previousMode)
        .putBoolean(KEY_BLUETOOTH, bluetoothInitiallyEnabled)
        .putBoolean(KEY_EXPORT_PENDING, exportPending)
        .putString(KEY_STAGE, stage.name)
        .apply()

    companion object {
        private const val PREFERENCES = "capture_session"
        private const val KEY_STARTED = "started_at"
        private const val KEY_TARGET = "target"
        private const val KEY_MODE = "mode"
        private const val KEY_BLUETOOTH = "bluetooth_initially_enabled"
        private const val KEY_EXPORT_PENDING = "export_pending"
        private const val KEY_STAGE = "stage"
        fun load(context: Context): CaptureSession? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val startedAt = preferences.getLong(KEY_STARTED, 0)
            return if (startedAt == 0L) null else CaptureSession(
                startedAt, preferences.getString(KEY_TARGET, "") ?: "",
                preferences.getString(KEY_MODE, "disabled") ?: "disabled",
                preferences.getBoolean(KEY_BLUETOOTH, true),
                preferences.getBoolean(KEY_EXPORT_PENDING, false),
                runCatching { CaptureStage.valueOf(preferences.getString(KEY_STAGE, CaptureStage.CAPTURING.name)!!) }
                    .getOrDefault(CaptureStage.CAPTURING),
            )
        }
        fun clear(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
