package io.github.carnager.bastion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.App
import io.github.carnager.bastion.data.SshKey
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.ssh.SshKeys
import java.util.UUID

/**
 * Adding keys: from a file (the system file picker), from pasted text, or freshly generated.
 * Asks for a passphrase only when the key turns out to be encrypted.
 */
@Stable
class KeyAdder(private val store: VaultRepository, private val onAdded: (SshKey) -> Unit) {
    private class Pending(val name: String, val text: String)

    private var pending by mutableStateOf<Pending?>(null)
    private var needsPassphrase by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var pasting by mutableStateOf(false)
    private var generating by mutableStateOf(false)
    internal var launchPicker: () -> Unit = {}

    fun importFile() = launchPicker()
    fun paste() { pasting = true }
    fun generate() { generating = true }

    internal fun picked(context: Context, uri: Uri) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        when {
            text == null -> error = "Could not read that file."
            text.size > 64 * 1024 -> error = "That file is too big to be an SSH key."
            else -> attempt(displayName(context, uri) ?: "key", text.decodeToString(), "")
        }
    }

    private fun attempt(name: String, text: String, passphrase: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("ssh-") || trimmed.startsWith("ecdsa-") || trimmed.startsWith("sk-")) {
            error = "That is a public key. Pick the private key: the file without “.pub”."
            return
        }
        val keyName = name.removeSuffix(".pem").removeSuffix(".key")
        runCatching { SshKeys.parse(keyName, trimmed, passphrase) }
            .onSuccess { pairs ->
                val key = SshKey(
                    id = UUID.randomUUID().toString(),
                    name = keyName,
                    privateKey = "$trimmed\n",
                    passphrase = passphrase,
                    publicKey = SshKeys.publicKeyLine(pairs.first().public, keyName),
                )
                store.update { it.copy(keys = it.keys + key) }
                pending = null
                needsPassphrase = false
                pasting = false
                Snack.show("Key “$keyName” added")
                onAdded(key)
            }
            .onFailure { e ->
                val encrypted = "ENCRYPTED" in trimmed || isEncryptedOpenSsh(trimmed)
                if (encrypted) {
                    pending = Pending(keyName, trimmed)
                    if (needsPassphrase && passphrase.isNotEmpty()) error = "Wrong passphrase."
                    needsPassphrase = true
                } else {
                    error = e.message ?: "Not a private key this app can read."
                }
            }
    }

    @Composable
    fun Dialogs() {
        val context = LocalContext.current
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) picked(context, uri)
        }
        launchPicker = {
            (context.applicationContext as App).lock.allowLeaving()
            picker.launch(arrayOf("*/*"))
        }

        val p = pending
        if (needsPassphrase && p != null) {
            var passphrase by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { needsPassphrase = false; pending = null },
                title = { Text("Passphrase for ${p.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This key is encrypted. The passphrase is saved with it in the app’s encrypted storage.", fontSize = 14.sp, color = extra.muted)
                        TextInput(passphrase, { passphrase = it }, secret = true, placeholder = "Passphrase")
                    }
                },
                confirmButton = { TextButton(onClick = { attempt(p.name, p.text, passphrase) }, enabled = passphrase.isNotEmpty()) { Text("Unlock") } },
                dismissButton = { TextButton(onClick = { needsPassphrase = false; pending = null }) { Text("Cancel") } },
            )
        }
        if (pasting) {
            var name by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { pasting = false },
                title = { Text("Paste private key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextInput(name, { name = it }, placeholder = "Name")
                        TextInput(text, { text = it }, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----", mono = true, singleLine = false, minHeight = 120.dp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { attempt(name.trim().ifEmpty { "key" }, text, "") }, enabled = text.isNotBlank()) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { pasting = false }) { Text("Cancel") } },
            )
        }
        if (generating) {
            var name by remember { mutableStateOf("bastion@${android.os.Build.MODEL.replace(' ', '-')}") }
            AlertDialog(
                onDismissRequest = { generating = false },
                title = { Text("New Ed25519 key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add its public key to ~/.ssh/authorized_keys on your servers afterwards.", fontSize = 14.sp, color = extra.muted)
                        TextInput(name, { name = it }, placeholder = "Name / comment")
                    }
                },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        val (private, public) = SshKeys.generateEd25519(name.trim())
                        val key = SshKey(UUID.randomUUID().toString(), name.trim(), private, publicKey = public)
                        store.update { it.copy(keys = it.keys + key) }
                        generating = false
                        copyText(context, public, "Public key")
                        Snack.show("Key created · public key copied")
                        onAdded(key)
                    }) { Text("Generate") }
                },
                dismissButton = { TextButton(onClick = { generating = false }) { Text("Cancel") } },
            )
        }
        error?.let {
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("Can’t use this key") },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            )
        }
    }

    private companion object {
        fun displayName(context: Context, uri: Uri): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()

        /** OpenSSH's own format: "openssh-key-v1\0", then the cipher name, which is "none" when unencrypted. */
        fun isEncryptedOpenSsh(text: String): Boolean {
            if ("BEGIN OPENSSH PRIVATE KEY" !in text) return false
            val body = text.lines().filterNot { it.startsWith("-----") }.joinToString("")
            val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(body) }.getOrNull() ?: return false
            val magic = 15
            if (bytes.size < magic + 4) return false
            val len = java.nio.ByteBuffer.wrap(bytes, magic, 4).int
            if (len <= 0 || magic + 4 + len > bytes.size) return false
            return String(bytes, magic + 4, len, Charsets.US_ASCII) != "none"
        }
    }
}

