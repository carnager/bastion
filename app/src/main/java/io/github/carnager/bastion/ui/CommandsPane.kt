package io.github.carnager.bastion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.data.Snippet
import io.github.carnager.bastion.session.Catalog
import io.github.carnager.bastion.session.CatalogEntry
import io.github.carnager.bastion.session.Field
import io.github.carnager.bastion.session.FieldType
import io.github.carnager.bastion.session.FinderKind
import io.github.carnager.bastion.session.Fuzzy
import io.github.carnager.bastion.session.Templates
import io.github.carnager.bastion.session.TerminalSession
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** A command being filled in: one step per field, then a preview. */
private data class Fill(val title: String, val template: String, val danger: Boolean, val values: Map<String, String> = emptyMap()) {
    val fields: List<Field> = Templates.fields(template)
    val step: Int get() = fields.indexOfFirst { it.token !in values }.let { if (it < 0) fields.size else it }
    val command: String get() = Templates.fill(template, values)
}

private sealed interface CommandItem {
    val title: String
    val template: String
    data class Builtin(val entry: CatalogEntry) : CommandItem {
        override val title get() = entry.title
        override val template get() = entry.template
    }
    data class Own(val snippet: Snippet) : CommandItem {
        override val title get() = snippet.title
        override val template get() = snippet.template
    }
}

