package io.github.carnager.bastion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.bastion.data.ColorSchemes
import io.github.carnager.bastion.data.TermScheme

fun TermScheme.color(rgb: Int) = Color(argb(rgb))

/**
 * Pick a terminal color scheme. [defaultOption]: label for "no scheme of its own"
 * (per-host choice), whose id is null.
 */
@Composable
fun SchemeSheet(
    title: String,
    selectedId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    defaultOption: String? = null,
    header: @Composable () -> Unit = {},
) {
    Sheet(onDismiss = onDismiss) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        header()
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (defaultOption != null) {
                item(key = "default") {
                    SchemeRow(null, defaultOption, selected = selectedId == null) { onPick(null) }
                }
            }
            items(ColorSchemes.all, key = { it.id }) { scheme ->
                SchemeRow(scheme, scheme.name, selected = scheme.id == selectedId) { onPick(scheme.id) }
            }
        }
    }
}

@Composable
private fun SchemeRow(scheme: TermScheme?, name: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) extra.accent else extra.line, shape)
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (scheme != null) SchemePreview(scheme) else Box(Modifier.width(132.dp).heightIn(min = 48.dp))
        Text(name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (selected) Icon(Ico.check, "Selected", Modifier.padding(end = 6.dp).size(20.dp), tint = extra.link)
    }
}

/** A tiny prompt line plus the eight normal ANSI colors, on the scheme's own background. */
@Composable
fun SchemePreview(scheme: TermScheme) {
    Column(
        Modifier.width(132.dp).clip(RoundedCornerShape(9.dp)).background(scheme.color(scheme.background))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = scheme.color(scheme.ansi[2]))) { append("user") }
                withStyle(SpanStyle(color = scheme.color(scheme.foreground))) { append(":") }
                withStyle(SpanStyle(color = scheme.color(scheme.ansi[4]))) { append("~") }
                withStyle(SpanStyle(color = scheme.color(scheme.foreground))) { append("$ ls") }
            },
            style = MonoStyle, fontSize = 11.sp, maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..7) Box(Modifier.size(width = 12.dp, height = 6.dp).clip(RoundedCornerShape(2.dp)).background(scheme.color(scheme.ansi[i])))
        }
    }
}