@Composable
fun rememberKeyAdder(store: VaultRepository, onAdded: (SshKey) -> Unit = {}): KeyAdder =
    remember(store) { KeyAdder(store, onAdded) }.also { it.Dialogs() }

fun copyText(context: Context, text: String, label: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun KeysScreen(vault: Vault, store: VaultRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val adder = rememberKeyAdder(store)
    var selected by remember { mutableStateOf<SshKey?>(null) }
    var deleting by remember { mutableStateOf<SshKey?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Keys", onNav = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedAction("Import file", adder::importFile, Modifier.weight(1f), icon = Ico.file)
                OutlinedAction("Generate", adder::generate, Modifier.weight(1f), icon = Ico.sparkle)
            }
            TextAction("Paste key text instead", adder::paste)
            Spacer(Modifier.height(4.dp))
            if (vault.keys.isEmpty()) {
                Hint("No keys yet. Import your existing private key (e.g. id_ed25519), or generate a new one here.")
            } else {
                SectionLabel("Your keys · ${vault.keys.size}")
                InfoCard {
                    vault.keys.forEachIndexed { index, key ->
                        val users = vault.hosts.count { it.keyId == key.id }
                        CardRow(first = index == 0, onClick = { selected = key }) {
                            IconTile(Ico.key, extra.fg2.copy(alpha = 0.85f), 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(key.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    key.publicKey.substringBefore(' ') + when (users) {
                                        0 -> " · unused"
                                        1 -> " · 1 host"
                                        else -> " · $users hosts"
                                    },
                                    fontSize = 13.sp, color = extra.muted, maxLines = 1,
                                )
                            }
                            IconAction(Ico.copy, "Copy public key", {
                                copyText(context, key.publicKey, "Public key")
                                Snack.show("Public key copied")
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    selected?.let { key ->
        Sheet(onDismiss = { selected = null }) {
            Text(key.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            Text(
                key.publicKey, style = MonoStyle, fontSize = 11.sp, color = extra.muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
            SheetRow("Copy public key", Ico.copy) {
                copyText(context, key.publicKey, "Public key")
                Snack.show("Public key copied")
                selected = null
            }
            SheetRow("Delete key", Ico.trash, color = extra.red) { deleting = key; selected = null }
        }
    }
    deleting?.let { key ->
        val users = vault.hosts.filter { it.keyId == key.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${key.name}”?") },
            text = {
                Text(
                    if (users.isEmpty()) "The private key is removed from this phone."
                    else "Used by ${users.joinToString { it.label }}. Those hosts can’t log in until you pick another key.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.update { v -> v.copy(keys = v.keys.filterNot { it.id == key.id }) }
                    deleting = null
                }) { Text("Delete", color = extra.red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
