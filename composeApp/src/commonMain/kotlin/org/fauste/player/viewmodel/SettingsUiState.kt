package org.fauste.player.viewmodel

import org.fauste.player.rust.BackendInfo
import org.fauste.player.rust.RemoteInfo
import org.fauste.player.ui.strings.UiText

/**
 * The Settings screen's UDF state, the third screen's mirror of
 * [PlayerUiState]/[LibraryUiState]. The defaults mirror the state
 * layer's own seeds (the controller's degenerate snapshot — backend
 * `"null"`, full volume — and [RemoteServer]'s pre-first-read
 * placeholder), so the pre-first-emission state is the same honest
 * placeholders the pipeline's first emission replaces within one period.
 *
 * Honest limits, surfaced rather than papered over:
 *
 * - [backends] is the ONE-SHOT probe's answer (`getAvailableBackends`,
 *   probed at call time — the desktop settings picker probes on open the
 *   same way), loaded when the screen appears; empty before the load
 *   lands or if the probe failed ([error] says which).
 * - [selectedBackendId] is the engine's OWN report of what is IN USE
 *   (`EngineState.backend`), not what was last requested: a switch the
 *   engine accepted is applied asynchronously, and a known-but-
 *   unavailable selection keeps the engine's fallback running while it
 *   retries (ffi.rs `set_backend`) — so the selected marker can lag a
 *   selection by a poll tick and can differ from it entirely.
 * - [volume]: the Phase-3 honest limit closed in the UI layer —
 *   [SettingsRepository] exposes only a non-observable snapshot, and its
 *   KDoc says Phase 4 surfaces volume by collecting the controller's
 *   engineState flow, which is exactly what the ViewModel does; the
 *   snapshot property stays for non-flow consumers.
 * - [remote] is [RemoteServer]'s record; a blank address while enabled
 *   is the FFI's retrying-bind shape (an error field is the Phase-5
 *   FFI candidate) — the screen renders the i18n seam's unknown marker
 *   for it, never a made-up address.
 *
 * [pendingVolume] is non-null while a volume drag (or its
 * committed-but-unconfirmed tail) is in flight — the screen draws
 * [sliderVolume] (the pending target, else the engine's) so the control
 * tracks the finger with no engine round-trip; see [SettingsViewModel]'s
 * throttle KDoc for the full semantics.
 */
data class SettingsUiState(
    /** The probed backends; empty until the one-shot load lands (or after a failed probe — see [error]). */
    val backends: List<BackendInfo> = emptyList(),
    /** The backend the engine reports IN USE (its snapshot's raw id; `"null"` until the engine reports one). */
    val selectedBackendId: String = "null",
    /** The engine's loopback remote record (enabled + address). */
    val remote: RemoteInfo = RemoteInfo(enabled = false, address = ""),
    /** The engine's polled volume (linear gain 0.0–1.0). */
    val volume: Float = 1.0f,
    /** The in-flight volume drag target, if any; drives [sliderVolume] and the pending feedback. */
    val pendingVolume: Float? = null,
    /** The latest command outcome — a Busy drop or a Failed cause as a locale-independent [UiText]; cleared by the next intent, whatever its outcome. */
    val error: UiText? = null,
) {
    /** The volume the slider shows: the pending drag target if any, else the engine's. */
    val sliderVolume: Float
        get() = pendingVolume ?: volume
}
