package org.fauste.player.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.fauste.player.player.CommandResult
import org.fauste.player.player.PlayerController
import org.fauste.player.remote.RemoteServer
import org.fauste.player.rust.BackendInfo
import org.fauste.player.rust.FausteEngineInterface
import org.fauste.player.rust.RemoteInfo

/**
 * The settings surface that exists in Phase 3. Rust owns the config file
 * (the engine loads and persists it; the mobile side never reads or
 * writes it), so what remains is the backend list, backend selection,
 * volume, and the remote server's status. A thin wrapper — every member
 * delegates to the FFI, the controller or [RemoteServer]; the repository
 * keeps no books of its own.
 *
 * **Threading**: FFI reads and commands run on the injected
 * [dispatcher]; volume mirrors the controller's already-polled snapshot
 * instead of making an engine call of its own.
 */
class SettingsRepository(
    private val engine: FausteEngineInterface,
    private val controller: PlayerController,
    private val remote: RemoteServer,
    private val dispatcher: CoroutineDispatcher,
) {
    /**
     * The wired backends, probed at call time (the FFI's
     * `getAvailableBackends`): `"null"` is the always-present fallback;
     * `"aaudio"` reports its real availability — unavailable on the host
     * JVM and on devices without the virtual audio device.
     */
    suspend fun backends(): List<BackendInfo> = withContext(dispatcher) { engine.getAvailableBackends() }

    /**
     * Selects the output backend by [backendId] — the desktop settings
     * picker's exact path (ffi.rs `set_backend`: one `Command::UpdateConfig`
     * with only `outputs.backend` changed), a queued command applied when
     * the engine's paths are quiet. A known but currently unavailable
     * backend is still accepted: the engine falls back to what opens and
     * keeps retrying (`fp-engine/src/engine.rs:456-468`). Watch the
     * engine state's `backend` field for what is actually in use.
     */
    suspend fun selectBackend(backendId: String): CommandResult =
        withContext(dispatcher) {
            try {
                engine.setBackend(backendId)
                CommandResult.Accepted
            } catch (e: Exception) {
                CommandResult.fromException(e)
            }
        }

    /** The remote server's status, straight from [RemoteServer]'s flow. */
    val remoteState: StateFlow<RemoteInfo> = remote.state

    /**
     * Re-reads the remote record NOW and publishes it into [remoteState]
     * — the on-demand refresh a settings screen uses the moment it appears
     * and on an explicit refresh intent ([RemoteServer.refresh]'s own
     * contract). Unlike the slow poll's silent-failure channel, a failure
     * here is the caller's answer: mapped to [CommandResult.Failed] with
     * the real cause (the same honest mapping as [selectBackend]; a
     * destroyed engine lands here with UniFFI's message), while the flow
     * keeps the last good record either way.
     */
    suspend fun refreshRemote(): CommandResult =
        withContext(dispatcher) {
            try {
                remote.refresh()
                CommandResult.Accepted
            } catch (e: Exception) {
                CommandResult.fromException(e)
            }
        }

    /**
     * The current volume (linear gain 0.0–1.0), mirrored from the
     * controller's polled snapshot — no engine call of its own.
     */
    val volume: Float
        get() = controller.engineState.value.volume

    /**
     * Queues a volume change (linear gain 0.0–1.0). The effect shows up in
     * the engine's next poll: [volume] is a plain property over the
     * controller's snapshot, which always reflects the last engine state —
     * it is not observable. Consumers observe changes through the
     * controller's `engineState` flow, which this repository does not
     * expose; Phase 4 will surface it in the UI layer.
     */
    suspend fun setVolume(volume: Float): CommandResult = controller.setVolume(volume)
}
