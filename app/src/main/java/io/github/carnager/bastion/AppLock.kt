package io.github.carnager.bastion

import android.os.SystemClock
import io.github.carnager.bastion.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hides the UI behind fingerprint/face/screen lock. Open sessions keep running underneath;
 * the lock only guards who can see and type into them.
 */
class AppLock(private val settings: Settings) {
    private val _locked = MutableStateFlow(settings.lockEnabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt = 0L
    private var leavingOnPurpose = false

    fun unlocked() {
        _locked.value = false
    }

    /** Called before the app opens another app on purpose (file picker), so returning doesn't lock. */
    fun allowLeaving() {
        leavingOnPurpose = true
    }

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        val purposeful = leavingOnPurpose
        leavingOnPurpose = false
        if (!settings.lockEnabled.value || backgroundedAt == 0L || purposeful) return
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        if (away >= settings.lockAfterSeconds.value * 1000L) _locked.value = true
    }
}
