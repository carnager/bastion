package io.github.carnager.bastion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.data.AuthMethod
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.data.jumpCandidates
import java.util.UUID

@Composable
fun HostEditScreen(
    vault: Vault,
    store: VaultRepository,
    hostId: String?,
    template: Host?,
    onSave: (Host) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    val original = remember(hostId) {
        vault.host(hostId)
            ?: template?.copy(id = UUID.randomUUID().toString(), name = template.label + " copy", lastUsed = 0)
            ?: Host(id = UUID.randomUUID().toString(), name = "", hostname = "", username = "")
    }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var hostname by rememberSaveable { mutableStateOf(original.hostname) }
    var port by rememberSaveable { mutableStateOf(original.port.toString()) }
    var username by rememberSaveable { mutableStateOf(original.username) }
    val isNew = hostId == null && template == null
    var auth by rememberSaveable { mutableStateOf(if (isNew && vault.keys.isNotEmpty()) AuthMethod.KEY else original.auth) }
    var password by rememberSaveable { mutableStateOf(original.password) }
    var keyId by rememberSaveable { mutableStateOf(original.keyId ?: vault.keys.singleOrNull()?.id) }
    var jumpHostId by rememberSaveable { mutableStateOf(original.jumpHostId) }
    var pickJump by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val adder = rememberKeyAdder(store) { keyId = it.id }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = hostname.isNotBlank() && username.isNotBlank() && portNumber != null &&
        (auth != AuthMethod.KEY || vault.key(keyId) != null)

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(if (hostId == null) "New host" else "Edit host", nav = Ico.close, navLabel = "Cancel", onNav = onBack) {
            AccentButton(
                "Save",
                onClick = {
                    onSave(
                        original.copy(
                            name = name.trim(),
                            hostname = hostname.trim(),
                            port = portNumber ?: 22,
                            username = username.trim(),
                            auth = auth,
                            password = if (auth == AuthMethod.PASSWORD) password else "",
                            keyId = if (auth == AuthMethod.KEY) keyId else null,
                            jumpHostId = jumpHostId,
                        ),
                    )
                },
                modifier = Modifier.padding(end = 8.dp),
                enabled = valid, height = 40.dp, radius = 12.dp, fontSize = 14.sp,
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LabeledInput("Name", name, { name = it }, placeholder = hostname.ifBlank { "e.g. homeserver" })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledInput(
                    "Host", hostname, { hostname = it.trim() }, Modifier.weight(1f),
                    placeholder = "example.org or 10.0.0.2", keyboard = KeyboardType.Uri,
                )
                LabeledInput(
                    "Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(84.dp),
                    keyboard = KeyboardType.Number, error = portNumber == null,
                )
            }
            LabeledInput("Username", username, { username = it.trim() }, keyboard = KeyboardType.Ascii)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InputLabel("Log in with")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("SSH key", auth == AuthMethod.KEY) { auth = AuthMethod.KEY }
                    SelectChip("Password", auth == AuthMethod.PASSWORD) { auth = AuthMethod.PASSWORD }
                }
            }
            when (auth) {
                AuthMethod.PASSWORD -> LabeledInput(
                    "Password", password, { password = it }, secret = true, keyboard = KeyboardType.Password,
                    hint = "Leave empty to be asked when connecting.",
                )
                AuthMethod.KEY -> KeyChooser(vault, keyId, onPick = { keyId = it }, adder = adder)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InputLabel("Jump host")
                val jump = vault.host(jumpHostId)
                InfoCard(radius = 14.dp) {
                    CardRow(first = true, onClick = { pickJump = true }) {
                        if (jump != null) {
                            HostTile(jump.label, jump.hostname, 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(jump.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(jump.address, fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            IconTile(Ico.route, extra.muted, 32.dp)
                            Text("Direct connection", Modifier.weight(1f), fontSize = 15.sp, color = extra.fg2)
                        }
                        Icon(Ico.chevron, null, Modifier.size(18.dp), tint = extra.muted)
                    }
                }
                Hint("Reach this host through another saved host, like ssh -J. Jump hosts can be chained.")
            }

            if (hostId != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedAction("Delete host", { confirmDelete = true }, Modifier.fillMaxWidth(), icon = Ico.trash, color = extra.red)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickJump) {
        val candidates = remember(vault) { jumpCandidates(original, vault) }
        Sheet(onDismiss = { pickJump = false }) {
            Text("Jump host", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            PickRow("Direct connection", null, selected = jumpHostId == null) { jumpHostId = null; pickJump = false }
            candidates.forEach { host ->
                PickRow(host.label, host.address, selected = host.id == jumpHostId, tile = host) { jumpHostId = host.id; pickJump = false }
            }
            if (candidates.isEmpty()) {
                Hint("Save the jump host itself as a host first; it then shows up here.", Modifier.padding(12.dp))
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${original.label}”?") },
            confirmButton = { TextButton(onClick = { onDelete(original.id) }) { Text("Delete", color = extra.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyChooser(vault: Vault, keyId: String?, onPick: (String) -> Unit, adder: KeyAdder) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InputLabel("Key")
        if (vault.keys.isNotEmpty()) {
            InfoCard(radius = 14.dp) {
                vault.keys.forEachIndexed { index, key ->
                    CardRow(first = index == 0, onClick = { onPick(key.id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(key.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(key.publicKey.substringBefore(' '), fontSize = 13.sp, color = extra.muted)
                        }
                        if (key.id == keyId) Icon(Ico.check, "Selected", Modifier.padding(end = 8.dp).size(20.dp), tint = extra.link)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction("Import key file", adder::importFile, Modifier.weight(1f), icon = Ico.file, height = 44.dp)
            OutlinedAction("Generate", adder::generate, Modifier.weight(1f), icon = Ico.sparkle, height = 44.dp)
        }
        if (vault.keys.isEmpty()) Hint("Pick your private key file (e.g. id_ed25519, not the .pub), or generate a new key.")
    }
}

@Composable
private fun PickRow(title: String, subtitle: String?, selected: Boolean, tile: Host? = null, onClick: () -> Unit) {
    CardRow(first = true, onClick = onClick) {
        if (tile != null) HostTile(tile.label, tile.hostname, 32.dp) else IconTile(Ico.route, extra.muted, 32.dp)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Ico.check, "Selected", Modifier.padding(end = 8.dp).size(20.dp), tint = extra.link)
    }
}