@Composable
internal fun ColumnScope.CommandsPane(
    session: TerminalSession,
    hostId: String,
    hostLabel: String,
    snippets: List<Snippet>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSaveSnippet: (Snippet) -> Unit,
    onDeleteSnippet: (String) -> Unit,
    onRun: (String) -> Unit,
    onInsert: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    var tools by remember { mutableStateOf<Set<String>?>(null) }
    var probed by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf<Fill?>(null) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var managing by remember { mutableStateOf<Snippet?>(null) }
    LaunchedEffect(Unit) {
        tools = session.tools()
        probed = true
    }

    fun start(title: String, template: String, danger: Boolean) {
        fill = Fill(title, template, danger)
        onQueryChange("")
    }

    val current = fill
    if (current == null) {
        val rows: List<CommandItem> = remember(tools, snippets) {
            snippets.map { CommandItem.Own(it) } + Catalog.available(tools).map { CommandItem.Builtin(it) }
        }
        val shown = remember(rows, query) {
            if (query.isBlank()) rows.map { it to IntArray(0) }
            else {
                val byKey = rows.associateBy { it.title + "  " + it.template }
                Fuzzy.filter(query, byKey.keys.toList(), limit = 200).mapNotNull { m ->
                    byKey[m.text]?.let { it to m.positions.filter { p -> p < it.title.length }.toIntArray() }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (!probed) "Checking what $hostLabel has…" else "${shown.size} commands",
                fontSize = 12.sp, color = extra.muted, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            OutlinedAction("New snippet", { editing = Snippet(UUID.randomUUID().toString(), "", "", hostId) }, icon = Ico.plus, height = 36.dp, radius = 18.dp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            var lastGroup: String? = null
            shown.forEach { (row, positions) ->
                val group = when (row) {
                    is CommandItem.Own -> "Your snippets"
                    is CommandItem.Builtin -> row.entry.group
                }
                if (query.isBlank() && group != lastGroup) {
                    lastGroup = group
                    item(key = "g:$group") { SectionLabel(group, Modifier.padding(start = 8.dp, top = 10.dp)) }
                }
                item(key = when (row) { is CommandItem.Own -> "s:" + row.snippet.id; is CommandItem.Builtin -> "b:" + row.entry.id }) {
                    CommandRow(
                        title = row.title, template = row.template, positions = positions,
                        danger = (row as? CommandItem.Builtin)?.entry?.danger == true,
                        own = row is CommandItem.Own,
                        onClick = { start(row.title, row.template, (row as? CommandItem.Builtin)?.entry?.danger == true) },
                        onLongClick = { if (row is CommandItem.Own) managing = row.snippet else editing = Snippet(UUID.randomUUID().toString(), row.title, row.template, hostId) },
                    )
                }
            }
        }
    } else {
        FillSteps(
            fill = current,
            session = session,
            query = query,
            onQueryChange = onQueryChange,
            onValue = { token, value ->
                fill = current.copy(values = current.values + (token to value))
                onQueryChange("")
            },
            onBack = {
                // Step back one field, or leave the flow from the first step.
                val filled = current.fields.filter { it.token in current.values }
                fill = if (filled.isEmpty()) null else current.copy(values = current.values - filled.last().token)
                onQueryChange("")
            },
            onRun = onRun, onInsert = onInsert, onEdit = onEdit,
        )
    }

    editing?.let { snippet ->
        SnippetEditor(snippet, hostId, hostLabel, onSave = { onSaveSnippet(it); editing = null; Snack.show("Snippet saved") }, onDismiss = { editing = null })
    }
    managing?.let { snippet ->
        Sheet(onDismiss = { managing = null }) {
            Text(snippet.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            Text(snippet.template, style = MonoStyle, fontSize = 12.sp, color = extra.muted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            SheetRow("Run", Ico.enter) { managing = null; start(snippet.title, snippet.template, false) }
            SheetRow("Edit", Ico.edit) { managing = null; editing = snippet }
            SheetRow("Delete", Ico.trash, color = extra.red) { managing = null; onDeleteSnippet(snippet.id) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandRow(title: String, template: String, positions: IntArray, danger: Boolean, own: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (own) "Edit snippet" else "Save as snippet")
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                buildAnnotatedString {
                    append(title)
                    positions.forEach { addStyle(SpanStyle(color = extra.link, fontWeight = FontWeight.Bold), it, it + 1) }
                },
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(templateText(template), style = MonoStyle, fontSize = 12.sp, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (danger) Dot(Palette.Amber, 7.dp)
    }
}

/** The template with its fields shown as accent-colored placeholders. */
@Composable
private fun templateText(template: String): AnnotatedString {
    val accent = extra.link
    return remember(template, accent) {
        buildAnnotatedString {
            append(template)
            Templates.fields(template).forEach { field ->
                var from = 0
                while (true) {
                    val at = template.indexOf(field.token, from)
                    if (at < 0) break
                    addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), at, at + field.token.length)
                    from = at + field.token.length
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.FillSteps(
    fill: Fill,
    session: TerminalSession,
    query: String,
    onQueryChange: (String) -> Unit,
    onValue: (String, String) -> Unit,
    onBack: () -> Unit,
    onRun: (String) -> Unit,
    onInsert: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val step = fill.step
    val field = fill.fields.getOrNull(step)
    // Header: back, title, progress.
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Ico.back, "Back", onBack, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(fill.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (field != null) "Step ${step + 1} of ${fill.fields.size}: ${field.label}" else "Ready",
                fontSize = 12.sp, color = extra.muted,
            )
        }
    }
    // The command so far, fields still open highlighted.
    Text(
        templateText(Templates.fill(fill.template, fill.values)), style = MonoStyle, fontSize = 13.sp, color = extra.fg2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )

    when {
        field == null -> Preview(fill, onRun, onInsert, onEdit)
        field.type == FieldType.TEXT -> TextStep(field, onValue)
        else -> PickStep(field, session, query, onValue)
    }
}

@Composable
private fun ColumnScope.PickStep(field: Field, session: TerminalSession, query: String, onValue: (String, String) -> Unit) {
    var choices by remember(field.token) { mutableStateOf<List<Catalog.Choice>?>(null) }
    var error by remember(field.token) { mutableStateOf<String?>(null) }
    LaunchedEffect(field.token) {
        try {
            choices = when (field.type) {
                FieldType.FILE -> session.list(FinderKind.FILES, session.guessCwd()).map { Catalog.Choice(it, it, "") }
                FieldType.FOLDER -> listOf(Catalog.Choice(".", ".", "this folder")) +
                    session.list(FinderKind.FOLDERS, session.guessCwd()).map { Catalog.Choice(it.trimEnd('/'), it, "") }
                else -> session.choices(field.type)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            choices = emptyList()
        }
    }
    val all = choices
    val shown = remember(all, query) {
        when {
            all == null -> emptyList()
            query.isBlank() -> all.take(300).map { it to IntArray(0) }
            else -> {
                val byLabel = all.groupBy { it.label }
                Fuzzy.filter(query, byLabel.keys.toList(), limit = 300).flatMap { m -> byLabel[m.text].orEmpty().map { it to m.positions } }
            }
        }
    }
    Text(
        when {
            error != null -> "Couldn’t list: $error"
            all == null -> "Loading ${field.type.noun}s…"
            all.isEmpty() -> "No ${field.type.noun}s found; type one above"
            else -> "Pick a ${field.type.noun} · ${shown.size} of ${all.size}"
        },
        fontSize = 12.sp, color = if (error != null) extra.red else extra.muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
        if (query.isNotBlank()) {
            item(key = "typed") {
                ChoiceRow("Use “${query.trim()}”", IntArray(0), "as typed", Ico.edit) { onValue(field.token, query.trim()) }
            }
        }
        items(shown, key = { it.first.value + "|" + it.first.label }) { (choice, positions) ->
            ChoiceRow(
                choice.label, positions, choice.detail,
                when (field.type) {
                    FieldType.FOLDER -> Ico.folder
                    FieldType.FILE -> Ico.file
                    else -> Ico.term
                },
            ) { onValue(field.token, choice.value) }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, positions: IntArray, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = extra.muted)
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(label)
                    positions.forEach { if (it < label.length) addStyle(SpanStyle(color = extra.link, fontWeight = FontWeight.Bold), it, it + 1) }
                },
                style = MonoStyle, fontSize = 14.sp, color = extra.fg, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ColumnScope.TextStep(field: Field, onValue: (String, String) -> Unit) {
    var text by remember(field.token) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(field.token) { runCatching { focus.requestFocus() } }
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InputLabel(field.label)
        TextInput(text, { text = it }, Modifier.focusRequester(focus), mono = true, placeholder = field.label)
        AccentButton("Next", { onValue(field.token, text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank(), height = 48.dp)
    }
}

@Composable
private fun ColumnScope.Preview(fill: Fill, onRun: (String) -> Unit, onInsert: (String) -> Unit, onEdit: (String) -> Unit) {
    val command = fill.command
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val shape = RoundedCornerShape(14.dp)
        Text(
            command, style = MonoStyle, fontSize = 15.sp, color = extra.fg,
            modifier = Modifier.fillMaxWidth().clip(shape).background(extra.field).border(1.dp, extra.line, shape).padding(14.dp),
        )
        if (fill.danger) {
            Spacer(Modifier.height(10.dp))
            WarnBanner { Text("This changes the system. Check it before running.", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedAction("Edit", { onEdit(command) }, Modifier.weight(1f), icon = Ico.edit)
            OutlinedAction("Insert", { onInsert(command) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val runColor = if (fill.danger) Palette.Bad else extra.accent
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(runColor).clickable { onRun(command) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Text("Run", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Icon(Ico.enter, null, Modifier.size(18.dp), tint = Color.White)
        }
    }
}

/** Create or edit a snippet. */
@Composable
internal fun SnippetEditor(snippet: Snippet, hostId: String, hostLabel: String, onSave: (Snippet) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(snippet.title) }
    var template by remember { mutableStateOf(snippet.template) }
    var thisHost by remember { mutableStateOf(snippet.hostId != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (snippet.title.isEmpty() && snippet.template.isEmpty()) "New snippet" else "Snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextInput(title, { title = it }, placeholder = "Name, e.g. Restart web stack")
                TextInput(template, { template = it }, mono = true, singleLine = false, minHeight = 90.dp, placeholder = "docker restart {container}")
                Text(
                    "Fields: {unit}, {container}, {process}, {file}, {folder} pick from the server; {name:Label} asks for text.",
                    fontSize = 12.sp, color = extra.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("Only $hostLabel", thisHost) { thisHost = true }
                    SelectChip("All hosts", !thisHost) { thisHost = false }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = template.isNotBlank(),
                onClick = {
                    onSave(
                        snippet.copy(
                            title = title.trim().ifEmpty { template.trim().take(40) },
                            template = template.trim(),
                            hostId = if (thisHost) hostId else null,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
