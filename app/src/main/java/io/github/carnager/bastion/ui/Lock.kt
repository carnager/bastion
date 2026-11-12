package io.github.carnager.bastion.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint/face (strong), falling back to the phone's PIN, pattern or password. */
private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

object DeviceAuth {
    /** Null when usable, otherwise why not. */
    fun unavailableReason(activity: FragmentActivity): String? =
        when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set up a screen lock (and optionally a fingerprint) in Android settings first."
            else -> "This phone offers no screen lock or biometrics to unlock with."
        }

    /** [done] gets null on success, otherwise a message (empty when the user just cancelled). */
    fun authenticate(activity: FragmentActivity, title: String, done: (String?) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(null)

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    done(if (cancelled) "" else message.toString())
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    val unlock = {
        DeviceAuth.authenticate(activity, "Unlock Bastion") { result ->
            if (result == null) onUnlocked() else error = result.ifEmpty { null }
        }
    }
    LaunchedEffect(Unit) { unlock() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(Ico.lock, Palette.Accent, 64.dp)
        Spacer(Modifier.height(20.dp))
        Text("Bastion is locked", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Open sessions keep running in the background.",
            fontSize = 14.sp, color = extra.muted, textAlign = TextAlign.Center,
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = extra.red, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(28.dp))
        AccentButton("Unlock", unlock, Modifier.fillMaxWidth(), icon = Ico.fingerprint)
    }
}
