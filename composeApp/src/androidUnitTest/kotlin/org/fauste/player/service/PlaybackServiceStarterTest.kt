package org.fauste.player.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.PlayerController
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE APP-SIDE START TRIGGER (Task 6 deliverable 5): the service starts
 * when PLAYBACK starts — observed on the engine's own status, the same
 * source every consumer reads — and only ONCE per distinct Playing
 * phase: a Busy-dropped or failed play never starts a service for
 * playback that never began, and a remote-driven play (the loopback
 * HTTP remote) starts it exactly like a local one. Paused and stopped
 * never start anything (Ruling 5: they only ever keep or end a service
 * that playing already started).
 *
 * The world is the established one — the REAL [PlayerController] poll
 * pipeline over [ScriptedEngine] on the test scheduler — and the
 * "start" side effect is a counting lambda, so the pin is the
 * TRIGGERING contract, not Android's service machinery (the manifest
 * and the real startForegroundService are the report's manual-
 * verification checklist).
 *
 * The suite runs under Robolectric (the final-review wave's lane
 * change): the trigger's refusal guard logs through android.util.Log,
 * which the plain JVM lane cannot shadow — and the guard test below
 * drives exactly that catch path with a scripted start function. The
 * two triggering tests are untouched by the lane (no Android API
 * before the guard existed; the scheduler drives them identically).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackServiceStarterTest {
    @Test
    fun playStartsTheForegroundServiceOncePerDistinctPlayingPhase() =
        runTest {
            val engine = ScriptedEngine(engineState(status = "stopped"))
            val controller = PlayerController(engine, AppLifecycleHolder(), StandardTestDispatcher(testScheduler), pollPeriodMillis = 10)
            val starts = AtomicInteger(0)
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))

            PlaybackServiceStarter(controller, scope) { starts.incrementAndGet() }

            // The pre-play state must not start anything.
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            assertEquals(0, starts.get(), "a stopped engine must not start the service")

            // Playback begins: exactly one start.
            engine.state.value = engineState(status = "playing", position = 10.0, track = track(path = "/music/a.flac"))
            advanceTimeBy(20)
            runCurrent()
            assertEquals(1, starts.get(), "the first Playing observation must start the foreground service once")

            // MORE Playing observations (the position advances every poll
            // tick): the distinct-status gate must hold at one start.
            engine.state.value = engineState(status = "playing", position = 42.0, track = track(path = "/music/a.flac"))
            engine.state.value = engineState(status = "playing", position = 61.0, track = track(path = "/music/a.flac"))
            advanceTimeBy(20)
            runCurrent()
            assertEquals(
                1,
                starts.get(),
                "a stream of Playing observations within one playback phase must not re-start the service",
            )

            // Pause then play again: a NEW distinct phase → a second start.
            // The real startForegroundService is idempotent on a running
            // service; the trigger's job is to fire per phase, so the pin
            // counts phases, not dedup.
            engine.state.value = engineState(status = "paused", position = 61.0, track = track(path = "/music/a.flac"))
            advanceTimeBy(20)
            runCurrent()
            engine.state.value = engineState(status = "playing", position = 61.0, track = track(path = "/music/a.flac"))
            advanceTimeBy(20)
            runCurrent()
            assertEquals(2, starts.get(), "a resumed phase must fire the trigger again")

            controller.close()
            scope.cancel()
        }

    @Test
    fun pauseAndStopNeverStartTheService() =
        runTest {
            val engine = ScriptedEngine(engineState(status = "stopped"))
            val controller = PlayerController(engine, AppLifecycleHolder(), StandardTestDispatcher(testScheduler), pollPeriodMillis = 10)
            val starts = AtomicInteger(0)
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))

            PlaybackServiceStarter(controller, scope) { starts.incrementAndGet() }

            engine.state.value = engineState(status = "paused", track = track(path = "/music/a.flac"))
            advanceTimeBy(20)
            runCurrent()
            engine.state.value = engineState(status = "stopped")
            advanceTimeBy(20)
            runCurrent()
            assertEquals(0, starts.get(), "only Playing starts the service — Ruling 5's other halves only keep or end it")

            controller.close()
            scope.cancel()
        }

    /**
     * THE FINAL-REVIEW F2 GUARD: a refused foreground start must be
     * log-and-skip, never process-fatal. The refusal the review named:
     * the trigger fires on every distinct Playing phase — including a
     * remote-driven pause → play while backgrounded — and on API 31+
     * `startForegroundService` throws
     * `ForegroundServiceStartNotAllowedException` (an
     * [IllegalStateException]) when the process is not in an
     * FGS-start-allowed state; the app scope's SupervisorJob does NOT
     * swallow it, so unguarded it kills the process mid-playback.
     *
     * The scripted start stands in for the platform call and throws the
     * ISE on the first distinct Playing phase; the pin: the collector
     * SURVIVES the refusal (the next distinct phase re-arms the start —
     * exactly the documented reasoning: media3 re-promotes a running
     * service on its own, and the next foregrounded play re-arms).
     */
    @Test
    fun aRefusedForegroundStartIsSkippedAndTheNextPlayingPhaseReArms() =
        runTest {
            val engine = ScriptedEngine(engineState(status = "stopped"))
            val controller = PlayerController(engine, AppLifecycleHolder(), StandardTestDispatcher(testScheduler), pollPeriodMillis = 10)
            val attempts = AtomicInteger(0)
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))

            PlaybackServiceStarter(controller, scope) {
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("ForegroundServiceStartNotAllowedException: the scripted refusal")
                }
            }

            // The teardown rides in finally: on ANY failure the controller's
            // infinite poll loop must still be closed before the method ends —
            // a leaked loop keeps the shared scheduler from ever idling and
            // runTest's completion advance spins forever (the Task-6 report's
            // documented Robolectric trap, hit here by a RED assertion).
            try {
                // The first distinct Playing phase: the start is attempted and REFUSED.
                engine.state.value = engineState(status = "playing", position = 10.0, track = track(path = "/music/a.flac"))
                advanceTimeBy(20)
                runCurrent()
                assertEquals(1, attempts.get(), "the first Playing phase must have attempted the refused start")

                // Pause then play — the refusal must not have killed the collector:
                // a NEW distinct phase must fire the trigger again.
                engine.state.value = engineState(status = "paused", position = 10.0, track = track(path = "/music/a.flac"))
                advanceTimeBy(20)
                runCurrent()
                engine.state.value = engineState(status = "playing", position = 12.0, track = track(path = "/music/a.flac"))
                advanceTimeBy(20)
                runCurrent()
                assertEquals(
                    2,
                    attempts.get(),
                    "a refused start is log-and-skip, not fatal — the next distinct Playing phase re-arms",
                )
            } finally {
                controller.close()
                scope.cancel()
            }
        }
}
