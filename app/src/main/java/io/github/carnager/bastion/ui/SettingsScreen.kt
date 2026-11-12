package io.github.carnager.bastion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.github.carnager.bastion.BuildConfig
import io.github.carnager.bastion.data.ColorSchemes
import io.github.carnager.bastion.data.Settings

private val LOCK_AFTER = listOf(0 to "Immediately", 60 to "1 min", 300 to "5 min", 900 to "15 min")

@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    val lockEnabled by settings.lockEnabled.collectAsState()
    val lockAfter by settings.lockAfterSeconds.collectAsState()
    val schemeId by settings.schemeId.collectAsState()
    var pickScheme by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onNav = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Security")
            InfoCard {
                CardRow(first = true, onClick = null) {
                    Icon(Ico.fingerprint, null, Modifier.size(22.dp), tint = extra.fg2)
                    Column(Modifier.weight(1f)) {
                        Text("App lock", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Fingerprint, face or screen lock to open Bastion", fontSize = 13.sp, color = extra.muted)
                    }
                    AccentSwitch(lockEnabled) { enable ->
                        if (!enable) {
                            settings.setLockEnabled(false)
                        } else {
                            // Only turn it on after proving it works, so nobody locks themselves out.
                            val reason = DeviceAuth.unavailableReason(activity)
                            if (reason != null) {
                                error = reason
                            } else {
                                DeviceAuth.authenticate(activity, "Turn on app lock") { result ->
                                    if (result == null) settings.setLockEnabled(true) else if (result.isNotEmpty()) error = result
                                }
                            }
                        }
                    }
                }
                if (lockEnabled) {
                    CardRow(onClick = null) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Lock after leaving the app", fontSize = 14.sp, color = extra.fg2)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LOCK_AFTER.forEach { (seconds, label) ->
                                    SelectChip(label, lockAfter == seconds) { settings.setLockAfterSeconds(seconds) }
                                }
                            }
                        }
                    }
                }
            }
            error?.let { message ->
                WarnBanner { Text(message, Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) }
            }
            Hint("Open sessions stay connected while the app is locked.", Modifier.padding(start = 4.dp))

            Spacer(Modifier.height(10.dp))
            SectionLabel("Terminal")
            InfoCard {
                val scheme = ColorSchemes.orDefault(schemeId)
                CardRow(first = true, onClick = { pickScheme = true }) {
                    SchemePreview(scheme)
                    Column(Modifier.weight(1f)) {
                        Text("Colors", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(scheme.name, fontSize = 13.sp, color = extra.muted)
                    }
                    Icon(Ico.chevron, null, Modifier.size(18.dp), tint = extra.muted)
                }
            }
            Hint("Hosts can override this in their own settings.", Modifier.padding(start = 4.dp))
            val suggestions by settings.suggestions.collectAsState()
            InfoCard {
                CardRow(first = true, onClick = null) {
                    Column(Modifier.weight(1f)) {
                        Text("Command suggestions", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Remember commands per host and suggest them while typing", fontSize = 13.sp, color = extra.muted)
                    }
                    AccentSwitch(suggestions, settings::setSuggestions)
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel("About")
            InfoCard {
                CardRow(first = true, onClick = null) {
                    Text("Bastion", Modifier.weight(1f), fontSize = 15.sp)
                    Text(BuildConfig.VERSION_NAME, fontSize = 14.sp, color = extra.muted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickScheme) {
        SchemeSheet(
            title = "Terminal colors",
            selectedId = schemeId,
            onPick = { id -> id?.let(settings::setSchemeId); pickScheme = false },
            onDismiss = { pickScheme = false },
        )
    }
}
