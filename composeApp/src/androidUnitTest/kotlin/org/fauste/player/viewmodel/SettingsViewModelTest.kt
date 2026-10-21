package org.fauste.player.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.PlayerController
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.remote.RemoteServer
import org.fauste.player.repository.SettingsRepository
import org.fauste.player.rust.BackendInfo
import org.fauste.player.rust.FausteException
import org.fauste.player.rust.RemoteInfo
import org.fauste.player.ui.strings.StringKeys
import org.fauste.player.ui.strings.UiText
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SettingsViewModel contract suite: mapping (the pipeline's
 * state-down: backends list, the engine's in-use backend as the selected
 * marker, remote status, volume), THE VOLUME THROTTLE on virtual time
 * (the shape proven in PlayerViewModelTest — N continuous ticks ⇒ zero
 * engine calls mid-drag ⇒ exactly one debounced commit), intent
 * dispatch (backend select, remote refresh), and error surfacing.
 * Everything runs against the REAL state layer — PlayerController,
 * RemoteServer, SettingsRepository — over the scripted [ScriptedEngine],
 * so the ViewModel's own behaviour is what's under test, through the
 * genuine poll pipeline and command path.
 *
 * Virtual-time mechanics (the established suite's): every dispatcher —
 * the ViewModel's command dispatcher, the controller's, the remote
 * wrapper's, and Dispatchers.Main via [setMain] — shares ONE
 * [TestCoroutineScheduler], and the tests advance it explicitly with
 * `advanceTimeBy`/`runCurrent`. NEVER `advanceUntilIdle`: the
 * controller's 100 ms loop AND the remote wrapper's 5 s loop re-arm
 * their delays forever, so "idle" never arrives. No `runTest` for the
 * same reason; the state layer closes in [tearDown] (after the
 * assertions), which `runTest`'s end-of-body drain would not have
 * survived.
 *
 * viewModelScope needs a Main dispatcher even though nothing it
 * launches runs there (everything hops to the command dispatcher),
 * hence the [setMain] pair.
 */
class SettingsViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val commandDispatcher = StandardTestDispatcher(scheduler)

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var remote: RemoteServer
    private lateinit var repository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        // The pure constant-lock test never builds the pipeline, so the
        // doubles may be uninitialized; only close what was constructed.
        if (::controller.isInitialized) controller.close()
        if (::remote.isInitialized) remote.close()
        Dispatchers.resetMain()
    }

    /**
     * Builds the real pipeline over the scripted double: the engine's
     * initial snapshot, its probed backends and its remote record are
     * scripted BEFORE anything boots (the controller's first poll tick,
     * the remote wrapper's first read, the VM's one-shot loads and the
     * collector are all queued on the scheduler — the first
     * `runCurrent()` starts them together).
     */
    private fun makeViewModel(
        initialBackend: String = "null",
        initialVolume: Float = 1.0f,
        backends: List<BackendInfo> = emptyList(),
        remoteInfo: RemoteInfo = RemoteInfo(enabled = false, address = ""),
    ): SettingsViewModel {
        engine = ScriptedEngine(engineState(status = "stopped", volume = initialVolume, backend = initialBackend))
        engine.backends.value = backends
        engine.remote.value = remoteInfo
        controller = PlayerController(engine, AppLifecycleHolder(), commandDispatcher)
        remote = RemoteServer(engine, commandDispatcher)
        repository = SettingsRepository(engine, controller, remote, commandDispatcher)
        viewModel =
            SettingsViewModel(
                repository = lazyOf(repository),
                controller = lazyOf(controller),
                commandDispatcher = commandDispatcher,
            )
        return viewModel
    }

    // ------------------------------------------------------------------
    // Mapping: the pipeline's state-down
    // ------------------------------------------------------------------

    @Test
    fun uiStateCarriesTheProbedBackendsTheSelectedMarkerAndTheRemoteRecord() {
        makeViewModel(
            initialBackend = "aaudio",
            initialVolume = 0.6f,
            backends =
                listOf(
                    BackendInfo(id = "null", name = "Null output", available = true),
                    BackendInfo(id = "aaudio", name = "AAudio", available = false),
                ),
            remoteInfo = RemoteInfo(enabled = true, address = "127.0.0.1:52341"),
        )
        scheduler.runCurrent()

        // The one-shot probe landed, the selected marker is the ENGINE's
        // in-use report (not the list's first row), volume mirrors the
        // snapshot, and the remote record flows through the repository.
        assertEquals(2, viewModel.uiState.value.backends.size)
        assertEquals("aaudio", viewModel.uiState.value.selectedBackendId)
        assertEquals(0.6f, viewModel.uiState.value.volume)
        assertNull(viewModel.uiState.value.pendingVolume)
        assertEquals(RemoteInfo(enabled = true, address = "127.0.0.1:52341"), viewModel.uiState.value.remote)
        assertNull(viewModel.uiState.value.error)
        assertTrue(engine.backendProbes.get() >= 1, "the backends load never probed the engine")
    }

    @Test
    fun anEmptyProbeAnswerRendersAsTheHonestEmptyList() {
        makeViewModel(backends = emptyList())
        scheduler.runCurrent()

        assertEquals(0, viewModel.uiState.value.backends.size)
        assertNull(viewModel.uiState.value.error, "an empty probe is an answer, not a failure")
    }

    // ------------------------------------------------------------------
    // Intent dispatch
    // ------------------------------------------------------------------

    @Test
    fun selectBackendDelegatesThroughTheRepositoryAndTheMarkerFollowsTheEngine() {
        makeViewModel(
            initialBackend = "null",
            backends = listOf(BackendInfo(id = "null", name = "Null output", available = true)),
        )
        scheduler.runCurrent()

        viewModel.onSelectBackend("aaudio")
        scheduler.runCurrent()

        // The switch reached the engine layer; the double applies it, and
        // the marker follows the ENGINE's report on the next poll tick —
        // never the call's return.
        assertEquals(listOf("aaudio"), engine.setBackendCalls)
        assertEquals("null", viewModel.uiState.value.selectedBackendId)
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
        assertEquals("aaudio", viewModel.uiState.value.selectedBackendId)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun refreshRemoteReReadsTheRecordThroughTheRepository() {
        makeViewModel()
        scheduler.runCurrent()
        val probesBefore = engine.remoteStatusCalls.get()

        viewModel.onRefreshRemote()
        scheduler.runCurrent()

        assertTrue(engine.remoteStatusCalls.get() > probesBefore, "the refresh never read the engine")
        assertNull(viewModel.uiState.value.error)
    }

    // ------------------------------------------------------------------
    // THE VOLUME THROTTLE (the shape proven in PlayerViewModelTest)
    // ------------------------------------------------------------------

    @Test
    fun volumeThrottleConstantsStayLockedToPlayerViewModels() {
        // SettingsViewModel duplicates PlayerViewModel's throttle shape
        // with its own per-companion constants; the duplication's stated
        // contract is that the mirrored values stay in lockstep — this is
        // the lock. A drift in either companion fails here, loudly.
        assertEquals(
            PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS,
            SettingsViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS,
        )
        assertEquals(
            PlayerViewModel.VOLUME_SETTLE_TIMEOUT_MILLIS,
            SettingsViewModel.VOLUME_SETTLE_TIMEOUT_MILLIS,
        )
        assertEquals(
            PlayerViewModel.VOLUME_SETTLE_TOLERANCE,
            SettingsViewModel.VOLUME_SETTLE_TOLERANCE,
        )
    }

    @Test
    fun aContinuousVolumeDragFloodsNothingAndCommitsExactlyOnceAtDragStop() {
        makeViewModel(initialVolume = 0.2f)
        scheduler.runCurrent()

        // A 50-tick drag, 100 ms apart: every tick re-arms the 200 ms
        // debounce, so no commit fires mid-drag. The values are exact
        // floats (a hold at 0.3, release at 0.7) so the trailing
        // assertions compare exactly, no arithmetic drift.
        repeat(50) { tick ->
            viewModel.onVolume(if (tick == 49) 0.7f else 0.3f)
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
        }

        // THE THROTTLE: 50 ticks over 5 virtual seconds and the engine got
        // ZERO volume commands; the slider showed the finger the whole way
        // (the pending target drives the value).
        assertEquals(0, engine.setVolumes.size)
        assertTrue(viewModel.uiState.value.pendingVolume != null, "the drag's pending echo vanished mid-drag")
        assertEquals(0.7f, viewModel.uiState.value.sliderVolume)

        // The drag stopped: one trailing commit with the last value.
        scheduler.advanceTimeBy(SettingsViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        assertEquals(listOf(0.7f), engine.setVolumes)

        // The double applied the volume; a poll tick settles the echo so
        // the slider returns to engine truth.
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
        assertNull(viewModel.uiState.value.pendingVolume)
        assertEquals(0.7f, viewModel.uiState.value.volume)
    }

    @Test
    fun separateVolumeGesturesCommitSeparately() {
        makeViewModel()
        scheduler.runCurrent()

        viewModel.onVolume(0.4f)
        scheduler.advanceTimeBy(SettingsViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        viewModel.onVolume(0.8f)
        scheduler.advanceTimeBy(SettingsViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        // A poll tick settles the second gesture's pending echo.
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()

        assertEquals(listOf(0.4f, 0.8f), engine.setVolumes)
        assertNull(viewModel.uiState.value.pendingVolume)
    }

    // ------------------------------------------------------------------
    // Error surfacing (never a crash)
    // ------------------------------------------------------------------

    @Test
    fun aBusyVolumeCommitSurfacesTheErrorAndClearsThePending() {
        makeViewModel(initialVolume = 0.5f)
        scheduler.runCurrent()
        engine.setVolumeError = FausteException.Busy()

        viewModel.onVolume(0.9f)
        scheduler.advanceTimeBy(SettingsViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()

        // The commit DID reach the engine layer (and was dropped there);
        // the dropped target must stop driving the UI.
        assertEquals(listOf(0.9f), engine.setVolumes)
        assertEquals(UiText.Key(StringKeys.PLAYER_BUSY), viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.pendingVolume)
        assertEquals(0.5f, viewModel.uiState.value.volume)

        // No crash: the next intent runs, succeeds and clears the error.
        engine.setVolumeError = null
        viewModel.onVolume(0.6f)
        scheduler.advanceTimeBy(SettingsViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf(0.9f, 0.6f), engine.setVolumes)
    }

    @Test
    fun aFailedSelectSurfacesTheEnginesMessageAndTheNextIntentClearsIt() {
        makeViewModel(
            backends = listOf(BackendInfo(id = "null", name = "Null output", available = true)),
        )
        scheduler.runCurrent()
        engine.setBackendError = FausteException.Core("backend on fire")

        viewModel.onSelectBackend("null")
        scheduler.runCurrent()

        // The engine's own cause travels raw — the app does not own it.
        assertEquals(UiText.Raw("backend on fire"), viewModel.uiState.value.error)

        engine.setBackendError = null
        viewModel.onSelectBackend("null")
        scheduler.runCurrent()

        assertNull(viewModel.uiState.value.error)
        assertEquals(2, engine.setBackendCalls.size)
    }

    @Test
    fun aFailedRemoteRefreshSurfacesTheCauseThroughTheRepositorysMapping() {
        makeViewModel()
        scheduler.runCurrent()
        engine.remoteStatusError = FausteException.Core("the remote read failed")

        viewModel.onRefreshRemote()
        scheduler.runCurrent()

        assertEquals(UiText.Raw("the remote read failed"), viewModel.uiState.value.error)

        engine.remoteStatusError = null
        viewModel.onRefreshRemote()
        scheduler.runCurrent()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun aFailedBackendsProbeSurfacesTheCauseAndNeverCrashes() {
        makeViewModel(
            backends = listOf(BackendInfo(id = "null", name = "Null output", available = true)),
        )
        engine.backendsError = FausteException.Core("the probe failed")
        scheduler.runCurrent()

        // The one-shot query mapped onto the error channel + an empty
        // list — the VM's one documented catch; never a crash.
        assertEquals(0, viewModel.uiState.value.backends.size)
        assertEquals(UiText.Raw("the probe failed"), viewModel.uiState.value.error)
    }
}
