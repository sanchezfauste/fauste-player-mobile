package org.fauste.player.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.fauste.player.player.CommandResult
import org.fauste.player.player.PlaybackState
import org.fauste.player.player.PlaybackStatus
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.rust.PlaylistEntry
import org.fauste.player.ui.strings.StringKeys
import org.fauste.player.ui.strings.UiText
import kotlin.math.abs

/**
 * THE SEEK THROTTLE (the Task-1 review carry), resolved as
 * **seek-commit-on-stop via a ~200 ms trailing debounce**:
 *
 * [PlayerControls][org.fauste.player.ui.components.PlayerControls] fires
 * [onSeek] continuously during a drag, and its stateless contract carries no
 * drag-end callback — so "stop" is INFERRED. Each tick publishes the new
 * position into a pending flow (instant UI feedback: the slider tracks the
 * finger with no engine round-trip) and re-arms ONE debounced commit. A
 * continuous drag therefore reaches the engine zero times; when the last
 * tick has been silent for [DRAG_COMMIT_DEBOUNCE_MILLIS], exactly one commit
 * with the latest position goes out. Volume drags get the same treatment
 * ([onVolume]): its slider fires continuously too, and flooding the
 * conductor's command queue with set_volume commands risks Busy-dropping the
 * final value — the one that matters.
 *
 * After an ACCEPTED commit the pending value STAYS until the engine's polled
 * position reaches it ([SEEK_SETTLE_TOLERANCE_SECONDS]): the conductor
 * applies the seek asynchronously, so showing the accepted target is more
 * truthful than the stale poll, and the bar does not snap back during the
 * apply window. A bounded settle timeout keeps a wedged engine from pinning
 * the UI at a position it never took — either way the bar returns to the
 * engine's truth. A Busy/Failed commit does the opposite immediately: the
 * dropped target stops driving the UI (never a lying seek bar) and the
 * outcome lands in [PlayerUiState.error].
 *
 * Threading / resolution: every engine call happens inside coroutines
 * launched on the injected [commandDispatcher] — nothing engine-adjacent
 * touches the main thread. The state layer arrives as kotlin [Lazy] handles
 * (Koin `inject()`) on purpose: koinViewModel() constructs the ViewModel on
 * the composition thread, and the engine's first Koin resolution is heavy
 * (its UniFFI constructor loads the store and spawns threads — the shared
 * AppModule's documented rule: that first resolution MUST happen off the
 * main thread). Deferring `.value` into the first dispatcher coroutine makes
 * the rule hold by construction instead of by a prewarm race.
 *
 * Errors: the latest command outcome — a Busy drop ("retrying is safe", the
 * FFI's own contract) or a Failed message — surfaces in [PlayerUiState.error]
 * and is cleared by the next intent, whatever its outcome. Never a crash:
 * the state layer maps engine exceptions to [CommandResult]; cancellation
 * always propagates.
 *
 * UDF (spec §5): state down through [uiState], intents up through the
 * `on*` functions; PlayerScreen holds no playback logic.
 */
