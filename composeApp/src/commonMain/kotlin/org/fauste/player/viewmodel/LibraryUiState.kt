package org.fauste.player.viewmodel

/**
 * One queue row as the Library screen renders it, mapped from the FFI's
 * [org.fauste.player.rust.PlaylistEntry]: the [id] the remove/move and
 * jump commands address (and the LazyColumn key — the Phase-3 rule: row
 * identity comes from the playlist flow, never from an enqueue call's
 * returned id), the row's [title] and [durationSeconds] (0.0 reads as
 * "unknown" and TrackRow renders an em dash — the honest-limits rule),
 * and the [isPlaying] flag.
 *
 * [isPlaying] follows the ENGINE's current track, not the transport
 * status: the flag marks the row the player holds on air (the desktop's
 * `p.current == Some(entry)`, `fp-app/src/ui/table.rs:860-863`) — also
 * while paused on it. Honest limit: the model knows its current ENTRY,
 * but the FFI's [org.fauste.player.rust.EngineState] exposes only the
 * current TRACK (path, no entry id), so the flag joins on PATH; duplicate
 * paths (the enqueue contract allows them) each read as current. A
 * Phase-5 FFI that reports the current entry id closes this exactly.
 */
data class LibraryRow(
    val id: ULong,
    val title: String,
    val durationSeconds: Double,
    val isPlaying: Boolean,
)

/**
 * The import pipeline's user-visible phase, driven by
 * [LibraryViewModel.onImportUri]: [Idle] before and after a dismissed
 * result, [Importing] while the copy + enqueue run (the copy is the slow
 * half — bytes move from the picked URI into app storage), then either
 * [Imported] with the app-storage path that is now queued or [Failed]
 * with a human-readable cause. [Failed] is also the honest answer for a
 * refused permission gate ([LibraryViewModel.onImportPermissionDenied]).
 */
sealed interface LibraryImport {
    /** No import in flight and no result waiting to be dismissed. */
    data object Idle : LibraryImport

    /** A pick is being copied and enqueued; a second pick is ignored. */
    data object Importing : LibraryImport

    /** The audio behind the pick is copied and queued; [path] is where it lives now. */
    data class Imported(
        val path: String,
    ) : LibraryImport

    /** The import did not happen; [message] says why, user-readably. */
    data class Failed(
        val message: String,
    ) : LibraryImport
}

/**
 * The Library screen's UDF state: the FILTERED [rows] (client-side title
 * match against [query] — the queue is small, so no engine round-trip is
 * warranted), the queue's UNFILTERED size ([queueEntryCount], so the
 * screen can tell an empty queue from an empty search result), the raw
 * [query] (the TextField's value, spaces and all — matching trims), the
 * [import] phase, and [error] — the latest queue-command outcome (a Busy
 * drop or a Failed message), cleared by the next intent, whatever its
 * outcome.
 */
data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val queueEntryCount: Int = 0,
    val query: String = "",
    val import: LibraryImport = LibraryImport.Idle,
    val error: String? = null,
)
