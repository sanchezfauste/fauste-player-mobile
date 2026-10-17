package org.fauste.player.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import org.fauste.player.player.PlayerController
import org.fauste.player.repository.SettingsRepository
import org.fauste.player.rust.BackendInfo
import kotlin.math.abs

/**
 * The Settings screen's UDF half, the third mirror of [PlayerViewModel]:
 * state down through [uiState], intents up through the `on*` functions —
 * no composable ever touches the engine or a repository. The single
 * composition pipeline in [init] maps the state layer's flows — the
 * controller's engineState (the selected backend + the observable
 * volume), the repository's remoteState — joined with the one-shot
 * backends list, the volume drag feedback and the error channel.
 *
 * THE VOLUME THROTTLE mirrors PlayerViewModel's proven seek-commit
 * shape verbatim (its KDoc is the full rationale): a slider fires
 * continuously during a drag, each tick publishes the pending value
 * (instant feedback — the control tracks the finger with no engine
 * round-trip) and re-arms ONE debounced commit; a continuous drag
 * reaches the engine zero times, and when the last tick has been
 * silent for [DRAG_COMMIT_DEBOUNCE_MILLIS] exactly one
 * [SettingsRepository.setVolume] with the latest value goes out —
 * flooding the conductor's queue would risk Busy-dropping the final
 * value, the one that matters. After an ACCEPTED commit the pending
 * value STAYS until the engine's polled volume reaches it (a bounded
 * settle window), so the control does not snap back during the apply
 * window; a Busy/Failed commit drops the target at once (never a
 * lying slider) and the outcome lands in [SettingsUiState.error].
 *
 * THE VOLUME OBSERVABILITY NOTE (Phase 3's documented limit, closed
 * here exactly as prescribed): [SettingsRepository] exposes volume as a
 * NON-observable snapshot property, and its KDoc says consumers
 * observe changes through the controller's engineState flow — "Phase 4
 * will surface it in the UI layer". This is that surfacing: the
 * pipeline collects the controller's flow, so the engine's volume —
 * and the selected backend — are live UI state; the repository remains
 * the COMMAND path (selectBackend/setVolume/refreshRemote).
 *
 * Threading: every engine/repository call runs inside
 * `viewModelScope.launch(commandDispatcher)`; the state layer arrives
 * as kotlin [Lazy] handles (Koin `inject()`) and first resolves inside
 * that dispatcher coroutine, so the engine's heavy first resolution
 * stays off the main thread by construction (the shared AppModule's
 * documented rule — see PlayerViewModel's KDoc).
 *
 * Errors never crash. Commands map to [CommandResult] in the layer; the
 * ONE catch in this class is [loadBackends]'s — the backends list is a
 * one-shot QUERY with no CommandResult-mapping sibling in the layer,
 * so its only consumer maps the failure onto the error channel plus an
 * empty list (the FFI's probe-at-call-time surface; a retry is a
 * re-appear, and a Phase-5 candidate is a re-probe on resume).
 * Cancellation always propagates: the catch rethrows it.
 */
