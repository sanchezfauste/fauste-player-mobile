package org.fauste.player.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.AppLifecycleState
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.ui.screens.PlayerScreen
import org.fauste.player.ui.theme.FausteTheme
import org.fauste.player.viewmodel.PlayerViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * THE BATTERY-CONTRACT PIN (Task 3; the Phase-4 final review's #2 gap):
 * the screens used plain `collectAsState()`, which survives ON_STOP — and
 * the keep-awake gate keeps the controller's poll emitting at 10 Hz while
 * the app is backgrounded AND playing (the service's notification needs
 * the feed), so the composition recomposed at up to 10 Hz all night.
 *
 * The honest contract, pinned here as one transcript:
 *
 * 1. **ON_STOP stops COLLECTION, not the poll.** The keep-awake
 *    configuration is modelled exactly — [AppLifecycleState.Background] +
 *    [PlayerController.setPlaybackServiceActive] `true` — so the poll
 *    keeps feeding [PlayerController.engineState] (the service's own
 *    feed; asserted by reading the flow's position move while the
 *    screen's timecode stays frozen at its last rendered value).
 * 2. **ON_START resumes from the LATEST state** — the screen catches up
 *    to the engine's current position, skipping the values it missed.
 *
 * What this lane honestly measures: the *rendered output* stops tracking
 * the flow — no recomposition runs for new emissions (a recomposition
 * with a new position would change the timecode text; it does not
 * change). A recomposition-count or profiler-grade measurement (and the
 * actual battery draw) needs real hardware — see the Task-3 report's
 * battery-honesty section; the unit pin is the deterministic proxy.
 *
 * Real-time lane: the controller polls the double from Dispatchers.IO at
 * the default 100 ms period, so every step waits with `waitUntil`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LifecycleAwareCollectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var engine: ScriptedEngine
    private lateinit var holder: AppLifecycleHolder
    private lateinit var controller: PlayerController
    private lateinit var queue: QueueManager
    private lateinit var repository: PlaylistRepository
    private lateinit var viewModel: PlayerViewModel
    private lateinit var lifecycleOwner: ScreenTestLifecycleOwner

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        engine =
            ScriptedEngine(
                engineState(
                    status = "playing",
                    position = 10.0,
                    duration = 301.0,
                    track = track(title = "Nocturne", artist = "Fauste Trio", duration = 301.0),
                ),
            )
        holder = AppLifecycleHolder()
        controller = PlayerController(engine, holder, Dispatchers.IO)
        queue = QueueManager(engine, Dispatchers.IO)
        repository = PlaylistRepository(queue, controller)
        viewModel =
            PlayerViewModel(
                controller = lazyOf(controller),
                queue = lazyOf(queue),
                repository = lazyOf(repository),
                commandDispatcher = Dispatchers.IO,
            )
        // THE KEEP-AWAKE GATE'S EXACT PRODUCTION SHAPE: backgrounded app,
        // playback service alive (notification feeding) — the poll must
        // NOT idle in this configuration; only the composition does.
        controller.setPlaybackServiceActive(true)
        holder.update(AppLifecycleState.Background)
        lifecycleOwner = ScreenTestLifecycleOwner()
    }

    @After
    fun tearDown() {
        controller.close()
        queue.close()
        Dispatchers.resetMain()
    }

    @Test
    fun onStopStopsCollectionWhileTheKeepAwakePollKeepsFeedingTheService() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
            }
        }

        // Foregrounded composition: the engine's position renders.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("00:10 / 05:01").fetchSemanticsNodes().isNotEmpty()
        }

        // ON_STOP. From here the composition must collect nothing.
        lifecycleOwner.stop()

        // The engine advances while the app is backgrounded+playing. The
        // keep-awake contract half: the poll DOES keep feeding
        // engineState — the playback service's notification source stays
        // live (asserted first, so the freeze below is not "the poll
        // idled" wearing a collection costume).
        engine.state.value = engine.state.value.copy(position = 20.0)
        composeRule.waitUntil(TIMEOUT_MILLIS) { controller.engineState.value.position == 20.0 }
        composeRule.onNodeWithText("00:10 / 05:01").assertExists()
        composeRule
            .onAllNodesWithText("00:20 / 05:01")
            .fetchSemanticsNodes()
            .isEmpty()

        // A second advance proves the freeze was not a one-tick miss:
        // the flow keeps moving (service fed), the screen stays frozen.
        engine.state.value = engine.state.value.copy(position = 30.0)
        composeRule.waitUntil(TIMEOUT_MILLIS) { controller.engineState.value.position == 30.0 }
        composeRule.onNodeWithText("00:10 / 05:01").assertExists()
        composeRule
            .onAllNodesWithText("00:30 / 05:01")
            .fetchSemanticsNodes()
            .isEmpty()

        // ON_START resumes collection from the LATEST state — the missed
        // values (20) are skipped, not replayed.
        lifecycleOwner.start()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("00:30 / 05:01").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule
            .onAllNodesWithText("00:10 / 05:01")
            .fetchSemanticsNodes()
            .isEmpty()
    }

    /** The honest measurement boundary of the pin above: the ViewModel's own state chain is NOT the battery claim. */
    @Test
    fun theStateLayerItselfKeepsPublishingWhileBackgrounded() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("00:10 / 05:01").fetchSemanticsNodes().isNotEmpty()
        }

        // The state layer's contract is unchanged by the collection gate:
        // the controller's poll feeds the service (keep-awake) and the
        // ViewModel's UDF pipeline keeps mapping it — the battery fix
        // gates the COMPOSITION's consumption only. (The service reads
        // the controller's flow directly, not this ViewModel; the VM is
        // here as the screen's state source, the half the freeze pinned.)
        lifecycleOwner.stop()
        engine.state.value = engine.state.value.copy(position = 20.0)
        composeRule.waitUntil(TIMEOUT_MILLIS) { controller.engineState.value.position == 20.0 }
        composeRule.waitUntil(TIMEOUT_MILLIS) { viewModel.uiState.value.position == 20.0 }

        assertEquals(20.0, viewModel.uiState.value.position)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
