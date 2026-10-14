package org.fauste.player.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fauste.player.rust.FausteEngineInterface
import org.fauste.player.rust.PlaylistEntry
import org.fauste.player.rust.Track
import java.util.concurrent.TimeUnit

/**
 * The outcome of an enqueue. The FFI's `enqueue` returns only acceptance:
 * the entry — and the id the remove/move commands address — exists once
 * the conductor applies the insert, so [QueueManager.enqueue] confirms
 * within a bounded window before answering with the id.
 */
sealed interface EnqueueResult {
    /** The entry is in the queue; [id] addresses it for remove/move. */
    data class Queued(
        val id: ULong,
    ) : EnqueueResult

    /** The command queue was full, so the enqueue was dropped; retrying is safe. */
    data object Busy : EnqueueResult

    /** The enqueue was refused or failed, with a human-readable cause. */
    data class Failed(
        val message: String,
    ) : EnqueueResult
}

/**
 * The main player's queue, wrapped into a [StateFlow]. The FFI applies
 * commands asynchronously (the conductor thread), so every mutation is
 * followed by a bounded refresh that publishes the engine's queue once
 * the change shows — never a stale pre-apply read.
 *
 * **Why mutation-driven and not polled like [PlayerController]**: the
 * queue's rows change only through explicit commands — the reducer's only
 * row mutations are `InsertPaths` / `RemoveEntry` / `MoveEntry`
 * (`fp-model/src/reducer.rs`), all of which go through this class.
 * Auto-advance and `previous` move the CURRENT track, not the rows
 * (`StartNextAt` / R24), which [PlayerController.engineState] already
 * covers. One boot refresh seeds the flow from the engine's persisted
 * queue.
 *
 * **Threading**: every engine call runs on the injected [dispatcher].
 *
 * **Shutdown**: [close] cancels the manager's scope (the boot refresh and
 * any in-flight mutation window); the engine itself is NOT closed — it is
 * owned by the DI container.
 */