class SettingsViewModel(
    private val repository: Lazy<SettingsRepository>,
    private val controller: Lazy<PlayerController>,
    private val commandDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())

    /** The Settings screen's single state stream — see [SettingsUiState]. */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Not backing properties (nothing exposes them under the same name):
    // plain private flows the uiState pipeline combines — hence no underscore.
    private val backends = MutableStateFlow<List<BackendInfo>>(emptyList())
    private val pendingVolume = MutableStateFlow<Float?>(null)
    private val lastError = MutableStateFlow<String?>(null)

    private var volumeCommitJob: Job? = null

    init {
        // The composition pipeline, on the command dispatcher (also where
        // the lazy state layer first resolves). Eager-started; the VM owns
        // it for its lifetime.
        viewModelScope.launch(commandDispatcher) {
            combine(
                controller.value.engineState,
                repository.value.remoteState,
                backends,
                pendingVolume,
                lastError,
            ) { engine, remote, backends, pendingVolume, error ->
                SettingsUiState(
                    backends = backends,
                    selectedBackendId = engine.backend,
                    remote = remote,
                    volume = engine.volume,
                    pendingVolume = pendingVolume,
                    error = error,
                )
            }.collect { _uiState.value = it }
        }
        // The screen's on-appear half: the remote refresh-on-appear
        // ([RemoteServer]'s documented on-demand contract — the cached
        // record can predate the screen by up to one slow-poll period)
        // and then the backends probe. Refresh first: the LAST outcome
        // wins the error channel, and a failed probe must not be cleared
        // by a refresh that runs after it.
        viewModelScope.launch(commandDispatcher) {
            lastError.value = message(repository.value.refreshRemote())
            loadBackends()
        }
    }

    /**
     * Selects the output backend by id. The FFI accepts a known but
     * currently unavailable backend (the engine falls back and keeps
     * retrying), but the screen only offers available rows — the
     * desktop settings picker's rule (ffi.rs `set_backend`). The switch
     * is applied asynchronously; the selected marker follows the engine's
     * own report in [SettingsUiState.selectedBackendId], never this
     * call's return.
     */
    fun onSelectBackend(backendId: String) {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            lastError.value = message(repository.value.selectBackend(backendId))
        }
    }

    /** A volume drag tick — publishes the pending value and re-arms the debounced commit (the class KDoc's throttle). */
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

    /** Re-reads the remote record now; a failure surfaces in [SettingsUiState.error] (the repository maps it). */
    fun onRefreshRemote() {
        viewModelScope.launch(commandDispatcher) {
            lastError.value = null
            lastError.value = message(repository.value.refreshRemote())
        }
    }

    /**
     * The one-shot backends probe, its failure mapped here (the class
     * KDoc's catch rationale) — THROUGH the layer's own
     * [CommandResult.fromException], so the surfaced cause is exactly
     * what a failed command surfaces everywhere else (the typed FFI
     * variants keep their payload in `v1`; a bare JVM exception degrades
     * to its message): an empty list plus the why, never a crash,
     * cancellation rethrown first.
     */
    private suspend fun loadBackends() {
        backends.value =
            try {
                repository.value.backends()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = message(CommandResult.fromException(e))
                emptyList()
            }
    }

    /** Commits the pending volume unless a newer tick already replaced/cleared it. */
    private suspend fun commitPendingVolume() {
        val target = pendingVolume.value ?: return
        when (val result = repository.value.setVolume(target)) {
            CommandResult.Accepted -> awaitEngineAtVolume(target)
            CommandResult.Busy, is CommandResult.Failed -> {
                // Dropped or refused: the target was never applied, so it
                // must stop driving the UI — back to engine truth + the why.
                pendingVolume.value = null
                lastError.value = message(result)
            }
        }
    }

    /**
     * Holds the pending volume until the engine's polled volume reaches
     * [target] (an accepted command applies within a bounded window),
     * then releases it — or releases it on timeout so a wedged engine
     * cannot pin the slider at a value it never took. Either way the UI
     * returns to the engine's truth.
     */
    private suspend fun awaitEngineAtVolume(target: Float) {
        withTimeoutOrNull(VOLUME_SETTLE_TIMEOUT_MILLIS) {
            controller.value.engineState
                .map { it.volume }
                .takeWhile { abs(it - target) > VOLUME_SETTLE_TOLERANCE }
                .collect { }
        }
        pendingVolume.value = null
    }

    /** Maps a command outcome onto the error channel: null on success, a human-readable message on failure. */
    private fun message(result: CommandResult): String? =
        when (result) {
            CommandResult.Accepted -> null
            CommandResult.Busy -> BUSY_MESSAGE
            is CommandResult.Failed -> result.message
        }

    companion object {
        /**
         * The trailing debounce, mirroring [PlayerViewModel.DRAG_COMMIT_DEBOUNCE_MILLIS]
         * (its KDoc's rationale: ~200 ms of silence after the last drag
         * tick reads as "drag stopped"). Duplicated rather than shared:
         * each VM owns its UX knobs per the established per-companion
         * pattern, and the value stays in lockstep by test.
         */
        const val DRAG_COMMIT_DEBOUNCE_MILLIS: Long = 200

        /** The volume settle window, the same rationale as PlayerViewModel's. */
        const val VOLUME_SETTLE_TIMEOUT_MILLIS: Long = 2_000

        /** Volume echo-release tolerance: one f32 round-trip's worth of slack. */
        const val VOLUME_SETTLE_TOLERANCE: Float = 0.005f

        /** The Busy channel's user-facing message — a dropped command is safe to retry (the FFI's contract). */
        const val BUSY_MESSAGE: String = "The player was busy and dropped the command — try again."
    }
}
