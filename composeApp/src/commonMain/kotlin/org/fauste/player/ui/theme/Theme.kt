// Composable factory functions are PascalCase by framework
// convention; ktlint 1.7.1's function-naming rule predates its
// @Composable awareness, so it is suppressed here (house style, as the
// Phase-0 stub of this file already documented).
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The Material3 scheme wired from [NocturneColors]. Only the slots with a
 * natural Nocturne counterpart are mapped; Material3 fills the rest with
 * its own dark defaults. Semantic extras that have no scheme slot —
 * [NocturneColors.Playing], [NocturneColors.CuePoint],
 * [NocturneColors.IntroOutro], the waveform tints — are read directly
 * from [NocturneColors] by the components that draw them.
 */
private val NocturneColorScheme =
    darkColorScheme(
        primary = NocturneColors.Primary,
        onPrimary = NocturneColors.OnPrimary,
        primaryContainer = NocturneColors.PrimaryVariant,
        onPrimaryContainer = NocturneColors.OnBackground,
        background = NocturneColors.Background,
        onBackground = NocturneColors.OnBackground,
        surface = NocturneColors.Surface,
        onSurface = NocturneColors.OnSurface,
        surfaceVariant = NocturneColors.SurfaceVariant,
        onSurfaceVariant = NocturneColors.OnSurfaceVariant,
        error = NocturneColors.Error,
    )

/**
 * The app theme. DARK-ONLY on purpose: Nocturne is a night-player design
 * — the spec defines exactly one dark palette (see Color.kt's Ruling 1
 * note), not a light/dark pair — so there is no darkTheme parameter and
 * no system-follows toggle to get wrong; the palette is fixed the way a
 * hardware dark deck would be. Typography comes from [NocturneTypography]
 * with the bundled Inter family (Ruling 3).
 */
@Composable
fun FausteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NocturneColorScheme,
        typography = NocturneTypography(InterFontFamily()),
        content = content,
    )
}
