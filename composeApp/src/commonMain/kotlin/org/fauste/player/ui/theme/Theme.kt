package org.fauste.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Nocturne palette from the spec.
private val Background = Color(0xFF0D0D0D)
private val Primary = Color(0xFF00BFA6)

private val NocturneColorScheme = darkColorScheme(
    primary = Primary,
    background = Background,
    surface = Background,
)

@Composable
fun FausteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NocturneColorScheme,
        content = content,
    )
}
