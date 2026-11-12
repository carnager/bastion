package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.carnager.bastion.session.FinderKind
import io.github.carnager.bastion.session.Fuzzy
import io.github.carnager.bastion.session.RemoteLists
import io.github.carnager.bastion.session.TerminalSession
import io.github.carnager.bastion.session.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Fuzzy finder for files, folders, commands and history. Lists come from the server over a
 * separate exec channel; matching runs on the phone. [onPick] gets text ready to insert.
 */
@Composable
fun FinderDialog(
    session: TerminalSession,
    history: List<String>,
    onPick: (text: String, kind: FinderKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val cwd = remember { session.guessCwd() }
    var base by rememberSaveable { mutableStateOf(cwd) }
    var kind by rememberSaveable { mutableStateOf(FinderKind.FILES) }
    var query by rememberSaveable { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Fuzzy.Match>>(emptyList()) }
    var changingBase by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // Typing a path navigates: "/srv/http" searches "http" in /srv, "/srv/http/" lists /srv/http.
    val pathMode = (kind == FinderKind.FILES || kind == FinderKind.FOLDERS) &&
        (query.startsWith("/") || query == "~" || query.startsWith("~/"))
    val searchBase = if (pathMode) query.substringBeforeLast('/', "~").ifEmpty { "/" } else base
    val searchQuery = if (pathMode) query.substringAfterLast('/', "") else query

    LaunchedEffect(kind, searchBase, refreshTick) {
        if (pathMode) delay(250) // don't list every folder on the way while typing a path
        items = null
        error = null
        items = if (kind == FinderKind.HISTORY) {
            history
        } else {
            try {
                session.list(kind, searchBase, refresh = refreshTick > 0)
            } catch (e: CancellationException) {
                throw e // superseded by a newer listing, not an error
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        }
    }
    LaunchedEffect(items, searchQuery) {
        val all = items ?: return@LaunchedEffect
        results = withContext(Dispatchers.Default) { Fuzzy.filter(searchQuery, all) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun pick(match: Fuzzy.Match) {
        val text = when (kind) {
            FinderKind.FILES, FinderKind.FOLDERS ->
                shellQuote(if (searchBase == cwd) match.text else RemoteLists.join(searchBase, match.text))
            else -> match.text
        }
        onPick(text, kind)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().imePadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.92f).clip(shape).background(extra.sheet).border(1.dp, extra.line, shape)
                    .padding(top = 12.dp),
            ) {
                // Search field
                Row(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .background(extra.field).border(2.dp, extra.accent, RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Ico.search, null, Modifier.size(18.dp), tint = extra.muted)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                        Text(
                            if (kind == FinderKind.FILES || kind == FinderKind.FOLDERS) "Fuzzy search, or type a /path"
                            else "Fuzzy search ${kind.label.lowercase()}",
                            color = extra.muted, fontSize = 15.sp,
                        )
                    }
                        BasicTextField(
                            query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus),
                            singleLine = true, textStyle = MonoStyle.copy(fontSize = 15.sp, color = extra.fg), cursorBrush = SolidColor(extra.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(::pick) }),
                        )
                    }
                    if (query.isNotEmpty()) IconAction(Ico.close, "Clear", { query = "" }, tint = extra.muted, iconSize = 18.dp)
                }
                // Kinds
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FinderKind.entries.forEach { k -> SelectChip(k.label, kind == k) { kind = k } }
                }
                // Where we're searching
                if (kind == FinderKind.FILES || kind == FinderKind.FOLDERS) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("in", fontSize = 13.sp, color = extra.muted)
                        Text(
                            searchBase, style = MonoStyle, fontSize = 13.sp, color = extra.fg2, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (base != "/") TextAction("Up", { base = parentOf(base) })
                        TextAction("Change", { changingBase = true })
                        IconAction(Ico.refresh, "Refresh", { refreshTick++ }, tint = extra.muted, iconSize = 18.dp, size = 40.dp)
                    }
                    if (!pathMode && !session.cwdIsReported && base == cwd) {
                        Hint(
                            if (cwd == "~") "Couldn’t tell the shell’s folder; searching home." else "Folder taken from your prompt.",
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                // Status
                val status = when {
                    error != null -> "Couldn’t list: $error"
                    items == null -> "Loading…"
                    items!!.isEmpty() -> "Nothing here"
                    items!!.size >= RemoteLists.LIMIT && kind != FinderKind.HISTORY -> "${results.size} shown · first ${RemoteLists.LIMIT} entries searched"
                    else -> "${results.size} of ${items!!.size}"
                }
                Text(
                    status, fontSize = 12.sp, color = if (error != null) extra.red else extra.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                    itemsIndexed(results, key = { index, m -> "$index:${m.text}" }) { _, match ->
                        ResultRow(match, kind) { pick(match) }
                    }
                }
            }
        }
    }

    if (changingBase) {
        var path by remember { mutableStateOf(base) }
        AlertDialog(
            onDismissRequest = { changingBase = false },
            title = { Text("Search in") },
            text = { TextInput(path, { path = it }, mono = true, placeholder = "~/src or /etc") },
            confirmButton = {
                TextButton(enabled = path.isNotBlank(), onClick = { base = path.trim().trimEnd('/').ifEmpty { "/" }; changingBase = false }) { Text("Search") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { base = cwd; changingBase = false }) { Text("Shell’s folder") }
                    TextButton(onClick = { changingBase = false }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun ResultRow(match: Fuzzy.Match, kind: FinderKind, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when (kind) {
                FinderKind.FILES -> Ico.file
                FinderKind.FOLDERS -> Ico.folder
                FinderKind.COMMANDS -> Ico.term
                FinderKind.HISTORY -> Ico.history
            },
            null, Modifier.size(18.dp), tint = extra.muted,
        )
        Text(highlight(match), style = MonoStyle, fontSize = 13.5.sp, color = extra.fg, maxLines = 2, overflow = TextOverflow.StartEllipsis)
    }
}

@Composable
private fun highlight(match: Fuzzy.Match): AnnotatedString {
    val accent = extra.link
    return remember(match, accent) {
        buildAnnotatedString {
            append(match.text)
            val style = SpanStyle(color = accent, fontWeight = FontWeight.Bold)
            match.positions.forEach { if (it < match.text.length) addStyle(style, it, it + 1) }
        }
    }
}

private fun parentOf(path: String): String = when {
    path == "~" || path.endsWith("/..") -> "$path/.."
    path.count { it == '/' } <= 1 && path.startsWith("/") -> "/"
    else -> path.substringBeforeLast('/').ifEmpty { "/" }
}
