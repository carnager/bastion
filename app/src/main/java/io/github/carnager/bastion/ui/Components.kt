package io.github.carnager.bastion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---- icons: 24×24 line icons, as in sesam ---------------------------------------------

private fun strokeIcon(name: String, d: String, width: Float = 1.9f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    )
    .build()

object Ico {
    // Key from Lucide ("key-round", ISC license).
    val key = strokeIcon(
        "key",
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4zM16.5 7a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z",
        1.8f,
    )
    val term = strokeIcon("term", "M4 5h16v14H4zM8 10l3 2-3 2M13 15h4")
    val search = strokeIcon("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-3.5-3.5", 2f)
    val back = strokeIcon("back", "M15 6l-6 6 6 6", 2f)
    val chevron = strokeIcon("chevron", "m9 6 6 6-6 6", 2f)
    val close = strokeIcon("close", "M6 6l12 12M18 6 6 18", 2f)
    val plus = strokeIcon("plus", "M12 5v14M5 12h14", 2f)
    val plusBold = strokeIcon("plusBold", "M12 5v14M5 12h14", 2.4f)
    val check = strokeIcon("check", "m5 12 4 4 10-10", 2.2f)
    val trash = strokeIcon("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val copy = strokeIcon("copy", "M11.5 9h6a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1-2.5 2.5h-6A2.5 2.5 0 0 1 9 17.5v-6A2.5 2.5 0 0 1 11.5 9zM5 15V6a2 2 0 0 1 2-2h8")
    val paste = strokeIcon("paste", "M8.5 4h7A1.5 1.5 0 0 1 17 5.5v1A1.5 1.5 0 0 1 15.5 8h-7A1.5 1.5 0 0 1 7 6.5v-1A1.5 1.5 0 0 1 8.5 4zM7 6H5v15h14V6h-2")
    val edit = strokeIcon("edit", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4")
    val file = strokeIcon("file", "M6 3h9l4 4v14H6zM14 3v5h5")
    val sparkle = strokeIcon("sparkle", "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6")
    val route = strokeIcon("route", "M6 5a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM18 15a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM6 9v3a3 3 0 0 0 3 3h7")
    val refresh = strokeIcon("refresh", "M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16M20 20v-4h-4")
    val eye = strokeIcon("eye", "M2.5 12S6 5 12 5s9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")
    val eyeOff = strokeIcon("eyeOff", "M3 3l18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 5.1A9.8 9.8 0 0 1 12 5c6 0 9.5 7 9.5 7a17 17 0 0 1-3.2 4.1M6.1 6.1C3.6 7.8 2.5 12 2.5 12S6 19 12 19a9.6 9.6 0 0 0 4.3-1")
    val alert = strokeIcon("alert", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 8v5M12 16.5v.5", 2f)
    val shield = strokeIcon("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5", 2f)
    val more = strokeIcon("more", "M12 5h.01M12 12h.01M12 19h.01", 3f)
    val power = strokeIcon("power", "M12 3v8M6.3 6.3a8 8 0 1 0 11.4 0", 2f)
    // Return arrow (run) and arrow-into-line (insert), Lucide-style.
    val enter = strokeIcon("enter", "M20 4v7a4 4 0 0 1-4 4H4M9 10l-5 5 5 5", 2f)
    val insert = strokeIcon("insert", "M12 3v12M7 10l5 5 5-5M5 20h14", 2f)
    val keyboard = strokeIcon("keyboard", "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM6 10h.01M10 10h.01M14 10h.01M18 10h.01M8 14h8")
    val textSize = strokeIcon("textSize", "M4 7V5h10v2M9 5v14M7 19h4M14 12v-1h7v1M17.5 11v8M16 19h3", 1.8f)
    val folder = strokeIcon("folder", "M4 20h16a1 1 0 0 0 1-1V8a1 1 0 0 0-1-1h-7.9a1 1 0 0 1-.8-.4L10 4.6a1 1 0 0 0-.8-.4H4a1 1 0 0 0-1 1V19a1 1 0 0 0 1 1z")
    val history = strokeIcon("history", "M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l4 2")
    val lock = strokeIcon("lock", "M6.5 10h11a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18.5v-6A2.5 2.5 0 0 1 6.5 10zM8 10V7a4 4 0 0 1 8 0v3", 2f)
    val fingerprint = strokeIcon("fingerprint", "M7.5 4.6A9 9 0 0 1 21 12v1M3 15.5V12a9 9 0 0 1 1.8-5.4M12 7a5 5 0 0 1 5 5v2a14 14 0 0 1-1 5M7 12a5 5 0 0 1 2.2-4.1M7 12v2a10 10 0 0 1-1.5 5.3M12 11v3a18 18 0 0 1-2 8M15.5 21a20 20 0 0 0 .5-4", 1.5f)
    // Gear outline from Lucide (ISC license); the eight-ray version looked like a sun / theme toggle.
    val settings = strokeIcon(
        "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
        1.8f,
    )
}

// ---- tiles ---------------------------------------------------------------------------

fun tileColor(key: String): Color {
    var h = 0
    for (c in key.lowercase()) h = 31 * h + c.code
    return Palette.tiles[Math.floorMod(h, Palette.tiles.size)]
}

/** Letter tile in a color derived from the host name. */
@Composable
fun HostTile(name: String, key: String, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.275f)).background(tileColor(key)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
        )
    }
}