class PlayerViewModel(
    private val controller: Lazy<PlayerController>,
    private val queue: Lazy<QueueManager>,
    private val repository: Lazy<PlaylistRepository>,
    private val commandDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState(PlaybackState()))

    /** The Player screen's single state stream — see [PlayerUiState]. */
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Not backing properties (nothing exposes them under the same name):
    // plain private flows the uiState pipeline combines — hence no underscore.
    private val pendingSeekPosition = MutableStateFlow<Double?>(null)
    private val pendingVolume = MutableStateFlow<Float?>(null)
    private val lastError = MutableStateFlow<UiText?>(null)

    private var seekCommitJob: Job? = null
    private var volumeCommitJob: Job? = null

    init {
        // The composition pipeline: the plan-mandated mapping runs here, on
        // the command dispatcher (which is also where the lazy state layer
        // first resolves). Eager-started; the VM owns it for its lifetime.
        // The queue flow joins for the up-next line: the armed id comes from
        // the engine snapshot, its TITLE from the queue rows.
        viewModelScope.launch(commandDispatcher) {
            combine(
                controller.value.engineState,
                repository.value.playlist,
                pendingSeekPosition,
                pendingVolume,
                lastError,
            ) { engineState, playlist, pendingSeek, pendingVolume, error ->
                PlayerUiState(
                    playback = PlaybackState.fromEngineState(engineState),
                    pendingSeekPosition = pendingSeek,
                    pendingVolume = pendingVolume,
                    upNextTitle = upNextTitle(engineState.armedEntryId, playlist),
                    error = error,
                )
            }.collect { _uiState.value = it }
        }
    }

    /** Plays when the engine reports stopped/paused, pauses when it reports playing. */
    fun onPlayPause() {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            val playing =
                PlaybackStatus.fromEngine(controller.value.engineState.value.status) == PlaybackStatus.Playing
            val result =
                if (playing) {
                    repository.value.pause()
                } else {
                    repository.value.play()
                }
            lastError.value = message(result)
        }
    }

    /** A drag tick — publishes the pending position and re-arms the debounced commit (the class KDoc's throttle). */
    fun onSeek(positionSeconds: Double) {
        lastError.value = null
        pendingSeekPosition.value = positionSeconds
        seekCommitJob?.cancel()
        seekCommitJob =
            viewModelScope.launch(commandDispatcher) {
                delay(DRAG_COMMIT_DEBOUNCE_MILLIS)
                commitPendingSeek()
            }
    }

    /** A volume drag tick — same trailing-debounce shape as [onSeek] (the class KDoc explains why). */
    fun onVolume(volume: Float) {
        lastError.value = null
        pendingVolume.value = volume
        volumeCommitJob?.cancel()
        volumeCommitJob =
            viewModelScope.launch(commandDispatcher) {
                delay(DRAG_COMMIT_DEBOUNCE_MILLIS)
                commitPendingVolume()
            }
    }

    /** Queues the transport grid's Previous button — a no-op unless playing (the model's rule). */
    fun onPrevious() {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            lastError.value = message(repository.value.previous())
        }
    }

    /**
     * ARM-ONLY queue jump (the desktop's row double-click, delegated to
     * [QueueManager.jump]): arms [entryId] as the player's explicit next
     * WITHOUT touching the transport — what is on air plays to its end and
     * the engine then starts the armed entry; a stopped player stays
     * stopped, and its next play starts it. The switch is observed through
     * the engine state, never the queue rows (they do not move).
     */
    fun onJump(entryId: ULong) {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            lastError.value = message(queue.value.jump(entryId))
        }
    }

    /** Commits the pending seek unless a newer tick already replaced/cleared it. */
    private suspend fun commitPendingSeek() {
        val target = pendingSeekPosition.value ?: return
        when (val result = repository.value.seek(target)) {
            CommandResult.Accepted -> awaitEngineAtPosition(target)
            CommandResult.Busy, is CommandResult.Failed -> {
                // Dropped or refused: the target was never applied, so it
                // must stop driving the UI — back to engine truth + the why.
                pendingSeekPosition.value = null
                lastError.value = message(result)
            }
        }
    }

    private suspend fun commitPendingVolume() {
        val target = pendingVolume.value ?: return
        when (val result = controller.value.setVolume(target)) {
            CommandResult.Accepted -> awaitEngineAtVolume(target)
            CommandResult.Busy, is CommandResult.Failed -> {
                pendingVolume.value = null
                lastError.value = message(result)
            }
        }
    }

    /**
     * Holds the pending seek until the engine's polled position reaches
     * [targetSeconds] (an accepted seek applies within a bounded window),
     * then releases it — or releases it on timeout so a wedged engine cannot
     * pin the bar at a position it never took. Either way the UI returns to
     * the engine's truth.
     */
    private suspend fun awaitEngineAtPosition(targetSeconds: Double) {
        withTimeoutOrNull(SEEK_SETTLE_TIMEOUT_MILLIS) {
            controller.value.engineState
                .map { it.position }
                .takeWhile { abs(it - targetSeconds) > SEEK_SETTLE_TOLERANCE_SECONDS }
                .collect { }
        }
        pendingSeekPosition.value = null
    }

    private suspend fun awaitEngineAtVolume(target: Float) {
        withTimeoutOrNull(VOLUME_SETTLE_TIMEOUT_MILLIS) {
            controller.value.engineState
                .map { it.volume }
                .takeWhile { abs(it - target) > VOLUME_SETTLE_TOLERANCE }
                .collect { }
        }
        pendingVolume.value = null
    }

    /** Maps a command outcome onto the error channel: null on success, a message id or raw cause on failure. */
    private fun message(result: CommandResult): UiText? =
        when (result) {
            CommandResult.Accepted -> null
            CommandResult.Busy -> BUSY_MESSAGE
            is CommandResult.Failed -> UiText.Raw(result.message)
        }

    companion object {
        /** The trailing debounce: ~200 ms of silence after the last drag tick reads as "drag stopped". */
        const val DRAG_COMMIT_DEBOUNCE_MILLIS: Long = 200

        /**
         * The up-next line's title (the class KDoc's contract): the ARMED
         * row's title by id as raw text; the bundle's unknown-marker KEY
         * while the armed row is not in the queue flow (the
         * remote-mutation staleness corner — the honest unknown, never a
         * fake title, resolved to the user's language at render time);
         * null while nothing is armed (the line hides — the surface
         * carries no derived-next read, so a rendered line would be a
         * play-now promise).
         */
        internal fun upNextTitle(
            armedEntryId: ULong?,
            playlist: List<PlaylistEntry>,
        ): UiText? =
            armedEntryId?.let { id ->
                playlist
                    .firstOrNull { entry -> entry.id == id }
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                    ?.let { title -> UiText.Raw(title) }
                    ?: UNKNOWN_UP_NEXT_TITLE
            }

        /** The honest unknown marker id for an armed row the queue flow cannot place (the state-layer id). */
        val UNKNOWN_UP_NEXT_TITLE: UiText = UiText.Key(StringKeys.UNKNOWN_VALUE)

        /** How long an accepted seek may take to show in the polled state (mirrors the state layer's bounded confirm window). */
        const val SEEK_SETTLE_TIMEOUT_MILLIS: Long = 2_000

        /** The engine counts as "at the target" within a quarter second (a poll period of playback advance, plus rounding). */
        const val SEEK_SETTLE_TOLERANCE_SECONDS: Double = 0.25

        /** The volume settle window, same rationale as [SEEK_SETTLE_TIMEOUT_MILLIS]. */
        const val VOLUME_SETTLE_TIMEOUT_MILLIS: Long = 2_000

        /** Volume echo-release tolerance: one f32 round-trip's worth of slack. */
        const val VOLUME_SETTLE_TOLERANCE: Float = 0.005f

        /** The Busy channel's user-facing message id — a dropped command is safe to retry (the FFI's contract). */
        val BUSY_MESSAGE: UiText = UiText.Key(StringKeys.PLAYER_BUSY)
    }
}
