package io.github.carnager.bastion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.ContainerInfo
import io.github.carnager.bastion.session.Containers
import io.github.carnager.bastion.session.StructuredLog
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Loads containers, through sudo when the user may not talk to the daemon. */
@Composable
private fun rememberContainers(session: TerminalSession, sudo: SudoRunner, tick: Int): Pair<List<ContainerInfo>?, String?> {
    var list by remember { mutableStateOf<List<ContainerInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        try {
            val direct = session.containers()
            if (direct != null) list = direct
            else sudo.run("Asking the daemon as root…", Containers.list(session.containerEngine())) { out -> list = Containers.parse(out) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    return list to error
}

@Composable
fun ContainersScreen(session: TerminalSession, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val sudo = rememberSudoRunner(session)
    var tick by remember { mutableIntStateOf(0) }
    val (list, error) = rememberContainers(session, sudo, tick)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Containers", onNav = onBack) { IconAction(Ico.refresh, "Refresh", { tick++ }) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            when {
                error != null -> item { WarnBanner { Text("Couldn’t list containers: $error", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) } }
                list == null -> item { Hint(sudo.busy ?: "Loading…", Modifier.padding(8.dp)) }
                list.isEmpty() -> item { Hint("No containers on ${session.host.label}.", Modifier.padding(8.dp)) }
                else -> item {
                    Hint("${list.count { it.running }} of ${list.size} running", Modifier.padding(start = 4.dp, bottom = 8.dp))
                    InfoCard {
                        list.sortedWith(compareByDescending<ContainerInfo> { it.running }.thenBy { it.name }).forEachIndexed { i, c ->
                            if (i > 0) HorizontalDivider(color = extra.line)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onOpen(c.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Dot(if (c.running) Palette.Good else extra.muted)
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${c.image} · ${c.status}", fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Ico.chevron, null, Modifier.size(18.dp), tint = extra.muted)
                            }
                        }
                    }
                }
            }
        }
    }
    sudo.Dialogs()
}

@Composable
fun ContainerScreen(session: TerminalSession, name: String, onRunInTerminal: (String) -> Unit, onBack: () -> Unit) {
    val sudo = rememberSudoRunner(session)
    var tick by remember { mutableIntStateOf(0) }
    val (list, error) = rememberContainers(session, sudo, tick)
    val c = list?.firstOrNull { it.name == name }
    var engine by remember { mutableStateOf("docker") }
    var logs by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(Unit) { engine = session.containerEngine() }
    LaunchedEffect(tick, engine) {
        val command = Containers.logs(engine, name)
        val out = runCatching { session.exec("sh -c " + shellQuote(command)) }.getOrDefault("")
        if (Containers.permissionDenied(out)) sudo.run("Reading log…", command) { logs = Containers.parseLogs(it) }
        else logs = Containers.parseLogs(out)
    }
    val q = shellQuote(name)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    /** Directly first; through sudo only if the daemon refuses this user. */
    fun act(label: String, done: String, verb: String) {
        val command = "$engine $verb $q"
        scope.launch {
            val (out, code) = runCatching { session.execResult("sh -c " + shellQuote("$command 2>&1"), null) }.getOrElse { (it.message ?: "") to 1 }
            when {
                code == 0 -> { Snack.show(done); tick++ }
                Containers.permissionDenied(out) -> sudo.run(label, command) { Snack.show(done); tick++ }
                else -> Snack.show(out.trim().ifEmpty { "$verb failed" })
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(name, onNav = onBack) { IconAction(Ico.refresh, "Refresh", { tick++ }) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            when {
                error != null -> item { WarnBanner { Text("Couldn’t read the container: $error", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) } }
                list == null -> item { Hint(sudo.busy ?: "Loading…", Modifier.padding(8.dp)) }
                c == null -> item { Hint("No container called “$name”.", Modifier.padding(8.dp)) }
                else -> {
                    item(key = "state") {
                        InfoCard {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Spacer(Modifier.size(14.dp).clip(CircleShape).background(if (c.running) Palette.Good else extra.muted))
                                Column(Modifier.weight(1f)) {
                                    Text(if (c.running) "Running" else c.state.replaceFirstChar { it.uppercase() }, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                        color = if (c.running) Palette.Good else extra.fg2)
                                    Text(c.status, fontSize = 13.sp, color = extra.muted)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    item(key = "actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (c.running) {
                                OutlinedAction("Restart", { act("Restarting…", "Restarted $name", "restart") }, Modifier.weight(1f), icon = Ico.refresh, height = 46.dp, enabled = sudo.busy == null)
                                OutlinedAction("Stop", { act("Stopping…", "Stopped $name", "stop") }, Modifier.weight(1f), icon = Ico.power, height = 46.dp, color = extra.red, enabled = sudo.busy == null)
                            } else {
                                OutlinedAction("Start", { act("Starting…", "Started $name", "start") }, Modifier.weight(1f), icon = Ico.enter, height = 46.dp, enabled = sudo.busy == null)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (c.running) OutlinedAction("Shell inside", { onRunInTerminal("$engine exec -it $q sh -c 'command -v bash >/dev/null && exec bash || exec sh'") }, Modifier.weight(1f), icon = Ico.term, height = 46.dp)
                            OutlinedAction("Follow log", { onRunInTerminal("$engine logs -f --tail 50 $q") }, Modifier.weight(1f), icon = Ico.term, height = 46.dp)
                        }
                        Hint(sudo.busy ?: "Uses sudo if your user can’t talk to $engine directly.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp))
                    }
                    item(key = "details") {
                        SectionLabel("Details")
                        InfoCard {
                            listOfNotNull("Image" to c.image, c.ports.takeIf { it.isNotBlank() }?.let { "Ports" to it }, "Created" to c.created, "ID" to c.id.take(12))
                                .forEachIndexed { i, (k, v) ->
                                    if (i > 0) HorizontalDivider(color = extra.line)
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                                        Text(k, fontSize = 14.sp, color = extra.muted, modifier = Modifier.width(80.dp))
                                        Text(v, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    item(key = "logs") {
                        SectionLabel("Recent log")
                        val l = logs
                        when {
                            l == null -> Hint("Loading log…", Modifier.padding(4.dp))
                            l.isEmpty() -> Hint("No log output.", Modifier.padding(4.dp))
                            else -> InfoCard {
                                l.takeLast(100).forEachIndexed { i, (time, message) ->
                                    if (i > 0) HorizontalDivider(color = extra.line.copy(alpha = 0.5f))
                                    val structured = StructuredLog.parse(message)
                                    val level = StructuredLog.priorityOf(structured?.level)
                                    val color = when {
                                        level != null && level <= 3 -> extra.red
                                        level == 4 -> Palette.Amber
                                        else -> extra.fg
                                    }
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        if (time.isNotEmpty()) Text(time, fontSize = 11.sp, color = extra.muted)
                                        Text(structured?.message ?: message, style = MonoStyle, fontSize = 12.5.sp, color = color)
                                        structured?.highlights?.takeIf { it.isNotEmpty() }?.let { h ->
                                            Text(h.joinToString("  ·  ") { (k, v) -> "$k $v" }, style = MonoStyle, fontSize = 11.5.sp, color = extra.fg2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    sudo.Dialogs()
}