@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.275f)).background(color),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.5f), tint = Color.White) }
}

// ---- headers -------------------------------------------------------------------------

/** 44 dp icon button. */
@Composable
fun IconAction(
    icon: ImageVector, description: String?, onClick: () -> Unit,
    tint: Color = extra.fg2, iconSize: Dp = 20.dp, enabled: Boolean = true, size: Dp = 44.dp,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.4f)) }
}

/** Sub-screen header: back/close, title, actions on the right. */
@Composable
fun ScreenHeader(
    title: String?, nav: ImageVector = Ico.back, navLabel: String = "Back", onNav: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconAction(nav, navLabel, onNav, iconSize = 22.dp)
        if (title != null) {
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** Main screen header: app mark + big title. */
@Composable
fun TabHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(Ico.term, Palette.Accent, 32.dp)
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        actions()
    }
}

// ---- cards ---------------------------------------------------------------------------

/** Card surface, 18 dp corners, hairline border. */
@Composable
fun InfoCard(modifier: Modifier = Modifier, radius: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(modifier.fillMaxWidth().clip(shape).background(extra.card).border(1.dp, extra.line, shape), content = content)
}

/** A row inside an [InfoCard]; rows after the first get a hairline above. */
@Composable
fun CardRow(first: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    if (!first) HorizontalDivider(color = extra.line)
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Uppercase section label ("OPEN SESSIONS"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
        fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = extra.muted,
    )
}

@Composable
fun InputLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = extra.fg2)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = extra.muted) {
    Text(text, modifier, fontSize = 12.5.sp, color = color)
}

// ---- buttons -------------------------------------------------------------------------

/** Filled accent button (primary action). */
@Composable
fun AccentButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, height: Dp = 52.dp, radius: Dp = 16.dp, fontSize: TextUnit = 15.sp,
) {
    val bg = extra.accent
    val fg = if (bg.luminance() > 0.5f) Color.Black else Color.White
    Row(
        modifier.height(height).clip(RoundedCornerShape(radius)).background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Hairline-bordered secondary button. */
@Composable
fun OutlinedAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    height: Dp = 48.dp, radius: Dp = 14.dp, color: Color = extra.fg2, enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(radius)
    Row(
        modifier.height(height).clip(shape).border(1.dp, extra.line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = color)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) color else color.copy(alpha = 0.5f), fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = extra.link) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Pill chip for choices. */
@Composable
fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        Modifier.height(36.dp).clip(shape).background(if (selected) extra.sel else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else extra.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) extra.selText else extra.fg2, maxLines = 1,
        )
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Badge(text: String, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(extra.chip).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(11.dp), tint = extra.fg2)
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = extra.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Amber banner for warnings and errors. */
@Composable
fun WarnBanner(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(extra.warnBg).border(1.dp, extra.warnLine, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Ico.alert, null, Modifier.size(18.dp), tint = extra.warnFg)
        content()
    }
}

