package org.fauste.player.service

import org.fauste.player.player.PlaybackStatus
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.PlaylistEntry
import kotlin.math.roundToLong

/**
 * THE SESSION BRIDGE'S PURE HALF (Task 6's design judgment, made a type):
 * the service must not depend on any ViewModel — they are UI-scoped and
 * die with the screen — so the bridge from the STATE LAYER (Koin's
 * [org.fauste.player.player.PlayerController] engineState flow, THE
 * source, plus [QueueManager][org.fauste.player.player.QueueManager]'s
 * playlist flow) to media3's session surface factors into two layers:
 *
 * 1. THIS mapping — pure Kotlin, no Android and no media3 type — from
 *    the FFI's `EngineState` + the queue's `PlaylistEntry` rows onto a
 *    session-shaped snapshot. Every rule lives here, where the host-JVM
 *    suite pins it without Robolectric.
 * 2. The media3 adapter ([FaustePlayerAdapter]) — thin glue that
 *    translates this snapshot into media3's `SimpleBasePlayer.State`
 *    and back-fires player commands into the controller. The glue is
 *    exercised by its own Robolectric lane; the RULES never leave here.
 *
 * Threading by construction: both inputs are StateFlow *snapshots* read
 * wherever the collector runs (the service's background scope); the
 * adapter publishes the computed snapshot behind a volatile and reads
 * it on the main thread. No getter in this class can ever touch the
 * engine — it only ever sees plain values.
 */
data class SessionPlaybackSnapshot(
    /** The transport status, mapped by the state layer's own never-throw rule — the service's lifecycle signal. */
    val status: PlaybackStatus,
    /** True while the engine's transport reports `"playing"`. */
    val isPlaying: Boolean,
    /**
     * media3's user-intent flag. The engine carries no separate intent
     * field — the transport's status IS the intent — so this mirrors
     * [isPlaying]: a paused engine is a paused intent, never a
     * pending play. (An ExoPlayer distinguishes the two for its
     * buffering window; the native engine's status is already the
     * post-buffering truth.)
     */
    val playWhenReady: Boolean,
    /**
     * True → media3 `STATE_READY` (playing or paused), false → `STATE_IDLE`
     * (stopped). The empty-queue clamp (see [from]) also forces false.
     */
    val playbackReady: Boolean,
    /** The polled position, crossed from the FFI's seconds to milliseconds. */
    val positionMs: Long,
    /** The on-air duration in ms, or [UNSET_DURATION_MS] while the engine reports its not-yet-filled 0.0. */
    val durationMs: Long,
    /** The engine's linear gain 0.0–1.0, as the FFI reports it — the session's volume reflection. */
    val volume: Float,
    /** The engine's OWN on-air title (never the queue's guess), null while none/blank. */
    val currentTitle: String?,
    /** The engine's on-air artist, null while none/blank — no fake placeholders. */
    val currentArtist: String?,
    /** The whole queue, in order — the session timeline's rows. */
    val queue: List<SessionQueueItem>,
    /**
     * The on-air row's index in [queue], resolved by the on-air ENTRY id
     * first (the FFI carries it since Phase 5 — the exact join the
     * desktop's table does, `fp-app/src/ui/view.rs:386`), falling back
     * to the documented PATH match while the id is absent, and
     * [INDEX_UNSET] while neither places the on-air entry — the queue
     * flow's documented stale-on-remote-mutations corner. Never a
     * guessed row. The id join is exact where the path join is
     * ambiguous: duplicate paths are distinct entries, and only the
     * row whose id is on air matches.
     *
     * The honest display-layer note (Task 6's review fold-in): the DATA
     * here keeps "never a guessed row", but media3's
     * `SimpleBasePlayer.State` constructor maps
     * `currentMediaItemIndex == C.INDEX_UNSET` to **row 0** whenever the
     * timeline is non-empty (verified in the 1.11.1 bytecode: the
     * builder's -1 becomes a plain 0 in the built State) — so over a
     * non-empty playlist the lock screen may display row 0's metadata
     * in exactly that stale corner, at the display layer only. The
     * empty-timeline case is unaffected (there is no row 0 to show), and
     * the mapping layer still reports the truth to anyone who asks.
     */
    val currentQueueIndex: Int,
) {
    companion object {
        /**
         * Mirrors media3's `C.INDEX_UNSET` without importing media3 here:
         * the pure layer stays testable without any Android artifact on
         * the classpath.
         */
        const val INDEX_UNSET: Int = -1

        /**
         * Mirrors media3's `C.TIME_UNSET` for the same reason as
         * [INDEX_UNSET]: the unknown-duration sentinel, never a lying 0.
         */
        const val UNSET_DURATION_MS: Long = Long.MIN_VALUE + 1

        /**
         * The seed the adapter boots with: exactly what [from] projects
         * from a fresh engine (stopped, empty queue) — the FFI's own
         * degenerate-state shape, so the first media3 state read before
         * the collector's first tick is already a legal one.
         */
        val idle: SessionPlaybackSnapshot =
            SessionPlaybackSnapshot(
                status = PlaybackStatus.Stopped,
                isPlaying = false,
                playWhenReady = false,
                playbackReady = false,
                positionMs = 0,
                durationMs = UNSET_DURATION_MS,
                volume = 1.0f,
                currentTitle = null,
                currentArtist = null,
                queue = emptyList(),
                currentQueueIndex = INDEX_UNSET,
            )

        /**
         * The bridge's single rule set:
         * - status through the state layer's own never-throw
         *   [PlaybackStatus.fromEngine] (unknown strings → Stopped);
         * - READY while playing or paused and the queue has rows —
         *   media3's `State.build()` REJECTS a non-IDLE state over an
         *   empty timeline, and the queue flow can momentarily lag the
         *   engine (remote-mutation staleness), so the clamp is a crash
         *   guard on an otherwise unreachable corner;
         * - seconds → ms by rounding; 0.0 durations (the FFI's
         *   not-yet-filled shape) → [UNSET_DURATION_MS];
         * - the on-air row by the ENTRY id first, then the documented
         *   path match while the id is absent; neither places it →
         *   [INDEX_UNSET];
         * - on-air title/artist from the ENGINE's own track, blanks
         *   read as null.
         */
        fun from(
            engineState: EngineState,
            playlist: List<PlaylistEntry>,
        ): SessionPlaybackSnapshot {
            val status = PlaybackStatus.fromEngine(engineState.status)
            val playing = status == PlaybackStatus.Playing
            val hasQueue = playlist.isNotEmpty()
            val currentPath = engineState.currentTrack?.path
            return SessionPlaybackSnapshot(
                status = status,
                isPlaying = playing,
                playWhenReady = playing,
                // The crash guard: media3's State.build() rejects a non-IDLE
                // state over an empty timeline, and the queue flow can
                // momentarily lag the engine (remote-mutation staleness),
                // so READY requires both a non-stopped transport AND rows.
                playbackReady = hasQueue && status != PlaybackStatus.Stopped,
                positionMs = secondsToMillis(engineState.position),
                durationMs = knownOrUnset(engineState.duration),
                volume = engineState.volume,
                currentTitle = engineState.currentTrack?.title?.takeIf { it.isNotBlank() },
                currentArtist = engineState.currentTrack?.artist?.takeIf { it.isNotBlank() },
                queue = playlist.map { entry -> SessionQueueItem.from(entry) },
                currentQueueIndex =
                    when (val id = engineState.currentEntryId) {
                        // The id is AUTHORITATIVE while carried: the exact
                        // join, and honest over a stale queue — an on-air id
                        // missing from the rows is INDEX_UNSET, never a
                        // guessed row (a path fallback here could pick a
                        // duplicate-path row that is NOT the on-air entry).
                        null ->
                            currentPath?.let { path ->
                                playlist.indexOfFirst { entry -> entry.path == path }.takeIf { it >= 0 }
                            } ?: INDEX_UNSET
                        else -> playlist.indexOfFirst { entry -> entry.id == id }.takeIf { it >= 0 } ?: INDEX_UNSET
                    },
            )
        }

        /** The FFI's seconds as media3-style whole milliseconds (round-half-up, matching its own conversions). */
        private fun secondsToMillis(seconds: Double): Long = (seconds * 1000.0).roundToLong()

        /** The FFI's 0.0 duration means unknown, not zero-length — map it to the sentinel. */
        private fun knownOrUnset(duration: Double): Long = if (duration > 0.0) secondsToMillis(duration) else UNSET_DURATION_MS
    }
}

