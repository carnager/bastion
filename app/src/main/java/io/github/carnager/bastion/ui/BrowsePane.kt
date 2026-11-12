package io.github.carnager.bastion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.Fuzzy
import io.github.carnager.bastion.session.RemoteDir
import io.github.carnager.bastion.session.RemoteEntry
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Folder browser over SFTP. [query] filters the folder; a typed /path or ~/path navigates.
 * [onInsert] gets a shell-quoted path for the command line, [onRun] a full command to run.
 */
@Composable
internal fun ColumnScope.BrowsePane(
    session: TerminalSession,
    query: String,
    onQueryChange: (String) -> Unit,
    onInsert: (String) -> Unit,
    onRun: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    /** Folder to start in instead of the shell's. */
    startPath: String? = null,
) {
    val context = LocalContext.current
    val cwd = remember { session.guessCwd() }
    var path by rememberSaveable { mutableStateOf(startPath ?: cwd) }
    var dir by remember { mutableStateOf<RemoteDir?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RemoteEntry?>(null) }

    val typedPath = query.startsWith("/") || query == "~" || query.startsWith("~/")
    val target = if (typedPath) query.substringBeforeLast('/', "~").ifEmpty { "/" } else path
    val filter = if (typedPath) query.substringAfterLast('/', "") else query

    LaunchedEffect(target, refreshTick) {
        if (typedPath) delay(250)
        error = null
        try {
            dir = session.listDirectory(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val current = dir
    // Paths relative to the shell's folder when inside it; otherwise ~/… or absolute.
    val cwdAbsolute = current?.let { d -> if (cwd == "~") d.home else if (cwd.startsWith("~/")) d.home + cwd.removePrefix("~") else cwd }
    fun pathForShell(entry: RemoteEntry): String {
        val home = current?.home ?: ""
        val relative = cwdAbsolute?.let { base -> entry.path.removePrefix("$base/").takeIf { entry.path.startsWith("$base/") } }
        return shellQuote(relative ?: RemoteDir.tilde(entry.path, home))
    }
    fun open(folder: String) {
        path = folder
        if (query.isNotEmpty()) onQueryChange("")
    }

    // Breadcrumb: tap a segment to jump there.
    if (current != null) {
        val segments = remember(current.path) { breadcrumb(current.path, current.home) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, (label, full) ->
                if (index > 0) Icon(Ico.chevron, null, Modifier.size(14.dp), tint = extra.muted)
                val last = index == segments.lastIndex
                TextAction(label, { open(full) }, color = if (last) extra.fg else extra.link)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectChip(if (showHidden) "Hidden shown" else "Show hidden", showHidden) { showHidden = !showHidden }
            OutlinedAction("cd here", { onRun("cd -- " + shellQuote(RemoteDir.tilde(current.path, current.home))) }, height = 36.dp, radius = 18.dp)
            IconAction(Ico.paste, "Insert this folder", { onInsert(shellQuote(RemoteDir.tilde(current.path, current.home))) }, tint = extra.muted, iconSize = 18.dp, size = 40.dp)
            IconAction(Ico.refresh, "Refresh", { refreshTick++ }, tint = extra.muted, iconSize = 18.dp, size = 40.dp)
        }
    }

    val visible = remember(current, showHidden, filter) {
        val entries = current?.entries.orEmpty().filter { showHidden || !it.name.startsWith(".") || filter.startsWith(".") }
        if (filter.isBlank()) {
            entries.map { it to IntArray(0) }
        } else {
            val byName = entries.associateBy { it.name }
            Fuzzy.filter(filter, entries.map { it.name }, limit = 500).mapNotNull { m -> byName[m.text]?.let { it to m.positions } }
        }
    }
    Text(
        when {
            error != null -> "Couldn’t open: $error"
            current == null -> "Loading…"
            visible.isEmpty() -> if (filter.isBlank()) "Empty folder" else "No match in this folder"
            else -> "${visible.size} items"
        },
        fontSize = 12.sp, color = if (error != null) extra.red else extra.muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    )
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
        if (current != null && current.path != "/" && filter.isBlank()) {
            item(key = "..") {
                EntryRow(name = "..", positions = IntArray(0), isDir = true, detail = "Parent folder", onClick = {
                    open(current.path.substringBeforeLast('/').ifEmpty { "/" })
                }, onLongClick = null)
            }
        }
        items(visible, key = { it.first.path }) { (entry, positions) ->
            EntryRow(
                name = entry.name + if (entry.isDir) "/" else "",
                positions = positions,
                isDir = entry.isDir,
                detail = buildString {
                    if (entry.isLink) append("link · ")
                    if (!entry.isDir) append(humanSize(entry.size)).append(" · ")
                    if (entry.modified > 0) append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.modified)))
                },
                onClick = { if (entry.isDir) open(entry.path) else onOpenFile(entry.path) },
                onLongClick = { selected = entry },
            )
        }
    }

    selected?.let { entry ->
        val shellPath = pathForShell(entry)
        Sheet(onDismiss = { selected = null }) {
            Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            Text(
                RemoteDir.tilde(entry.path, current?.home ?: ""), style = MonoStyle, fontSize = 12.sp, color = extra.muted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
            SheetRow("Insert path", Ico.paste) { selected = null; onInsert(shellPath) }
            if (entry.isDir) {
                SheetRow("Open folder", Ico.folder) { selected = null; open(entry.path) }
                SheetRow("cd here", Ico.term) { selected = null; onRun("cd -- $shellPath") }
            } else {
                SheetRow("Open in viewer", Ico.file) { selected = null; onOpenFile(entry.path) }
                SheetRow("Open in \$EDITOR", Ico.edit) { selected = null; onRun("\${EDITOR:-vi} $shellPath") }
                SheetRow("View with less", Ico.term) { selected = null; onRun("less $shellPath") }
                SheetRow("cd to its folder", Ico.term) {
                    selected = null
                    onRun("cd -- " + shellQuote(RemoteDir.tilde(entry.path.substringBeforeLast('/').ifEmpty { "/" }, current?.home ?: "")))
                }
            }
            SheetRow("Copy path", Ico.copy) {
                copyText(context, entry.path, "Path")
                Snack.show("Path copied")
                selected = null
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(name: String, positions: IntArray, isDir: Boolean, detail: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val accent = extra.link
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (isDir) Ico.folder else Ico.file, null, Modifier.size(20.dp), tint = if (isDir) accent else extra.muted)
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(name)
                    positions.forEach { if (it < name.length) addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), it, it + 1) }
                },
                style = MonoStyle, fontSize = 14.sp, color = extra.fg, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = extra.muted, maxLines = 1)
        }
    }
}

/** "/home/me/src" with home "/home/me" -> [~ , src]; outside home -> [/, etc, nginx]. */
private fun breadcrumb(path: String, home: String): List<Pair<String, String>> {
    val underHome = home.length > 1 && (path == home || path.startsWith("$home/"))
    val (rootLabel, rootPath, rest) = if (underHome) Triple("~", home, path.removePrefix(home)) else Triple("/", "/", path)
    val out = mutableListOf(rootLabel to rootPath)
    var acc = rootPath.trimEnd('/')
    rest.split('/').filter { it.isNotEmpty() }.forEach { part ->
        acc = "$acc/$part"
        out += part to acc
    }
    return out
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 10) "${value.toInt()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
}
