package io.github.carnager.bastion.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.carnager.bastion.session.FinderKind
import io.github.carnager.bastion.session.CommandLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.carnager.bastion.data.ColorSchemes
import io.github.carnager.bastion.data.TermScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.session.SessionStatus
import io.github.carnager.bastion.session.TerminalSession
import kotlinx.coroutines.launch
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/** Terminal chrome (bars, keys) derived from the active color scheme, so light schemes get light bars. */
internal data class Chrome(val bg: Color, val fg: Color, val bar: Color, val line: Color, val keyBg: Color, val key: Color, val accent: Color) {
    companion object {
        fun of(s: TermScheme): Chrome {
            val bg = s.color(s.background)
            val fg = s.color(s.foreground)
            return Chrome(
                bg = bg, fg = fg,
                bar = lerp(bg, fg, 0.06f), line = lerp(bg, fg, 0.14f), keyBg = lerp(bg, fg, 0.14f),
                key = lerp(fg, bg, 0.12f), accent = s.color(s.ansi[12]),
            )
        }
    }
}

internal val LocalChrome = staticCompositionLocalOf { Chrome.of(ColorSchemes.all.first()) }
private val TerminalBackground: Color @Composable get() = LocalChrome.current.bg
private val BarColor: Color @Composable get() = LocalChrome.current.bar
private val KeyText: Color @Composable get() = LocalChrome.current.key
private val Accent: Color @Composable get() = LocalChrome.current.accent

private object Dimens {
    val barHeight = 40.dp
    val keyHeight = 40.dp
}

