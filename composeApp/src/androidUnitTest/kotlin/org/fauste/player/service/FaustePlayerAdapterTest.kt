package org.fauste.player.service

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.playlistEntry
import org.fauste.player.player.track
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.PlaylistEntry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * THE BRIDGE'S MEDIA3 HALF (Task 6): [FaustePlayerAdapter] over
 * SimpleBasePlayer, driven through the REAL state layer (a
 * [PlayerController] poll loop + [QueueManager] over [ScriptedEngine],
 * all on one test scheduler) and asserted through media3's OWN Player
 * getters, so the lane pins the glue translation, not the rules (those
 * live in the [SessionPlaybackSnapshot] suite) and not the real
 * notification rendering (an emulator's job — the report's manual
 * checklist).
 *
 * Robolectric supplies the main looper the SimpleBasePlayer contract
 * pins every Player method to; the test thread IS that main thread, so
 * adapter calls are legal exactly where production (media3's session)
 * makes them.
 *
 * DRIVING DISCIPLINE (a finding worth recording): the suite drives a
 * [TestCoroutineScheduler] EXPLICITLY — `runCurrent`/`advanceTimeBy`,
 * the shared module's controller-idling suite's own pattern — and does
 * NOT use `runTest`. `runTest`'s completion advance spins forever under
 * this setup: media3's listener machinery on Robolectric's paused
 * looper plus a live poll loop on the shared scheduler produce an
 * immediate-task stream `runTest`'s own virtual-clock timeout task can
 * never catch up with (observed: 500 s+ of 100% CPU with no guard
 * firing). Driving the scheduler directly makes the test's end the
 * method's end: [TestWorld.release] closes every loop, and anything
 * still scheduled dies with the test.
 *
 * THE MAIN-THREAD PUBLISH CONTRACT (the Task-6 review's Critical
 * finding): media3 1.11.1's `SimpleBasePlayer.invalidateState()` throws
 * `IllegalStateException` off the application looper
 * (`verifyApplicationThreadAndInitState`), and the production collector
 * ran it from the service's IO scope — a crash within milliseconds of
 * the service's onCreate on a real device. The fix hops the publish
 * through an injectable dispatcher whose DEFAULT is
 * `Dispatchers.Main.immediate` (forced main-ness in production: the
 * service's construction site passes nothing), and this lane swaps in
 * [HopRecordingDispatcher] to pin the hop STRUCTURALLY: every
 * collected snapshot must dispatch through the injected dispatcher
 * (hops counted) before the state move is visible.
 *
 * THE RESIDUAL MASK, disclosed honestly: under Robolectric the test
 * thread IS the main looper thread, so this lane CANNOT negatively
 * prove production main-ness — a deleted hop would still pass every
 * OTHER test here (any inline publish lands on the "main" thread by
 * test-lane identity). That is exactly why the structural pin exists:
 * a collector that publishes without dispatching through the injected
 * dispatcher leaves [HopRecordingDispatcher.hops] at zero and THIS
 * suite fails, whatever thread it landed on. What the pin cannot see
 * (a swapped-in dispatcher that is not main in production) is guarded
 * by the default-only construction site, reviewed by eye, and by the
 * on-emulator smoke (the report's manual checklist).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FaustePlayerAdapterTest {
    @Test
    fun aFreshEngineIsIdleWithTheSessionCommands() {
        val world = adapterWorld()
        world.adapter.apply {
            world.pump()

            assertEquals(Player.STATE_IDLE, playbackState, "a fresh engine must project as IDLE")
            assertFalse(playWhenReady)
            assertFalse(isPlaying)
            assertEquals(0, mediaItemCount, "the fresh queue is empty")
            assertTrue(
                availableCommands.contains(Player.COMMAND_PLAY_PAUSE),
                "the notification needs the play/pause command",
            )
            assertTrue(
                availableCommands.contains(Player.COMMAND_STOP),
                "the notification's Stop button rides the stop command",
            )
            assertTrue(
                availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                "the lock-screen seek bar needs in-item seeking",
            )
            assertTrue(
                availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
                "the deck's Previous is the notification's previous",
            )
            assertFalse(
                availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
                "the engine has no next command — the notification must not promise one",
            )
            assertTrue(
                availableCommands.contains(Player.COMMAND_GET_METADATA),
                "the provider gates its title/artist rendering on the metadata command",
            )
        }
        world.release()
    }

    @Test
    fun aPlayingEngineIsReadyWithOnAirMetadataAndPosition() {
        val world =
            adapterWorld(
                queue =
                    listOf(
                        playlistEntry(id = 1u, title = "Row One", path = "/music/one.flac", duration = 120.0),
                        playlistEntry(id = 2u, title = "Row Two", path = "/music/two.flac", duration = 180.0),
                    ),
            )
        world.engine.state.value =
            engineState(
                status = "playing",
                position = 61.5,
                duration = 180.0,
                track = track(title = "On Air", artist = "Someone", path = "/music/two.flac"),
            )
        world.adapter.apply {
            world.pump()

            assertEquals(Player.STATE_READY, playbackState)
            assertTrue(playWhenReady)
            assertTrue(isPlaying)
            assertEquals(1, currentMediaItemIndex, "the on-air row is the timeline's current item")
            assertEquals("On Air", mediaMetadata.title.toString(), "the engine's own title, not the row's")
            assertEquals("Someone", mediaMetadata.artist.toString())
            assertEquals(2, mediaItemCount)
            assertEquals(180_000L, duration, "a known duration must ride the player surface in ms")
            assertTrue(
                currentPosition in 61_500..61_600,
                "position rides the state in ms (the window allows media3's own between-poll extrapolation)",
            )
        }
        world.release()
    }

    @Test
    fun anUnknownDurationReadsAsTimeUnset() {
        val world = adapterWorld(queue = listOf(playlistEntry(id = 1u, path = "/music/one.flac")))
        world.adapter.apply {
            world.pump()

            assertEquals(C.TIME_UNSET, duration, "a 0.0 duration is unknown, never a seekable zero")
        }
        world.release()
    }

    @Test
    fun playPauseAndStopRouteThroughTheControllerToTheEngine() {
        val world = adapterWorld(queue = listOf(playlistEntry(id = 1u, path = "/music/one.flac")))
        world.adapter.apply {
            world.pump()

            play()
            world.pump()
            assertEquals(1, world.engine.playCalls.get(), "Player.play() must queue the engine's play")

            pause()
            world.pump()
            assertEquals(1, world.engine.pauseCalls.get(), "Player.pause() must queue the engine's pause")

            stop()
            world.pump()
            assertEquals(1, world.engine.stopCalls.get(), "Player.stop() must queue the engine's stop")
        }
        world.release()
    }

    @Test
    fun seekRoutesThroughTheControllerAsSeconds() {
        val world =
            adapterWorld(
                initial =
                    engineState(
                        status = "playing",
                        duration = 200.0,
                        track = track(path = "/music/one.flac"),
                    ),
                queue = listOf(playlistEntry(id = 1u, path = "/music/one.flac")),
            )
        world.adapter.apply {
            world.pump()

            seekTo(30_000)
            world.pump()
            assertEquals(
                listOf(30.0),
                world.engine.seeks.toList(),
                "the session's ms seek must arrive as the FFI's seconds",
            )
        }
        world.release()
    }

    @Test
    fun seekToPreviousItemRoutesThroughTheQueuesDeckCommand() {
        val world =
            adapterWorld(
                initial = engineState(status = "playing", track = track(path = "/music/two.flac")),
                queue =
                    listOf(
                        playlistEntry(id = 1u, path = "/music/one.flac"),
                        playlistEntry(id = 2u, path = "/music/two.flac"),
                    ),
            )
        world.adapter.apply {
            world.pump()
            assertEquals(1, currentMediaItemIndex)

            seekToPreviousMediaItem()
            world.pump()
            assertEquals(
                1,
                world.engine.previousCalls.get(),
                "the notification's previous is the deck's crossfade-back, not a timeline seek",
            )
        }
        world.release()
    }

    @Test
    fun setVolumeRoutesToTheEngine() {
        val world = adapterWorld(queue = listOf(playlistEntry(id = 1u, path = "/music/one.flac")))
        world.adapter.apply {
            world.pump()

            setVolume(0.4f)
            world.pump()
            assertEquals(listOf(0.4f), world.engine.setVolumes.toList())
        }
        world.release()
    }

    @Test
    fun theCollectorStopsWithItsScopeAndNeverLeaks() {
        val world = adapterWorld()
        world.pump()

        world.scope.cancel()
        world.engine.state.value = engineState(status = "playing", track = track(path = "/music/x.flac"))
        world.pump()

        // The collector is dead: the adapter's published snapshot must
        // not have moved — closing the scope ends the bridge.
        assertFalse(world.adapter.playWhenReady, "a cancelled scope must end the snapshot pipeline")
        world.release()
    }

    /**
     * THE MAIN-THREAD PUBLISH HOP, pinned structurally (the class KDoc's
     * Critical finding): every collected snapshot must dispatch through
     * the injected publish dispatcher — the production default is
     * `Dispatchers.Main.immediate`, the only thread where media3's
     * `invalidateState` is legal. A collector that publishes inline
     * leaves the hop counter at zero; one that hops but never lands
     * fails the state assertion underneath (the pipeline's end-to-end
     * pin).
     */
    @Test
    fun everyPublishedSnapshotHopsThroughTheInjectableMainDispatcher() {
        val world = adapterWorld(queue = listOf(playlistEntry(id = 1u, path = "/music/one.flac")))
        world.pump()
        val hopsAfterBoot = world.publish.hops.get()
        assertTrue(hopsAfterBoot >= 1, "the collector never dispatched through the publish (main) dispatcher")

        // A fresh state transition must ride the same hop: more
        // snapshots, more dispatches — and the moved state is visible
        // through media3's own getters, proving the hop is the live
        // pipeline, not a boot-time one-off.
        world.engine.state.value =
            engineState(
                status = "playing",
                duration = 60.0,
                track = track(title = "Hopped", path = "/music/one.flac"),
            )
        world.pump()
        assertTrue(world.publish.hops.get() > hopsAfterBoot, "a new snapshot did not hop to the publish dispatcher")
        assertEquals(Player.STATE_READY, world.adapter.playbackState, "the hopped publish never landed")
        assertTrue(world.adapter.playWhenReady)
        world.release()
    }

    /** The whole world one test needs: the real state layer over the double, plus the adapter under test. */
    private class TestWorld(
        val engine: ScriptedEngine,
        val controller: PlayerController,
        val queue: QueueManager,
        val scope: CoroutineScope,
        val adapter: FaustePlayerAdapter,
        val publish: HopRecordingDispatcher,
        private val scheduler: TestCoroutineScheduler,
    ) {
        /**
         * Pumps the world: one scheduler pass (pending launches: adapter
         * commands, queue confirms), then enough poll ticks for the
         * controller to read the scripted engine back, then one more pass
         * for the collector's snapshot publish. NEVER advanceUntilIdle —
         * the controller's poll loop re-arms forever (the ViewModel
         * suite's documented rule). The looper drain keeps media3's
         * posted listener events inside THIS test instead of leaking
         * into Robolectric's inter-test cleanup.
         */
        fun pump() {
            scheduler.runCurrent()
            scheduler.advanceTimeBy(20) // two poll periods at the 10 ms test rate
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }

        /**
         * Ends the world the way every state-layer suite must: the poll
         * loops are cancelled before the method returns, or a lingering
         * re-arm would outlive the test.
         */
        fun release() {
            controller.close()
            queue.close()
            scope.cancel()
        }
    }

    private fun adapterWorld(
        scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
        initial: EngineState = engineState(status = "stopped"),
        queue: List<PlaylistEntry> = emptyList(),
    ): TestWorld {
        val engine = ScriptedEngine(initial)
        engine.playlist.value = queue
        val dispatcher = StandardTestDispatcher(scheduler)
        val controller = PlayerController(engine, AppLifecycleHolder(), dispatcher, pollPeriodMillis = 10)
        val queueManager = QueueManager(engine, dispatcher)
        val scope = CoroutineScope(Job() + dispatcher)
        val publish = HopRecordingDispatcher(dispatcher)
        val adapter = FaustePlayerAdapter(controller, queueManager, scope, publish)
        return TestWorld(engine, controller, queueManager, scope, adapter, publish, scheduler)
    }

    /**
     * The structural probe of the main-thread publish contract (the class
     * KDoc): counts every dispatch handed to it and passes each through
     * to the delegate. The production default this stand-in replaces is
     * `Dispatchers.Main.immediate`; a fresh wrapper instance is used per
     * world so `withContext` always records a hop (no "already on this
     * dispatcher" shortcut).
     */
    private class HopRecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val hops = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            hops.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }
}
