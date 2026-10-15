package org.fauste.player.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fauste.player.rust.FausteEngineInterface
import org.fauste.player.rust.RemoteInfo
import kotlin.coroutines.cancellation.CancellationException

/**
 * The engine's loopback remote, wrapped as a [StateFlow]: what the
 * settings/status UI draws is the [RemoteInfo] the FFI's
 * `remote_status()` reports (loopback bind, ephemeral port —
 * `player_core.rs`).
 *
 * **Refresh choice — on demand plus a slow poll, NOT controller-state
 * watching.** The record is boot-time information: the server binds once
 * when the engine is constructed, and the backend switch's port guard
 * (ffi.rs / `PlayerCore::set_backend`) deliberately keeps that binding
 * through config round-trips, so the address has no reason to change
 * while the app runs. Collecting `PlayerController.engineState` would
 * fire on every position tick (ten per second while playing) for
 * information that cannot have changed; the slow poll (default 5 s, one
 * small FFI read per period) is the bounded safety net that keeps the
 * flow fresh even for a consumer that never calls [refresh] — and
 * [refresh] itself is the on-demand path a settings screen uses the
 * moment it appears.
 *
 * The seed value (disabled, empty address) is the pre-first-read
 * placeholder; the poll loop's first tick replaces it within one poll
 * period of construction. The loop's failure channel — [lastPollFailure]
 * — mirrors the controller's: transient read failures are recorded and
 * kept polling, a destroyed engine is recorded once and the loop stops.
 *
 * **Threading**: every engine call runs on the injected [dispatcher].
 *
 * **Shutdown**: [close] cancels the poll loop (structured concurrency —
 * no orphaned coroutine survives the wrapper). The engine itself is NOT
 * closed: it is owned by the DI container.
 */
class RemoteServer(
    private val engine: FausteEngineInterface,
    private val dispatcher: CoroutineDispatcher,
    pollPeriodMillis: Long = DEFAULT_POLL_PERIOD_MILLIS,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(RemoteInfo(enabled = false, address = ""))

    /** The engine's remote-server status; see the class KDoc for the refresh model. */
    val state: StateFlow<RemoteInfo> = _state.asStateFlow()

    private val _lastPollFailure = MutableStateFlow<Throwable?>(null)

    /**
     * The most recent poll failure, or null while polls succeed — the
     * same model as `PlayerController.lastPollFailure`: a successful
     * read clears it (current health, not history), while the one
     * sticky case is the destroyed engine, where the loop stops and
     * nothing after that can clear it. [refresh] surfaces failures to
     * its direct caller instead of recording them here.
     */
    val lastPollFailure: StateFlow<Throwable?> = _lastPollFailure.asStateFlow()

    init {
        require(pollPeriodMillis > 0) { "pollPeriodMillis must be positive, was $pollPeriodMillis" }
    }

    private val pollLoop: Job =
        scope.launch {
            while (isActive) {
                try {
                    _state.value = engine.remoteStatus()
                    // A good read restores the channel to null; the
                    // StateFlow de-duplicates the repeated value.
                    _lastPollFailure.value = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    // The engine object was destroyed under us — polling a
                    // dead engine is futile. Record why the loop is
                    // stopping, keep the last record and stop.
                    _lastPollFailure.value = e
                    return@launch
                } catch (e: Exception) {
                    // One bad read must not kill the loop: record it, keep
                    // the last record and try again next period.
                    _lastPollFailure.value = e
                }
                delay(pollPeriodMillis)
            }
        }

    /**
     * Re-reads the FFI now and answers the fresh record — the on-demand
     * path. Unlike the poll loop, this surfaces a failure to the caller:
     * whoever asks explicitly deserves the real answer, and the flow
     * keeps the last good record either way.
     */
    suspend fun refresh(): RemoteInfo =
        withContext(dispatcher) {
            engine.remoteStatus().also { _state.value = it }
        }

    /** Stops the poll loop. Idempotent; never touches the engine itself. */
    override fun close() {
        pollLoop.cancel()
        scope.cancel()
    }

    companion object {
        /**
         * The slow-poll period. The record is near-static (see the class
         * KDoc), so 5 s is responsive enough for a settings screen while
         * costing one small FFI read per period.
         */
        const val DEFAULT_POLL_PERIOD_MILLIS: Long = 5_000
    }
}
