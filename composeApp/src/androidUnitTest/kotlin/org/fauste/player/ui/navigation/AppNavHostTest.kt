package org.fauste.player.ui.navigation

import android.app.Application
import android.content.ContextWrapper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.di.DefaultDispatcher
import org.fauste.player.di.IoDispatcher
import org.fauste.player.di.viewModelModule
import org.fauste.player.platform.Storage
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.track
import org.fauste.player.repository.MediaRepository
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
import kotlin.test.assertEquals

/**
 * The navigation shell's contract, on the Robolectric lane: the shell runs
 * on a REAL Koin container started per test with a scripted-engine graph —
 * so this suite also proves the production wiring shape (FausteApplication's
 * modules + MainActivity's KoinContext → the screens' `koinViewModel()`
 * default resolution through the default ViewModelStoreOwner) rather than
 * injecting a hand-made screen.
 *
 * Asserted: the Player start destination renders through that wiring, all
 * three tabs carry their label as a contentDescription, every tab is
 * reachable — the Library tab now renders the real LibraryScreen (its
 * empty-queue call to action), whose Import button fires the host seam
 * the Activity supplies (the launchers themselves are the Activity's
 * manually-verified part — see LibraryScreen's KDoc).
 */
@RunWith(RobolectricTestRunner::class)
// A bare Application instead of the manifest's FausteApplication: this suite
// owns Koin explicitly (startKoin/stopKoin per test with a scripted graph).
// The @Config-less default lives in androidUnitTest/resources/robolectric.properties.
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
                    AppNavHost(onImportClick = {})
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
                    AppNavHost(onImportClick = {})
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

        // ... and every tab is reachable. The Library tab renders the REAL
        // screen now: with the scripted engine's queue empty, its honest
        // state is the import call to action.
        composeRule.onNodeWithContentDescription("Library").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText("Import music to build the queue")
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

    @Test
    fun theLibraryTabRendersThroughTheKoinWiringAndFiresTheImportSeam() {
        var importClicks = 0
        composeRule.setContent {
            KoinContext {
                FausteTheme {
                    AppNavHost(onImportClick = { importClicks++ })
                }
            }
        }
        composeRule.onNodeWithContentDescription("Library").performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Import").fetchSemanticsNodes().isNotEmpty()
        }

        // The Library's ViewModel resolved through the production
        // viewModelModule over the scripted graph (no import fired — the
        // seam's Activity half owns the launchers); the button itself
        // reaches the host callback.
        composeRule.onNodeWithText("Import").performClick()

        assertEquals(1, importClicks)
    }

    /**
     * The scripted-engine graph: the same shape as the shared module's
     * appModule (controller/queue/repositories over one engine, dispatchers
     * by qualifier) with the heavy real engine swapped for the double, plus
     * composeApp's real [viewModelModule] — so the production definitions
     * themselves are exercised. The Storage seam is stubbed (no import runs
     * here; the Android actual's Uri/ContentResolver path cannot run on the
     * host JVM anyway).
     */
    private fun scriptedGraphModule(): Module =
        module {
            single<FausteEngineInterface> { engine }
            single(IoDispatcher) { Dispatchers.IO }
            single(DefaultDispatcher) { Dispatchers.Default }
            single { PlayerController(get(), get(IoDispatcher)) }.onClose { it?.close() }
            single { QueueManager(get(), get(IoDispatcher)) }.onClose { it?.close() }
            single { PlaylistRepository(get(), get()) }
            single { MediaRepository(NoopStorage(), get()) }
        }

    /** A do-nothing copy seam: these tests never import, so it never answers. */
    private class NoopStorage : Storage(ContextWrapper(null)) {
        override suspend fun importToAppStorage(uri: String): String? = null
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
