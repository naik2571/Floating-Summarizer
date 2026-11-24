package com.aisummarizer.floating

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("summarizer_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_MAX_BULLETS = "max_bullets"
        const val KEY_AUTO_COPY = "auto_copy"
        const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
    }

    // Master Switch: DORMANT BY DEFAULT (false)
    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var maxBullets: Int
        get() = prefs.getInt(KEY_MAX_BULLETS, 4)
        set(value) = prefs.edit().putInt(KEY_MAX_BULLETS, value).apply()

    var isAutoCopy: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COPY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COPY, value).apply()

    var isHapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()
}
