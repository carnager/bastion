package io.github.carnager.bastion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
private data class Fill(
    val title: String,
    val template: String,
    val danger: Boolean,
    val values: Map<String, String> = emptyMap(),
    /** Opens a native screen instead of a preview, e.g. "service". */
    val native: String? = null,
) {
    val fields: List<Field> = Templates.fields(template)
    val step: Int get() = fields.indexOfFirst { it.token !in values }.let { if (it < 0) fields.size else it }
    val field: Field? get() = fields.getOrNull(step)
    val command: String get() = Templates.fill(template, values)
}

private sealed interface CommandItem {
    val key: String
    val title: String
    val template: String
    val group: String
    val danger: Boolean
    val native: String? get() = null

    data class Builtin(val entry: CatalogEntry) : CommandItem {
        override val key get() = "b:" + entry.id
        override val title get() = entry.title
        override val template get() = entry.template
        override val group get() = entry.group
        override val danger get() = entry.danger
        override val native get() = entry.native
    }

    data class Own(val snippet: Snippet) : CommandItem {
        override val key get() = "s:" + snippet.id
        override val title get() = snippet.title
        override val template get() = snippet.template
        override val group get() = "Your snippets"
        override val danger get() = false
    }
}

/**
 * Ready-made commands and the user's snippets for one session. Picking one walks through its
 * fields (services, containers… listed from the server) and ends in a preview.
 */
