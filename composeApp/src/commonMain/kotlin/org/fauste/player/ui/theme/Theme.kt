// Compose composable factory functions are PascalCase by framework
// convention; ktlint 1.7.1's function-naming rule predates its
// @Composable awareness, so it is suppressed here the same way
// PowerManager.android.kt documents its filename suppression.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Nocturne palette from the spec.
private val Background = Color(0xFF0D0D0D)
private val Primary = Color(0xFF00BFA6)

private val NocturneColorScheme =
    darkColorScheme(
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
