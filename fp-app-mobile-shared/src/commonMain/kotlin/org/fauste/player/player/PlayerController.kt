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
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.AppLifecycleState
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
 * **LIFECYCLE-AWARE IDLING** (the Phase-3 final-review carry, closed
 * here): the loop checks [lifecycle] — the plain [AppLifecycleHolder]
 * Phase 3 defined, fed by the host Activity's own onStart/onStop — at
 * every wake. Background → the tick SKIPS the engine read entirely: no
 * FFI call happens while the app is backgrounded. The loop itself stays
 * alive, suspended in its plain in-process delay (the idle heartbeat — a
 * coroutine delay re-arm per period, not a JNA hop), so it notices the
 * resume without anyone restarting it. Foreground → the FIRST wake reads
 * immediately — the fresh-state tick after idling, bounded by the loop's
 * own one-period granularity (the same freshness bound the UI always
 * has) — and then returns to the periodic rhythm.
 *
 * **THE SERVICE KEEP-AWAKE GATE** (the Task-6 review's follow-on): the
 * idle condition is `Background && !serviceActive`, set through
 * [setPlaybackServiceActive] by the playback service itself — true from
 * its foreground promotion, false on its exit path. While the service is
 * active the poll therefore keeps its periodic rhythm even with the app
 * backgrounded: the service's media notification is fed by THIS flow
 * (through [engineState] → the session adapter), so an unconditional
 * Background idle froze the lock-screen state within one period — the
 * exact conflict the review found between this Task-5 optimization and
 * its Task-6 consumer. The gate is not an exemption from execution
 * rules: the service is a FOREGROUND service, so background
 * restrictions do not apply to it, and the poll's reads stay the cheap
 * JNA snapshots they always were. Paused counts as active too — the
 * service keeps running with its notification while paused (Ruling 5),
 * and that notification still needs live state. Background with NO
 * service idles exactly as Task 5 intended.
 *
 * **The disclosed limit** (recorded in the Task-6 report): a
 * remote-driven play while the app is backgrounded AND fully stopped
 * still idles — no service is alive to hold the gate open, so the poll
 * never sees the Playing transition that would arm the service
 * starter; the play happens in the engine and the notification never
 * arrives until the app is foregrounded again. A loopback/paired
 * client issuing play to a stopped, backgrounded app is the corner
 * (the remote API has no way to start an Android service by itself).
 *
 * **IDLING STOPS UI STATE UPDATES, NOT PLAYBACK.** The engine's audio
 * path is native (the conductor + AAudio threads run in Rust); the poll
 * only feeds UI state, so a backgrounded app keeps playing and simply
 * stops refreshing its snapshot. The Phase-4 foreground service (Task 6)
 * builds on exactly this distinction: it observes a UI-facing bridge and
 * the audio continues — and since Task 6 the keep-awake gate above
 * keeps THAT observation live while the service runs. There is
 * deliberately NO `androidx.lifecycle`/`ProcessLifecycleOwner` here — the
 * holder is the Phase-3 plain box (see `Lifecycle.kt`'s
 * dependency-discipline note), constructor-injected so DI keeps one
 * source of truth the service can read too; a `fun onAppLifecycle(...)`
 * callback would have duplicated the holder's state and forced the
 * Activity to resolve the engine-backed controller on the main thread
 * (the graph's documented off-main rule).
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
 * survives the controller; idling is not a second lifetime, close from a
 * backgrounded state ends the loop all the same). The engine itself is
 * NOT closed: it is owned by the DI container, which outlives the
 * controller.
 */
class PlayerController(
    private val engine: FausteEngineInterface,
    private val lifecycle: AppLifecycleHolder,
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
     * The most recent poll failure, or null while ticks succeed — a
     * successful tick clears it, so the channel reads as *current* poll
     * health (what a Phase-4 error indicator wants), not an error
     * history. The one sticky case: a destroyed engine (UniFFI raises
     * `IllegalStateException` from `callWithPointer`) is recorded here
     * and the loop stops — with the loop dead nothing can ever clear
     * it, so the failure stays visible beside the last snapshot on
     * [engineState]. This channel exists so a consumer can tell "the
     * engine is slow" from "the engine is gone".
     */
    val lastPollFailure: StateFlow<Throwable?> = _lastPollFailure.asStateFlow()

    private val pollLoop: Job =
        scope.launch {
            while (isActive) {
                // THE IDLE GATE (the class KDoc's idling contract, now with
                // the Task-6 keep-awake gate): the tick skips the engine
                // read — the whole FFI boundary — while the app is
                // backgrounded AND the playback service is not active; the
                // service holds the gate open because its notification is
                // fed by this very poll. Both reads are cheap in-memory
                // volatile reads (the holder and the service flag), so the
                // idle heartbeat costs no JNA hop; the first Foreground
                // wake — or the first wake after the service exits a
                // backgrounded app — falls through to a read at once (the
                // immediate fresh tick after idling).
                if (lifecycle.current() != AppLifecycleState.Background || playbackServiceActive) {
                    try {
                        _engineState.value = engine.getState()
                        // A good read restores the channel to null — current
                        // health, not history. StateFlow de-duplicates the
                        // repeated null, so the happy path emits nothing.
                        _lastPollFailure.value = null
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
                }
                delay(pollPeriodMillis)
            }
        }

    /**
     * The playback service's half of the keep-awake gate (the class KDoc):
     * `active = true` from the service's foreground promotion, `false` on
     * its exit path. A volatile, not a flow: the poll re-reads it every
     * wake, so the flag needs exactly the same memory visibility the
     * lifecycle holder has, and nothing more — and idempotent by design,
     * so the service's repeated promotions and its onDestroy belt-and-
     * braces clear are all safe.
     */
    @Volatile
    private var playbackServiceActive: Boolean = false

    /** Opens/closes the keep-awake gate; see the class KDoc's gate section. Idempotent, thread-safe. */
    fun setPlaybackServiceActive(active: Boolean) {
        playbackServiceActive = active
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
