package org.fauste.player.viewmodel

import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import org.fauste.player.ui.strings.StringKeys
import org.fauste.player.ui.strings.UiText
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The LibraryViewModel contract suite: row mapping (the playing flag
 * follows the engine's current track), the client-side filter, the
 * arm-only row tap, the import intent's Importing → Imported/Failed
 * phases, the permission-denied surfacing, and error handling. Like the
 * PlayerViewModel suite, everything runs against the REAL state layer —
 * PlayerController, QueueManager, PlaylistRepository, MediaRepository —
 * over the scripted [ScriptedEngine], with the copy seam doubled through
 * the sanctioned `open`-class [Storage] seam (the Android actual's
 * Uri/ContentResolver path cannot run on the host JVM — the same
 * honesty call as the shared module's MediaRepositoryTest). What is
 * under test is the ViewModel's own behaviour through the genuine
 * pipeline.
 *
 * Virtual-time mechanics (mirroring PlayerViewModelTest): every
 * dispatcher — the ViewModel's command dispatcher, the controller's, the
 * queue manager's, Dispatchers.Main via [setMain] — shares ONE
 * [TestCoroutineScheduler], driven explicitly with `runCurrent`; never
 * `advanceUntilIdle` (the poll loop re-arms forever) and deliberately no
 * `runTest`. The import phases are observed deterministically by parking
 * the copy seam on a [CompletableDeferred]: Importing publishes while
 * the import is parked, completing the gate lets the outcome land.
 */
class LibraryViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val commandDispatcher = StandardTestDispatcher(scheduler)

    private lateinit var engine: ScriptedEngine
    private lateinit var controller: PlayerController
    private lateinit var queue: QueueManager
    private lateinit var repository: PlaylistRepository
    private lateinit var media: MediaRepository
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        // The two pure-filter tests never build the pipeline, so the
        // state layer may legitimately be uninitialized here.
        if (this::controller.isInitialized) controller.close()
        if (this::queue.isInitialized) queue.close()
        Dispatchers.resetMain()
    }

    /** The real pipeline over the double, with the copy seam's scripted answer. */
    private fun makeViewModel(
        initial: EngineState = engineState(status = "stopped"),
        playlist: List<PlaylistEntry> = emptyList(),
        storageAnswer: suspend (String) -> String? = { null },
    ): LibraryViewModel {
        engine = ScriptedEngine(initial)
        // Scripted BEFORE the QueueManager boots — its ONE boot refresh is
        // the only re-read of the engine's queue, so the rows must exist
        // by then (mirrors real boot: the persisted queue precedes the
        // manager). On this suite's single scheduler the refresh runs at
        // the first runCurrent() either way; the LibraryScreen suite's
        // real-IO lane needs this ordering to stay deterministic.
        engine.playlist.value = playlist
        controller = PlayerController(engine, AppLifecycleHolder(), commandDispatcher)
        queue = QueueManager(engine, commandDispatcher)
        repository = PlaylistRepository(queue, controller)
        media = MediaRepository(CannedStorage(storageAnswer), repository)
        viewModel =
            LibraryViewModel(
                queue = lazyOf(queue),
                repository = lazyOf(repository),
                media = lazyOf(media),
                commandDispatcher = commandDispatcher,
            )
        return viewModel
    }

    // ------------------------------------------------------------------
    // The pure filter (title match, client-side — the list is small)
    // ------------------------------------------------------------------

    @Test
    fun thePureFilterMatchesTitlesCaseInsensitivelyAndTrimsTheNeedle() {
        val rows =
            LibraryViewModel.visibleRows(
                listOf(
                    playlistEntry(id = 1u, title = "Nocturne"),
                    playlistEntry(id = 2u, title = "Sea Breeze"),
                ),
                engineState(status = "stopped"),
                query = "  OCT ",
            )

        assertEquals<List<ULong>>(listOf(1u), rows.map { it.id }, "only the matching title survives, trimmed + case-insensitive")
    }

    @Test
    fun aBlankQueryReturnsEveryRowInQueueOrder() {
        val rows =
            LibraryViewModel.visibleRows(
                listOf(
                    playlistEntry(id = 2u, title = "B"),
                    playlistEntry(id = 1u, title = "A"),
                ),
                engineState(status = "stopped"),
                query = "   ",
            )

        assertEquals<List<ULong>>(listOf(2u, 1u), rows.map { it.id }, "queue order is the map's order, untouched")
    }

    // ------------------------------------------------------------------
    // Row mapping: playlist + engine state → rows
    // ------------------------------------------------------------------

    @Test
    fun rowsMapThePlaylistAndThePlayingFlagFollowsTheOnAirEntryId() {
        makeViewModel(
            initial =
                engineState(
                    status = "playing",
                    track = track(path = "/music/breeze.flac", duration = 0.0),
                    currentEntryId = 12u,
                ),
            playlist =
                listOf(
                    playlistEntry(id = 11u, title = "Nocturne", path = "/music/nocturne.flac", duration = 301.0),
                    playlistEntry(id = 12u, title = "Sea Breeze", path = "/music/breeze.flac", duration = 0.0),
                ),
        )
        scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(2, state.queueEntryCount)
        assertEquals<List<ULong>>(
            listOf(11u, 12u),
            state.rows.map { it.id },
            "the row id is the playlist flow's entry id — never an enqueue call's returned id",
        )
        assertEquals(listOf("Nocturne", "Sea Breeze"), state.rows.map { it.title })
        assertEquals(listOf(301.0, 0.0), state.rows.map { it.durationSeconds })
        assertFalse(state.rows[0].isPlaying, "the off-air row is not flagged")
        assertTrue(state.rows[1].isPlaying, "the on-air ENTRY's row is flagged — by id, the desktop's own join key")
    }

    @Test
    fun duplicatePathsNoLongerEachReadAsCurrentTheIdJoinDisambiguates() {
        // THE Phase-5 regression: two rows share one path, the on-air one
        // is the second — the old path join flagged both; the id join
        // flags exactly the row the player holds on air.
        val rows =
            LibraryViewModel.visibleRows(
                listOf(
                    playlistEntry(id = 1u, title = "First copy", path = "/music/b.flac"),
                    playlistEntry(id = 2u, title = "Second copy", path = "/music/b.flac"),
                ),
                engineState(
                    status = "playing",
                    track = track(path = "/music/b.flac"),
                    currentEntryId = 2u,
                ),
                query = "",
            )

        assertEquals(
            listOf(false, true),
            rows.map { it.isPlaying },
            "only the on-air ENTRY's row is flagged, duplicate paths included",
        )
    }

    @Test
    fun theArmedRowCarriesTheArmFlagAndNothingElseDoes() {
        val rows =
            LibraryViewModel.visibleRows(
                listOf(
                    playlistEntry(id = 1u, title = "Nocturne"),
                    playlistEntry(id = 2u, title = "Sea Breeze"),
                    playlistEntry(id = 3u, title = "Drift"),
                ),
                engineState(status = "playing", currentEntryId = 1u, armedEntryId = 3u),
                query = "",
            )

        assertEquals(
            listOf(false, false, true),
            rows.map { it.isArmed },
            "the ARMED row alone carries the flag — the arm, not the derived next",
        )
        assertEquals(listOf(true, false, false), rows.map { it.isPlaying }, "playing and armed stay distinct flags")
    }

    @Test
    fun aDerivedNextAloneIsNeverAnArm() {
        // The FFI's armed id is the model's next GATED on next_explicit —
        // a derived next (the idle player's queue-head pick) must not
        // badge any row. The engine snapshot below carries no armed id,
        // which is exactly what the FFI reports for a derived next.
        val rows =
            LibraryViewModel.visibleRows(
                listOf(playlistEntry(id = 1u, title = "Nocturne")),
                engineState(status = "stopped"),
                query = "",
            )

        assertEquals(listOf(false), rows.map { it.isArmed }, "no armed id in the snapshot: no row is badged")
    }

    @Test
    fun whileNoIdIsCarriedThePlayingFlagFallsBackToTheDocumentedPathJoin() {
        // The id-absent shape: the FFI reports the id only for an on-air
        // entry, so the path fallback documents the precedence — a total
        // mapping, never a second guess over an id that already answered.
        val rows =
            LibraryViewModel.visibleRows(
                listOf(
                    playlistEntry(id = 1u, path = "/music/a.flac"),
                    playlistEntry(id = 2u, path = "/music/b.flac"),
                ),
                engineState(status = "playing", track = track(path = "/music/b.flac")),
                query = "",
            )

        assertEquals(
            listOf(false, true),
            rows.map { it.isPlaying },
            "no id carried: the row with the engine's track path is flagged",
        )
    }

    @Test
    fun aBlankRowTitleStaysBlankForTheRenderLayerToMark() {
        makeViewModel(playlist = listOf(playlistEntry(id = 3u, title = "")))
        scheduler.runCurrent()

        // The state layer carries the RAW title (never an invented
        // marker); TrackRow renders the i18n seam's unknown-marker key
        // for a blank — the Task-5 migration moved the em dash from
        // here to the draw, so a locale can mark it its own way.
        val rows = viewModel.uiState.value.rows
        assertEquals("", rows.single().title)
    }

    // ------------------------------------------------------------------
    // Search: the query drives the visible rows (client-side)
    // ------------------------------------------------------------------

    @Test
    fun onQueryChangeFiltersTheVisibleRowsAndCarriesTheRawQueryBack() {
        makeViewModel(
            playlist =
                listOf(
                    playlistEntry(id = 1u, title = "Nocturne"),
                    playlistEntry(id = 2u, title = "Sea Breeze"),
                ),
        )
        scheduler.runCurrent()

        viewModel.onQueryChange("OCT")
        scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("OCT", state.query, "the raw query rides back out — the TextField's value")
        assertEquals<List<ULong>>(listOf(1u), state.rows.map { it.id })
        assertEquals(2, state.queueEntryCount, "the queue count stays unfiltered — the two empties stay distinct")

        viewModel.onQueryChange("")
        scheduler.runCurrent()
        val cleared = viewModel.uiState.value
        assertEquals<List<ULong>>(listOf(1u, 2u), cleared.rows.map { it.id })
    }

    // ------------------------------------------------------------------
    // The row tap: ARM-ONLY (the Task-2 carry)
    // ------------------------------------------------------------------

    @Test
    fun aRowTapArmsTheEntryAsNextWithoutStartingPlayback() {
        makeViewModel(
            initial = engineState(status = "stopped"),
            playlist = listOf(playlistEntry(id = 7u, title = "Armed")),
        )
        scheduler.runCurrent()

        viewModel.onRowTap(7u)
        scheduler.runCurrent()

        // ARM-ONLY: set_next arms the entry as the explicit next; a
        // stopped player stays stopped (no play issued), and the queue
        // rows never move.
        assertEquals<List<ULong>>(listOf(7u), engine.setNextCalls)
        assertEquals(0, engine.playCalls.get())
        assertNull(viewModel.uiState.value.error)
        // The arm the tap made is VISIBLE: the double applies the arm
        // (the conductor would land it within its bounded window); one
        // poll period carries the armed id into the pipeline, and the
        // tapped row is badged.
        scheduler.advanceTimeBy(150)
        scheduler.runCurrent()
        assertTrue(
            viewModel.uiState.value.rows
                .single()
                .isArmed,
            "the tap's arm lands on the row — the Phase-4 invisible-tap gap, closed",
        )
    }

    // ------------------------------------------------------------------
    // The import intent: Importing → Imported / Failed
    // ------------------------------------------------------------------

    @Test
    fun aPickPublishesImportingThenImportedWithTheQueuedPath() {
        val gate = CompletableDeferred<String?>()
        makeViewModel { gate.await() }

        viewModel.onImportUri("content://picked/1")
        scheduler.runCurrent()

        assertEquals(LibraryImport.Importing, viewModel.uiState.value.import)

        gate.complete("/data/import/6f2c.flac")
        scheduler.runCurrent()

        assertEquals(LibraryImport.Imported("/data/import/6f2c.flac"), viewModel.uiState.value.import)
        assertEquals(1, engine.enqueueCalls.size, "the successful copy IS enqueued")
        val state = viewModel.uiState.value
        assertEquals<List<ULong>>(listOf(1u), state.rows.map { it.id }, "the pipeline published the new row — id from the playlist flow")
        assertEquals("6f2c", state.rows.single().title, "the model's stem-derived title")
        assertEquals(1, state.queueEntryCount)
    }

    @Test
    fun aFailedCopySurfacesTheRepositorysMessageAndEnqueuesNothing() {
        makeViewModel(storageAnswer = { null })

        viewModel.onImportUri("content://picked/2")
        scheduler.runCurrent()

        // The never-enqueue-a-failed-copy contract is MediaRepository's
        // own (pinned by the shared suite); here it is consumed: no
        // enqueue call, no row, and the honest cause on the state.
        assertEquals(
            LibraryImport.Failed(UiText.Raw("nothing was copied from content://picked/2 — the queue was left untouched")),
            viewModel.uiState.value.import,
        )
        assertTrue(engine.enqueueCalls.isEmpty(), "a failed copy never reaches the engine")
        assertEquals(0, viewModel.uiState.value.queueEntryCount)
    }

    @Test
    fun aSecondPickWhileImportingIsIgnored() {
        val gate = CompletableDeferred<String?>()
        makeViewModel { gate.await() }

        viewModel.onImportUri("content://picked/first")
        scheduler.runCurrent()
        viewModel.onImportUri("content://picked/second")
        scheduler.runCurrent()
        gate.complete("/data/import/first.flac")
        scheduler.runCurrent()

        assertEquals(LibraryImport.Imported("/data/import/first.flac"), viewModel.uiState.value.import)
        assertEquals(1, engine.enqueueCalls.size, "only the first pick ran; the in-flight import was not raced")
        assertEquals("/data/import/first.flac", engine.enqueueCalls.single().path)
    }

    @Test
    fun onDismissImportResultReturnsTheImportStateToIdle() {
        makeViewModel()

        viewModel.onImportPermissionDenied()
        scheduler.runCurrent()
        viewModel.onDismissImportResult()
        scheduler.runCurrent()

        assertEquals(LibraryImport.Idle, viewModel.uiState.value.import)
    }

    // ------------------------------------------------------------------
    // The permission gate + error surfacing (never a crash)
    // ------------------------------------------------------------------

    @Test
    fun aPermissionRefusalSurfacesTheHonestMessageInsteadOfCrashing() {
        makeViewModel()

        viewModel.onImportPermissionDenied()
        scheduler.runCurrent()

        assertEquals(
            LibraryImport.Failed(LibraryViewModel.PERMISSION_DENIED_MESSAGE),
            viewModel.uiState.value.import,
        )
    }

    @Test
    fun aBusyJumpSurfacesTheErrorAndTheNextIntentClearsIt() {
        makeViewModel(playlist = listOf(playlistEntry(id = 4u)))
        scheduler.runCurrent()
        engine.setNextError = FausteException.Busy()

        viewModel.onRowTap(4u)
        scheduler.runCurrent()

        // KEY-BASED (Task 5): the state carries the bundle's message id,
        // never rendered copy — the suite asserts the id, and the
        // resolver suites prove what it renders.
        assertEquals(UiText.Key(StringKeys.PLAYER_BUSY), viewModel.uiState.value.error)

        // No crash: the next intent runs and clears the error.
        viewModel.onQueryChange("oct")
        scheduler.runCurrent()

        assertNull(viewModel.uiState.value.error)
    }

    /**
     * The copy-seam double, mirroring the shared module's MediaRepositoryTest
     * fixture: canned answers through the sanctioned `open`-class seam,
     * zero Android API calls.
     */
    private class CannedStorage(
        private val answer: suspend (String) -> String?,
    ) : Storage(ContextWrapper(null)) {
        override suspend fun importToAppStorage(uri: String): String? = answer(uri)
    }
}
