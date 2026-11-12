package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.Overview
import io.github.carnager.bastion.session.Overviews
import io.github.carnager.bastion.session.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/** The server at a glance: system, memory, disks, services, updates, containers, top processes. */
@Composable
fun OverviewScreen(
    session: TerminalSession,
    onOpenService: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onRunInTerminal: (String) -> Unit,
    onBack: () -> Unit,
) {
    var overview by remember { mutableStateOf<Overview?>(null) }
    var updates by remember { mutableStateOf<Overviews.Updates?>(null) }
    var updatesChecked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    // Live while open: refresh every 10 s.
    LaunchedEffect(tick) {
        while (true) {
            try {
                overview = session.overview()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            delay(10_000)
        }
    }
    LaunchedEffect(Unit) {
        updates = runCatching { session.updates() }.getOrNull()
        updatesChecked = true
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(session.host.label, onNav = onBack) {
            IconAction(Ico.refresh, "Refresh", { tick++ })
        }
        val o = overview
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (error != null) item { WarnBanner(Modifier.padding(bottom = 12.dp)) { Text("Couldn’t read the server: $error", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) } }
            if (o == null) {
                if (error == null) item { Hint("Loading…", Modifier.padding(8.dp)) }
                return@LazyColumn
            }
            item(key = "system") { SystemCard(o) }
            item(key = "updates") { UpdatesCard(updates, updatesChecked, onRunInTerminal) }
            if (o.runningServices != null) item(key = "services") { ServicesCard(o, onOpenService) }
            if (o.disks.isNotEmpty()) item(key = "disks") { DisksCard(o.disks, onOpenFolder) }
            o.containers?.let { item(key = "containers") { ContainersCard(it) } }
            if (o.topProcesses.isNotEmpty()) item(key = "top") { TopCard(o.topProcesses) }
        }
    }
}

@Composable
private fun Card(label: String, content: @Composable () -> Unit) {
    SectionLabel(label, Modifier.padding(top = 4.dp))
    InfoCard { content() }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun SystemCard(o: Overview) {
    Card("System") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(o.os ?: "Unknown system", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(o.kernel?.let { "Linux $it" }, o.uptimeSeconds?.let { "up " + uptime(it) }).joinToString(" · "),
                    fontSize = 13.sp, color = extra.muted,
                )
            }
            if (o.load.isNotEmpty()) {
                val cores = o.cores ?: 1
                Meter(
                    label = "Load",
                    value = o.load.joinToString("  ") { String.format(Locale.ROOT, "%.2f", it) } + " · $cores cores",
                    fraction = (o.load[0] / cores).toFloat(),
                )
            }
            o.memUsedFraction?.let { f ->
                Meter("Memory", "${kb(o.memTotalKb!! - o.memAvailableKb!!)} of ${kb(o.memTotalKb)}", f)
            }
            if ((o.swapTotalKb ?: 0) > 0) {
                val used = o.swapTotalKb!! - (o.swapFreeKb ?: 0)
                Meter("Swap", "${kb(used)} of ${kb(o.swapTotalKb)}", used.toFloat() / o.swapTotalKb)
            }
        }
    }
}

/** Label, value and a bar that turns amber above 80 % and red above 90 %. */
@Composable
private fun Meter(label: String, value: String, fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    val color = when {
        fraction >= 0.9f -> Palette.Bad
        fraction >= 0.8f -> Palette.Amber
        else -> extra.accent
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = extra.fg2, modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp, color = extra.muted, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(extra.chip)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

@Composable
private fun UpdatesCard(updates: Overviews.Updates?, checked: Boolean, run: (String) -> Unit) {
    if (checked && updates == null) return
    Card("Updates") {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        updates == null -> "Checking for updates…"
                        updates.count == 0 -> "Up to date"
                        updates.count == 1 -> "1 update available"
                        else -> "${updates.count} updates available"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = if ((updates?.count ?: 0) > 0) extra.fg else Palette.Good,
                )
                updates?.let { Text("via ${it.manager}", fontSize = 13.sp, color = extra.muted) }
            }
            val command = updates?.upgradeCommand
            if (command != null && updates.count > 0) {
                AccentButton("Update", { run(command) }, height = 40.dp, radius = 12.dp, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ServicesCard(o: Overview, open: (String) -> Unit) {
    Card("Services") {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dot(Palette.Good)
            Text("${o.runningServices} running", fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (o.failedServices.isNotEmpty()) {
                Dot(Palette.Bad)
                Text("${o.failedServices.size} failed", fontSize = 15.sp, color = extra.red, fontWeight = FontWeight.SemiBold)
            }
        }
        o.failedServices.forEach { unit ->
            HorizontalDivider(color = extra.line)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open(unit) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Ico.alert, null, Modifier.size(18.dp), tint = extra.red)
                Text(unit, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Ico.chevron, null, Modifier.size(18.dp), tint = extra.muted)
            }
        }
    }
}

@Composable
private fun DisksCard(disks: List<Overview.Disk>, open: (String) -> Unit) {
    Card("Disks") {
        disks.forEachIndexed { index, disk ->
            if (index > 0) HorizontalDivider(color = extra.line)
            Column(Modifier.fillMaxWidth().clickable { open(disk.mount) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Meter(
                    label = disk.mount,
                    value = "${kb(disk.usedKb)} of ${kb(disk.sizeKb)} · ${(disk.fraction * 100).toInt()}%",
                    fraction = disk.fraction,
                )
            }
        }
    }
}

@Composable
private fun ContainersCard(containers: List<Overview.Container>) {
    val running = containers.count { it.running }
    Card("Containers") {
        Text(
            if (containers.isEmpty()) "No containers" else "$running of ${containers.size} running",
            fontSize = 15.sp, modifier = Modifier.padding(16.dp),
        )
        containers.take(8).forEach { c ->
            HorizontalDivider(color = extra.line)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Dot(if (c.running) Palette.Good else extra.muted)
                Text(c.name, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.status, fontSize = 12.5.sp, color = extra.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TopCard(procs: List<Overview.Proc>) {
    Card("Busiest processes") {
        procs.forEachIndexed { index, p ->
            if (index > 0) HorizontalDivider(color = extra.line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.cpu}% cpu", fontSize = 13.sp, color = extra.fg2, modifier = Modifier.width(80.dp))
                Text("${p.mem}% mem", fontSize = 13.sp, color = extra.muted, modifier = Modifier.width(80.dp))
            }
        }
    }
}

private fun kb(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f MB", kb / 1024.0)
    kb < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", kb / 1024.0 / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f TB", kb / 1024.0 / 1024.0 / 1024.0)
}

private fun uptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
