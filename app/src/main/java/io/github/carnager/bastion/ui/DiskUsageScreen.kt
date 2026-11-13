package io.github.carnager.bastion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.DiskUsage
import io.github.carnager.bastion.session.UsageEntry
import io.github.carnager.bastion.session.UsageScan
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What takes the space under a folder, biggest first; tap folders to drill down. Stays on one
 * filesystem. "Scan with sudo" counts folders this user can't read.
 */
@Composable
fun DiskUsageScreen(session: TerminalSession, start: String, onOpenInFiles: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sudo = rememberSudoRunner(session)
    var trail by remember { mutableStateOf(listOf(start)) }
    val path = trail.last()
    var scan by remember { mutableStateOf<UsageScan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var withSudo by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<UsageEntry?>(null) }
    var deleting by remember { mutableStateOf<UsageEntry?>(null) }

    BackHandler(enabled = trail.size > 1) { trail = trail.dropLast(1) }

    LaunchedEffect(path, tick, withSudo) {
        scan = null
        error = null
        if (withSudo) {
            sudo.run("Scanning as root…", DiskUsage.script(path)) { out -> scan = DiskUsage.parse(path, out) }
            return@LaunchedEffect
        }
        try {
            scan = session.diskUsage(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(path.substringAfterLast('/').ifEmpty { "/" }, onNav = { if (trail.size > 1) trail = trail.dropLast(1) else onBack() }) {
            IconAction(Ico.refresh, "Scan again", { tick++ })
        }
        Text(path, style = MonoStyle, fontSize = 12.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp))
        val s = scan
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            when {
                error != null -> item { WarnBanner { Text("Couldn’t scan: $error", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) } }
                s == null -> item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(sudo.busy ?: "Measuring… big folders take a moment.", fontSize = 14.sp, color = extra.muted)
                    }
                }
                else -> {
                    item(key = "total") {
                        Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(memory(s.totalKb), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (!withSudo) TextAction("Scan with sudo", { withSudo = true })
                        }
                    }
                    if (s.entries.isEmpty()) item { Hint("Empty folder.", Modifier.padding(4.dp)) }
                    else item(key = "list") {
                        InfoCard {
                            s.entries.take(200).forEachIndexed { i, entry ->
                                if (i > 0) HorizontalDivider(color = extra.line)
                                UsageRow(entry, entry.kb.toFloat() / s.largestKb.coerceAtLeast(1), onClick = {
                                    if (entry.isDir) trail = trail + entry.path else selected = entry
                                }, onLongClick = { selected = entry })
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        Sheet(onDismiss = { selected = null }) {
            Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            Text("${memory(entry.kb)} · ${entry.path}", style = MonoStyle, fontSize = 12.sp, color = extra.muted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            if (entry.isDir) SheetRow("Look inside", Ico.folder) { selected = null; trail = trail + entry.path }
            SheetRow("Open in Files", Ico.file) {
                selected = null
                onOpenInFiles(if (entry.isDir) entry.path else entry.path.substringBeforeLast('/').ifEmpty { "/" })
            }
            SheetRow("Delete…", Ico.trash, color = extra.red) { selected = null; deleting = entry }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Ico.trash, null, tint = extra.red) },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    "${entry.path}\n\n${memory(entry.kb)}" + (if (entry.isDir) ", with everything inside" else "") +
                        ". This can’t be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    val command = "rm -rf -- ${shellQuote(entry.path)}"
                    scope.launch {
                        val (out, code) = runCatching { session.execResult("sh -c " + shellQuote("$command 2>&1"), null) }
                            .getOrElse { (it.message ?: "") to 1 }
                        if (code == 0) {
                            Snack.show("Deleted ${entry.name}")
                            tick++
                        } else if ("ermission denied" in out) {
                            sudo.run("Deleting…", command) { Snack.show("Deleted ${entry.name}"); tick++ }
                        } else {
                            Snack.show(out.trim().ifEmpty { "Couldn’t delete" })
                        }
                    }
                }) { Text("Delete", color = extra.red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    sudo.Dialogs()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UsageRow(entry: UsageEntry, fraction: Float, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (entry.isDir) Ico.folder else Ico.file, null, Modifier.size(18.dp), tint = if (entry.isDir) extra.link else extra.muted)
            Text(entry.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(memory(entry.kb), fontSize = 13.sp, color = extra.fg2)
            if (entry.isDir) Icon(Ico.chevron, null, Modifier.size(16.dp), tint = extra.muted)
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(extra.chip)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight().background(extra.accent))
        }
    }
}
