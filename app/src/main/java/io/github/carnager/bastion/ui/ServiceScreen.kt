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
import io.github.carnager.bastion.session.Services
import io.github.carnager.bastion.session.SudoResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

/**
 * A systemd service at a glance: state, details, actions and its recent log, instead of
 * `systemctl status` text. Root actions and the log run through sudo in the background,
 * asking for the password natively when sudo needs one.
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
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf<Pair<Boolean, suspend () -> Unit>?>(null) } // (wrong before, retry)
    var failure by remember { mutableStateOf<String?>(null) }
    val q = shellQuote(unit)

    /** Runs [command] as root in the background; asks for the sudo password natively if needed. */
    suspend fun sudo(label: String, command: String, onOk: (String) -> Unit) {
        busy = label
        val result = try {
            session.execSudo(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SudoResult.Failed(e.message ?: e.javaClass.simpleName, -1)
        }
        busy = null
        when (result) {
            is SudoResult.Ok -> onOk(result.output)
            SudoResult.NeedsPassword -> askPassword = false to suspend { sudo(label, command, onOk) }
            SudoResult.WrongPassword -> askPassword = true to suspend { sudo(label, command, onOk) }
            is SudoResult.Failed -> failure = result.output.ifBlank { "Exit status ${result.code}" }
        }
    }

    suspend fun loadSudoLog() = sudo("Reading log…", Services.journalCommand(unit)) { out ->
        val (lines, note) = Services.parseJournal(out)
        log = lines
        notice = note ?: if (lines.isEmpty()) "No log entries for this service." else null
    }

    LaunchedEffect(unit, tick) {
        error = null
        try {
            info = session.serviceInfo(unit)
            val (lines, note) = session.journal(unit)
            log = lines
            notice = note
            // Can't read system logs as this user: try sudo quietly (works with NOPASSWD or a
            // password already given this session); otherwise the log card offers it.
            if (lines.isEmpty() && note == Services.NO_LOG_ACCESS && session.canSudoQuietly()) loadSudoLog()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun act(label: String, done: String, command: String) {
        scope.launch {
            sudo(label, command) {
                Snack.show(done)
                tick++
            }
        }
    }
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
                    item(key = "actions") { Actions(i, q, busy, ::act, onRunInTerminal) }
                    item(key = "details") { Details(i) }
                    logSection(log, notice) { scope.launch { loadSudoLog() } }
                }
            }
        }
    }

    askPassword?.let { (wrong, retry) ->
        SudoPasswordDialog(
            host = session.host.label,
            wrong = wrong,
            onSubmit = { password ->
                askPassword = null
                session.setSudoPassword(password)
                scope.launch { retry() }
            },
            onDismiss = { askPassword = null },
        )
    }
    failure?.let { output ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("That didn’t work") },
            text = { Text(output.take(1500), style = MonoStyle, fontSize = 12.5.sp) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }
}

/** Asks for the sudo password; kept in memory for this session only. */
@Composable
fun SudoPasswordDialog(host: String, wrong: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Ico.shield, null, tint = extra.link) },
        title = { Text("sudo on $host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (wrong) Text("Wrong password, try again.", fontSize = 14.sp, color = extra.red)
                TextInput(
                    password, { password = it }, Modifier.focusRequester(focus), secret = true,
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Password, placeholder = "Your password",
                )
                Text("Remembered until you disconnect, never stored.", fontSize = 12.5.sp, color = extra.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun Actions(
    info: ServiceInfo,
    q: String,
    busy: String?,
    act: (label: String, done: String, command: String) -> Unit,
    runInTerminal: (String) -> Unit,
) {
    val name = info.unit
    val idle = busy == null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info.running) {
            OutlinedAction("Restart", { act("Restarting…", "Restarted $name", "systemctl restart $q") }, Modifier.weight(1f), icon = Ico.refresh, height = 46.dp, enabled = idle)
            OutlinedAction("Stop", { act("Stopping…", "Stopped $name", "systemctl stop $q") }, Modifier.weight(1f), icon = Ico.power, height = 46.dp, color = extra.red, enabled = idle)
        } else {
            OutlinedAction("Start", { act("Starting…", "Started $name", "systemctl start $q") }, Modifier.weight(1f), icon = Ico.enter, height = 46.dp, enabled = idle)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (info.enabled) {
            "enabled" -> OutlinedAction("Don’t start at boot", { act("Updating…", "$name won’t start at boot", "systemctl disable $q") }, Modifier.weight(1f), height = 46.dp, enabled = idle)
            "disabled" -> OutlinedAction("Start at boot", { act("Updating…", "$name starts at boot", "systemctl enable $q") }, Modifier.weight(1f), height = 46.dp, enabled = idle)
        }
        OutlinedAction("Follow log", { runInTerminal("journalctl -u $q -f") }, Modifier.weight(1f), icon = Ico.term, height = 46.dp)
    }
    Hint(busy ?: "Actions run with sudo; you’re asked for the password if needed.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp), color = if (busy != null) extra.link else extra.muted)
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

private fun LazyListScope.logSection(log: List<LogLine>?, notice: String?, onSudo: () -> Unit) {
    item(key = "log-label") { SectionLabel("Recent log") }
    when {
        log == null -> item { Hint("Loading log…", Modifier.padding(4.dp)) }
        log.isEmpty() -> item {
            InfoCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(notice ?: "No log entries for this service.", fontSize = 14.sp, color = extra.fg2)
                    OutlinedAction("Show log with sudo", onSudo, Modifier.fillMaxWidth(), icon = Ico.shield, height = 46.dp)
                }
            }
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
