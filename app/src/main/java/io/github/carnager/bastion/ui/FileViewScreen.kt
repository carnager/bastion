package io.github.carnager.bastion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.RemoteFile
import io.github.carnager.bastion.session.SaveResult
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Largest file that can be edited in place; bigger ones open read-only. */
private const val EDIT_LIMIT = 1024 * 1024

/**
 * A remote text file in a native view: line numbers, wrap toggle, search; "Edit" makes it a
 * normal text field and Save writes back over SFTP (or via sudo in the terminal).
 */
@Composable
fun FileViewScreen(
    session: TerminalSession,
    path: String,
    onBack: () -> Unit,
    /** Run a command in the terminal and go back to it (save with sudo). */
    onRunInTerminal: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<RemoteFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var modified by remember { mutableStateOf(0L) }
    var saving by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "conflict", "permission", "discard"
    val listState = rememberLazyListState()

    LaunchedEffect(path, reloadTick) {
        error = null
        try {
            val f = session.readFile(path)
            file = f
            text = f.text.orEmpty()
            modified = f.modified
            editing = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    val f = file
    val dirty = f != null && editing && text != f.text
    val name = path.substringAfterLast('/')
    val lines = remember(text) { text.split('\n') }
    val matches = remember(lines, query) {
        if (query.isBlank()) emptyList()
        else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    }
    LaunchedEffect(matches, matchIndex) {
        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(maxOf(0, it - 3)) }
    }

    fun save(force: Boolean = false) {
        val current = file ?: return
        saving = true
        scope.launch {
            when (val result = session.writeFile(current.path, text, modified, force)) {
                SaveResult.Saved -> {
                    modified = session.modifiedTime(current.path)
                    file = current.copy(text = text)
                    editing = false
                    Snack.show("Saved $name")
                }
                SaveResult.Conflict -> dialog = "conflict"
                SaveResult.PermissionDenied -> dialog = "permission"
                is SaveResult.Failed -> Snack.show("Couldn’t save: ${result.message}")
            }
            saving = false
        }
    }

    BackHandler(enabled = dirty || searching) {
        if (searching) { searching = false; query = "" } else dialog = "discard"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(name, nav = Ico.back, onNav = { if (dirty) dialog = "discard" else onBack() }) {
            if (f?.text != null) {
                if (!editing) {
                    IconAction(Ico.search, "Search", { searching = !searching; if (!searching) query = "" })
                    IconAction(if (wrap) Ico.unwrap else Ico.wrap, if (wrap) "Don’t wrap lines" else "Wrap lines", { wrap = !wrap })
                    if (!f.truncated && f.size <= EDIT_LIMIT) IconAction(Ico.edit, "Edit", { editing = true })
                } else {
                    TextAction("Cancel", { if (dirty) dialog = "discard" else editing = false }, color = extra.fg2)
                    AccentButton(
                        if (saving) "Saving…" else "Save", { save() }, Modifier.padding(end = 8.dp),
                        enabled = dirty && !saving, height = 40.dp, radius = 12.dp, fontSize = 14.sp,
                    )
                }
            }
        }
        Text(
            buildString {
                append(f?.path ?: path)
                if (f != null) append("  ·  ").append(humanBytes(f.size))
            },
            style = MonoStyle, fontSize = 11.5.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        if (searching && !editing) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SearchField(query, { query = it; matchIndex = 0 }, "Find in file", Modifier.weight(1f))
                Text(
                    if (query.isBlank()) "" else if (matches.isEmpty()) "0" else "${matchIndex + 1}/${matches.size}",
                    fontSize = 13.sp, color = extra.muted, modifier = Modifier.width(52.dp), textAlign = TextAlign.Center,
                )
                IconAction(Ico.up, "Previous match", { if (matches.isNotEmpty()) matchIndex = (matchIndex - 1 + matches.size) % matches.size }, size = 40.dp)
                IconAction(Ico.down, "Next match", { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size }, size = 40.dp)
            }
        }
        if (f?.truncated == true) {
            WarnBanner(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                Text("Large file: showing the first ${humanBytes(f.text?.length?.toLong() ?: 0)}. Read-only.", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(extra.card)) {
            when {
                error != null -> Message("Couldn’t open: $error") { TextAction("Try again", { reloadTick++ }) }
                f == null -> Message("Loading…")
                f.text == null -> Message("Binary file. Not shown.")
                editing -> Editor(text) { text = it }
                else -> Lines(lines, wrap, query, matches.getOrNull(matchIndex), listState)
            }
        }
    }

    when (dialog) {
        "conflict" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Changed on the server") },
            text = { Text("$name was modified since you opened it. Overwrite it with your version?") },
            confirmButton = { TextButton(onClick = { dialog = null; save(force = true) }) { Text("Overwrite", color = extra.red) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "permission" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("No permission to write") },
            text = {
                Text(
                    "Save with sudo? Your version is uploaded to a private temp file and copied over $name with sudo in the terminal, " +
                        "where sudo may ask for your password. The file keeps its owner and permissions.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    val target = file?.path ?: return@TextButton
                    scope.launch {
                        runCatching { session.writeTemp(text) }
                            .onSuccess { tmp ->
                                val q = shellQuote(tmp)
                                onRunInTerminal("sudo cp -- $q ${shellQuote(target)} && rm -f -- $q")
                            }
                            .onFailure { Snack.show("Couldn’t upload: ${it.message}") }
                    }
                }) { Text("Save with sudo") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "discard" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Discard changes?") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    text = file?.text.orEmpty()
                    editing = false
                }) { Text("Discard", color = extra.red) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 14.sp, color = extra.muted, textAlign = TextAlign.Center)
        action()
    }
}

@Composable
private fun Lines(
    lines: List<String>,
    wrap: Boolean,
    query: String,
    currentMatchLine: Int?,
    state: androidx.compose.foundation.lazy.LazyListState,
) {
    val gutter = (lines.size.toString().length * 9 + 14).dp
    val horizontal = rememberScrollState()
    val hit = SpanStyle(background = Palette.Amber.copy(alpha = 0.35f), fontWeight = FontWeight.Bold)
    val comment = extra.muted
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        itemsIndexed(lines) { index, line ->
            Row(
                Modifier.fillMaxWidth().background(if (index == currentMatchLine) extra.sel else extra.card)
                    .padding(vertical = 1.dp),
            ) {
                Text(
                    "${index + 1}", style = MonoStyle, fontSize = 12.sp, color = extra.muted.copy(alpha = 0.7f),
                    textAlign = TextAlign.End, modifier = Modifier.width(gutter).padding(end = 8.dp, top = 1.dp),
                )
                val isComment = line.trimStart().let { it.startsWith("#") || it.startsWith("//") || it.startsWith(";") }
                val styled = buildAnnotatedString {
                    append(line.ifEmpty { " " })
                    if (query.isNotBlank()) {
                        var from = 0
                        while (true) {
                            val at = line.indexOf(query, from, ignoreCase = true)
                            if (at < 0) break
                            addStyle(hit, at, at + query.length)
                            from = at + query.length
                        }
                    }
                }
                Text(
                    styled, style = MonoStyle, fontSize = 13.sp, color = if (isComment) comment else extra.fg, softWrap = wrap,
                    modifier = Modifier.weight(1f).then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)).padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Editor(text: String, onChange: (String) -> Unit) {
    BasicTextField(
        text, onChange,
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        textStyle = MonoStyle.copy(fontSize = 13.sp, color = extra.fg),
        cursorBrush = SolidColor(extra.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
    )
}

private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
