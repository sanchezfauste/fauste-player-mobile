package org.fauste.player.player

/**
 * The main player's transport status, mapped 1:1 from the FFI's status
 * strings. The FFI reports the model's `Transport` as exactly one of
 * `"playing"` / `"paused"` / `"stopped"`
 * (`crates-mobile/fp-backends-mobile/src/ffi.rs`, `get_state` — the
 * strings mirror the remote DTO, `fp-remote/src/dto.rs:361-363`); the
 * declaration order mirrors the Rust `Transport` variants.
 */
enum class PlaybackStatus {
    /** The FFI's `"stopped"`. */
    Stopped,

    /** The FFI's `"playing"`. */
    Playing,

    /** The FFI's `"paused"`. */
    Paused,
    ;

    companion object {
        /**
         * Maps an FFI status string. **Never throws**: an unknown string
         * (a future engine revision, a corrupted read) falls back to
         * [Stopped] — the state a freshly booted engine is in and the
         * safest for the UI to draw (no phantom progress; the transport
         * buttons keep working). The mapping is case-sensitive on
         * purpose: the FFI strings are all lowercase.
         */
        fun fromEngine(status: String?): PlaybackStatus =
            when (status) {
                "playing" -> Playing
                "paused" -> Paused
                "stopped" -> Stopped
                else -> Stopped
            }
    }
}
