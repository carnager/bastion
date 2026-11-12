package io.github.carnager.bastion.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.bastion.data.SshKey
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.data.VaultRepository
import io.github.carnager.bastion.ssh.SshKeys
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(vault: Vault, store: VaultRepository, onBack: () -> Unit) {
    var addMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshKey?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Add key") }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text("Generate Ed25519") }, onClick = { addMenu = false; generating = true })
                            DropdownMenuItem(text = { Text("Import existing") }, onClick = { addMenu = false; importing = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (vault.keys.isEmpty()) {
            Text(
                "No keys. Generate one here, or import an existing private key.",
                modifier = Modifier.padding(padding).padding(Dimens.gutter),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(vault.keys, key = { it.id }) { key ->
                KeyRow(
                    key = key,
                    usedBy = vault.hosts.count { it.keyId == key.id },
                    onCopy = {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", key.publicKey))) }
                    },
                    onDelete = { deleting = key },
                )
            }
        }
    }

    if (generating) {
        GenerateKeyDialog(
            onDismiss = { generating = false },
            onCreate = { name ->
                val (private, public) = SshKeys.generateEd25519(name)
                store.update { it.copy(keys = it.keys + SshKey(UUID.randomUUID().toString(), name, private, publicKey = public)) }
                generating = false
            },
        )
    }
    if (importing) {
        ImportKeyDialog(
            onDismiss = { importing = false },
            onImport = { key -> store.update { it.copy(keys = it.keys + key) }; importing = false },
        )
    }
    deleting?.let { key ->
        val users = vault.hosts.filter { it.keyId == key.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${key.name}?") },
            text = { if (users.isNotEmpty()) Text("Used by: ${users.joinToString { it.label }}") },
            confirmButton = {
                TextButton(onClick = {
                    store.update { v -> v.copy(keys = v.keys.filterNot { it.id == key.id }) }
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyRow(key: SshKey, usedBy: Int, onCopy: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowHeight).clickable(onClick = onCopy).padding(start = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(key.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                key.publicKey.substringBefore(' ') + if (usedBy > 0) " · $usedBy host${if (usedBy == 1) "" else "s"}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy public key") }, onClick = { menu = false; onCopy() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun GenerateKeyDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("bastion@${android.os.Build.MODEL.replace(' ', '-')}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Ed25519 key") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name / comment") }, singleLine = true)
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Generate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImport: (SshKey) -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                .onSuccess { text = it.orEmpty(); if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty() }
                .onFailure { error = "Could not read file." }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    label = { Text("Private key") }, minLines = 3, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose file…") }
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it; error = null },
                    label = { Text("Passphrase (if any)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val keyName = name.trim().ifBlank { "key" }
                runCatching { SshKeys.parse(keyName, text, passphrase) }
                    .onSuccess { pairs ->
                        onImport(
                            SshKey(
                                id = UUID.randomUUID().toString(),
                                name = keyName,
                                privateKey = text.trim() + "\n",
                                passphrase = passphrase,
                                publicKey = SshKeys.publicKeyLine(pairs.first().public, keyName),
                            ),
                        )
                    }
                    .onFailure { error = it.message }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