@Composable
fun TerminalScreen(
    session: TerminalSession,
    sessions: List<TerminalSession>,
    onSwitch: (Long) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    globalSchemeId: String,
    hostSchemeId: String?,
    onSetGlobalScheme: (String) -> Unit,
    onSetHostScheme: (String?) -> Unit,
    fontSize: Float,
    onFontSize: (Float) -> Unit,
    history: List<String>,
    suggestionsEnabled: Boolean,
) {
    val status by session.status.collectAsState()
    val scheme by session.scheme.collectAsState()
    val chrome = remember(scheme) { Chrome.of(scheme) }
    val modifiers = remember { StickyModifiers() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    /** Non-null while the input field is open. */
    var editing by remember(session.id) { mutableStateOf<TextFieldValue?>(null) }
    var finding by remember { mutableStateOf(false) }
    val termFocus = remember { FocusRequester() }
    val openEditor = {
        val text = session.currentCommand().orEmpty()
        editing = TextFieldValue(text, TextRange(text.length))
    }
    val closeEditor: () -> Unit = {
        editing = null
        runCatching { termFocus.requestFocus() }
    }
    BackHandler(enabled = editing != null) { closeEditor() }

    // What's typed after the (learned) prompt, refreshed as the screen changes.
    val typed by produceState<String?>(null, session, suggestionsEnabled) {
        if (!suggestionsEnabled) return@produceState
        session.screen.changes?.collectLatest {
            delay(60)
            value = session.prompt?.let { p -> session.screen.read()?.let { CommandLine.current(it, p) } }
        }
    }
    val suggestions = remember(typed, history, suggestionsEnabled) {
        if (suggestionsEnabled) CommandLine.suggestions(history, typed.orEmpty()) else emptyList()
    }

    // Status bar icons readable on light schemes; restored when leaving the terminal.
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    DisposableEffect(scheme.isLight) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = scheme.isLight
        controller?.isAppearanceLightNavigationBars = scheme.isLight
        onDispose {
            controller?.isAppearanceLightStatusBars = !systemDark
            controller?.isAppearanceLightNavigationBars = !systemDark
        }
    }

    CompositionLocalProvider(LocalChrome provides chrome) {
    Column(
        Modifier.fillMaxSize().background(TerminalBackground).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SessionBar(
            current = session,
            sessions = sessions,
            onSwitch = onSwitch,
            onBack = onBack,
            onPaste = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                    if (!text.isNullOrEmpty()) session.emulator.pasteText(text.toString())
                }
            },
            onReconnect = session::reconnect,
            onClose = onClose,
            onColors = { picking = true },
            onTextSize = { sizing = true },
        )
        // Keyed by session so switching tabs gets a fresh view bound to the other emulator.
        androidx.compose.runtime.key(session.id) {
            Box(
                Modifier.weight(1f).fillMaxWidth().terminalGestures(
                    onPinch = { scale -> onFontSize(fontSize * scale) },
                    onTap = { position, size ->
                        // Tapping the line with the cursor opens it in the input field.
                        val screen = session.screen.read()
                        if (editing == null && screen != null && !screen.alternateScreen && screen.lines.isNotEmpty()) {
                            val row = (position.y / (size.height.toFloat() / screen.lines.size)).toInt()
                            if (row == screen.cursorRow) openEditor()
                        }
                    },
                ),
            ) {
            Terminal(
                terminalEmulator = session.emulator,
                modifier = Modifier.fillMaxSize(),
                initialFontSize = fontSize.sp,
                focusRequester = termFocus,
                backgroundColor = TerminalBackground,
                foregroundColor = chrome.fg,
                keyboardEnabled = true,
                modifierManager = modifiers,
                // termlib would otherwise turn Back into terminal input, which ends the shell.
                onInterceptKey = { event ->
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) onBack()
                        true
                    } else {
                        false
                    }
                },
            )
            }
        }
        if (status is SessionStatus.Ended) {
            Row(
                Modifier.fillMaxWidth().background(BarColor).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (status as SessionStatus.Ended).error ?: "Disconnected",
                    color = KeyText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = session::reconnect) { Text("Reconnect", color = Accent) }
                TextButton(onClick = onClose) { Text("Close", color = KeyText) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(chrome.line))
        // While the input field is open it replaces the keys row: those keys don't apply to it.
        AnimatedVisibility(
            visible = editing != null,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            // Keep showing the last text while the panel animates out.
            var last by remember { mutableStateOf(TextFieldValue()) }
            editing?.let { last = it }
            CommandEditor(
                value = last,
                onValueChange = { editing = it },
                history = if (suggestionsEnabled) history else emptyList(),
                onRun = { text -> session.replaceLine(text, run = true); closeEditor() },
                onInsert = { text -> session.replaceLine(text, run = false); closeEditor() },
                onClose = closeEditor,
            )
        }
        if (editing == null) {
            if (suggestions.isNotEmpty() && status is SessionStatus.Connected) {
                SuggestionStrip(suggestions) { picked ->
                    val current = typed.orEmpty()
                    if (picked.startsWith(current)) session.send(picked.removePrefix(current).encodeToByteArray())
                    else session.replaceLine(picked, run = false)
                }
            }
            ExtraKeys(session, modifiers, onEdit = openEditor, onFind = { finding = true })
        }
    }
    }

    if (finding) {
        FinderDialog(
            session = session,
            history = history,
            onDismiss = { finding = false },
            onRun = { command ->
                finding = false
                editing = null
                session.replaceLine(command, run = true)
            },
            onPick = { text, kind ->
                finding = false
                val edit = editing
                // Folders get no trailing space, so the path can be continued.
                val tail = if (kind == FinderKind.FOLDERS || (kind == FinderKind.BROWSE && text.endsWith("/"))) "" else " "
                when {
                    edit != null -> {
                        val before = edit.text.substring(0, edit.selection.start)
                        val lead = if (before.isNotEmpty() && !before.endsWith(" ")) " " else ""
                        val insert = if (kind == FinderKind.HISTORY) text else lead + text + tail
                        val newText = if (kind == FinderKind.HISTORY) text else before + insert + edit.text.substring(edit.selection.end)
                        val cursor = if (kind == FinderKind.HISTORY) text.length else before.length + insert.length
                        editing = TextFieldValue(newText, TextRange(cursor))
                    }
                    kind == FinderKind.HISTORY -> session.replaceLine(text, run = false)
                    else -> {
                        val current = session.currentCommand().orEmpty()
                        val lead = if (current.isNotEmpty() && !current.endsWith(" ")) " " else ""
                        session.send((lead + text + tail).encodeToByteArray())
                    }
                }
            },
        )
    }
    if (sizing) {
        TextSizeSheet(fontSize, onFontSize, onDismiss = { sizing = false })
    }
    if (picking) {
        var onlyThisHost by remember { mutableStateOf(hostSchemeId != null) }
        SchemeSheet(
            title = "Colors",
            selectedId = if (onlyThisHost) hostSchemeId else globalSchemeId,
            defaultOption = if (onlyThisHost) "Same as other hosts" else null,
            onPick = { id -> if (onlyThisHost) onSetHostScheme(id) else id?.let(onSetGlobalScheme) },
            onDismiss = { picking = false },
            header = {
                Row(Modifier.padding(start = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("All hosts", !onlyThisHost) { onlyThisHost = false }
                    SelectChip("Only ${session.host.label}", onlyThisHost) { onlyThisHost = true }
                }
            },
        )
    }
}

@Composable
private fun SessionBar(
    current: TerminalSession,
    sessions: List<TerminalSession>,
    onSwitch: (Long) -> Unit,
    onBack: () -> Unit,
    onPaste: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    onColors: () -> Unit,
    onTextSize: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(Dimens.barHeight).background(BarColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(Dimens.barHeight)) {
            Icon(Ico.back, "Hosts", tint = KeyText, modifier = Modifier.size(20.dp))
        }
        Row(
            Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sessions.forEach { s ->
                val status by s.status.collectAsState()
                val selected = s.id == current.id
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (selected) LocalChrome.current.keyBg else Color.Transparent)
                        .clickable { onSwitch(s.id) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        s.host.label,
                        color = if (selected) LocalChrome.current.fg else KeyText.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(Dimens.barHeight)) {
                Icon(Ico.more, "Menu", tint = KeyText, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; onPaste() })
                DropdownMenuItem(text = { Text("Text size") }, onClick = { menu = false; onTextSize() })
                DropdownMenuItem(text = { Text("Colors") }, onClick = { menu = false; onColors() })
                DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; onReconnect() })
                DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onClose() })
            }
        }
    }
}

