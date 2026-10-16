package org.fauste.player.ui.format

/**
 * `mm:ss` timecode clock mirroring the desktop's `fp-app::ui::format::clock`
 * (crates/fp-app/src/ui/format.rs): minutes and seconds are zero-padded to
 * two digits and the format escalates to `h:mm:ss` from one hour on.
 *
 * Honest limits, never a crash: non-finite or negative input reads as zero.
 * Durations are 0.0 until a services layer fills them, so an unloaded track
 * renders "00:00", not garbage.
 */
fun formatClock(seconds: Double): String {
    // Floor to whole seconds; degenerate input reads as zero. toLong()
    // saturates at Long.MAX_VALUE for absurd magnitudes instead of
    // throwing, so even a corrupted engine value renders, never crashes.
    val total = if (seconds.isFinite() && seconds > 0.0) seconds.toLong() else 0L
    val hours = total / 3_600
    val minutes = total % 3_600 / 60
    val secs = total % 60
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${secs.padded()}"
    } else {
        "${minutes.padded()}:${secs.padded()}"
    }
}

/** Two-digit, locale-independent padding (no String.format digit locales). */
private fun Long.padded(): String = toString().padStart(length = 2, padChar = '0')
