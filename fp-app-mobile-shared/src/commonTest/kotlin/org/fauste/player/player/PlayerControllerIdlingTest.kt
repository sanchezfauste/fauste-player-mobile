package org.fauste.player.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.AppLifecycleState
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.FausteEngine
import org.fauste.player.rust.FausteEngineInterface
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * THE POLL IDLING CONTRACT (the Phase-3 final-review carry): the holder's
 * Background signal must stop the poll's ENGINE calls — the FFI boundary —
 * while the loop stays alive to notice the resume; Foreground must read
 * again at its FIRST wake (the immediate fresh tick), then return to the
 * periodic rhythm; and the loop's existing death/cancellation semantics
 * must hold through an idle window (the controller's idling KDoc).
 *
 * Evidence mechanics — a finding about the established pattern, worth
 * recording: `RealEngineFixture.CountingDispatches` is a plain
 * `CoroutineDispatcher`, so a loop dispatched through it does NOT route
 * `delay()` into the test scheduler (kotlinx `delay` only honors the
 * interceptor's clock when it implements `Delay`; otherwise it falls back
 * to the real-time `DefaultDelay`). The existing counting tests pass
 * either way because they only pin close-freezes. These pins need the
 * loop to actually tick on virtual time, so the controller runs on the
 * RAW [StandardTestDispatcher] (the PlayerViewModel-suite mechanics) and
 * the counting moves to [CountingGets] — the `getState` counter, which
 * is the very boundary the requirement names: "no FFI calls while
 * backgrounded". A freeze there is also the death/close probe: a loop
 * that survived its death would keep waking and reading on every period.
 *
 * The real engine per method (Ruling 7; a fresh instance per JUnit method,
 * like every suite in this package): the destroyed-engine test kills its
 * own instance only.
 */
class PlayerControllerIdlingTest {
    private val dirs = RealEngineFixture.TempDirs("controller-idling")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close() // idempotent: the destroyed-engine test already did
        dirs.cleanup()
    }

    @Test
    fun backgroundIdlesThePollNoEngineCallsWhileBackgrounded() =
        runTest {
            val gets = CountingGets(engine)
            val holder = AppLifecycleHolder()
            val controller =
                PlayerController(gets, holder, StandardTestDispatcher(testScheduler), pollPeriodMillis = POLL_PERIOD)

            // Tick #1 reads, then arms its delay for t=10.
            runCurrent()
            val afterFirstTick = gets.count
            assertTrue(afterFirstTick >= 1, "the foreground poll never read the engine")

            // Signal Background mid-delay, then let ONE full period pass:
            // the settle bound — a read already in flight when the signal
            // landed may complete, but nothing after this may cross the FFI.
            holder.update(AppLifecycleState.Background)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            val settled = gets.count
            assertEquals(
                afterFirstTick,
                settled,
                "a read landed past the settle bound after the Background signal",
            )

            // Five idle heartbeats: the loop wakes, re-checks, re-arms —
            // and never crosses the FFI boundary.
            repeat(IDLE_HEARTBEATS_TO_PROBE) {
                advanceTimeBy(POLL_PERIOD)
                runCurrent()
            }
            assertEquals(settled, gets.count, "the poll crossed the FFI boundary while backgrounded")
            controller.close()
        }

    @Test
    fun foregroundResumesWithAnImmediateTickBeforeThePeriodicRhythm() =
        runTest {
            val gets = CountingGets(engine)
            val holder = AppLifecycleHolder()
            val controller =
                PlayerController(gets, holder, StandardTestDispatcher(testScheduler), pollPeriodMillis = POLL_PERIOD)

            // Park the loop in the idle state first (tick #1, the signal,
            // then one settle period).
            runCurrent()
            holder.update(AppLifecycleState.Background)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            val idled = gets.count

            // Signal Foreground; the loop's delay is armed for t=20. The
            // immediate-tick contract: the FIRST wake after the signal
            // reads — not after a fresh full period. A wrong loop that
            // delayed before reading would still be silent at t=20.
            holder.update(AppLifecycleState.Foreground)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            assertEquals(idled + 1, gets.count, "the resume did not read at the loop's first wake")

            // And back on the periodic rhythm: one read per period — also
            // the alive-through-idle pin (the loop survived the idle).
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            assertEquals(idled + 3, gets.count, "the loop did not return to its periodic rhythm")
            controller.close()
        }

    /**
     * The destroyed-engine path, exercised THROUGH an idle window: the
     * engine dies while backgrounded (nothing notices — no reads), and the
     * first resume tick takes the throwing read, records the failure once
     * and stops the loop. The poll's death semantics are unchanged by
     * idling; a loop that survived its death would keep waking and
     * reading on every further period.
     */
    @Test
    fun aDestroyedEngineSurfacesThroughTheIdleWindowAndStopsTheLoop() =
        runTest {
            val gets = CountingGets(engine)
            val holder = AppLifecycleHolder()
            val controller =
                PlayerController(gets, holder, StandardTestDispatcher(testScheduler), pollPeriodMillis = POLL_PERIOD)

            runCurrent()
            holder.update(AppLifecycleState.Background)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()

            engine.close() // the ENGINE, not the controller — while backgrounded

            holder.update(AppLifecycleState.Foreground)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()

            // The resume tick hit the dead engine: recorded, not swallowed.
            assertTrue(
                controller.lastPollFailure.value is IllegalStateException,
                "the resume tick did not surface the destroyed engine",
            )
            val recorded = controller.lastPollFailure.value
            val atDeath = gets.count

            // The loop stopped on the dead engine: no further reads, and
            // the failure is the one recorded, once and sticky.
            repeat(IDLE_HEARTBEATS_TO_PROBE) {
                advanceTimeBy(POLL_PERIOD)
                runCurrent()
            }
            assertEquals(atDeath, gets.count, "the loop kept reading the engine after the dead-engine stop")
            assertSame(recorded, controller.lastPollFailure.value, "the failure channel changed after the stop")

            controller.close() // must not throw on the already-dead loop
        }

    /** close() from an IDLED state must still end the loop — idling is not a second lifetime. */
    @Test
    fun closeWhileBackgroundedStillEndsTheLoop() =
        runTest {
            val gets = CountingGets(engine)
            val holder = AppLifecycleHolder()
            val controller =
                PlayerController(gets, holder, StandardTestDispatcher(testScheduler), pollPeriodMillis = POLL_PERIOD)

            runCurrent()
            holder.update(AppLifecycleState.Background)
            advanceTimeBy(POLL_PERIOD)
            runCurrent() // one idle wake proves the idling engaged before close

            controller.close()
            val atClose = gets.count

            // Foreground AFTER close: a loop that survived close() from an
            // idle state would resume reading on its next wake — the idle
            // itself would otherwise mask the leak.
            holder.update(AppLifecycleState.Foreground)
            repeat(IDLE_HEARTBEATS_TO_PROBE) {
                advanceTimeBy(POLL_PERIOD)
                runCurrent()
            }
            assertEquals(atClose, gets.count, "the loop survived close() from an idle state")
        }

    /** Counts the `getState` calls handed to [delegate] — the FFI-boundary probe for every pin above. */
    private class CountingGets(
        private val delegate: FausteEngineInterface,
    ) : FausteEngineInterface by delegate {
        var count: Int = 0
            private set

        override fun getState(): EngineState {
            count += 1
            return delegate.getState()
        }
    }

    private companion object {
        /** The virtual poll period (small so the pins read clearly). */
        const val POLL_PERIOD: Long = 10

        /** Enough idle wakes that a mis-idling loop would have crossed the FFI several times over. */
        const val IDLE_HEARTBEATS_TO_PROBE: Int = 5
    }
}
