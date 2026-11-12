package io.github.carnager.bastion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.carnager.bastion.data.AuthMethod
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.data.jumpCandidates
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    vault: Vault,
    hostId: String?,
    onSave: (Host) -> Unit,
    onManageKeys: () -> Unit,
    onBack: () -> Unit,
) {
    val original = remember(hostId) {
        vault.host(hostId) ?: Host(id = UUID.randomUUID().toString(), name = "", hostname = "", username = "")
    }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var hostname by rememberSaveable { mutableStateOf(original.hostname) }
    var port by rememberSaveable { mutableStateOf(original.port.toString()) }
    var username by rememberSaveable { mutableStateOf(original.username) }
    var auth by rememberSaveable { mutableStateOf(original.auth) }
    var password by rememberSaveable { mutableStateOf(original.password) }
    var keyId by rememberSaveable { mutableStateOf(original.keyId) }
    var jumpHostId by rememberSaveable { mutableStateOf(original.jumpHostId) }

    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val valid = hostname.isNotBlank() && username.isNotBlank() && portNumber != null &&
        (auth != AuthMethod.KEY || vault.key(keyId) != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hostId == null) "New host" else "Edit host") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton(
                        enabled = valid,
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
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.gutter, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                OutlinedTextField(
                    value = hostname, onValueChange = { hostname = it },
                    label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") }, singleLine = true, modifier = Modifier.width(84.dp),
                    isError = portNumber == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMethod.entries.forEachIndexed { index, method ->
                    SegmentedButton(
                        selected = auth == method,
                        onClick = { auth = method },
                        shape = SegmentedButtonDefaults.itemShape(index, AuthMethod.entries.size),
                    ) { Text(if (method == AuthMethod.PASSWORD) "Password" else "Key") }
                }
            }
            when (auth) {
                AuthMethod.PASSWORD -> OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    supportingText = { Text("Leave empty to be asked each time") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                AuthMethod.KEY -> {
                    Picker(
                        label = "Key",
                        selected = vault.key(keyId)?.name ?: "Choose a key",
                        options = vault.keys.map { it.id to it.name },
                        onPick = { keyId = it },
                    )
                    TextButton(onClick = onManageKeys) { Text("Manage keys") }
                }
            }

            val candidates = remember(vault, hostId) { jumpCandidates(original, vault) }
            Picker(
                label = "Jump host",
                selected = vault.host(jumpHostId)?.label ?: "None (direct)",
                options = listOf<Pair<String?, String>>(null to "None (direct)") + candidates.map { it.id to it.label },
                onPick = { jumpHostId = it },
            )
            Text(
                "Connects through the chosen saved host, like ssh -J. The jump host can have its own jump host.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, selected: String, options: List<Pair<String?, String>>, onPick: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onPick(id); expanded = false })
            }
        }
    }
}