/** Ctrl/Alt from the key row. One tap: next key only. Second tap: locked. Third: off. */
@Stable
class StickyModifiers : ModifierManager {
    enum class State { OFF, ONCE, LOCKED }

    var ctrl by mutableStateOf(State.OFF)
    var alt by mutableStateOf(State.OFF)

    fun cycle(state: State) = when (state) {
        State.OFF -> State.ONCE
        State.ONCE -> State.LOCKED
        State.LOCKED -> State.OFF
    }

    override fun isCtrlActive() = ctrl != State.OFF
    override fun isAltActive() = alt != State.OFF
    override fun isShiftActive() = false
    override fun clearTransients() {
        if (ctrl == State.ONCE) ctrl = State.OFF
        if (alt == State.ONCE) alt = State.OFF
    }

    /** libvterm modifier bits: shift 1, alt 2, ctrl 4. */
    fun bits(): Int = (if (isAltActive()) 2 else 0) or (if (isCtrlActive()) 4 else 0)
}

private sealed interface ExtraKey {
    val label: String
    data class Special(override val label: String, val code: Int) : ExtraKey
    data class Chars(override val label: String, val text: String = label) : ExtraKey
}

private val KEYS = listOf(
    ExtraKey.Special("Esc", VTermKey.ESCAPE),
    ExtraKey.Special("Tab", VTermKey.TAB),
    ExtraKey.Special("←", VTermKey.LEFT),
    ExtraKey.Special("↓", VTermKey.DOWN),
    ExtraKey.Special("↑", VTermKey.UP),
    ExtraKey.Special("→", VTermKey.RIGHT),
    ExtraKey.Chars("|"),
    ExtraKey.Chars("-"),
    ExtraKey.Chars("/"),
    ExtraKey.Chars("~"),
    ExtraKey.Special("Home", VTermKey.HOME),
    ExtraKey.Special("End", VTermKey.END),
    ExtraKey.Special("PgUp", VTermKey.PAGEUP),
    ExtraKey.Special("PgDn", VTermKey.PAGEDOWN),
)

@Composable
private fun ExtraKeys(session: TerminalSession, modifiers: StickyModifiers, onEdit: () -> Unit, onFind: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.keyHeight).background(BarColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.fillMaxHeight().width(44.dp).padding(2.dp).clip(RoundedCornerShape(5.dp)).clickable(onClick = onEdit),
            contentAlignment = Alignment.Center,
        ) { Icon(Ico.keyboard, "Edit line in input field", Modifier.size(20.dp), tint = Accent) }
        Box(
            Modifier.fillMaxHeight().width(44.dp).padding(2.dp).clip(RoundedCornerShape(5.dp)).clickable(onClick = onFind),
            contentAlignment = Alignment.Center,
        ) { Icon(Ico.search, "Find files, folders, commands", Modifier.size(20.dp), tint = Accent) }
        // Modifiers stay put; the rest scrolls, so Ctrl is always under the thumb.
        ModifierKey("Ctrl", modifiers.ctrl) { modifiers.ctrl = modifiers.cycle(modifiers.ctrl) }
        ModifierKey("Alt", modifiers.alt) { modifiers.alt = modifiers.cycle(modifiers.alt) }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            KEYS.forEach { key ->
                Key(key.label) {
                    val bits = modifiers.bits()
                    when (key) {
                        is ExtraKey.Special -> session.emulator.dispatchKey(bits, key.code)
                        is ExtraKey.Chars -> key.text.codePoints().forEach { session.emulator.dispatchCharacter(bits, it) }
                    }
                    modifiers.clearTransients()
                }
            }
        }
    }
}

@Composable
private fun ModifierKey(label: String, state: StickyModifiers.State, onClick: () -> Unit) {
    val (bg, fg) = when (state) {
        StickyModifiers.State.OFF -> Color.Transparent to KeyText
        StickyModifiers.State.ONCE -> Accent.copy(alpha = 0.25f) to Accent
        StickyModifiers.State.LOCKED -> Accent to TerminalBackground
    }
    Box(
        Modifier.fillMaxHeight().widthIn(min = 48.dp).padding(2.dp).clip(RoundedCornerShape(5.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxHeight().widthIn(min = 42.dp).clip(RoundedCornerShape(5.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = KeyText, style = MaterialTheme.typography.labelLarge) }
}
