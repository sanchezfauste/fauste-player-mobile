package org.fauste.player.ui.screens

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.playlistEntry
import org.fauste.player.player.track
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.FausteException
import org.fauste.player.ui.ScreenTestLifecycleOwner
import org.fauste.player.ui.strings.FluentStrings
import org.fauste.player.ui.strings.StringKeys
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
 * THE OWNER PATTERN (Task 3): the screen collects its state flow through the
 * lifecycle-aware helper, so every composition here runs under an explicit
 * [ScreenTestLifecycleOwner] (RESUMED) provided as the composition's
 * LifecycleOwner — deterministic, and the same wiring the ON_STOP/ON_START
 * pin drives in [org.fauste.player.ui.LifecycleAwareCollectionTest].
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

    private val lifecycleOwner = ScreenTestLifecycleOwner()

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var queue: QueueManager
    private lateinit var repository: PlaylistRepository
    private lateinit var viewModel: PlayerViewModel

    // The composition renders through LocalStrings' en-US default (the
    // i18n seam's documented test shape); message assertions resolve the
    // SAME memoized en-US resolver, so the suite never forks the copy.
    private val enUs: FluentStrings by lazy { runBlocking { FluentStrings.forLocale("en-US") } }

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
        // The queue rows the up-next line resolves from — scripted BEFORE
        // the QueueManager boots (its ONE boot refresh reads the engine
        // here; nothing else re-reads the queue, so this stays
        // deterministic on the real-IO lane — the LibraryScreen suite's
        // same ordering note).
        engine.playlist.value = listOf(playlistEntry(id = 2u, title = "Sea Breeze"))
        controller = PlayerController(engine, AppLifecycleHolder(), Dispatchers.IO)
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
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
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
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
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
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
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
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Pause").performClick()

        // The Busy drop surfaces as text in the error colour, not a crash
        // — rendered from the bundle's id by the seam's en-US default.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(enUs[StringKeys.PLAYER_BUSY]).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theUpNextLineRendersTheArmedRowsTitleUnderTheControls() {
        engine.state.value = engine.state.value.copy(armedEntryId = 2u)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
            }
        }

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Up next: Sea Breeze").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theUpNextLineHidesWhileNothingIsArmed() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    PlayerScreen(viewModel)
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Nocturne").fetchSemanticsNodes().isNotEmpty()
        }

        // Nothing armed → no line: the surface carries no derived-next
        // read, so rendering one would promise play-now.
        composeRule
            .onAllNodesWithText("Up next: Sea Breeze")
            .fetchSemanticsNodes()
            .isEmpty()
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