@Composable
fun AccentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked, onChange,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = extra.accent, checkedThumbColor = Color.White,
            uncheckedTrackColor = extra.chip, uncheckedBorderColor = extra.line, uncheckedThumbColor = extra.muted,
        ),
    )
}

// ---- inputs --------------------------------------------------------------------------

/** 50 dp input, 12 dp corners, hairline border (2 dp accent while focused). */
@Composable
fun TextInput(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text, mono: Boolean = false, secret: Boolean = false,
    singleLine: Boolean = true, minHeight: Dp = 50.dp, radius: Dp = 12.dp, error: Boolean = false,
    leading: @Composable (() -> Unit)? = null, trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var shown by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    val style = if (mono) MonoStyle.copy(fontSize = 13.sp, color = extra.fg) else TextStyle(fontSize = 15.sp, color = extra.fg)
    val borderColor = when {
        error -> extra.red
        focused -> extra.accent
        else -> extra.line
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(extra.field)
            .border(if (focused || error) 2.dp else 1.dp, borderColor, shape)
            .padding(start = if (leading != null) 12.dp else 14.dp, end = if (secret || trailing != null) 4.dp else 14.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Box(Modifier.weight(1f).padding(vertical = if (singleLine) 0.dp else 14.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = extra.muted, fontSize = 15.sp)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine, textStyle = style,
                interactionSource = src, cursorBrush = SolidColor(extra.accent),
                visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret && shown) KeyboardType.Text else keyboard,
                    autoCorrectEnabled = false,
                ),
            )
        }
        if (secret) IconAction(if (shown) Ico.eyeOff else Ico.eye, if (shown) "Hide" else "Show", { shown = !shown })
        trailing?.invoke(this)
    }
}

@Composable
fun LabeledInput(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text, mono: Boolean = false, secret: Boolean = false,
    singleLine: Boolean = true, minHeight: Dp = 50.dp, error: Boolean = false, hint: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InputLabel(label)
        TextInput(
            value, onChange, placeholder = placeholder, keyboard = keyboard, mono = mono, secret = secret,
            singleLine = singleLine, minHeight = minHeight, error = error,
        )
        if (hint != null) Hint(hint, Modifier.padding(start = 2.dp))
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextInput(
        value, onChange, modifier, placeholder = placeholder, minHeight = 48.dp, radius = 14.dp,
        leading = { Icon(Ico.search, null, Modifier.size(18.dp), tint = extra.muted) },
        trailing = if (value.isNotEmpty()) ({ IconAction(Ico.close, "Clear", { onChange("") }, tint = extra.muted, iconSize = 18.dp) }) else null,
    )
}

// ---- sheets --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = extra.sheet,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(extra.line))
        },
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
fun SheetRow(label: String, icon: ImageVector, color: Color = extra.fg, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (color == extra.fg) extra.fg2 else color)
        Text(label, fontSize = 15.sp, color = color)
    }
}

// ---- snackbar ------------------------------------------------------------------------

/** In-app confirmation toast ("Public key copied"). */
object Snack {
    var message by mutableStateOf<String?>(null)
        private set
    var seq by mutableIntStateOf(0)
        private set

    fun show(text: String) {
        message = text
        seq++
    }

    fun hide() {
        message = null
    }
}

@Composable
fun BoxScope.SnackHost(bottom: Dp = 24.dp) {
    val msg = Snack.message
    val seq = Snack.seq
    LaunchedEffect(seq) {
        if (msg != null) {
            delay(2400)
            Snack.hide()
        }
    }
    AnimatedVisibility(
        msg != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = bottom),
        enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        var last by remember { mutableStateOf("") }
        if (msg != null) last = msg
        Row(
            Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(extra.toast).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ico.check, null, Modifier.size(18.dp), tint = Color(0xFF5CCF9B))
            Text(last, Modifier.weight(1f), color = Color(0xFFEEF0F3), fontSize = 14.sp)
        }
    }
}
