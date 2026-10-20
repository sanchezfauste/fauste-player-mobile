package org.fauste.player.ui.screens

import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.Storage
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.player.playlistEntry
import org.fauste.player.player.track
import org.fauste.player.repository.MediaRepository
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.FausteException
import org.fauste.player.rust.PlaylistEntry
import org.fauste.player.ui.ScreenTestLifecycleOwner
import org.fauste.player.ui.theme.FausteTheme
import org.fauste.player.viewmodel.LibraryImport
import org.fauste.player.viewmodel.LibraryViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * LibraryScreen rendering and wiring, on the Robolectric lane: a real
 * [LibraryViewModel] over the scripted engine (the copy seam doubled
 * through the sanctioned `open`-class [Storage] seam), so the assertions
 * cover the whole state-down / intents-up path — rows render from the
 * pipeline's state, the search filters the visible list, a row tap
 * reaches the engine ARM-ONLY, and the Import button fires the host
 * seam callback the Activity owns (see LibraryScreen's KDoc).
 *
 * THE @CONFIG-LESS CANARY (the Task-3 carry): this suite declares NO
 * `@Config`, so it runs on `composeApp/src/androidUnitTest/resources/
 * robolectric.properties`'s defaults — sdk 36 and the BARE Application.
 * Its 2nd+ methods would crash with Koin's double-start exception if
 * anyone pointed that default back at the manifest's FausteApplication
 * (Robolectric creates the Application per method; Koin's static
 * GlobalContext persists across methods in the reused sandbox loader).
 * Every method after the first is the pin.
 *
 * THE OWNER PATTERN (Task 3): the screen collects lifecycle-aware, so
 * [render] runs every composition under an explicit
 * [ScreenTestLifecycleOwner] (RESUMED) as the composition's
 * LifecycleOwner — the pattern's rationale in PlayerScreenTest's KDoc.
 *
 * Real-time lane (no virtual clock): the controller polls the double
 * from Dispatchers.IO at the 100 ms period, so each test waits for the
 * composition with `waitUntil` instead of sleeps.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lifecycleOwner = ScreenTestLifecycleOwner()

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var queue: QueueManager
    private lateinit var repository: PlaylistRepository
    private lateinit var media: MediaRepository
    private lateinit var viewModel: LibraryViewModel

    /** The copy seam's parked answer, cancelled in tearDown so a mid-import test cannot leak its coroutine. */
    private var storageGate: CompletableDeferred<String?>? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        controller.close()
        queue.close()
        storageGate?.cancel()
        Dispatchers.resetMain()
    }

    private fun makeViewModel(
        initial: EngineState = engineState(status = "stopped"),
        playlist: List<PlaylistEntry> = emptyList(),
        storageAnswer: suspend (String) -> String? = { null },
    ): LibraryViewModel {
        engine = ScriptedEngine(initial)
        // Scripted BEFORE the QueueManager boots: its ONE boot refresh
        // reads the engine here, and nothing else ever re-reads the
        // queue — scripting after construction is a race this lane
        // (real IO threads) loses arbitrarily. Mirrors real boot: the
        // persisted queue exists before the manager reads it.
        engine.playlist.value = playlist
        controller = PlayerController(engine, AppLifecycleHolder(), Dispatchers.IO)
        queue = QueueManager(engine, Dispatchers.IO)
        repository = PlaylistRepository(queue, controller)
        media = MediaRepository(CannedStorage(storageAnswer), repository)
        viewModel =
            LibraryViewModel(
                queue = lazyOf(queue),
                repository = lazyOf(repository),
                media = lazyOf(media),
                commandDispatcher = Dispatchers.IO,
            )
        return viewModel
    }

    private fun render(onImportClick: () -> Unit = {}) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                FausteTheme {
                    LibraryScreen(onImportClick = onImportClick, viewModel = viewModel)
                }
            }
        }
    }

    @Test
    fun rowsRenderFromTheViewModelStateWithTheCurrentRowMarked() {
        makeViewModel(
            initial =
                engineState(
                    status = "playing",
                    track = track(path = "/music/breeze.flac", duration = 0.0),
                    currentEntryId = 22u,
                ),
            playlist =
                listOf(
                    playlistEntry(id = 21u, title = "Nocturne", path = "/music/nocturne.flac", duration = 301.0),
                    playlistEntry(id = 22u, title = "Sea Breeze", path = "/music/breeze.flac", duration = 0.0),
                ),
        )
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Sea Breeze").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Nocturne").assertExists()
        // TrackRow's honest-limits duration: 0.0 renders the em dash.
        composeRule.onNodeWithText("05:01").assertExists()
        composeRule.onNodeWithText("—").assertExists()
        // Exactly the on-air ENTRY's row carries the playing indicator —
        // joined by id, the Phase-5 read.
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription("Now playing").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, composeRule.onAllNodesWithContentDescription("Now playing").fetchSemanticsNodes().size)
    }

    @Test
    fun searchFiltersTheVisibleList() {
        makeViewModel(
            playlist =
                listOf(
                    playlistEntry(id = 1u, title = "Nocturne"),
                    playlistEntry(id = 2u, title = "Sea Breeze"),
                ),
        )
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Sea Breeze").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Search the queue").performTextInput("OCT")

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Sea Breeze").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("Nocturne").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anEmptyQueueShowsTheImportCallToActionNotAnEmptyList() {
        makeViewModel()
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText("Import music to build the queue")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Import").assertExists()
    }

    @Test
    fun aRowTapArmsTheEntryAndTheBadgeMakesTheArmVisible() {
        makeViewModel(
            initial = engineState(status = "stopped"),
            playlist = listOf(playlistEntry(id = 31u, title = "Armed")),
        )
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Armed").fetchSemanticsNodes().isNotEmpty()
        }
        // Before the tap: no badge — an unbadged row makes no arm claim.
        composeRule
            .onAllNodesWithContentDescription("Armed as next track")
            .fetchSemanticsNodes()
            .isEmpty()

        composeRule.onNodeWithText("Armed").performClick()

        // ARM-ONLY: the tap armed the row as next; nothing started playing.
        // (The poll watches size — an untyped boolean — because a bare
        // `listOf(31u)` literal is a List<UInt> and never equals the
        // engine's List<ULong> at runtime; the precise identity assert
        // below carries the explicit type argument, like the established
        // PlayerViewModelTest's jump assertion.)
        composeRule.waitUntil(TIMEOUT_MILLIS) { engine.setNextCalls.isNotEmpty() }
        assertEquals<List<ULong>>(listOf(31u), engine.setNextCalls)
        assertEquals(0, engine.playCalls.get())

        // THE PHASE-4 GAP, CLOSED END TO END: the tap's arm lands on the
        // engine (the double applies it; the poll carries the armed id),
        // and the tapped row now shows the "UP NEXT" badge with its
        // "Armed as next track" description — the arm visible, still no
        // play-now promise (the transport never moved).
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("UP NEXT").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Armed as next track").assertExists()
        assertEquals("stopped", engine.state.value.status, "the badge never promised play-now")
    }

    @Test
    fun theImportButtonFiresTheHostSeamCallback() {
        makeViewModel()
        var importClicks = 0
        render(onImportClick = { importClicks++ })

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Import").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Import").performClick()

        assertEquals(1, importClicks)
    }

    @Test
    fun anImportResultSurfacesAndDismissesThroughTheViewModel() {
        makeViewModel()
        viewModel.onImportPermissionDenied()
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText(LibraryViewModel.PERMISSION_DENIED_MESSAGE)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("Dismiss").performClick()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText(LibraryViewModel.PERMISSION_DENIED_MESSAGE)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        assertEquals(LibraryImport.Idle, viewModel.uiState.value.import)
    }

    @Test
    fun theImportButtonDisablesWhileAnImportIsInFlightAndRecovers() {
        val gate = CompletableDeferred<String?>()
        storageGate = gate
        makeViewModel(storageAnswer = { gate.await() })
        render()

        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Import").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Import").assertIsEnabled()

        viewModel.onImportUri("content://picked/host")
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Importing…").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Import").assertIsNotEnabled()

        gate.complete(null)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("Importing…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Import").assertIsEnabled()
    }

    /** The copy-seam double — canned answers, zero Android API calls. */
    private class CannedStorage(
        private val answer: suspend (String) -> String?,
    ) : Storage(ContextWrapper(null)) {
        override suspend fun importToAppStorage(uri: String): String? = answer(uri)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 5_000
    }
}
