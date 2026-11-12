package io.github.carnager.bastion.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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

private val TerminalBackground = Color(0xFF101010)
private val BarColor = Color(0xFF1C1C1C)
private val KeyText = Color(0xFFDDDDDD)
private val Accent = Color(0xFF7FB4FF)

@Composable
fun TerminalScreen(
    session: TerminalSession,
    sessions: List<TerminalSession>,
    onSwitch: (Long) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    val status by session.status.collectAsState()
    val modifiers = remember { StickyModifiers() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

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
        )
        // Keyed by session so switching tabs gets a fresh view bound to the other emulator.
        androidx.compose.runtime.key(session.id) {
            Terminal(
                terminalEmulator = session.emulator,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                initialFontSize = 11.sp,
                backgroundColor = TerminalBackground,
                foregroundColor = Color(0xFFE6E6E6),
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
                TextButton(onClick = session::reconnect) { Text("Reconnect") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
        ExtraKeys(session, modifiers)
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
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(Dimens.barHeight).background(BarColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(Dimens.barHeight)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Hosts", tint = KeyText, modifier = Modifier.size(18.dp))
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
                        .background(if (selected) Color(0xFF2E2E2E) else Color.Transparent)
                        .clickable { onSwitch(s.id) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        s.host.label,
                        color = if (selected) Color.White else KeyText.copy(alpha = 0.7f),
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
                Icon(Icons.Default.MoreVert, "Menu", tint = KeyText, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; onPaste() })
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
private fun ExtraKeys(session: TerminalSession, modifiers: StickyModifiers) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.keyHeight).background(BarColor),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        StickyModifiers.State.LOCKED -> Accent to Color.Black
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
