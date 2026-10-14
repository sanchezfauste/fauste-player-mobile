package org.fauste.player.player

import org.fauste.player.rust.FausteException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The outcome of one engine command (transport or queue), with the FFI's
 * error surface mapped honestly:
 *
 * - [Accepted] — the conductor took the command. The model applies it
 *   asynchronously; state flows reflect it within a bounded window.
 * - [Busy] — the conductor's command queue was full and the command was
 *   **dropped** (`FausteException.Busy`, ffi.rs `send_main`:
 *   `ConductorHandle::send` returned false). Nothing was applied;
 *   retrying is safe.
 * - [Failed] — the engine refused or failed the command
 *   (`FausteException.NotFound` / `FausteException.Core`) or an unexpected
 *   JVM-side failure occurred; [message] is human-readable.
 *
 * Cancellation is never mapped: a cancelled caller stays cancelled.
 */
sealed interface CommandResult {
    /** The conductor accepted the command; watch the state flows for the effect. */
    data object Accepted : CommandResult

    /** The command queue was full, so the command was dropped; retrying is safe. */
    data object Busy : CommandResult

    /** The command was refused or failed, with a human-readable cause. */
    data class Failed(
        val message: String,
    ) : CommandResult

    companion object {
        /**
         * Maps an engine exception into a [CommandResult]. The typed FFI
         * variants keep their payload (`NotFound`/`Core` carry the
         * engine's message in their `v1` field); anything unexpected
         * degrades to [Failed] with the exception's message — a caller
         * never needs to know JNA details. Cancellation propagates.
         */
        fun fromException(exception: Exception): CommandResult =
            when (exception) {
                is FausteException.Busy -> Busy
                is FausteException.NotFound -> Failed(exception.v1)
                is FausteException.Core -> Failed(exception.v1)
                is CancellationException -> throw exception
                else -> Failed(exception.message ?: exception.javaClass.simpleName)
            }
    }
}
