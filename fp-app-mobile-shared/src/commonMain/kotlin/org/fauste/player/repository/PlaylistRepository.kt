package org.fauste.player.repository

import kotlinx.coroutines.flow.StateFlow
import org.fauste.player.player.CommandResult
import org.fauste.player.player.EnqueueResult
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.PlaylistEntry

/**
 * The UI-facing player repository: a thin, honest wrapper over
 * [QueueManager] and [PlayerController]. Every state holder below is the
 * manager's/controller's own flow and every command a straight delegate
 * — the repository re-implements nothing, so the bounded-apply semantics
 * of the layer below are the semantics the UI sees.
 *
 * **Threading**: every engine call runs on the layer below's dispatcher
 * (I/O by DI); nothing here touches the main thread.
 */
class PlaylistRepository(
    private val queue: QueueManager,
    private val controller: PlayerController,
) {
    /** The queue rows, straight from [QueueManager.playlist] (the manager owns the flow). */
    val playlist: StateFlow<List<PlaylistEntry>> = queue.playlist

    /**
     * The polled engine snapshot: transport status, position, current
     * track and volume, straight from [PlayerController.engineState] —
     * every field comes from the FFI.
     */
    val engineState: StateFlow<EngineState> = controller.engineState

    /** Appends the file at [path] to the queue via [QueueManager.enqueue]. */
    suspend fun enqueue(path: String): EnqueueResult = queue.enqueue(path)

    /** Removes the entry with [entryId]; a missing entry is refused up front by the FFI. */
    suspend fun remove(entryId: ULong): CommandResult = queue.remove(entryId)

    /** Moves the entry with [entryId] to [toIndex] via [QueueManager.move]. */
    suspend fun move(
        entryId: ULong,
        toIndex: UInt,
    ): CommandResult = queue.move(entryId, toIndex)

    /** Queues the transport grid's Previous button, mirroring [QueueManager.previous]. */
    suspend fun previous(): CommandResult = queue.previous()

    // The transport surface: straight delegates over the controller, so
    // the repository is the UI's single door to the player.

    /** Queues `Command::Play` for the main player. */
    suspend fun play(): CommandResult = controller.play()

    /** Queues `Command::Pause` for the main player. */
    suspend fun pause(): CommandResult = controller.pause()

    /** Queues `Command::Stop` for the main player. */
    suspend fun stop(): CommandResult = controller.stop()

    /** Queues `Command::Seek` of the current entry to [positionSeconds]. */
    suspend fun seek(positionSeconds: Double): CommandResult = controller.seek(positionSeconds)
}
