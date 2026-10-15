package org.fauste.player.repository

import org.fauste.player.platform.Storage
import org.fauste.player.player.EnqueueResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * The import path for user-picked audio: copy the URI into app-private
 * storage, then enqueue the copied file. The ordering is the contract —
 * [Storage.importToAppStorage] answers `null` (or throws) on a failed
 * copy, and ONLY a successful copy is ever enqueued, so the queue never
 * holds a path that is not really on disk.
 *
 * Phase 4 adds metadata/cover analysis at this seam (the desktop's
 * import pipeline, `fp-analysis`); Phase 3 deliberately stops at
 * copy + enqueue.
 */
class MediaRepository(
    private val storage: Storage,
    private val playlist: PlaylistRepository,
) {
    /**
     * Imports the audio behind [uri] and enqueues it; success answers the
     * app-storage path that is now queued. Failure is either the copy —
     * nothing was enqueued, the contract above — or the enqueue: the copy
     * IS on disk in that case, and the cause's message carries its path,
     * so a caller can retry the enqueue without re-copying.
     *
     * Cancellation propagates: a cancelled import is not a failure.
     */
    suspend fun import(uri: String): Result<String> {
        val path =
            try {
                storage.importToAppStorage(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(e)
            } ?: return Result.failure(
                ImportException("nothing was copied from $uri — the queue was left untouched"),
            )
        return when (val enqueued = playlist.enqueue(path)) {
            is EnqueueResult.Queued -> Result.success(path)
            is EnqueueResult.Busy ->
                Result.failure(
                    ImportException(
                        "the command queue was full and the enqueue of $path was dropped; " +
                            "the copy is on disk — retrying the enqueue is safe",
                    ),
                )
            is EnqueueResult.Failed ->
                Result.failure(
                    ImportException(
                        "the engine refused the enqueue of $path (${enqueued.message}); " +
                            "the copy stays on disk",
                    ),
                )
        }
    }
}

/**
 * The human-readable import failure; [message] is written to be shown to
 * a user (and, for enqueue failures, to name the path that is already on
 * disk).
 */
class ImportException(
    message: String,
) : Exception(message)
