package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.session.SessionStatus
import io.github.carnager.bastion.session.TerminalSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    vault: Vault,
    sessions: List<TerminalSession>,
    onConnect: (Host) -> Unit,
    onOpenSession: (Long) -> Unit,
    onCloseSession: (Long) -> Unit,
    onEdit: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onKeys: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Host?>(null) }

    val hosts = remember(vault.hosts, query) {
        val q = query.trim().lowercase()
        vault.hosts
            .filter { q.isEmpty() || q in it.label.lowercase() || q in it.address.lowercase() }
            .sortedWith(compareByDescending<Host> { it.lastUsed }.thenBy { it.label.lowercase() })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search hosts") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Bastion")
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    IconButton(onClick = onKeys) { Icon(Icons.Default.Key, "Keys") }
                    IconButton(onClick = { onEdit(null) }) { Icon(Icons.Default.Add, "Add host") }
                },
            )
        },
    ) { padding ->
        if (vault.hosts.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(Dimens.gutter),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No hosts yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(Dimens.gap))
                Button(onClick = { onEdit(null) }) { Text("Add host") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (sessions.isNotEmpty()) {
                item { SectionLabel("Open") }
                items(sessions, key = { "s${it.id}" }) { session ->
                    SessionRow(session, onClick = { onOpenSession(session.id) }, onClose = { onCloseSession(session.id) })
                }
                item { HorizontalDivider(Modifier.padding(top = 4.dp)) }
                item { SectionLabel("Hosts") }
            }
            items(hosts, key = { it.id }) { host ->
                HostRow(
                    host = host,
                    via = vault.host(host.jumpHostId)?.label,
                    onClick = { onConnect(host) },
                    onEdit = { onEdit(host.id) },
                    onDelete = { confirmDelete = host },
                )
            }
        }
    }

    confirmDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${host.label}?") },
            text = {
                val dependents = vault.hosts.filter { it.jumpHostId == host.id }
                if (dependents.isNotEmpty()) {
                    Text("Used as jump host by: ${dependents.joinToString { it.label }}. Those will fail to connect.")
                }
            },
            confirmButton = { TextButton(onClick = { onDelete(host.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dimens.gutter, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HostRow(host: Host, via: String?, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.rowHeight).clickable(onClick = onClick).padding(start = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(host.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = if (via != null) "${host.address} · via $via" else host.address
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun SessionRow(session: TerminalSession, onClick: () -> Unit, onClose: () -> Unit) {
    val status by session.status.collectAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(start = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status)
        Spacer(Modifier.width(10.dp))
        Text(
            session.host.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close session", Modifier.size(18.dp)) }
    }
}

@Composable
fun StatusDot(status: SessionStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        SessionStatus.Connecting -> Color(0xFFE0B040)
        SessionStatus.Connected -> Color(0xFF50C070)
        is SessionStatus.Ended -> if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(8.dp).background(color, CircleShape))
}
