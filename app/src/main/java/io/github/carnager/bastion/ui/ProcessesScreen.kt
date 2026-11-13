package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.ProcessInfo
import io.github.carnager.bastion.session.Processes
import io.github.carnager.bastion.session.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class ProcSort(val label: String) { CPU("CPU"), MEMORY("Memory"), NAME("Name") }

/** Live process list (every 3 s), sortable and filterable; tap for details and stopping. */
@Composable
fun ProcessesScreen(session: TerminalSession, onBack: () -> Unit) {
    var list by remember { mutableStateOf<List<ProcessInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(ProcSort.CPU) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ProcessInfo?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        while (true) {
            try {
                list = session.processes()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            delay(3_000)
        }
    }

    val shown = remember(list, sort, query) {
        val q = query.trim().lowercase()
        list.orEmpty()
            .filter { q.isEmpty() || q in it.name.lowercase() || q in it.user.lowercase() || it.pid.toString() == q }
            .sortedWith(
                when (sort) {
                    ProcSort.CPU -> compareByDescending<ProcessInfo> { it.cpu }.thenByDescending { it.rssKb }
                    ProcSort.MEMORY -> compareByDescending { it.rssKb }
                    ProcSort.NAME -> compareBy { it.name.lowercase() }
                },
            )
            .take(300)
    }
    val maxCpu = (shown.maxOfOrNull { it.cpu } ?: 1.0).coerceAtLeast(1.0)
    val maxMem = (shown.maxOfOrNull { it.rssKb } ?: 1L).coerceAtLeast(1L)

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Processes", onNav = onBack) { IconAction(Ico.refresh, "Refresh", { tick++ }) }
        SearchField(query, { query = it }, "Filter by name, user or PID", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProcSort.entries.forEach { s -> SelectChip(s.label, sort == s) { sort = s } }
        }
        Hint(
            when {
                error != null -> "Couldn’t list processes: $error"
                list == null -> "Loading…"
                else -> "${shown.size} of ${list!!.size} · updates every 3 s"
            },
            Modifier.padding(start = 20.dp, bottom = 6.dp),
            color = if (error != null) extra.red else extra.muted,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item {
                InfoCard {
                    shown.forEachIndexed { index, p ->
                        if (index > 0) HorizontalDivider(color = extra.line)
                        ProcessRow(p, if (sort == ProcSort.MEMORY) p.rssKb.toFloat() / maxMem else (p.cpu / maxCpu).toFloat()) { selected = p }
                    }
                }
            }
        }
    }

    selected?.let { p -> ProcessSheet(session, p, onDone = { selected = null; tick++ }, onDismiss = { selected = null }) }
}

@Composable
private fun ProcessRow(p: ProcessInfo, bar: Float, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(String.format(Locale.ROOT, "%.1f%%", p.cpu), fontSize = 13.sp, color = extra.fg2, modifier = Modifier.width(56.dp))
            Text(memory(p.rssKb), fontSize = 13.sp, color = extra.muted, modifier = Modifier.width(64.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${p.pid} · ${p.user}", fontSize = 12.sp, color = extra.muted, modifier = Modifier.weight(1f), maxLines = 1)
            Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(extra.chip)) {
                Box(Modifier.fillMaxWidth(bar.coerceIn(0.02f, 1f)).fillMaxHeight().background(extra.accent))
            }
        }
    }
}

@Composable
private fun ProcessSheet(session: TerminalSession, p: ProcessInfo, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sudo = rememberSudoRunner(session)
    var command by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // signal awaiting confirmation
    LaunchedEffect(p.pid) { command = runCatching { session.processDetail(p.pid).first }.getOrNull() }

    fun send(signal: String, done: String) {
        scope.launch {
            val (out, code) = runCatching { session.kill(p.pid, signal) }.getOrElse { (it.message ?: "") to 1 }
            when {
                code == 0 -> { Snack.show(done); onDone() }
                Processes.notPermitted(out) -> sudo.run("Stopping…", "kill -$signal ${p.pid}") { Snack.show(done); onDone() }
                else -> Snack.show(out.trim().ifEmpty { "kill failed" })
            }
        }
    }

    Sheet(onDismiss = onDismiss) {
        Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        Text(
            command?.ifEmpty { null } ?: "…", style = MonoStyle, fontSize = 12.sp, color = extra.muted, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        )
        InfoCard {
            listOf(
                "PID" to p.pid.toString(),
                "User" to p.user,
                "CPU" to String.format(Locale.ROOT, "%.1f%%", p.cpu),
                "Memory" to "${memory(p.rssKb)} · ${String.format(Locale.ROOT, "%.1f%%", p.mem)}",
                "Running for" to duration(p.elapsedSeconds),
            ).forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = extra.line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Text(k, fontSize = 14.sp, color = extra.muted, modifier = Modifier.width(110.dp))
                    Text(v, fontSize = 14.sp)
                }
            }
        }
        Row(Modifier.padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction("Stop", { confirm = "TERM" }, Modifier.weight(1f), icon = Ico.power, height = 48.dp, enabled = sudo.busy == null)
            OutlinedAction("Force kill", { confirm = "KILL" }, Modifier.weight(1f), height = 48.dp, color = extra.red, enabled = sudo.busy == null)
        }
        Hint("Stop asks the process to quit (TERM); Force kill ends it at once (KILL).", Modifier.padding(start = 4.dp, bottom = 8.dp))
    }
    confirm?.let { signal ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (signal == "KILL") "Force kill ${p.name}?" else "Stop ${p.name}?") },
            text = { Text("PID ${p.pid}, owned by ${p.user}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    send(signal, if (signal == "KILL") "Killed ${p.name}" else "Asked ${p.name} to stop")
                }) { Text(if (signal == "KILL") "Force kill" else "Stop", color = extra.red) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    sudo.Dialogs()
}

internal fun memory(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> "${kb / 1024} MB"
    else -> String.format(Locale.ROOT, "%.1f GB", kb / 1024.0 / 1024.0)
}

internal fun duration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    else -> "${seconds / 86400} d ${seconds % 86400 / 3600} h"
}
