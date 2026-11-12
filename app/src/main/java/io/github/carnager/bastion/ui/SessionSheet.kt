package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.SessionStatus
import io.github.carnager.bastion.session.TerminalSession

/** Everything a session offers, behind one button: big labelled tiles, then connection actions. */
@Composable
fun SessionSheet(
    session: TerminalSession,
    status: SessionStatus,
    onDismiss: () -> Unit,
    onCommands: () -> Unit,
    onFiles: () -> Unit,
    onSearch: () -> Unit,
    onType: () -> Unit,
    onPaste: () -> Unit,
    onTextSize: () -> Unit,
    onColors: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = status is SessionStatus.Connected
    Sheet(onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HostTile(session.host.label, session.host.hostname, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(session.host.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(status)
                    Text(
                        when (status) {
                            SessionStatus.Connecting -> "Connecting…"
                            SessionStatus.Connected -> session.host.address
                            is SessionStatus.Ended -> status.error ?: "Disconnected"
                        },
                        fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Commands", Ico.term, enabled = connected, onClick = onCommands)
            Tile("Files", Ico.folder, enabled = connected, onClick = onFiles)
            Tile("Search", Ico.search, enabled = connected, onClick = onSearch)
            Tile("Type", Ico.keyboard, enabled = connected, onClick = onType)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Paste", Ico.paste, enabled = connected, onClick = onPaste)
            Tile("Text size", Ico.textSize, onClick = onTextSize)
            Tile("Colors", Ico.palette, onClick = onColors)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        SheetRow("Reconnect", Ico.refresh, onClick = onReconnect)
        SheetRow("Disconnect", Ico.power, color = extra.red, onClick = onDisconnect)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tile(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val alpha = if (enabled) 1f else 0.4f
    Column(
        Modifier.weight(1f).heightIn(min = 76.dp).clip(shape).background(extra.card).border(1.dp, extra.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = extra.link.copy(alpha = alpha))
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = extra.fg.copy(alpha = alpha), textAlign = TextAlign.Center, maxLines = 1)
    }
}
