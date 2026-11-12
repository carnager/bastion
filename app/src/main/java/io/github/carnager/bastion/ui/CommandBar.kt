package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.data.Settings
import io.github.carnager.bastion.session.CommandLine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A real text field for the shell's input line: native cursor, selection, swipe typing and
 * paste. "Run" replaces the shell's line and presses Enter; "Insert" only puts it there.
 */
@Composable
internal fun CommandEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    history: List<String>,
    onRun: (String) -> Unit,
    onInsert: (String) -> Unit,
    onClose: () -> Unit,
) {
    val chrome = LocalChrome.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val suggestions = remember(value.text, history) {
        CommandLine.suggestions(history, value.text, limit = 12, recentWhenBlank = true)
    }

    Column(Modifier.fillMaxWidth().background(chrome.bar)) {
        if (suggestions.isNotEmpty()) {
            SuggestionChips(suggestions) { picked ->
                onValueChange(TextFieldValue(picked, TextRange(picked.length)))
            }
        }
        // The field: full width, roomy, with its own clear button.
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp).heightIn(min = 50.dp)
                .clip(shape).background(chrome.bg).border(1.5.dp, chrome.accent, shape).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) Text("Type a command", color = chrome.key.copy(alpha = 0.5f), fontSize = 15.sp)
                BasicTextField(
                    value = value,
                    // Enter runs; a pasted newline shouldn't sneak a second command in.
                    onValueChange = { onValueChange(it.copy(text = it.text.replace('\n', ' '))) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = MonoStyle.copy(color = chrome.fg, fontSize = 15.sp, letterSpacing = 0.sp),
                    cursorBrush = SolidColor(chrome.accent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { onRun(value.text) }),
                )
            }
            if (value.text.isNotEmpty()) {
                IconAction(Ico.close, "Clear", { onValueChange(TextFieldValue("")) }, tint = chrome.key, iconSize = 18.dp, size = 40.dp)
            }
        }
        // Actions, spelled out.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarButton("Cancel", onClose, color = chrome.key)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            BarButton("Insert", { onInsert(value.text) }, color = chrome.fg, outlined = true)
            Row(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(chrome.accent)
                    .clickable { onRun(value.text) }.padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Run", color = chrome.bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(Ico.enter, null, Modifier.size(17.dp), tint = chrome.bg)
            }
        }
    }
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color, outlined: Boolean = false) {
    val chrome = LocalChrome.current
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.heightIn(min = 40.dp).clip(shape)
            .then(if (outlined) Modifier.border(1.dp, chrome.line, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

/** Matching history while typing straight into the terminal. */
@Composable
internal fun SuggestionStrip(items: List<String>, onPick: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().background(LocalChrome.current.bar)) {
        SuggestionChips(items, onPick)
    }
}

@Composable
private fun SuggestionChips(items: List<String>, onPick: (String) -> Unit) {
    val chrome = LocalChrome.current
    LazyRow(
        Modifier.fillMaxWidth().height(44.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { item ->
            Row(
                Modifier.widthIn(max = 280.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(chrome.keyBg)
                    .clickable { onPick(item) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Ico.history, null, Modifier.size(13.dp), tint = chrome.key.copy(alpha = 0.7f))
                Text(item, color = chrome.fg, style = MonoStyle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun TextSizeSheet(size: Float, onSize: (Float) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss = onDismiss) {
        Text("Text size", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedAction("A−", { onSize(size - 1f) }, Modifier.weight(1f), enabled = size > Settings.MIN_FONT_SIZE)
            Text("${size.roundToInt()}", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedAction("A+", { onSize(size + 1f) }, Modifier.weight(1f), enabled = size < Settings.MAX_FONT_SIZE)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint("Or pinch the terminal with two fingers.", Modifier.weight(1f))
            TextAction("Reset", { onSize(Settings.DEFAULT_FONT_SIZE) })
        }
    }
}

/**
 * Watches touches on the terminal without taking them away from it: a pinch reports its final
 * scale (termlib only magnifies while pinching), a short tap reports where it landed.
 */
internal fun Modifier.terminalGestures(onPinch: (Float) -> Unit, onTap: (Offset, IntSize) -> Unit): Modifier =
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var startDistance = 0f
            var scale = 1f
            var multi = false
            var moved = false
            var lastTime = first.uptimeMillis
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                event.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }
                if (pressed.size >= 2) {
                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                    if (!multi) {
                        multi = true
                        startDistance = distance
                    } else if (startDistance > 0f) {
                        scale = distance / startDistance
                    }
                } else if (pressed.size == 1 && !multi && (pressed[0].position - first.position).getDistance() > slop) {
                    moved = true
                }
                if (pressed.isEmpty()) break
            }
            when {
                multi && abs(scale - 1f) > 0.08f -> onPinch(scale)
                !multi && !moved && lastTime - first.uptimeMillis < 350 -> onTap(first.position, size)
            }
        }
    }
