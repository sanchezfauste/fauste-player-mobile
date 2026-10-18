package org.fauste.player.player

import org.fauste.player.rust.EngineState
import org.fauste.player.rust.Track

/**
 * The composed UI-facing playback snapshot — spec §5's `PlayerUiState`
 * trimmed to what the FFI exposes today: no waveform, cue points or error
 * channel exist on the engine surface yet (later phases add them as the
 * FFI grows). [currentTrack] is the FFI's own [Track] record — path,
 * title, artist, duration, sample rate, channels — passed through
 * untouched rather than re-wrapped.
 *
 * The ENTRY id reads (Phase 5): [currentEntryId] is the on-air entry's
 * id and [armedEntryId] the armed entry's — the exact join keys the
 * desktop's table uses (`p.current == Some(entry.id)` /
 * `p.next == Some(entry.id)`, `fp-app/src/ui/view.rs:386-389`), now
 * carried by the FFI so consumers join by id instead of the ambiguous
 * path (duplicate paths each read as current). `armedEntryId` is the
 * ARM — the model's `next` gated on `next_explicit`, so `null` never
 * means "a derived next exists" (see the FFI record's field doc).
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Stopped,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val currentTrack: Track? = null,
    val currentEntryId: ULong? = null,
    val armedEntryId: ULong? = null,
    val volume: Float = 1.0f,
    val backendName: String = "",
) {
    companion object {
        /**
         * Pure projection of the FFI's [EngineState] — unit-testable
         * without any engine. The defaults of this class match a fresh
         * engine, so a projection of a degenerate state is a valid
         * [PlaybackState]. [backendName] is the raw FFI backend id
         * (`"null"` until the engine reports one in use); Phase 4 can
         * resolve display names via `getAvailableBackends` when needed.
         */
        fun fromEngineState(state: EngineState): PlaybackState =
            PlaybackState(
                status = PlaybackStatus.fromEngine(state.status),
                position = state.position,
                duration = state.duration,
                currentTrack = state.currentTrack,
                currentEntryId = state.currentEntryId,
                armedEntryId = state.armedEntryId,
                volume = state.volume,
                backendName = state.backend,
            )
    }
}
