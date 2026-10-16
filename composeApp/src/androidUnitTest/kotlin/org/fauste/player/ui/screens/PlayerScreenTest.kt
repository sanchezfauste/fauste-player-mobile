package org.fauste.player.ui.screens

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.FausteException
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
 * PlayerScreen rendering and wiring, on the Robolectric lane (see
 * composeApp/build.gradle.kts's androidUnitTest note): a real PlayerViewModel
 * over the scripted engine, so the assertions cover the whole state-down /
 * intents-up path — the screen renders what the pipeline publishes, and a
 * control tap reaches the engine exactly once.
 *
 * Real-time lane (no virtual clock here): the controller polls the double
 * from Dispatchers.IO at the default 100 ms period, so each test waits for
 * the composition with `waitUntil` instead of sleeps. The commit debounce
 * (200 ms) is exercised for real by the drag test — the seek lands once.
 */
@RunWith(RobolectricTestRunner::class)
// A bare Application instead of the manifest's FausteApplication: this suite
// owns its Koin-free graph explicitly (the ViewModel is constructed
// directly), and Robolectric must not startKoin behind its back.
@Config(sdk = [36], application = Application::class)
class PlayerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var queue: QueueManager
    private lateinit var repository: PlaylistRepository
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        engine =
            ScriptedEngine(
                engineState(
                    status = "playing",
                    position = 83.0,
                    duration = 301.0,
                    track = track(title = "Nocturne", artist = "Fauste Trio", duration = 301.0),
                ),
            )
        controller = PlayerController(engine, Dispatchers.IO)
        queue = QueueManager(engine, Dispatchers.IO)
        repository = PlaylistRepository(queue, controller)
        viewModel =
            PlayerViewModel(
                controller = lazyOf(controller),
                queue = lazyOf(queue),
                repository = lazyOf(repository),
                commandDispatcher = Dispatchers.IO,
            )
    }

    @After
    fun tearDown() {
        controller.close()
        queue.close()
        Dispatchers.resetMain()
    }

    @Test
    fun rendersThePlayingState() {
        composeRule.setContent {
            FausteTheme {
                PlayerScreen(viewModel)
            }
        }

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Nocturne").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Nocturne").assertExists()
        composeRule.onNodeWithText("Fauste Trio").assertExists()
        // Playing → the toggle shows the Pause action, and the mono timecode
        // renders position/duration from the engine snapshot.
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithText("01:23 / 05:01").assertExists()
    }

    @Test
    fun playPauseTapReachesTheEngine() {
        composeRule.setContent {
            FausteTheme {
                PlayerScreen(viewModel)
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Pause").performClick()

        // Playing → the tap paused; the intent went through the VM to the
        // engine exactly once, on the VM's dispatcher.
        composeRule.waitUntil(TIMEOUT_MILLIS) { engine.pauseCalls.get() == 1 }
        assertEquals(0, engine.playCalls.get())
    }

    @Test
    fun seekDragShowsPendingInstantlyAndCommitsOnce() {
        composeRule.setContent {
            FausteTheme {
                PlayerScreen(viewModel)
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Seek bar").fetchSemanticsNodes().isNotEmpty()
        }

        // Drag the seek bar to 50% of the 301 s track: the pending position
        // (150.5 s) shows in the timecode IMMEDIATELY — before the engine
        // has anything — which is the instant-feedback half of the throttle.
        composeRule
            .onNodeWithContentDescription("Seek bar")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(0.5f) }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("02:30 / 05:01").fetchSemanticsNodes().isNotEmpty()
        }

        // The trailing debounce then lands exactly ONE commit, with the
        // pending value the bar showed.
        composeRule.waitUntil(TIMEOUT_MILLIS) { engine.seeks.size == 1 }
        assertEquals(listOf(150.5), engine.seeks)
    }

    @Test
    fun aDroppedCommandSurfacesAnErrorOnScreen() {
        engine.pauseError = FausteException.Busy()
        composeRule.setContent {
            FausteTheme {
                PlayerScreen(viewModel)
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Pause").performClick()

        // The Busy drop surfaces as text in the error colour, not a crash.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(PlayerViewModel.BUSY_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