@Composable
fun CommandsScreen(
    session: TerminalSession,
    snippets: List<Snippet>,
    onSaveSnippet: (Snippet) -> Unit,
    onDeleteSnippet: (String) -> Unit,
    onRun: (String) -> Unit,
    onInsert: (String) -> Unit,
    onEdit: (String) -> Unit,
    /** A native view for a filled command: kind ("service") and the first field's value. */
    onOpenNative: (kind: String, value: String) -> Unit,
    onBack: () -> Unit,
) {
    val host = session.host
    var tools by remember { mutableStateOf<Set<String>?>(null) }
    var probed by remember { mutableStateOf(false) }
    var fill by remember { mutableStateOf<Fill?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var managing by remember { mutableStateOf<CommandItem?>(null) }
    LaunchedEffect(Unit) {
        tools = session.tools()
        probed = true
    }

    fun stepBack() {
        val current = fill ?: return onBack()
        val filled = current.fields.filter { it.token in current.values }
        fill = if (filled.isEmpty()) null else current.copy(values = current.values - filled.last().token)
        query = ""
    }
    BackHandler(enabled = fill != null) { stepBack() }

    val current = fill
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(
            title = current?.title ?: "Commands",
            onNav = { if (current != null) stepBack() else onBack() },
        ) {
            if (current == null) IconAction(Ico.plus, "New snippet", { editing = Snippet(UUID.randomUUID().toString(), "", "", host.id) })
        }
        Text(
            when {
                current == null -> if (probed) "On ${host.label}" else "Checking what ${host.label} has…"
                current.field != null -> "Step ${current.step + 1} of ${current.fields.size} · Choose ${current.field!!.label.lowercase()}"
                else -> "Ready to run"
            },
            fontSize = 13.sp, color = extra.muted,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 10.dp),
        )

        val field = current?.field
        val searchable = current == null || (field != null && field.type != FieldType.TEXT)
        if (searchable) {
            SearchField(
                query, { query = it },
                if (current == null) "Search commands" else "Search or type ${field!!.label.lowercase()}",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        if (current != null) {
            Text(
                commandText(current.command), style = MonoStyle, fontSize = 13.sp, color = extra.fg2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 10.dp),
            )
        }

        when {
            current == null -> CommandList(
                items = remember(tools, snippets) {
                    snippets.map { CommandItem.Own(it) } + Catalog.available(tools).map { CommandItem.Builtin(it) }
                },
                query = query,
                onPick = { item ->
                    fill = Fill(item.title, item.template, item.danger, native = item.native)
                    query = ""
                },
                onLongPress = { managing = it },
            )
            field == null -> Preview(current, onRun, onInsert, onEdit)
            field.type == FieldType.TEXT -> TextStep(field) { value -> fill = current.copy(values = current.values + (field.token to value)) }
            else -> PickStep(field, session, query) { value ->
                val next = current.copy(values = current.values + (field.token to value))
                query = ""
                if (next.native != null && next.field == null) {
                    // Fully chosen: show it natively; Back from there returns to the list.
                    fill = null
                    onOpenNative(next.native, next.values.values.first())
                } else {
                    fill = next
                }
            }
        }
    }

    editing?.let { snippet ->
        SnippetEditor(snippet, host.id, host.label, onSave = { onSaveSnippet(it); editing = null; Snack.show("Snippet saved") }, onDismiss = { editing = null })
    }
    managing?.let { item ->
        Sheet(onDismiss = { managing = null }) {
            Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            Text(item.template, style = MonoStyle, fontSize = 12.sp, color = extra.muted, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            SheetRow("Run", Ico.enter) { managing = null; fill = Fill(item.title, item.template, item.danger) }
            when (item) {
                is CommandItem.Own -> {
                    SheetRow("Edit snippet", Ico.edit) { managing = null; editing = item.snippet }
                    SheetRow("Delete snippet", Ico.trash, color = extra.red) { managing = null; onDeleteSnippet(item.snippet.id) }
                }
                is CommandItem.Builtin -> SheetRow("Save a copy as snippet", Ico.copy) {
                    managing = null
                    editing = Snippet(UUID.randomUUID().toString(), item.title, item.template, host.id)
                }
            }
        }
    }
}

/** Catalog and snippets, grouped into cards; a flat ranked list while searching. */
@Composable
private fun CommandList(items: List<CommandItem>, query: String, onPick: (CommandItem) -> Unit, onLongPress: (CommandItem) -> Unit) {
    val ranked = remember(items, query) {
        if (query.isBlank()) null
        else {
            val byText = items.associateBy { it.title + "  " + it.template }
            Fuzzy.filter(query, byText.keys.toList(), limit = 100).mapNotNull { m ->
                byText[m.text]?.let { item -> item to m.positions.filter { it < item.title.length }.toIntArray() }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        if (ranked != null) {
            if (ranked.isEmpty()) item { Hint("Nothing matches “$query”.", Modifier.padding(8.dp)) }
            else card("results", null, ranked, onPick, onLongPress)
        } else {
            items.groupBy { it.group }.forEach { (group, inGroup) ->
                card(group, group, inGroup.map { it to IntArray(0) }, onPick, onLongPress)
            }
        }
    }
}

private fun LazyListScope.card(
    key: String,
    label: String?,
    rows: List<Pair<CommandItem, IntArray>>,
    onPick: (CommandItem) -> Unit,
    onLongPress: (CommandItem) -> Unit,
) {
    item(key = "card:$key") {
        Column {
            if (label != null) SectionLabel(label, Modifier.padding(top = 8.dp))
            InfoCard {
                rows.forEachIndexed { index, (item, positions) ->
                    if (index > 0) HorizontalDivider(color = extra.line)
                    CommandRow(item, positions, { onPick(item) }, { onLongPress(item) })
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandRow(item: CommandItem, positions: IntArray, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "More")
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                buildAnnotatedString {
                    append(item.title)
                    positions.forEach { addStyle(SpanStyle(color = extra.link, fontWeight = FontWeight.Bold), it, it + 1) }
                },
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(commandText(item.template), style = MonoStyle, fontSize = 12.sp, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (item.danger) Dot(Palette.Amber, 7.dp)
        Icon(Ico.chevron, null, Modifier.size(18.dp), tint = extra.muted)
    }
}

/** A command with its open fields shown as accent-colored placeholders. */
@Composable
private fun commandText(text: String): AnnotatedString {
    val accent = extra.link
    return remember(text, accent) {
        buildAnnotatedString {
            append(text)
            Templates.fields(text).forEach { field ->
                var from = 0
                while (true) {
                    val at = text.indexOf(field.token, from)
                    if (at < 0) break
                    addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), at, at + field.token.length)
                    from = at + field.token.length
                }
            }
        }
    }
}

@Composable
private fun PickStep(field: Field, session: TerminalSession, query: String, onValue: (String) -> Unit) {
    var choices by remember(field.token) { mutableStateOf<List<Catalog.Choice>?>(null) }
    var error by remember(field.token) { mutableStateOf<String?>(null) }
    LaunchedEffect(field.token) {
        try {
            choices = when (field.type) {
                FieldType.FILE -> session.list(FinderKind.FILES, session.guessCwd()).map { Catalog.Choice(it, it, "") }
                FieldType.FOLDER -> listOf(Catalog.Choice(".", ".", "The shell’s current folder")) +
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
    val icon = when (field.type) {
        FieldType.FOLDER -> Ico.folder
        FieldType.FILE -> Ico.file
        FieldType.CONTAINER -> Ico.box
        FieldType.PROCESS -> Ico.cpu
        else -> Ico.server
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item(key = "status") {
            Hint(
                when {
                    error != null -> "Couldn’t list ${field.type.noun}s: $error"
                    all == null -> "Loading ${field.type.noun}s…"
                    all.isEmpty() -> "No ${field.type.noun}s found. Type one in the search field."
                    else -> "${shown.size} of ${all.size}"
                },
                Modifier.padding(start = 4.dp, bottom = 8.dp),
                color = if (error != null) extra.red else extra.muted,
            )
        }
        if (query.isNotBlank() || shown.isNotEmpty()) {
            item(key = "list") {
                InfoCard {
                    var first = true
                    if (query.isNotBlank()) {
                        ChoiceRow("Use “${query.trim()}”", IntArray(0), "As typed", Ico.edit) { onValue(query.trim()) }
                        first = false
                    }
                    shown.forEach { (choice, positions) ->
                        if (!first) HorizontalDivider(color = extra.line)
                        first = false
                        ChoiceRow(choice.label, positions, choice.detail, icon) { onValue(choice.value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, positions: IntArray, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = extra.muted)
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append(label)
                    positions.forEach { if (it < label.length) addStyle(SpanStyle(color = extra.link, fontWeight = FontWeight.Bold), it, it + 1) }
                },
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            if (detail.isNotEmpty()) Text(detail, fontSize = 13.sp, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TextStep(field: Field, onValue: (String) -> Unit) {
    var text by remember(field.token) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(field.token) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InputLabel(field.label)
            TextInput(text, { text = it }, Modifier.focusRequester(focus), mono = true, placeholder = field.label)
        }
        AccentButton("Next", { onValue(text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
    }
}

@Composable
private fun Preview(fill: Fill, onRun: (String) -> Unit, onInsert: (String) -> Unit, onEdit: (String) -> Unit) {
    val command = fill.command
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val shape = RoundedCornerShape(16.dp)
        Text(
            command, style = MonoStyle, fontSize = 15.sp, color = extra.fg,
            modifier = Modifier.fillMaxWidth().clip(shape).background(extra.field).border(1.dp, extra.line, shape).padding(16.dp),
        )
        if (fill.danger) {
            Spacer(Modifier.height(12.dp))
            WarnBanner { Text("This changes the system. Check it before running.", Modifier.weight(1f), fontSize = 13.sp, color = extra.warnFg) }
        }
        Spacer(Modifier.height(20.dp))
        val runColor = if (fill.danger) Palette.Bad else extra.accent
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(runColor).clickable { onRun(command) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Text("Run", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Icon(Ico.enter, null, Modifier.size(18.dp), tint = Color.White)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedAction("Edit first", { onEdit(command) }, Modifier.weight(1f), icon = Ico.edit, height = 50.dp)
            OutlinedAction("Insert only", { onInsert(command) }, Modifier.weight(1f), height = 50.dp)
        }
        Spacer(Modifier.height(24.dp))
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
