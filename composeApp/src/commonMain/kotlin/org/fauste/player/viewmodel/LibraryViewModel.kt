package org.fauste.player.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.fauste.player.player.CommandResult
import org.fauste.player.player.QueueManager
import org.fauste.player.repository.MediaRepository
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.PlaylistEntry

/**
 * The Library screen's UDF half, mirroring [PlayerViewModel]: state down
 * through [uiState], intents up through the `on*` functions — no composable
 * ever touches the engine or a repository. The single composition pipeline
 * in [init] maps the repository's playlist + engine-state flows (the
 * state layer the shared graph owns) into [LibraryUiState] rows, joined
 * with the search query, the import phase and the error channel.
 *
 * Threading: every engine/repository call — the row-tap jump, the import —
 * runs inside `viewModelScope.launch(commandDispatcher)`; the state layer
 * arrives as kotlin [Lazy] handles (Koin `inject()`) and first resolves
 * inside that dispatcher coroutine, so the engine's heavy first
 * resolution stays off the main thread by construction (the shared
 * AppModule's documented rule — see PlayerViewModel's KDoc).
 *
 * THE ROW TAP IS ARM-ONLY (the Task-2 carry, mirrored from
 * [QueueManager.jump]): a tap arms the row as the player's explicit next
 * WITHOUT touching the transport — what is on air plays to its end and
 * the engine then starts the armed row; a stopped player stays stopped,
 * so a tap-while-stopped is silent from the user's view and the row UI
 * must not promise play-now. Since Phase 5 the FFI carries the armed id
 * (`armed_entry_id`), so the armed row now shows the "UP NEXT" badge
 * (TrackRow) — the arm the tap made is visible, still without any
 * play-now promise.
 *
 * The search filter is CLIENT-SIDE by design (documented per the plan):
 * the queue is small — a screenful — so [visibleRows] filters the mapped
 * rows against the query in the pipeline, and no engine call is made.
 *
 * Errors never crash: the jump outcome maps onto [LibraryUiState.error]
 * (cleared by the next intent, whatever its outcome), and an import
 * failure arrives as [LibraryImport.Failed] with the repository's
 * human-readable cause — [MediaRepository]'s contract already keeps a
 * failed copy out of the queue, so no failed path ever becomes a row.
 * Cancellation is never swallowed: there is no catch anywhere in this
 * class, so it propagates.
 */
