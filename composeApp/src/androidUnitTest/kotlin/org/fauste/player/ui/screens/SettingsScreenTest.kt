package org.fauste.player.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.fauste.player.ui.ScreenTestLifecycleOwner
import org.fauste.player.ui.strings.FluentStrings
import org.fauste.player.ui.strings.StringKeys
import org.fauste.player.ui.theme.FausteTheme
import org.fauste.player.viewmodel.SettingsViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * SettingsScreen rendering and wiring, on the Robolectric lane (the
 * Library-screen suite's shape): a real [SettingsViewModel] over the
 * scripted engine, so the assertions cover the whole state-down /
 * intents-up path — the backend rows render from the probed list with
 * the selected marker following the engine's report, an unavailable row
 * is disabled (the picker's rule), a select tap reaches the engine
 * through the repository, the remote section shows the record, and the
 * volume slider's pending feedback surfaces and settles.
 *
 * THE @CONFIG-LESS CANARY (the Task-4 carry): this suite declares NO
 * `@Config`, so it runs on the module-wide robolectric.properties
 * defaults — sdk 36 and the BARE Application. The whole graph here is
 * hand-built (no Koin), so the default is what makes the suite safe.
 *
 * THE OWNER PATTERN (Task 3): the screen collects lifecycle-aware, so
 * [render] runs every composition under an explicit
 * [ScreenTestLifecycleOwner] (RESUMED) as the composition's
 * LifecycleOwner — the pattern's rationale in PlayerScreenTest's KDoc.
 *
 * Real-time lane (no virtual clock): the controller polls the double
 * from Dispatchers.IO at the 100 ms period and the volume commit's
 * debounce is a real 200 ms, so each test waits with `waitUntil`
 * instead of sleeps.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lifecycleOwner = ScreenTestLifecycleOwner()

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var remote: RemoteServer
    private lateinit var repository: SettingsRepository
    private lateinit var viewModel: SettingsViewModel

    // The composition renders through LocalStrings' en-US default (the
    // i18n seam's documented test shape); message assertions resolve the
    // SAME memoized en-US resolver, so the suite never forks the copy.
    private val enUs: FluentStrings by lazy { runBlocking { FluentStrings.forLocale("en-US") } }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        controller.close()
        remote.close()
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        backend: String = "null",
        backends: List<BackendInfo> = emptyList(),
        remoteInfo: RemoteInfo = RemoteInfo(enabled = false, address = ""),
    ): SettingsViewModel {
        engine = ScriptedEngine(engineState(status = "stopped", backend = backend))
        engine.backends.value = backends
        engine.remote.value = remoteInfo
        controller = PlayerController(engine, AppLifecycleHolder(), Dispatchers.IO)
        remote = RemoteServer(engine, Dispatchers.IO)
        repository = SettingsRepository(engine, controller, remote, Dispatchers.IO)
        viewModel =
            SettingsViewModel(
                repository = lazyOf(repository),
                controller = lazyOf(controller),
                commandDispatcher = Dispatchers.IO,
            )
        return viewModel
    }

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    SettingsScreen(viewModel)
                }
            }
        }
    }

    @Test
    fun backendRowsRenderWithAvailabilityAndTheSelectedMarkerFollowsTheEngine() {
        makeViewModel(
            backend = "null",
            backends =
                listOf(
                    BackendInfo(id = "null", name = "Null output", available = true),
                    BackendInfo(id = "aaudio", name = "AAudio", available = false),
                ),
        )
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Null output").fetchSemanticsNodes().isNotEmpty()
        }
        // The rows carry the probed names and the availability words; the
        // selected marker follows the ENGINE's in-use report (the double's
        // initial backend), and each row's spoken description reads all
        // three facts in one string.
        composeRule.onNodeWithText("AAudio").assertExists()
        composeRule.onNodeWithText("Available").assertExists()
        composeRule.onNodeWithText("Unavailable").assertExists()
        composeRule
            .onNodeWithContentDescription("Null output, available, selected")
            .assertExists()
        composeRule
            .onNodeWithContentDescription("AAudio, unavailable, not selected")
            .assertExists()
    }

    @Test
    fun anUnavailableRowIsDisabledAndAnAvailableRowSelects() {
        makeViewModel(
            backend = "aaudio",
            backends =
                listOf(
                    BackendInfo(id = "null", name = "Null output", available = true),
                    BackendInfo(id = "aaudio", name = "AAudio", available = false),
                ),
        )
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Null output").fetchSemanticsNodes().isNotEmpty()
        }
        // The unavailable row is disabled — the picker's rule (ffi.rs
        // `set_backend`: the UI offers only the available ones) — even
        // though it is the engine's current in-use report.
        composeRule
            .onNodeWithContentDescription("AAudio, unavailable, selected")
            .assertIsNotEnabled()
        composeRule
            .onNodeWithContentDescription("Null output, available, not selected")
            .assertIsEnabled()

        composeRule.onNodeWithContentDescription("Null output, available, not selected").performClick()

        // The tap reached the engine through the repository; the double
        // applies it and the marker follows on the next poll tick.
        composeRule.waitUntil(TIMEOUT_MILLIS) { engine.setBackendCalls.isNotEmpty() }
        assertEquals(listOf("null"), engine.setBackendCalls)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithContentDescription("Null output, available, selected")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun theRemoteSectionShowsTheRecordAndTheHonestPlaceholder() {
        makeViewModel(remoteInfo = RemoteInfo(enabled = true, address = "127.0.0.1:52341"))
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("On at 127.0.0.1:52341").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Refresh").assertExists()
    }

    @Test
    fun theVolumeSliderShowsPendingFeedbackAndSettles() {
        makeViewModel()
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Volume").fetchSemanticsNodes().isNotEmpty()
        }

        // A drag tick (the VM's own intent — the slider's gesture handling
        // is Compose's): the pending echo shows instantly, the engine is
        // untouched before the debounce window ends.
        viewModel.onVolume(0.4f)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Applying…").fetchSemanticsNodes().isNotEmpty()
        }

        // The trailing debounce lands exactly one commit; the double
        // applies it and a poll tick releases the echo.
        composeRule.waitUntil(TIMEOUT_MILLIS) { engine.setVolumes.size == 1 }
        assertEquals(listOf(0.4f), engine.setVolumes)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Applying…").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun anEmptyProbeAnswerSaysSoNeverInventingRows() {
        makeViewModel(backends = emptyList())
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("No backends reported.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aDroppedSelectSurfacesAnErrorOnScreen() {
        makeViewModel(
            backends = listOf(BackendInfo(id = "null", name = "Null output", available = true)),
        )
        engine.setBackendError = FausteException.Busy()
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Null output").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Null output, available, selected").performClick()

        // The Busy drop surfaces as text in the error colour, not a crash
        // — rendered from the bundle's id by the seam's en-US default.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(enUs[StringKeys.PLAYER_BUSY]).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
