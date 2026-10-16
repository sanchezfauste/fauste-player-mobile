package org.fauste.player.ui.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.di.DefaultDispatcher
import org.fauste.player.di.IoDispatcher
import org.fauste.player.di.viewModelModule
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.FausteEngineInterface
import org.fauste.player.ui.theme.FausteTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The navigation shell's contract, on the Robolectric lane: the shell runs
 * on a REAL Koin container started per test with a scripted-engine graph —
 * so this suite also proves the production wiring shape (FausteApplication's
 * modules + MainActivity's KoinContext → PlayerScreen's `koinViewModel()`
 * default resolution through the default ViewModelStoreOwner) rather than
 * injecting a hand-made screen.
 *
 * Asserted: the Player start destination renders through that wiring, all
 * three tabs carry their label as a contentDescription, and every tab is
 * reachable — including back to Player.
 */
@RunWith(RobolectricTestRunner::class)
// A bare Application instead of the manifest's FausteApplication: this suite
// owns Koin explicitly (startKoin/stopKoin per test with a scripted graph).
@Config(sdk = [36], application = Application::class)
class AppNavHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var engine: ScriptedEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        engine =
            ScriptedEngine(
                engineState(
                    status = "playing",
                    position = 83.0,
                    duration = 301.0,
                    track = track(title = "Nocturne", duration = 301.0),
                ),
            )
        startKoin { modules(scriptedGraphModule(), viewModelModule) }
    }

    @After
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun playerRendersThroughTheKoinWiring() {
        composeRule.setContent {
            KoinContext {
                FausteTheme {
                    AppNavHost()
                }
            }
        }

        // The start destination resolves its ViewModel through koinViewModel()
        // and renders the engine's snapshot (the poll pipeline delivers within
        // a few periods — hence waitUntil, no sleeps).
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Nocturne").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun allThreeTabsAreLabeledAndReachable() {
        composeRule.setContent {
            KoinContext {
                FausteTheme {
                    AppNavHost()
                }
            }
        }
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Nocturne").fetchSemanticsNodes().isNotEmpty()
        }

        // Every destination is labeled (contentDescription) — TalkBack and
        // the tests address tabs by name.
        listOf("Player", "Library", "Settings").forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertExists()
        }

        // ... and every tab is reachable, including back to Player.
        composeRule.onNodeWithContentDescription("Library").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText("Library screen arrives in a later task.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText("Settings screen arrives in a later task.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Player").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Nocturne").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The scripted-engine graph: the same shape as the shared module's
     * appModule (controller/queue/repository over one engine, dispatchers
     * by qualifier) with the heavy real engine swapped for the double, plus
     * composeApp's real [viewModelModule] — so the production definitions
     * themselves are exercised.
     */
    private fun scriptedGraphModule(): Module =
        module {
            single<FausteEngineInterface> { engine }
            single(IoDispatcher) { Dispatchers.IO }
            single(DefaultDispatcher) { Dispatchers.Default }
            single { PlayerController(get(), get(IoDispatcher)) }.onClose { it?.close() }
            single { QueueManager(get(), get(IoDispatcher)) }.onClose { it?.close() }
            single { PlaylistRepository(get(), get()) }
        }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
