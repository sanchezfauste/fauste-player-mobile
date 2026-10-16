// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.fauste.player.res.Res
import org.fauste.player.res.inter_medium
import org.fauste.player.res.inter_regular
import org.fauste.player.res.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * RULING 3 (fonts): Inter for UI text, monospace for timecode.
 *
 * Inter (weights 400/500/600) is bundled from the desktop repo's
 * crates/fp-app/assets/fonts/ under its SIL OFL 1.1 licence (the notice
 * ships as composeResources/files/inter_ofl.txt), as the spec requires
 * the same typeface as desktop.
 *
 * The spec names JetBrains Mono for the timecode, but no JetBrains Mono TTF
 * exists in the desktop repo (its timecode rendering uses egui's built-in
 * system monospace — crates/fp-app/src/ui/format.rs has no mono font
 * asset), so per Ruling 3's fallback the timecode family is
 * [FontFamily.Monospace] — the platform's monospaced face — until a
 * licensed JetBrains Mono TTF is bundled. No downloadable-fonts
 * dependency: the app must render offline, as a player does.
 */
@Composable
fun InterFontFamily(): FontFamily =
    FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
    )

/**
 * Material3 typography with every slot remapped to Inter. Sizes are the
 * Material3 defaults — every one in `sp`, so the whole UI participates in
 * Android font scaling (the Task 6 accessibility audit checks this stays
 * true). Slots this app never styles still get Inter rather than the
 * platform default sans, because Nocturne is a single-typeface design.
 */
fun NocturneTypography(inter: FontFamily): Typography {
    val base = Typography()

    fun TextStyle.asInter() = copy(fontFamily = inter)
    return base.copy(
        displayLarge = base.displayLarge.asInter(),
        displayMedium = base.displayMedium.asInter(),
        displaySmall = base.displaySmall.asInter(),
        headlineLarge = base.headlineLarge.asInter(),
        headlineMedium = base.headlineMedium.asInter(),
        headlineSmall = base.headlineSmall.asInter(),
        titleLarge = base.titleLarge.asInter(),
        titleMedium = base.titleMedium.asInter(),
        titleSmall = base.titleSmall.asInter(),
        bodyLarge = base.bodyLarge.asInter(),
        bodyMedium = base.bodyMedium.asInter(),
        bodySmall = base.bodySmall.asInter(),
        labelLarge = base.labelLarge.asInter(),
        labelMedium = base.labelMedium.asInter(),
        labelSmall = base.labelSmall.asInter(),
    )
}

/**
 * Timecode style for position/duration text (PlayerControls' seek readout,
 * TrackRow's duration): Material3 labelLarge (14sp Medium) with the
 * monospace family per Ruling 3 — tabular figures so a running timecode
 * doesn't jitter horizontally. Spelled as an extension so usage reads as
 * `MaterialTheme.typography.Timecode`, a first-class typography slot.
 */
val Typography.Timecode: TextStyle
    get() = labelLarge.copy(fontFamily = FontFamily.Monospace)
