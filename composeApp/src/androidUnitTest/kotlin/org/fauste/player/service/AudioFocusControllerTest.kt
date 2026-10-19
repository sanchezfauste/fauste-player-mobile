package org.fauste.player.service

import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.PlayerController
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE FOCUS WIRING (Phase 5, Ruling 1): [AudioFocusController] over
 * the REAL state layer (a [PlayerController] poll loop over
 * [ScriptedEngine], all on one test scheduler) with a scripted
 * [FocusRequester] seam — the platform AudioManager is not
 * host-mockable for the focus stack, so the seam is the honest
 * boundary: what this lane pins is the controller's own contract
 * (acquire/abandon discipline, the action → command dispatch over the
 * SAME controller path the UI rides, the listener → scope hop, the
 * duck-restore bookkeeping, the teardown that survives the service
 * scope), while the platform's actual focus arbitration stays on the
 * manual checklist — `cmd audio` has no focus simulation on API 34
 * (the report's investigation) and only real competing audio on a
 * device exercises the listener for real.
 *
 * THE DRIVING SHAPE mirrors the starter suite: the scripted engine
 * moves the "engine", the controller's REAL poll pipeline (10 ms
 * period) carries it into engineState, and the focus controller's
 * collector — the same distinctUntilChanged discipline the starter
 * uses — turns it into policy inputs. The scripted requester records
 * request/abandon calls; the TEST fires the platform's answers
 * (request results, listener changes) through the seam's sink in any
 * order/thread it wants to prove.
 *
 * THE HOP PIN: the sink may fire from ANY thread (production: the
 * main looper; here also a bare background thread) — no policy or
 * engine work may run on the sink's caller; every command lands only
 * through the service scope, after the pump. THE RESIDUAL: the
 * main-ness of the production request thread is a
 * PlatformAudioFocusRequester property this lane cannot see (it
 * scripts the seam) — pinned by review + the on-emulator smoke.
 *
 * Robolectric for the one android.util.Log in the unknown-focus-drop
 * guard (the starter suite's own lane rationale); the rest of the lane
 * is plain coroutine-scheduler driving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioFocusControllerTest {
    /** The exact float the controller computes for 0.8 × the 0.2 factor — asserted bit-exact. */
    private companion object {
        val EXPECTED_DUCK: Float = 0.8f * 0.2f
    }

    private class World(
        val engine: ScriptedEngine = ScriptedEngine(engineState(status = "stopped", volume = 0.8f)),
    ) {
        val scheduler = TestCoroutineScheduler()
        val controller =
            PlayerController(engine, AppLifecycleHolder(), StandardTestDispatcher(scheduler), pollPeriodMillis = 10)
        val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val releaseScope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val requester = ScriptedFocusRequester()
        val focus =
            AudioFocusController(
                player = controller,
                scope = scope,
                requester = requester,
                releaseScope = releaseScope,
            )

        /** Moves the "engine" to [status] and pumps the poll + collectors. */
        fun engineMoves(status: String) {
            engine.state.value =
                engineState(
                    status = status,
                    position = 10.0,
                    track = track(path = "/music/a.flac"),
                    volume = engine.state.value.volume,
                )
            scheduler.advanceTimeBy(20)
            scheduler.runCurrent()
        }

        /** The request's outcome arrives, then the hop runs. */
        fun requestAnswers(result: Int) {
            requester.requestResultSink?.invoke(result)
            scheduler.runCurrent()
        }

        /** The listener fires, then the hop runs. */
        fun listenerDelivers(focusChange: Int) {
            requester.focusChangeSink?.invoke(focusChange)
            scheduler.runCurrent()
        }

        fun release() {
            focus.release()
            scheduler.runCurrent()
        }

        fun close() {
            controller.close()
            scope.cancel()
            releaseScope.cancel()
        }
    }

    /**
     * The scripted seam: records the request/abandon discipline; the
     * test drives the two streams' answers explicitly — the request
     * results and the listener changes stay SEPARATE (the platform's
     * GRANTED and GAIN ints collide at 1; the seam separates them
     * structurally, so the scripted drives must too).
     */
    private class ScriptedFocusRequester : FocusRequester {
        val requests = AtomicInteger(0)
        val abandons = AtomicInteger(0)

        override var requestResultSink: ((Int) -> Unit)? = null
        override var focusChangeSink: ((Int) -> Unit)? = null

        override fun requestFocus() {
            requests.incrementAndGet()
        }

        override fun abandonFocus() {
            abandons.incrementAndGet()
        }
    }

    @Test
    fun theFirstPlayingObservationAcquiresFocusAndAGrantCommandsNothing() {
        val w = World()
        try {
            w.engineMoves("playing")
            assertEquals(1, w.requester.requests.get(), "the first Play observation must request focus")
            assertEquals(0, w.requester.abandons.get())

            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            assertEquals(0, w.engine.pauseCalls.get(), "a grant over a playing engine commands nothing")
            assertEquals(0, w.engine.playCalls.get())
            assertEquals(1, w.requester.requests.get(), "holding focus re-requests nothing")

            // The grant's listener echo (AUDIOFOCUS_GAIN on request
            // success) must be inert.
            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, w.engine.pauseCalls.get())
        } finally {
            w.close()
        }
    }

    @Test
    fun aDeniedRequestPausesThePlayingEngineAgain() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertEquals(
                1,
                w.engine.pauseCalls.get(),
                "AUDIOFOCUS_REQUEST_FAILED over a playing engine: the honest pause-again",
            )
            assertEquals(0, w.engine.playCalls.get())
        } finally {
            w.close()
        }
    }

    @Test
    fun aFullLossPausesAndNeverAutoResumesButTheNextPlayReRequests() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1, w.engine.pauseCalls.get(), "a full loss pauses the engine")
            w.engineMoves("paused") // the scripted engine confirms our pause

            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(0, w.engine.playCalls.get(), "no auto-resume after a full loss")

            // The user hits play: the engine plays again, the controller
            // re-requests — the honest request-and-see.
            w.engineMoves("playing")
            assertEquals(2, w.requester.requests.get(), "a user resume after a full loss re-requests focus")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            assertEquals(
                0,
                w.engine.playCalls.get(),
                "the granted re-request commands nothing — the user's (scripted) play already stands",
            )
        } finally {
            w.close()
        }
    }

    @Test
    fun aTransientLossPausesAndTheRegainResumes() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertEquals(1, w.engine.pauseCalls.get(), "the phone-call mirror pauses")
            w.engineMoves("paused") // the scripted engine confirms our pause

            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(1, w.engine.playCalls.get(), "the flagged regain resumes")
        } finally {
            w.close()
        }
    }

    @Test
    fun aCanDuckMultipliesTheModelVolumeAndTheRegainRestoresIt() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(listOf(EXPECTED_DUCK), w.engine.setVolumes.toList(), "duck = the model's 0.8 × the 0.2 factor")

            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(
                listOf(EXPECTED_DUCK, 0.8f),
                w.engine.setVolumes.toList(),
                "the regain restores the STORED duck-time volume, not a constant",
            )
        } finally {
            w.close()
        }
    }

    @Test
    fun aSecondCanDuckDoesNotCompoundTheMultiply() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(
                listOf(EXPECTED_DUCK),
                w.engine.setVolumes.toList(),
                "a duck of a duck must not compound (0.8 × 0.2 × 0.2)",
            )
        } finally {
            w.close()
        }
    }

    @Test
    fun aGainFollowedByACanDuckWithinOnePollPeriodDucksFromTheJustRestoredValue() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

            // The first prompt ducks the 0.8 model volume...
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(listOf(EXPECTED_DUCK), w.engine.setVolumes.toList(), "the first duck = 0.8 × 0.2")
            // ...and the poll OBSERVES the ducked value before the prompt ends.
            w.scheduler.advanceTimeBy(20)
            w.scheduler.runCurrent()
            assertEquals(EXPECTED_DUCK, w.controller.engineState.value.volume, "the poll has observed the ducked value")

            // The prompt ends (the restore is dispatched ASYNCHRONOUSLY)
            // and a SECOND prompt lands within the same poll period —
            // back-to-back navigation prompts — before any poll can
            // carry the just-restored 0.8 back into engineState. The
            // second duck must store the ORIGINAL pre-duck value (0.8):
            // reading the stale polled 0.16 would send 0.032 and
            // "restore" 0.16 at the next regain — the user's 0.8
            // permanently cut 5×, compounding on every repeat.
            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(listOf(EXPECTED_DUCK, 0.8f), w.engine.setVolumes.toList(), "the regain restores 0.8")
            assertEquals(
                EXPECTED_DUCK,
                w.controller.engineState.value.volume,
                "the restore is not yet poll-visible — the exact stale-read window",
            )
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(
                listOf(EXPECTED_DUCK, 0.8f, EXPECTED_DUCK),
                w.engine.setVolumes.toList(),
                "the second duck multiplies the just-restored 0.8, not the stale polled 0.16",
            )

            // ...and the second regain restores the original 0.8 again.
            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(
                listOf(EXPECTED_DUCK, 0.8f, EXPECTED_DUCK, 0.8f),
                w.engine.setVolumes.toList(),
                "the second regain restores the original 0.8, not the compounded 0.16",
            )
        } finally {
            w.close()
        }
    }

    @Test
    fun theEngineStopAbandonsAndRestoresALiveDuckFirst() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

            w.engineMoves("stopped")
            assertEquals(1, w.requester.abandons.get(), "the engine stop abandons focus")
            assertEquals(
                listOf(EXPECTED_DUCK, 0.8f),
                w.engine.setVolumes.toList(),
                "the stop restores the ducked volume — the model's volume survives Stop, an unrestored duck would leak",
            )
        } finally {
            w.close()
        }
    }

    @Test
    fun aUserPauseKeepsTheRequestAndOnlyTheStopAbandons() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.engineMoves("paused")
            assertEquals(0, w.requester.abandons.get(), "Ruling 1: abandon on Stop/teardown, not on pause")

            w.engineMoves("stopped")
            assertEquals(1, w.requester.abandons.get())
        } finally {
            w.close()
        }
    }

    @Test
    fun theReleaseAbandonsIdempotentlyAndItsDuckRestoreSurvivesTheServiceScopeCancel() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

            // The service's onDestroy order: release FIRST, then the
            // scope cancel. The restore must also survive the reverse
            // race — the whole reason the controller owns a release
            // scope.
            w.scope.cancel()
            w.release()
            assertEquals(1, w.requester.abandons.get(), "the teardown abandons")
            assertEquals(
                listOf(EXPECTED_DUCK, 0.8f),
                w.engine.setVolumes.toList(),
                "the duck restore rides the release scope, not the cancelled service scope",
            )

            w.release()
            assertEquals(2, w.requester.abandons.get(), "release is idempotent (a belt-and-braces second call)")
            assertEquals(
                listOf(EXPECTED_DUCK, 0.8f),
                w.engine.setVolumes.toList(),
                "the second release re-restores nothing",
            )

            // After release, no event path may react anymore.
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertEquals(0, w.engine.pauseCalls.get(), "released: listener changes are dead")
        } finally {
            w.close()
        }
    }

    @Test
    fun listenerChangesHopTheScopeAndNeverRunOnTheCallerThread() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

            // The listener thread in production is the main looper;
            // here a BARE thread proves the hop structurally: the
            // command must not run on the sink's caller.
            val done = CountDownLatch(1)
            Thread {
                w.requester.focusChangeSink?.invoke(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                done.countDown()
            }.start()
            done.await()
            assertEquals(0, w.engine.pauseCalls.get(), "the sink's caller thread must not touch the engine")

            w.scheduler.runCurrent()
            assertEquals(1, w.engine.pauseCalls.get(), "the change lands through the service scope")
        } finally {
            w.close()
        }
    }

    @Test
    fun aDeniedReRequestDuringATransientDefersTheUsersPlayToTheRegain() {
        val w = World()
        try {
            w.engineMoves("playing")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            w.listenerDelivers(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertEquals(1, w.engine.pauseCalls.get())
            w.engineMoves("paused") // the scripted engine confirms our pause

            // The user hits play during the call: the engine plays,
            // the controller re-requests, the system denies.
            w.engineMoves("playing")
            assertEquals(2, w.requester.requests.get(), "a user resume while displaced re-requests")
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertEquals(2, w.engine.pauseCalls.get(), "the denial pauses again")

            // The call ends: the regain honors the user's deferred play.
            w.listenerDelivers(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(1, w.engine.playCalls.get(), "the flagged regain resumes the deferred play")
        } finally {
            w.close()
        }
    }

    @Test
    fun anUnmappedFocusChangeIsDroppedWithoutDisturbingThePolicy() {
        val w = World()
        try {
            w.engineMoves("playing")
            assertEquals(1, w.requester.requests.get())
            w.listenerDelivers(999) // no platform constant matches
            assertEquals(0, w.engine.pauseCalls.get())
            assertEquals(0, w.engine.playCalls.get())
            assertEquals(1, w.requester.requests.get())

            // The request in flight still resolves normally.
            w.requestAnswers(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            assertEquals(0, w.engine.pauseCalls.get())
        } finally {
            w.close()
        }
    }
}
