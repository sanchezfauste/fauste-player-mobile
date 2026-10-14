package org.fauste.player.player

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
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.FausteEngineInterface
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wraps a [FausteEngineInterface] (the real `FausteEngine` from DI) into
 * reactive Kotlin state: a poll loop reads `get_state()` every
 * [pollPeriodMillis] into [engineState] — there is no UniFFI callback
 * surface this phase, so polling is the delivery mechanism (plan Ruling 2).
 * Transport commands are suspend functions returning [CommandResult].
 *
 * **Threading**: every engine call — poll ticks and commands alike — runs
 * on the injected [dispatcher]; nothing touches the main thread here.
 * `get_state` is a non-blocking model/telemetry snapshot read (ffi.rs),
 * but it is still a JNA hop, so it stays off-main like everything else.
 *
 * **The Busy surface**: a command whose conductor queue is full comes
 * back [CommandResult.Busy] — the command was dropped, retrying is safe
 * (ffi.rs, `FausteError::Busy`). The poll loop itself can never be Busy:
 * `get_state` is infallible on the Rust side (it returns a snapshot, never
 * an error), so ticks cannot fail that way; see [lastPollFailure] for the
 * failure channel that does exist.
 *
 * **Shutdown**: the poll loop is a child of the controller's own scope;
 * [close] cancels it (structured concurrency — no orphaned coroutine
 * survives the controller). The engine itself is NOT closed: it is owned
 * by the DI container, which outlives the controller.
 */
class PlayerController(
    private val engine: FausteEngineInterface,
    private val dispatcher: CoroutineDispatcher,
    pollPeriodMillis: Long = DEFAULT_POLL_PERIOD_MILLIS,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _engineState =
        MutableStateFlow(
            // The seed mirrors the FFI's own degenerate-state shape (ffi.rs,
            // `get_state`'s no-player branch): stopped, zeroed, full volume,
            // the "null" fallback backend. The first tick — within one poll
            // period of construction — replaces it with the real snapshot.
            EngineState(
                status = "stopped",
                position = 0.0,
                duration = 0.0,
                currentTrack = null,
                volume = 1.0f,
                backend = "null",
            ),
        )

    /** The polled engine snapshot; every field comes straight from the FFI. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _lastPollFailure = MutableStateFlow<Throwable?>(null)

    /**
     * The last poll tick failure, or null while every tick succeeds. A
     * destroyed engine (UniFFI raises `IllegalStateException` from
     * `callWithPointer`) is recorded here and the loop stops — the last
     * snapshot stays on [engineState] for the UI. This channel exists so a
     * consumer can tell "the engine is slow" from "the engine is gone".
     */
    val lastPollFailure: StateFlow<Throwable?> = _lastPollFailure.asStateFlow()

    private val pollLoop: Job =
        scope.launch {
            while (isActive) {
                try {
                    _engineState.value = engine.getState()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    // The engine object was destroyed under us — polling a
                    // dead engine is futile. Record it and stop the loop;
                    // the last snapshot stays for the UI.
                    _lastPollFailure.value = e
                    return@launch
                } catch (e: Exception) {
                    // Anything else (a JNA hiccup, an unexpected FFI
                    // failure): skip this tick, keep the last snapshot and
                    // keep polling — one bad read must not kill the flow.
                    _lastPollFailure.value = e
                }
                delay(pollPeriodMillis)
            }
        }

    /** Queues `Command::Play` for the main player. */
    suspend fun play(): CommandResult = command { it.play() }

    /** Queues `Command::Pause` for the main player. */
    suspend fun pause(): CommandResult = command { it.pause() }

    /** Queues `Command::Stop` for the main player. */
    suspend fun stop(): CommandResult = command { it.stop() }

    /** Queues `Command::Seek` of the current entry to [positionSeconds]. */
    suspend fun seek(positionSeconds: Double): CommandResult = command { it.seek(positionSeconds) }

    /**
     * Queues `Command::SetVolume` with the linear gain 0.0–1.0 (the FFI
     * takes the linear value directly; a fader-style UI converts first).
     */
    suspend fun setVolume(volume: Float): CommandResult = command { it.setVolume(volume) }

    /** Stops the poll loop. Idempotent; never touches the engine itself. */
    override fun close() {
        pollLoop.cancel()
        scope.cancel()
    }

    /** Runs one engine command on the injected dispatcher, mapping FFI errors. */
    private suspend fun command(block: (FausteEngineInterface) -> Unit): CommandResult =
        withContext(dispatcher) {
            try {
                block(engine)
                CommandResult.Accepted
            } catch (e: Exception) {
                CommandResult.fromException(e)
            }
        }

    companion object {
        /** The default poll period: 10 reads per second. */
        const val DEFAULT_POLL_PERIOD_MILLIS: Long = 100
    }
}
