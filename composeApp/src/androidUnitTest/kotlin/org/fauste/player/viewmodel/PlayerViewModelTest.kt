package org.fauste.player.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.PlaybackState
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.FausteException
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PlayerViewModel contract suite: mapping (engineState → [PlayerUiState]
 * via [PlaybackState.fromEngineState]), intent dispatch (play vs pause per
 * the reported status), THE SEEK THROTTLE on virtual time, and error
 * surfacing. Everything runs against the REAL state layer — PlayerController,
 * QueueManager, PlaylistRepository — over the scripted [ScriptedEngine], so
 * the ViewModel's own behaviour is what's under test, through the genuine
 * poll loop and command path.
 *
 * Virtual-time mechanics (mirroring the shared module's controller suite):
 * every dispatcher — the ViewModel's command dispatcher, the controller's,
 * the queue manager's, and Dispatchers.Main via [setMain] — shares ONE
 * [TestCoroutineScheduler], and the tests advance it explicitly with
 * `advanceTimeBy`/`runCurrent`. NEVER `advanceUntilIdle`: the controller's
 * poll loop re-arms its 100 ms delay forever, so "idle" never arrives.
 * There is also deliberately NO `runTest` here: after its body it drains
 * the shared scheduler until idle, which would hang the same way — the
 * tests are fully synchronous (intents are plain calls, the scheduler is
 * driven by hand), so no test-coroutine harness is needed at all. The
 * controller and queue close in [tearDown] (after the assertions), which
 * `runTest` would not have survived.
 *
 * viewModelScope needs a Main dispatcher even though nothing it launches
 * runs there (everything hops to the command dispatcher), hence the
 * [setMain] pair.
 */
class PlayerViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val commandDispatcher = StandardTestDispatcher(scheduler)

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var queue: QueueManager
    private lateinit var repository: PlaylistRepository
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        controller.close()
        queue.close()
        Dispatchers.resetMain()
    }

    /**
     * Builds the real pipeline over the scripted double. Nothing is primed:
     * the controller's first poll tick, the queue's boot refresh and the
     * ViewModel's collector are all queued on the scheduler, so each test
     * decides when the pipeline starts (the first `runCurrent()`).
     */
    private fun makeViewModel(initial: EngineState): PlayerViewModel {
        engine = ScriptedEngine(initial)
        controller = PlayerController(engine, AppLifecycleHolder(), commandDispatcher)
        queue = QueueManager(engine, commandDispatcher)
        repository = PlaylistRepository(queue, controller)
        viewModel =
            PlayerViewModel(
                controller = lazyOf(controller),
                queue = lazyOf(queue),
                repository = lazyOf(repository),
                commandDispatcher = commandDispatcher,
            )
        return viewModel
    }

    // ------------------------------------------------------------------
    // Mapping: engineState → PlayerUiState via PlaybackState.fromEngineState
    // ------------------------------------------------------------------

    @Test
    fun uiStateCarriesThePlanMandatedPlaybackStateProjection() {
        val state =
            engineState(
                status = "playing",
                position = 83.0,
                duration = 301.0,
                track = track(title = "One", artist = "Fauste Trio", duration = 301.0),
                volume = 0.6f,
            )
        makeViewModel(state)
        scheduler.runCurrent()

        // THE mapping pin: the UiState's playback is exactly the
        // plan-mandated projection — consumed, never re-implemented.
        assertEquals(PlaybackState.fromEngineState(state), viewModel.uiState.value.playback)
        assertTrue(viewModel.uiState.value.isPlaying)
        assertEquals("One", viewModel.uiState.value.trackTitle)
        assertEquals("Fauste Trio", viewModel.uiState.value.trackArtist)
        assertEquals(83.0, viewModel.uiState.value.position)
        assertEquals(301.0, viewModel.uiState.value.playback.duration)
        assertEquals(0.6f, viewModel.uiState.value.volume)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun noTrackOnAirRendersTheHonestLimitsPlaceholders() {
        makeViewModel(engineState(status = "stopped"))
        scheduler.runCurrent()

        assertEquals("—", viewModel.uiState.value.trackTitle)
        assertNull(viewModel.uiState.value.trackArtist)
        assertFalse(viewModel.uiState.value.isPlaying)
    }

    // ------------------------------------------------------------------
    // Intent dispatch
    // ------------------------------------------------------------------

    @Test
    fun playPauseIssuesPlayWhenTheEngineIsNotPlaying() {
        makeViewModel(engineState(status = "stopped"))
        scheduler.runCurrent()

        viewModel.onPlayPause()
        scheduler.runCurrent()

        assertEquals(1, engine.playCalls.get())
        assertEquals(0, engine.pauseCalls.get())
    }

    @Test
    fun playPauseIssuesPauseWhenTheEngineIsPlaying() {
        makeViewModel(engineState(status = "playing"))
        scheduler.runCurrent()

        viewModel.onPlayPause()
        scheduler.runCurrent()

        assertEquals(1, engine.pauseCalls.get())
        assertEquals(0, engine.playCalls.get())
    }

    @Test
    fun previousReachesTheEngineOnce() {
        makeViewModel(engineState(status = "playing"))
        scheduler.runCurrent()

        viewModel.onPrevious()
        scheduler.runCurrent()

        assertEquals(1, engine.previousCalls.get())
    }

    @Test
    fun jumpDelegatesToTheQueueManagersArmOnlyJump() {
        makeViewModel(engineState(status = "stopped"))
        scheduler.runCurrent()

        viewModel.onJump(7u)
        scheduler.runCurrent()

        // ARM-ONLY: set_next arms the entry as the explicit next; a
        // stopped player stays stopped (no play issued).
        assertEquals<List<ULong>>(listOf(7u), engine.setNextCalls)
        assertEquals(0, engine.playCalls.get())
    }

    // ------------------------------------------------------------------
    // THE SEEK THROTTLE (the Task-1 review carry)
    // ------------------------------------------------------------------

    @Test
    fun aContinuousSeekDragFloodsNothingAndCommitsExactlyOnceAtDragStop() {
        makeViewModel(engineState(status = "playing", duration = 300.0))
        scheduler.runCurrent()

        // A 5-second drag: 50 continuous ticks 100 ms apart. The commit
        // debounce is 200 ms, so every tick re-arms it and none fires.
        repeat(50) { tick ->
            viewModel.onSeek(10.0 + tick * 2.0)
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
        }

        // THE THROTTLE: 50 onSeek ticks over 5 virtual seconds, and the
        // engine got ZERO seeks; the UI showed the finger the whole way
        // (the pending position drives the bar).
        assertEquals(0, engine.seeks.size)
        assertEquals(108.0, viewModel.uiState.value.pendingSeekPosition)
        assertEquals(108.0, viewModel.uiState.value.position)

        // The drag stopped: one trailing commit with the last position.
        scheduler.advanceTimeBy(PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        assertEquals(listOf(108.0), engine.seeks)

        // The double applied the seek; a poll tick settles the pending so
        // the bar returns to engine truth.
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
        assertNull(viewModel.uiState.value.pendingSeekPosition)
        assertEquals(108.0, viewModel.uiState.value.position)
    }

    @Test
    fun separateSeekGesturesCommitSeparately() {
        makeViewModel(engineState(status = "playing", duration = 300.0))
        scheduler.runCurrent()

        viewModel.onSeek(50.0)
        scheduler.advanceTimeBy(PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        viewModel.onSeek(120.0)
        scheduler.advanceTimeBy(PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        // A poll tick settles the second gesture's pending echo.
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()

        assertEquals(listOf(50.0, 120.0), engine.seeks)
        assertNull(viewModel.uiState.value.pendingSeekPosition)
    }

    @Test
    fun aVolumeDragEchoesInstantlyAndCommitsOnce() {
        makeViewModel(engineState(status = "playing"))
        scheduler.runCurrent()

        viewModel.onVolume(0.4f)
        // Instant local echo (runCurrent lets the collector publish it);
        // the engine is untouched before the debounce window ends.
        scheduler.runCurrent()
        assertEquals(0.4f, viewModel.uiState.value.volume)
        assertEquals(0, engine.setVolumes.size)

        scheduler.advanceTimeBy(PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        assertEquals(listOf(0.4f), engine.setVolumes)

        // The double applied the volume; a poll tick settles the echo.
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
        assertNull(viewModel.uiState.value.pendingVolume)
        assertEquals(0.4f, viewModel.uiState.value.volume)
    }

    // ------------------------------------------------------------------
    // Error surfacing (never a crash)
    // ------------------------------------------------------------------

    @Test
    fun aBusyPlaySurfacesTheErrorAndTheNextIntentClearsIt() {
        makeViewModel(engineState(status = "stopped"))
        scheduler.runCurrent()
        engine.playError = FausteException.Busy()

        viewModel.onPlayPause()
        scheduler.runCurrent()

        assertEquals(PlayerViewModel.BUSY_MESSAGE, viewModel.uiState.value.error)

        // No crash: the next intent runs, succeeds and clears the error.
        engine.playError = null
        viewModel.onPlayPause()
        scheduler.runCurrent()

        assertNull(viewModel.uiState.value.error)
        assertEquals(2, engine.playCalls.get())
    }

    @Test
    fun aBusySeekCommitSurfacesTheErrorAndClearsThePending() {
        makeViewModel(engineState(status = "playing", position = 10.0, duration = 300.0))
        scheduler.runCurrent()
        engine.seekError = FausteException.Busy()

        viewModel.onSeek(42.0)
        scheduler.advanceTimeBy(PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()

        // The commit DID reach the engine layer (and was dropped there);
        // the dropped target must stop driving the UI.
        assertEquals(listOf(42.0), engine.seeks)
        assertEquals(PlayerViewModel.BUSY_MESSAGE, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.pendingSeekPosition)
        assertEquals(10.0, viewModel.uiState.value.position)
    }

    @Test
    fun aFailedCommandSurfacesTheEnginesMessage() {
        makeViewModel(engineState(status = "stopped"))
        scheduler.runCurrent()
        engine.playError = FausteException.Core("disk on fire")

        viewModel.onPlayPause()
        scheduler.runCurrent()

        assertEquals("disk on fire", viewModel.uiState.value.error)
    }
}
