package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.LogLine
import io.github.carnager.bastion.session.ServiceInfo
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

/**
 * A systemd service at a glance: state, details, actions and its recent log, instead of
 * `systemctl status` text. Actions needing root run with sudo in the terminal.
 */
@Composable
fun ServiceScreen(
    session: TerminalSession,
    unit: String,
    onRunInTerminal: (String) -> Unit,
    onBack: () -> Unit,
) {
    var info by remember { mutableStateOf<ServiceInfo?>(null) }
    var log by remember { mutableStateOf<List<LogLine>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(unit, tick) {
        error = null
        try {
            info = session.serviceInfo(unit)
            val (lines, note) = session.journal(unit)
            log = lines
            notice = note
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val q = shellQuote(unit)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(unit, onNav = onBack) {
            IconAction(Ico.refresh, "Refresh", { tick++ })
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            val i = info
            when {
                error != null -> item { WarnBanner { Text("Couldn’t read the service: $error", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) } }
                i == null -> item { Hint("Loading…", Modifier.padding(8.dp)) }
                i.loadState == "not-found" -> item { WarnBanner { Text("No service called “$unit” on ${session.host.label}.", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) } }
                else -> {
                    item(key = "state") { StateCard(i) }
                    item(key = "actions") { Actions(i, q, onRunInTerminal) }
                    item(key = "details") { Details(i) }
                    logSection(log, notice)
                }
            }
        }
    }
}

@Composable
private fun StateCard(info: ServiceInfo) {
    val (color, label) = when {
        info.running && info.subState == "running" -> Palette.Good to "Running"
        info.running -> Palette.Good to "Active (${info.subState})"
        info.failed -> Palette.Bad to "Failed"
        info.activeState == "activating" -> Palette.Amber to "Starting"
        info.activeState == "deactivating" -> Palette.Amber to "Stopping"
        else -> extra.muted to "Not running"
    }
    InfoCard {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.size(14.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
                if (info.description.isNotEmpty()) Text(info.description, fontSize = 14.sp, color = extra.fg2)
                info.since?.let { Text(if (info.running) "since $it" else "last change $it", fontSize = 12.5.sp, color = extra.muted) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Actions(info: ServiceInfo, q: String, run: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info.running) {
            OutlinedAction("Restart", { run("sudo systemctl restart $q") }, Modifier.weight(1f), icon = Ico.refresh, height = 46.dp)
            OutlinedAction("Stop", { run("sudo systemctl stop $q") }, Modifier.weight(1f), icon = Ico.power, height = 46.dp, color = extra.red)
        } else {
            OutlinedAction("Start", { run("sudo systemctl start $q") }, Modifier.weight(1f), icon = Ico.enter, height = 46.dp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (info.enabled) {
            "enabled" -> OutlinedAction("Don’t start at boot", { run("sudo systemctl disable $q") }, Modifier.weight(1f), height = 46.dp)
            "disabled" -> OutlinedAction("Start at boot", { run("sudo systemctl enable $q") }, Modifier.weight(1f), height = 46.dp)
        }
        OutlinedAction("Follow log", { run("journalctl -u $q -f") }, Modifier.weight(1f), icon = Ico.term, height = 46.dp)
    }
    Hint("Actions run in the terminal, where sudo can ask for your password.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp))
}

@Composable
private fun Details(info: ServiceInfo) {
    val rows = buildList {
        info.mainPid?.let { add("Main PID" to it) }
        info.memory?.let { add("Memory" to humanMemory(it)) }
        info.cpuNanos?.let { add("CPU time" to humanDuration(it / 1_000_000)) }
        info.tasks?.let { add("Tasks" to it.toString()) }
        if (info.enabled.isNotEmpty()) add("At boot" to info.enabled)
        info.unitFile?.let { add("Unit file" to it) }
    }
    if (rows.isEmpty()) return
    SectionLabel("Details")
    InfoCard {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) HorizontalDivider(color = extra.line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, color = extra.muted, modifier = Modifier.width(96.dp))
                Text(value, fontSize = 14.sp, style = if (label == "Unit file") MonoStyle else androidx.compose.ui.text.TextStyle.Default, color = extra.fg, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

private fun LazyListScope.logSection(log: List<LogLine>?, notice: String?) {
    item(key = "log-label") { SectionLabel("Recent log") }
    when {
        log == null -> item { Hint("Loading log…", Modifier.padding(4.dp)) }
        log.isEmpty() -> item {
            Hint(notice ?: "No log entries.", Modifier.padding(4.dp))
        }
        else -> {
            if (notice != null) item(key = "notice") { Hint(notice, Modifier.padding(start = 4.dp, bottom = 6.dp)) }
            item(key = "log") {
                InfoCard {
                    log.forEachIndexed { index, line ->
                        if (index > 0) HorizontalDivider(color = extra.line.copy(alpha = 0.5f))
                        LogRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when {
        line.priority <= 3 -> extra.red
        line.priority == 4 -> Palette.Amber
        else -> extra.fg
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(line.timeMicros / 1000)),
            fontSize = 11.sp, color = extra.muted,
        )
        Text(line.message, style = MonoStyle, fontSize = 12.5.sp, color = color)
    }
}

private fun humanMemory(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun humanDuration(millis: Long): String = when {
    millis < 1000 -> "$millis ms"
    millis < 60_000 -> "%.1f s".format(millis / 1000.0)
    millis < 3_600_000 -> "${millis / 60_000} min ${(millis / 1000) % 60} s"
    else -> "${millis / 3_600_000} h ${(millis / 60_000) % 60} min"
}
