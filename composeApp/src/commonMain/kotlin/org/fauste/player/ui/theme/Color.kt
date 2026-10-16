// The file is the SPEC's `theme/Color.kt` (the Nocturne block's own
// header names it), which ktlint's filename rule cannot know: it sees a
// single NocturneColors object and wants NocturneColors.kt. The spec
// name wins.
@file:Suppress("ktlint:standard:filename")

package org.fauste.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RULING 1 (Phase 4 plan, binding): this palette is the SPEC's Nocturne
 * block — docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md,
 * "Nocturne Design System", ~lines 344-366 — copied VERBATIM: hex values,
 * oklch provenance comments and semantic names exactly as written there.
 *
 * The desktop repo's live `crates/fp-app/src/ui/theme.rs` has DRIFTED from
 * this block (its accent is now lavender, not the Fauste teal). Do NOT
 * "fix" these values toward the desktop's current colours: the spec block
 * is the mobile app's single source of truth. Renaming or re-hexing any
 * entry below is a spec change, not a cleanup.
 *
 * Semantic extras with no Material3 scheme slot (Playing, Paused, CuePoint,
 * IntroOutro, the waveform tints) are read directly from this object by the
 * components that draw them (TrackRow's indicator, later waveform/cue UI).
 */
object NocturneColors {
    val Background = Color(0xFF0D0D0D) // oklch(0.12 0 0)
    val Surface = Color(0xFF161616) // oklch(0.18 0 0)
    val SurfaceVariant = Color(0xFF1E1E1E) // oklch(0.24 0 0)
    val OnBackground = Color(0xFFE8E8E8) // oklch(0.92 0 0)
    val OnSurface = Color(0xFFD0D0D0) // oklch(0.85 0 0)
    val OnSurfaceVariant = Color(0xFF9E9E9E) // oklch(0.65 0 0)
    val Primary = Color(0xFF00BFA6) // oklch(0.72 0.18 185) — Fauste teal
    val PrimaryVariant = Color(0xFF009E8A) // oklch(0.62 0.15 185)
    val OnPrimary = Color(0xFF001A18) // oklch(0.12 0.02 185)
    val Error = Color(0xFFE57373) // oklch(0.68 0.22 25)
    val Success = Color(0xFF81C784) // oklch(0.72 0.18 145)
    val Warning = Color(0xFFFFB74D) // oklch(0.82 0.16 75)
    val Playing = Primary
    val Paused = OnSurfaceVariant
    val CuePoint = Color(0xFFFFD54F) // Yellow cue markers
    val IntroOutro = Color(0xFFCE93D8) // Purple intro/outro
    val WaveformPlayed = Primary.copy(alpha = 0.7f)
    val WaveformUnplayed = OnSurfaceVariant.copy(alpha = 0.4f)
    val WaveformCue = CuePoint
}