class LibraryViewModel(
    private val queue: Lazy<QueueManager>,
    private val repository: Lazy<PlaylistRepository>,
    private val media: Lazy<MediaRepository>,
    private val commandDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())

    /** The Library screen's single state stream — see [LibraryUiState]. */
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Not backing properties (nothing exposes them under the same name):
    // plain private flows the uiState pipeline combines — hence no underscore.
    private val query = MutableStateFlow("")
    private val importState = MutableStateFlow<LibraryImport>(LibraryImport.Idle)
    private val lastError = MutableStateFlow<String?>(null)

    init {
        // The composition pipeline, on the command dispatcher (also where
        // the lazy state layer first resolves). Eager-started; the VM owns
        // it for its lifetime.
        viewModelScope.launch(commandDispatcher) {
            combine(
                repository.value.playlist,
                repository.value.engineState,
                query,
                importState,
                lastError,
            ) { playlist, engine, query, import, error ->
                LibraryUiState(
                    rows = visibleRows(playlist, engine, query),
                    queueEntryCount = playlist.size,
                    query = query,
                    import = import,
                    error = error,
                )
            }.collect { _uiState.value = it }
        }
    }

    /** The search field's every keystroke — pure local state; matching trims the needle. */
    fun onQueryChange(text: String) {
        lastError.value = null
        query.value = text
    }

    /**
     * ARM-ONLY queue jump (the class KDoc's carry): a row tap delegates
     * to [QueueManager.jump] and arms [entryId] as the player's explicit
     * next WITHOUT touching the transport — what is on air plays to its
     * end and the engine then starts the armed entry; a stopped player
     * stays stopped, and its next play starts it. The queue rows never
     * move. A Busy/Failed outcome surfaces in [LibraryUiState.error].
     */
    fun onRowTap(entryId: ULong) {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            lastError.value = message(queue.value.jump(entryId))
        }
    }

    /**
     * A picked SAF document (the host Activity owns the picker and hands
     * over the URI; see LibraryScreen's seam KDoc). Runs the import on the
     * command dispatcher — the IO lane the copy bytes move on — through
     * [LibraryImport] phases: Importing while the copy + enqueue run,
     * then [LibraryImport.Imported] with the queued path or
     * [LibraryImport.Failed] with the cause. A second pick while one is
     * importing is ignored (the screen also disables its Import button);
     * a failed copy is NEVER enqueued — [MediaRepository]'s contract —
     * so no failed path becomes a row.
     */
    fun onImportUri(uri: String) {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            if (importState.value is LibraryImport.Importing) {
                return@launch
            }
            importState.value = LibraryImport.Importing
            media.value
                .import(uri)
                .onSuccess { path -> importState.value = LibraryImport.Imported(path) }
                .onFailure { cause ->
                    importState.value =
                        LibraryImport.Failed(
                            cause.message?.takeIf { it.isNotBlank() } ?: IMPORT_FAILED_MESSAGE,
                        )
                }
        }
    }

    /**
     * The host Activity reports its permission gate refused the import
     * (the audio-read permission, per the platform contract): surfaced
     * through the same [LibraryImport] channel — an honest message, never
     * a crash and never a silent dead button.
     */
    fun onImportPermissionDenied() {
        lastError.value = null
        importState.value = LibraryImport.Failed(PERMISSION_DENIED_MESSAGE)
    }

    /** Dismisses the surfaced import result — back to [LibraryImport.Idle]. */
    fun onDismissImportResult() {
        lastError.value = null
        importState.value = LibraryImport.Idle
    }

    /** Maps a queue-command outcome onto the error channel: null on success, a message on failure. */
    private fun message(result: CommandResult): String? =
        when (result) {
            CommandResult.Accepted -> null
            CommandResult.Busy -> BUSY_MESSAGE
            is CommandResult.Failed -> result.message
        }

    companion object {
        /** The Busy channel's user-facing message — a dropped command is safe to retry (the FFI's contract). */
        const val BUSY_MESSAGE: String = "The player was busy and dropped the command — try again."

        /** The audio-read permission refusal — the honest dead-end message, phrased as the next step. */
        const val PERMISSION_DENIED_MESSAGE: String =
            "Import needs access to your audio files — grant the permission and tap Import again."

        /**
         * The never-fallback for a failure that carries no message
         * ([MediaRepository] failures are message-bearing exceptions, so
         * this is belt-and-braces, never the interesting path).
         */
        const val IMPORT_FAILED_MESSAGE: String = "the import failed"

        /**
         * The pipeline's pure row mapping + filter: playlist rows in queue
         * order, [LibraryRow.isPlaying] by the engine's on-air ENTRY id
         * (the id-join — duplicate paths no longer each read as current;
         * the documented path fallback covers only the id-absent shape),
         * [LibraryRow.isArmed] by the engine's armed id (the arm-only
         * badge), blank titles as the em dash marker
         * (PlayerUiState.trackTitle's convention), and the trimmed,
         * case-insensitive title match on a non-blank [query].
         */
        internal fun visibleRows(
            entries: List<PlaylistEntry>,
            engine: EngineState,
            query: String,
        ): List<LibraryRow> {
            val rows =
                entries.map { entry ->
                    LibraryRow(
                        id = entry.id,
                        title = entry.title.takeIf { it.isNotBlank() } ?: "—",
                        durationSeconds = entry.duration,
                        isPlaying =
                            when (val onAir = engine.currentEntryId) {
                                // The id is authoritative while carried —
                                // exactly one row can match it, where the
                                // old path join flagged every duplicate.
                                null -> entry.path == engine.currentTrack?.path
                                else -> entry.id == onAir
                            },
                        isArmed = engine.armedEntryId?.let { entry.id == it } ?: false,
                    )
                }
            val needle = query.trim()
            return if (needle.isEmpty()) {
                rows
            } else {
                rows.filter { row -> row.title.contains(needle, ignoreCase = true) }
            }
        }
    }
}