class QueueManager(
    private val engine: FausteEngineInterface,
    private val dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _playlist = MutableStateFlow<List<PlaylistEntry>>(emptyList())

    /** The main player's queue, one [PlaylistEntry] per row, in order. */
    val playlist: StateFlow<List<PlaylistEntry>> = _playlist.asStateFlow()

    init {
        // One boot refresh: a consumer collecting before any mutation sees
        // the engine's queue, not a stale empty list.
        scope.launch { refresh() }
    }

    /**
     * Appends the file at [path] to the end of the queue and answers with
     * its entry id once the conductor has applied the insert — the id is
     * minted at apply time, so it cannot be returned synchronously. Every
     * metadata field but the path is ignored by the model (the enqueue
     * contract, ffi.rs): the title comes from the file stem.
     *
     * When several new entries carry the same path (duplicate enqueues),
     * the newest append wins. A command-queue-full drop answers
     * [EnqueueResult.Busy] — retrying is safe.
     */
    suspend fun enqueue(path: String): EnqueueResult =
        withContext(dispatcher) {
            val knownIds = engine.getPlaylist().mapTo(HashSet()) { it.id }
            try {
                engine.enqueue(
                    Track(
                        path = path,
                        title = "",
                        artist = "",
                        duration = 0.0,
                        sampleRate = 0u,
                        channels = 0u,
                    ),
                )
            } catch (e: Exception) {
                return@withContext enqueueFailure(e)
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONFIRM_TIMEOUT_MILLIS)
            var fresh: List<PlaylistEntry> = emptyList()
            while (true) {
                val queue = engine.getPlaylist()
                _playlist.value = queue
                fresh = queue.filter { it.id !in knownIds && it.path == path }
                if (fresh.isNotEmpty() || System.nanoTime() >= deadline) {
                    break
                }
                delay(CONFIRM_PERIOD_MILLIS)
            }
            // Defensive expiry branch: a healthy engine applies within
            // milliseconds (the model inserts even unreadable files,
            // marking their file state — ffi.rs `enqueue`), so this means
            // a wedged conductor; the flow above still shows the engine's
            // truth.
            if (fresh.isNotEmpty()) {
                EnqueueResult.Queued(fresh.last().id)
            } else {
                EnqueueResult.Failed(
                    "the engine accepted the enqueue but no entry for $path appeared " +
                        "within $CONFIRM_TIMEOUT_MILLIS ms",
                )
            }
        }

    /**
     * Removes the entry with [entryId] from the queue. A missing entry is
     * refused up front by the FFI (`NotFound`, ffi.rs `remove_entry`) and
     * maps to [CommandResult.Failed]. The model enforces its own rules on
     * apply (the desktop refuses removing the on-air entry,
     * `fp-app/src/ui/table.rs:993`).
     */
    suspend fun remove(entryId: ULong): CommandResult =
        mutate({ it.removeEntry(entryId) }) { queue -> queue.none { entry -> entry.id == entryId } }

    /**
     * Moves the entry with [entryId] to [toIndex] (the position it lands
     * at; a target past the end is clamped to it, ffi.rs `move_entry`).
     * The model enforces its own rules on apply — if it drops the move,
     * the bounded refresh simply ends and the flow keeps showing the
     * engine's queue.
     */
    suspend fun move(
        entryId: ULong,
        toIndex: UInt,
    ): CommandResult =
        mutate({ it.moveEntry(entryId, toIndex) }) { queue ->
            val expectedIndex = minOf(toIndex.toInt(), queue.lastIndex)
            queue.indexOfFirst { entry -> entry.id == entryId } == expectedIndex
        }

    /**
     * Queues the transport grid's Previous button (ffi.rs `previous`): the
     * model crossfades back to the last entry the player left and no-ops
     * unless playing (`fp-model/src/reducer.rs:563`). There is no `next`:
     * advancing is the engine's own transition (`StartNextAt`), not a
     * queue command.
     */
    suspend fun previous(): CommandResult = mutate({ it.previous() }) { true }

    /** Re-reads the engine's queue into [playlist]. */
    suspend fun refresh() {
        withContext(dispatcher) { _playlist.value = engine.getPlaylist() }
    }

    /** Stops the boot refresh / any in-flight mutation window. Idempotent. */
    override fun close() {
        scope.cancel()
    }

    /** Runs one queue mutation, then refreshes until the effect shows (bounded). */
    private suspend fun mutate(
        command: (FausteEngineInterface) -> Unit,
        observed: (List<PlaylistEntry>) -> Boolean,
    ): CommandResult =
        withContext(dispatcher) {
            try {
                command(engine)
            } catch (e: Exception) {
                return@withContext CommandResult.fromException(e)
            }
            refreshUntil(observed)
            CommandResult.Accepted
        }

    /**
     * Polls the engine's queue until [observed] holds or the bounded
     * window ends, publishing each read — the flow always mirrors the
     * engine, even when an expected change never shows (a wedged
     * conductor).
     */
    private suspend fun refreshUntil(observed: (List<PlaylistEntry>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONFIRM_TIMEOUT_MILLIS)
        while (true) {
            val queue = withContext(dispatcher) { engine.getPlaylist() }
            _playlist.value = queue
            if (observed(queue) || System.nanoTime() >= deadline) {
                return
            }
            delay(CONFIRM_PERIOD_MILLIS)
        }
    }

    /** Maps a failed enqueue command's exception onto [EnqueueResult]. */
    private fun enqueueFailure(e: Exception): EnqueueResult =
        when (val result = CommandResult.fromException(e)) {
            CommandResult.Busy -> EnqueueResult.Busy
            is CommandResult.Failed -> EnqueueResult.Failed(result.message)
            CommandResult.Accepted -> EnqueueResult.Failed("an exception cannot map to Accepted")
        }

    companion object {
        /** Bounded window for confirming a mutation against the engine — the Rust suite's wait budget (ffi_state.rs). */
        const val CONFIRM_TIMEOUT_MILLIS: Long = 2_000

        /** The confirm poll period, as fine as the Rust suite's polling. */
        const val CONFIRM_PERIOD_MILLIS: Long = 20
    }
}
