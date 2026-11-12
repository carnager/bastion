package io.github.carnager.bastion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.data.Host
import io.github.carnager.bastion.data.Vault
import io.github.carnager.bastion.session.SessionStatus
import io.github.carnager.bastion.session.TerminalSession

@Composable
fun HostListScreen(
    vault: Vault,
    sessions: List<TerminalSession>,
    onConnect: (Host) -> Unit,
    onOpenSession: (Long) -> Unit,
    onCloseSession: (Long) -> Unit,
    onEdit: (String?) -> Unit,
    onDuplicate: (Host) -> Unit,
    onDelete: (String) -> Unit,
    onKeys: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var quick by remember { mutableStateOf<Host?>(null) }
    var confirmDelete by remember { mutableStateOf<Host?>(null) }

    val hosts = remember(vault.hosts, query) {
        val q = query.trim().lowercase()
        vault.hosts
            .filter { q.isEmpty() || q in it.label.lowercase() || q in it.address.lowercase() }
            .sortedWith(compareByDescending<Host> { it.lastUsed }.thenBy { it.label.lowercase() })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabHeader("Bastion") {
                IconAction(Ico.key, "Keys", onKeys)
                IconAction(Ico.settings, "Settings", onSettings)
            }
            if (vault.hosts.size > 4) {
                SearchField(
                    query, { query = it }, "Search ${vault.hosts.size} hosts",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            if (vault.hosts.isEmpty()) {
                EmptyState(onAdd = { onEdit(null) }, onKeys = onKeys)
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                if (sessions.isNotEmpty() && query.isEmpty()) {
                    item(key = "h-open") { SectionLabel("Open sessions") }
                    item(key = "open") {
                        InfoCard {
                            sessions.forEachIndexed { index, session ->
                                SessionRow(session, index == 0, onClick = { onOpenSession(session.id) }, onClose = { onCloseSession(session.id) })
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
                item(key = "h-hosts") {
                    SectionLabel(if (query.isEmpty()) "Hosts · ${hosts.size}" else if (hosts.size == 1) "1 result" else "${hosts.size} results")
                }
                items(hosts, key = { it.id }) { host ->
                    HostRow(
                        host = host,
                        via = vault.host(host.jumpHostId)?.label,
                        open = sessions.count { it.host.id == host.id },
                        onClick = { onConnect(host) },
                        onLongClick = { quick = host },
                    )
                }
            }
        }
        // New host: 58 dp, 18 dp corners.
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).size(58.dp)
                .shadow(10.dp, RoundedCornerShape(18.dp), ambientColor = Color(0xFF1F3C8C), spotColor = Color(0xFF1F3C8C))
                .clip(RoundedCornerShape(18.dp)).background(Palette.Accent).clickable { onEdit(null) },
            contentAlignment = Alignment.Center,
        ) { Icon(Ico.plusBold, "New host", Modifier.size(24.dp), tint = Color.White) }
    }

    quick?.let { host ->
        Sheet(onDismiss = { quick = null }) {
            Row(
                Modifier.padding(start = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HostTile(host.label, host.hostname)
                Column(Modifier.weight(1f)) {
                    Text(host.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(host.address, fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SheetRow("Connect", Ico.term) { quick = null; onConnect(host) }
            SheetRow("Edit", Ico.edit) { quick = null; onEdit(host.id) }
            SheetRow("Duplicate", Ico.copy) { quick = null; onDuplicate(host) }
            SheetRow("Copy ssh command", Ico.paste) {
                val jump = vault.host(host.jumpHostId)
                val command = buildString {
                    append("ssh ")
                    if (jump != null) append("-J ${jump.address} ")
                    if (host.port != 22) append("-p ${host.port} ")
                    append("${host.username}@${host.hostname}")
                }
                copyText(context, command, "ssh command")
                Snack.show("Command copied")
                quick = null
            }
            SheetRow("Delete", Ico.trash, color = extra.red) { quick = null; confirmDelete = host }
        }
    }

    confirmDelete?.let { host ->
        val dependents = vault.hosts.filter { it.jumpHostId == host.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${host.label}”?") },
            text = {
                Text(
                    if (dependents.isEmpty()) "Its saved login is removed too."
                    else "It is the jump host for ${dependents.joinToString { it.label }}. Those won’t connect until you pick another jump host.",
                )
            },
            confirmButton = { TextButton(onClick = { onDelete(host.id); confirmDelete = null }) { Text("Delete", color = extra.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, onKeys: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Ico.term, null, Modifier.size(40.dp), tint = extra.muted)
        Text("No hosts yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Add a server with +. Using SSH keys? Import or generate one under Keys first.",
            fontSize = 14.sp, color = extra.muted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedAction("Keys", onKeys, icon = Ico.key)
    }
}

/** 40 dp tile, name, user@host, jump badge, open-session dot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(host: Host, via: String?, open: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "More actions")
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HostTile(host.label, host.hostname)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(host.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    host.address, fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (via != null) Badge(via, Ico.route)
            }
        }
        if (open > 0) Dot(Palette.Good)
    }
}

@Composable
private fun SessionRow(session: TerminalSession, first: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val status by session.status.collectAsState()
    CardRow(first = first, onClick = onClick) {
        StatusDot(status)
        Column(Modifier.weight(1f)) {
            Text(session.host.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (status) {
                    SessionStatus.Connecting -> "Connecting…"
                    SessionStatus.Connected -> session.host.address
                    is SessionStatus.Ended -> (status as SessionStatus.Ended).error ?: "Disconnected"
                },
                fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconAction(Ico.close, "Close session", onClose, tint = extra.muted, iconSize = 18.dp)
    }
}

@Composable
fun StatusDot(status: SessionStatus) {
    Dot(
        when (status) {
            SessionStatus.Connecting -> Palette.Amber
            SessionStatus.Connected -> Palette.Good
            is SessionStatus.Ended -> if (status.error != null) Palette.Bad else extra.muted
        },
    )
}
