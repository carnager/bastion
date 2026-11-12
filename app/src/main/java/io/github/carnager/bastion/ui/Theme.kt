package io.github.carnager.bastion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** One place for spacing, sized for small phones: tight but still finger-sized. */
object Dimens {
    val gutter = 12.dp
    val rowHeight = 52.dp
    val gap = 8.dp
    val barHeight = 36.dp
    val keyHeight = 38.dp
}

@Composable
fun BastionTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
