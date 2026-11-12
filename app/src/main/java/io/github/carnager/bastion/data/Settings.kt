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

    private companion object {
        const val KEY_SCHEME = "scheme"
    }
}
