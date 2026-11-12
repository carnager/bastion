package io.github.carnager.bastion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret preferences. Secrets live in the encrypted vault. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _schemeId = MutableStateFlow(prefs.getString(KEY_SCHEME, null) ?: ColorSchemes.DEFAULT_ID)
    /** Color scheme for hosts without their own. */
    val schemeId: StateFlow<String> = _schemeId.asStateFlow()

    fun setSchemeId(id: String) {
        prefs.edit().putString(KEY_SCHEME, id).apply()
        _schemeId.value = id
    }

    private val _lockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK, false))
    /** Require fingerprint/face or the screen lock to open the app. */
    val lockEnabled: StateFlow<Boolean> = _lockEnabled.asStateFlow()

    private val _lockAfterSeconds = MutableStateFlow(prefs.getInt(KEY_LOCK_AFTER, 60))
    /** How long the app may be in the background before it locks again; 0 = right away. */
    val lockAfterSeconds: StateFlow<Int> = _lockAfterSeconds.asStateFlow()

    fun setLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK, enabled).apply()
        _lockEnabled.value = enabled
    }

    fun setLockAfterSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_LOCK_AFTER, seconds).apply()
        _lockAfterSeconds.value = seconds
    }

    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT, DEFAULT_FONT_SIZE))
    /** Terminal text size in sp. */
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    fun setFontSize(size: Float) {
        val clamped = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        prefs.edit().putFloat(KEY_FONT, clamped).apply()
        _fontSize.value = clamped
    }

    private val _suggestions = MutableStateFlow(prefs.getBoolean(KEY_SUGGESTIONS, true))
    /** Remember commands per host and suggest them while typing. */
    val suggestions: StateFlow<Boolean> = _suggestions.asStateFlow()

    fun setSuggestions(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUGGESTIONS, enabled).apply()
        _suggestions.value = enabled
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 11f
        const val MIN_FONT_SIZE = 6f
        const val MAX_FONT_SIZE = 30f
        private const val KEY_FONT = "font_size"
        private const val KEY_SUGGESTIONS = "suggestions"
        private const val KEY_SCHEME = "scheme"
        private const val KEY_LOCK = "lock"
        private const val KEY_LOCK_AFTER = "lock_after"
    }
}
