package org.fauste.player.viewmodel

import org.fauste.player.player.PlaybackState
import org.fauste.player.player.PlaybackStatus
import org.fauste.player.ui.strings.StringKeys
import org.fauste.player.ui.strings.UiText

/**
 * The Player screen's UDF state — spec §5's `PlayerUiState` trimmed to what
 * the engine surface carries today (see [PlaybackState]'s own honest-limits
 * note: no waveform, cue points or dedicated engine-error channel exist on
 * the FFI yet; [error] below is the command-outcome channel that DOES).
 *
 * [playback] is the plan-mandated [PlaybackState.fromEngineState] projection
 * of the polled engine snapshot, passed through untouched — the UiState adds
 * only the drag-feedback, up-next and error channels on top, so the mapping
 * is consumed, never re-implemented.
 *
 * Drag feedback: [pendingSeekPosition]/[pendingVolume] are non-null while a
 * drag (or its committed-but-unconfirmed tail) is in flight; the screen draws
 * them INSTEAD of the engine's values so the sliders track the finger — see
 * [PlayerViewModel]'s throttle KDoc for the full semantics.
 *
 * The up-next line (Phase 5): [upNextTitle] is the ARMED row's title,
 * resolved from the queue flow by the armed id — non-null only while an arm
 * exists (the model's `next` gated on `next_explicit`; the surface carries
 * no derived-next read, so the line renders nothing when nobody armed —
 * inventing a "next" there would be exactly the play-now promise the
 * arm-only contract forbids). It travels as a [UiText] — the placed
 * title raw, the bundle's unknown-marker key while the arm cannot be
 * placed in the queue flow (the remote-mutation staleness corner), so
 * the state stays locale-independent and the marker says what it means
 * in the user's language, never a fake title.
 */
data class PlayerUiState(
    val playback: PlaybackState,
    val pendingSeekPosition: Double? = null,
    val pendingVolume: Float? = null,
    val upNextTitle: UiText? = null,
    val error: UiText? = null,
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
     * The current track's RAW title — the FFI carries the model's
     * stem-derived title — or the empty string while no track is on air
     * or the title is blank (the honest-limits rule: the state carries
     * the data and never a placeholder; PlayerScreen renders the i18n
     * seam's unknown-marker key for the blank case, the same convention
     * TrackRow's unknown duration follows).
     */
    val trackTitle: String
        get() = playback.currentTrack?.title.orEmpty()

    /** The current track's artist when the FFI carries one; null otherwise (no fake placeholder). */
    val trackArtist: String?
        get() = playback.currentTrack?.artist?.takeIf { it.isNotBlank() }
}
