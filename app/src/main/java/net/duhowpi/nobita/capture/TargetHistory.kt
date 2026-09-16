package net.duhowpi.nobita.capture

import android.content.Context

object TargetHistory {
    private const val PREFERENCES = "target_history"
    private const val KEY_TARGETS = "targets"
    private const val SEPARATOR = "\u0000"
    fun add(context: Context, target: String) {
        if (target.isBlank()) return
        val values = (list(context).filterNot { it.equals(target, true) } + target).takeLast(10)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY_TARGETS, values.joinToString(SEPARATOR)).apply()
    }
    fun list(context: Context): List<String> = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY_TARGETS, "").orEmpty().split(SEPARATOR).filter(String::isNotBlank)
}
