package org.fauste.player.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fauste.player.player.PlaybackStatus
import org.fauste.player.player.PlayerController
import kotlin.coroutines.cancellation.CancellationException

/**
 * THE APP-SIDE START TRIGGER (Task 6 deliverable 5): watches the
 * controller's engineState — the SAME state-layer flow the UI reads,
 * via Koin, never a ViewModel (they are UI-scoped and die with the
 * screen) — and asks [startPlaybackService] to bring the foreground
 * service up each time playback enters a new distinct Playing phase.
 *
 * WHY THIS TRIGGER POINT (the judgment the task asks to document):
 * - Observation-based, not command-injection-based: the service
 *   follows the ENGINE's truth, so a Busy-dropped or failed play
 *   command never starts a service for playback that never began —
 *   and playback started through ANY source (the app UI, the loopback
 *   HTTP remote) gets its notification. Watching a command would miss
 *   the remote entirely.
 * - [distinctUntilChanged] over the mapped status: one start per
 *   Playing PHASE, not one per poll tick (the flow emits ten times a
 *   second while playing). A resumed phase fires again — the real
 *   startForegroundService is idempotent on an already-running
 *   service, and re-firing is what heals a service the system killed
 *   mid-pause.
 * - Known limit, documented honestly: a service killed by the system
 *   WHILE playing leaves playback dead with it (process death kills
 *   the native engine). The system DOES restart the sticky service,
 *   which exits quietly with nothing to serve (PlaybackService's
 *   restart-semantics KDoc); the notification returns only with the
 *   next Play, whose distinct Playing phase re-arms this trigger (a
 *   paused notification swiped away already exits through media3's
 *   own dismissal path).
 * - A REFUSED start is log-and-skip, never fatal (the final review's
 *   F2 finding): API 31+ can throw
 *   `ForegroundServiceStartNotAllowedException` from behind this
 *   trigger (see [startForegroundServiceSafely] for the reasoning).
 *
 * The constructor resolves nothing: whoever builds it owns the
 * threading. FausteApplication arms it inside its application scope
 * (background dispatcher), so the controller's first Koin resolution
 * — the heavy lazy engine single — happens off the main thread, the
 * graph's documented rule.
 */
internal class PlaybackServiceStarter(
    controller: PlayerController,
    scope: CoroutineScope,
    private val startPlaybackService: () -> Unit,
) {
    @Suppress("unused") // the Job is the trigger's lifetime: ends with the app scope
    private val collector =
        scope.launch {
            controller.engineState
                .map { engineState -> PlaybackStatus.fromEngine(engineState.status) }
                .distinctUntilChanged()
                .collect { status ->
                    if (serviceTriggerFor(status) == ServiceTrigger.StartForeground) {
                        startForegroundServiceSafely()
                    }
                }
        }

    /**
     * THE FINAL-REVIEW F2 GUARD: a refused foreground start is
     * log-and-skip, never process-fatal. This trigger fires on every
     * distinct Playing phase — including a remote-driven pause → play
     * while backgrounded, the exact path the keep-awake gate holds the
     * poll open for — and on API 31+ a start landing outside an
     * FGS-start-allowed state makes `startForegroundService` throw
     * `ForegroundServiceStartNotAllowedException` (an
     * [IllegalStateException]); the app scope's SupervisorJob does NOT
     * swallow it, so unguarded it kills the process mid-playback.
     * Skipping is enough — mirroring the stop path's own log-and-exit
     * hardening choice: media3 re-promotes a RUNNING service on its
     * own, and the next foregrounded play re-arms this trigger, so a
     * skipped start costs at most one deferred notification, never
     * playback.
     *
     * [CancellationException] is rethrown FIRST: on the JVM it IS an
     * IllegalStateException (java.util.concurrent.CancellationException
     * extends it), so the guard below must never swallow a cancellation.
     */
    private fun startForegroundServiceSafely() {
        try {
            startPlaybackService()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Log.w(
                LOG_TAG,
                "startForegroundService refused (API 31+ start-not-allowed?) — skipping this start; the next distinct Playing phase re-arms",
                e,
            )
        }
    }

    private companion object {
        /** The trigger's logcat tag (the refusal guard's one honest log). */
        const val LOG_TAG = "FaustePlaybackStarter"
    }
}