/** One queue row on the session timeline: the playlist flow's id, title and duration. */
data class SessionQueueItem(
    /** The playlist flow's row id — the Phase-3 rule: row identity is never an enqueue call's returned id. */
    val id: ULong,
    /** The row's title (the model's stem-derived one from the FFI). */
    val title: String,
    /** The row's duration in ms, or [SessionPlaybackSnapshot.UNSET_DURATION_MS] while the FFI reports 0.0. */
    val durationMs: Long,
) {
    companion object {
        fun from(entry: PlaylistEntry): SessionQueueItem =
            SessionQueueItem(
                id = entry.id,
                title = entry.title,
                durationMs =
                    if (entry.duration > 0.0) {
                        (entry.duration * 1000.0).roundToLong()
                    } else {
                        SessionPlaybackSnapshot.UNSET_DURATION_MS
                    },
            )
    }
}

/**
 * What the engine's transport status says the SERVICE should do
 * (Ruling 5, as a total function both service-side consumers share):
 *
 * - [StartForeground] — playback began: the Application-side starter
 *   answers by starting the foreground service (the service promotes
 *   itself via media3 from then on);
 * - [KeepAlive] — paused: the service keeps running and its
 *   notification stays, like every music player;
 * - [StopService] — stopped: the service exits the foreground and
 *   tears itself down.
 */
enum class ServiceTrigger {
    StartForeground,
    KeepAlive,
    StopService,
}

/** Ruling 5's lifecycle decision for the transport status [status]. */
fun serviceTriggerFor(status: PlaybackStatus): ServiceTrigger =
    when (status) {
        PlaybackStatus.Playing -> ServiceTrigger.StartForeground
        PlaybackStatus.Paused -> ServiceTrigger.KeepAlive
        PlaybackStatus.Stopped -> ServiceTrigger.StopService
    }
