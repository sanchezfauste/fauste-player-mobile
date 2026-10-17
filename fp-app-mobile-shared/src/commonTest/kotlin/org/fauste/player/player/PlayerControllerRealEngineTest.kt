package org.fauste.player.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.rust.FausteEngine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real engine under the controller (Ruling 7): every test constructs a
 * fresh FausteEngine over unique temp dirs and drives it through the
 * committed bindings — the same end-to-end exercise as the Rust host
 * suite (`ffi_state.rs`), with every wait bounded (2 s) exactly like it.
 * No stub, no fake: if the cdylib wiring breaks, the load test in this
 * package fails first.
 */
class PlayerControllerRealEngineTest {
    private val dirs = RealEngineFixture.TempDirs("controller")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close()
        dirs.cleanup()
    }

    @Test
    fun transportCommandsRoundTripThroughThePolledFlow() =
        runBlocking {
            // A 1 s track (not the Rust suite's 23 ms tone): the controller
            // polls at 20 ms, so a tone-length track could auto-advance
            // before the paused/stopped assertions read it.
            val wav = RealEngineFixture.tinyWav(dirs, "long.wav", 44_100)
            engine.enqueue(RealEngineFixture.bareTrack(wav.absolutePath))
            val controller = PlayerController(engine, AppLifecycleHolder(), Dispatchers.Default, pollPeriodMillis = 20)

            assertEquals("stopped", controller.engineState.value.status, "the seed is a fresh engine's state")

            assertEquals(CommandResult.Accepted, controller.play())
            withTimeout(2_000) { controller.engineState.first { it.status == "playing" } }

            assertEquals(CommandResult.Accepted, controller.pause())
            withTimeout(2_000) { controller.engineState.first { it.status == "paused" } }

            assertEquals(CommandResult.Accepted, controller.stop())
            withTimeout(2_000) { controller.engineState.first { it.status == "stopped" } }

            controller.close()
        }

    @Test
    fun seekAndVolumeRoundTripThroughThePolledFlow() =
        runBlocking {
            val wav = RealEngineFixture.tinyWav(dirs, "long.wav", 44_100) // 1 s of audio
            engine.enqueue(RealEngineFixture.bareTrack(wav.absolutePath))
            val controller = PlayerController(engine, AppLifecycleHolder(), Dispatchers.Default, pollPeriodMillis = 20)

            assertEquals(CommandResult.Accepted, controller.play())
            withTimeout(2_000) { controller.engineState.first { it.status == "playing" } }

            assertEquals(CommandResult.Accepted, controller.setVolume(0.5f))
            withTimeout(2_000) { controller.engineState.first { kotlin.math.abs(it.volume - 0.5f) < 1e-6f } }

            assertEquals(CommandResult.Accepted, controller.seek(0.5))
            withTimeout(2_000) { controller.engineState.first { it.position > 0.4 } }

            controller.close()
        }

    /**
     * The structured-concurrency shutdown pin: `close()` must end the poll
     * loop, not orphan it. The engine is warmed into Playing in real time
     * first (its conductor thread is real); then the loop runs on virtual
     * time and [CountingDispatches] proves no dispatch happens after
     * `close()` — a leaked loop would re-arm its delay and dispatch again
     * on every virtual period.
     */
    @Test
    fun closeCancelsThePollLoopWithoutLeakingIt() {
        // A 1 s track: the status must still be Playing when the first
        // virtual tick reads it, comfortably within the window.
        val wav = RealEngineFixture.tinyWav(dirs, "long.wav", 44_100)
        engine.enqueue(RealEngineFixture.bareTrack(wav.absolutePath))
        engine.play()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (engine.getState().status != "playing") {
            assertTrue(System.nanoTime() < deadline, "the engine never reached Playing")
            Thread.sleep(10)
        }

        runTest {
            val counting = CountingDispatches(StandardTestDispatcher(testScheduler))
            val controller = PlayerController(engine, AppLifecycleHolder(), counting, pollPeriodMillis = 10)
            // The first tick replaces the seed with the engine's real snapshot.
            runCurrent()
            assertEquals("playing", controller.engineState.value.status)

            controller.close()

            val afterClose = counting.count
            repeat(5) {
                advanceTimeBy(10)
                runCurrent()
            }
            assertEquals(afterClose, counting.count, "the poll loop kept dispatching after close")
        }
    }

    @Test
    fun aDestroyedEngineStopsTheLoopAndIsSurfacedNotSwallowed() =
        runBlocking {
            val controller = PlayerController(engine, AppLifecycleHolder(), Dispatchers.Default, pollPeriodMillis = 20)
            assertNull(controller.lastPollFailure.value)

            engine.close() // the ENGINE, not the controller

            val recorded =
                withTimeout(2_000) {
                    controller.lastPollFailure.first { it is IllegalStateException }
                }
            // The loop exited on the dead engine instead of spinning: the
            // failure recorded once and never changes over several poll periods.
            delay(200)
            assertSame(recorded, controller.lastPollFailure.value)
            assertEquals("stopped", controller.engineState.value.status, "the last snapshot stays for the UI")

            controller.close() // must not throw on the already-dead loop
        }

    /** Counts the dispatches handed to [delegate] — the leak probe above. */
    private class CountingDispatches(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var count = 0
            private set

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            count += 1
            delegate.dispatch(context, block)
        }
    }
}
