package org.fauste.player.viewmodel

import org.fauste.player.ui.strings.UiText

/**
 * One queue row as the Library screen renders it, mapped from the FFI's
 * [org.fauste.player.rust.PlaylistEntry]: the [id] the remove/move and
 * jump commands address (and the LazyColumn key — the Phase-3 rule: row
 * identity comes from the playlist flow, never from an enqueue call's
 * returned id), the row's [title] and [durationSeconds] (0.0 reads as
 * "unknown" and TrackRow renders the unknown marker — the honest-limits
 * rule; a BLANK title likewise stays raw here and the row renders the
 * marker for it at draw time), the [isPlaying] flag and the [isArmed]
 * flag.
 *
 * [isPlaying] follows the ENGINE's on-air entry, not the transport
 * status: the flag marks the row the player holds on air (the desktop's
 * `p.current == Some(entry)`, `fp-app/src/ui/table.rs:860-863`) — also
 * while paused on it. Since Phase 5 the FFI carries the on-air ENTRY id
 * (`current_entry_id`), so the flag joins by ID — the desktop's own
 * join key (`fp-app/src/ui/view.rs:386`) — and duplicate paths (the
 * enqueue contract allows them) no longer each read as current. The
 * documented PATH fallback stays for the id-absent shape (nothing on
 * air): a total mapping, never a second guess over a queue the id
 * already answered.
 *
 * [isArmed] marks the row the user ARMED as the player's next (the
 * arm-only row tap — [LibraryViewModel.onRowTap]): the engine's
 * `armed_entry_id`, which is the model's `next` gated on
 * `next_explicit` — so the badge never promises play-now and never
 * marks the model's derived next (the FFI's field doc carries the
 * exact reducer semantics: the arm consumes when it fires, `previous`
 * re-arms the entry left, a remove clears it).
 */
data class LibraryRow(
    val id: ULong,
    val title: String,
    val durationSeconds: Double,
    val isPlaying: Boolean,
    val isArmed: Boolean = false,
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

    /** The import did not happen; [text] says why — a locale-independent [UiText] the render layer resolves. */
    data class Failed(
        val text: UiText,
    ) : LibraryImport
}

/**
 * The Library screen's UDF state: the FILTERED [rows] (client-side title
 * match against [query] — the queue is small, so no engine round-trip is
 * warranted), the queue's UNFILTERED size ([queueEntryCount], so the
 * screen can tell an empty queue from an empty search result), the raw
 * [query] (the TextField's value, spaces and all — matching trims), the
 * [import] phase, and [error] — the latest queue-command outcome (a Busy
 * drop or a Failed cause, as a locale-independent [UiText] the
 * composition resolves through the i18n seam — state never carries
 * rendered copy), cleared by the next intent, whatever its outcome.
 */
data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val queueEntryCount: Int = 0,
    val query: String = "",
    val import: LibraryImport = LibraryImport.Idle,
    val error: UiText? = null,
)
