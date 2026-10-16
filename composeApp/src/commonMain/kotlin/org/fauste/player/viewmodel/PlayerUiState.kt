package org.fauste.player.viewmodel

import org.fauste.player.player.PlaybackState
import org.fauste.player.player.PlaybackStatus

/**
 * The Player screen's UDF state — spec §5's `PlayerUiState` trimmed to what
 * the engine surface carries today (see [PlaybackState]'s own honest-limits
 * note: no waveform, cue points or dedicated engine-error channel exist on
 * the FFI yet; [error] below is the command-outcome channel that DOES).
 *
 * [playback] is the plan-mandated [PlaybackState.fromEngineState] projection
 * of the polled engine snapshot, passed through untouched — the UiState adds
 * only the drag-feedback and error channels on top, so the mapping is
 * consumed, never re-implemented.
 *
 * Drag feedback: [pendingSeekPosition]/[pendingVolume] are non-null while a
 * drag (or its committed-but-unconfirmed tail) is in flight; the screen draws
 * them INSTEAD of the engine's values so the sliders track the finger — see
 * [PlayerViewModel]'s throttle KDoc for the full semantics.
 */
data class PlayerUiState(
    val playback: PlaybackState,
    val pendingSeekPosition: Double? = null,
    val pendingVolume: Float? = null,
    val error: String? = null,
) {
    /** True while the transport reports playing — drives the play/pause icon. */
    val isPlaying: Boolean
        get() = playback.status == PlaybackStatus.Playing

    /** The position the seek bar shows: the pending drag target if any, else the engine's. */
    val position: Double
        get() = pendingSeekPosition ?: playback.position

    /** The volume the slider shows: the pending drag target if any, else the engine's. */
    val volume: Float
        get() = pendingVolume ?: playback.volume

    /**
     * The current track's title — the FFI carries the model's stem-derived
     * title — or an em dash while no track is on air / the title is blank
     * (the honest-limits rule: a placeholder marker, never a fake title;
     * TrackRow's unknown duration follows the same convention).
     */
    val trackTitle: String
        get() = playback.currentTrack?.title?.takeIf { it.isNotBlank() } ?: "—"

    /** The current track's artist when the FFI carries one; null otherwise (no fake placeholder). */
    val trackArtist: String?
        get() = playback.currentTrack?.artist?.takeIf { it.isNotBlank() }
}
