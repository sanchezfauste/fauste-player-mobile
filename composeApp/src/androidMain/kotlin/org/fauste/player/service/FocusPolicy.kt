package org.fauste.player.service

import org.fauste.player.player.PlaybackStatus

/**
 * THE FOCUS POLICY (Phase 5, Ruling 1, as a pure state machine): the
 * total mapping from focus events + the observed engine status onto
 * the ACTIONS the service must execute — zero Android imports, so the
 * full matrix is host-pinned by [FocusPolicyTest] and the service-side
 * [AudioFocusController] is only plumbing over it.
 *
 * RULING 1, RESTATED AS CELLS:
 * - an [FocusEvent.RequestGranted] answers the request the policy
 *   itself emitted on the first Playing observation (acquire on first
 *   Play; the service must exist to hold focus, so the acquisition
 *   hook is the service's own engine-state observation, not the play
 *   command);
 * - [FocusEvent.FocusLost] → [FocusAction.Pause], and NO auto-resume
 *   ever follows a full loss (the platform does not even deliver a
 *   GAIN after one — a fresh request, which only the next Playing
 *   observation makes, is the only way back);
 * - [FocusEvent.FocusLostTransient] → [FocusAction.PauseAndResumeOnGain]
 *   — the phone-call mirror: pause now, set the resume-when-regained
 *   flag, resume on the GAIN. The flag is armed ONLY when the engine
 *   was playing at loss time (a transient over an already-paused deck
 *   must not auto-resume a deck the user never had playing);
 * - [FocusEvent.FocusLostTransientCanDuck] → [FocusAction.Duck] of the
 *   configured factor (0.2): duck the ENGINE's volume through the same
 *   `Command::SetVolume` path the UI's fader rides, and restore on
 *   regain. The duck is emitted regardless of the play state — a paused
 *   deck that the user resumes mid-prompt must come up DUCKED, not
 *   over the prompt. A second can-duck while ducked is a NO-OP: the
 *   duck is a multiply of the duck-time volume, and re-applying would
 *   compound (0.2 × 0.2 = 0.04);
 * - [FocusEvent.FocusGained] → [FocusAction.Resume] ONLY if the
 *   transient flag is set (never on a plain regain), plus
 *   [FocusAction.Unduck] when a duck is live.
 *
 * THE TWO JUDGMENTS BEYOND THE RULING'S LETTER (documented, judged):
 * - **The deferred play.** While transiently displaced, a user resume
 *   re-requests (the honest request-and-see). A DENIED re-request
 *   pauses again — but KEEPS the resume flag: the user's play was
 *   deferred by the system, not cancelled, so the regain honors the
 *   last expressed intent (the call ends → the music the user pressed
 *   play for comes back). The alternative — treating a denial as
 *   intent cancellation — would leave the user pressing play into a
 *   dead deck every time a call outlasts their patience.
 * - **The user pause cancels the flag.** The policy tracks whether a
 *   pause it emitted has been observed ([pendingOwnPause]): the first
 *   Paused observation after our own transient pause is consumed
 *   (it confirms OUR action, the flag stays); a Paused observation we
 *   did NOT cause clears the flag — the user took control, and the
 *   regain must not startle them with a resume. Honest limit, pinned
 *   in the matrix suite: the service-side collector observes STATUS
 *   transitions, so a pause issued while already paused is invisible —
 *   the flag can only be cancelled by an engine transition someone
 *   caused, never by a repeated no-op tap.
 *
 * DUCK LIFECYCLE (why UNDUCK rides every exit): the engine model's
 * volume is per-PLAYER state that SURVIVES Stop (`stop_player` never
 * touches `player.volume`, fp-model/src/reducer.rs), so a duck left
 * un-restored would bleed into the next playback at 0.2×. Every path
 * that leaves the ducked states — a regain, a full loss, the engine
 * stop, teardown — therefore restores first. A duck also SURVIVES a
 * transient escalation (prompt → call): one restore at the end, not a
 * restore/re-duck churn in the middle.
 *
 * THREADING: the entry points are `@Synchronized` because the two
 * arrival paths — the engine-status collector and the focus-listener
 * hop — run as separate coroutines on the service's IO scope and can
 * interleave; each call must see a consistent (state, bookkeeping)
 * pair. Event ORDERING between the two sources is inherently racy (a
 * focus change may be observed before the status flip it answered);
 * every cell re-evaluates against the LATEST observed status, so any
 * interleaving converges on the same fixed point. Event-EVENT
 * ordering is racy in the same microscopic way — two rapid listener
 * deliveries hop through separate `scope.launch`es on the IO pool,
 * and dispatch order is not contractually FIFO — a window whose worst
 * case recovers with one user tap.
 */
internal class FocusPolicy(
    /** The duck multiply — 0.2 by default, a constant documented as configurable-not-yet-exposed. */
    private val duckFactor: Float = DEFAULT_DUCK_FACTOR,
) {
    /**
     * The policy's phase. INTERNAL for the matrix suite: the tests
     * assert both the actions AND the state each cell lands in.
     */
    internal val state: FocusState
        get() = stateField

    private var stateField: FocusState = FocusState.NoFocus

    /** The engine's last observed status — the play-state input every loss cell reads. */
    private var lastStatus: PlaybackStatus = PlaybackStatus.Stopped

    /** A request the policy emitted is at the platform, unanswered. */
    private var requestPending: Boolean = false

    /**
     * A pause the POLICY emitted, not yet observed in the engine's
     * status — the bookkeeping that separates "our pause confirmed"
     * from "a pause someone else caused" (the flag-cancel rule).
     */
    private var pendingOwnPause: Boolean = false

    /**
     * One engine-status observation — the collector feeds only
     * DISTINCT transitions (the controller dedupes, mirroring the
     * service starter); the raw contract here accepts any sequence.
     */
    @Synchronized
    fun onPlayback(status: PlaybackStatus): List<FocusAction> {
        when (status) {
            PlaybackStatus.Playing -> pendingOwnPause = false
            PlaybackStatus.Paused ->
                if (pendingOwnPause) {
                    pendingOwnPause = false
                } else {
                    clearResumeFlag()
                }
            PlaybackStatus.Stopped -> pendingOwnPause = false
        }
        lastStatus = status
        return when (stateField) {
            FocusState.NoFocus ->
                when (status) {
                    PlaybackStatus.Playing ->
                        if (requestPending) {
                            listOf(FocusAction.None)
                        } else {
                            requestPending = true
                            listOf(FocusAction.RequestFocus)
                        }
                    else -> listOf(FocusAction.None)
                }
            FocusState.Holding ->
                when (status) {
                    PlaybackStatus.Stopped -> abandon()
                    else -> listOf(FocusAction.None)
                }
            FocusState.Ducked ->
                when (status) {
                    PlaybackStatus.Stopped -> abandonWithUnduck()
                    else -> listOf(FocusAction.None)
                }
            is FocusState.TransientLost ->
                when (status) {
                    PlaybackStatus.Playing ->
                        if (requestPending) {
                            listOf(FocusAction.None)
                        } else {
                            requestPending = true
                            listOf(FocusAction.RequestFocus)
                        }
                    PlaybackStatus.Stopped -> abandon()
                    PlaybackStatus.Paused -> listOf(FocusAction.None)
                }
            is FocusState.TransientLostDucked ->
                when (status) {
                    PlaybackStatus.Playing ->
                        if (requestPending) {
                            listOf(FocusAction.None)
                        } else {
                            requestPending = true
                            listOf(FocusAction.RequestFocus)
                        }
                    PlaybackStatus.Stopped -> abandonWithUnduck()
                    PlaybackStatus.Paused -> listOf(FocusAction.None)
                }
        }
    }

    /** One platform focus event — a request result or a listener change. */
    @Synchronized
    fun onEvent(event: FocusEvent): List<FocusAction> {
        val isPlaying = lastStatus == PlaybackStatus.Playing
        return when (stateField) {
            FocusState.NoFocus ->
                when (event) {
                    FocusEvent.RequestGranted ->
                        if (requestPending) {
                            requestPending = false
                            stateField = FocusState.Holding
                            listOf(FocusAction.None)
                        } else {
                            // A late grant for a request the stop/teardown
                            // path already gave up on — release it; never
                            // hold focus the policy can no longer want.
                            listOf(FocusAction.Abandon)
                        }
                    FocusEvent.RequestFailed -> {
                        requestPending = false
                        pauseAgainIfPlaying(isPlaying)
                    }
                    // No standing request exists in NoFocus: listener
                    // events here are strays (or the grant echo arriving
                    // before the request result — inert either way).
                    else -> listOf(FocusAction.None)
                }
            FocusState.Holding ->
                when (event) {
                    FocusEvent.RequestGranted, FocusEvent.RequestFailed, FocusEvent.FocusGained ->
                        listOf(FocusAction.None)
                    FocusEvent.FocusLost -> addPauseIfPlaying(isPlaying) + abandon()
                    FocusEvent.FocusLostTransient -> {
                        stateField = FocusState.TransientLost(resumeOnGain = isPlaying)
                        if (isPlaying) {
                            pendingOwnPause = true
                            listOf(FocusAction.PauseAndResumeOnGain)
                        } else {
                            listOf(FocusAction.None)
                        }
                    }
                    FocusEvent.FocusLostTransientCanDuck -> {
                        stateField = FocusState.Ducked
                        listOf(FocusAction.Duck(duckFactor))
                    }
                }
            FocusState.Ducked ->
                when (event) {
                    FocusEvent.RequestGranted, FocusEvent.RequestFailed -> listOf(FocusAction.None)
                    FocusEvent.FocusGained -> unduckToHolding()
                    FocusEvent.FocusLost ->
                        addPauseIfPlaying(isPlaying) + abandonWithUnduck()
                    FocusEvent.FocusLostTransient -> {
                        stateField = FocusState.TransientLostDucked(resumeOnGain = isPlaying)
                        if (isPlaying) {
                            pendingOwnPause = true
                            listOf(FocusAction.PauseAndResumeOnGain)
                        } else {
                            listOf(FocusAction.None)
                        }
                    }
                    // Already ducked: a re-duck would compound the multiply.
                    FocusEvent.FocusLostTransientCanDuck -> listOf(FocusAction.None)
                }
            is FocusState.TransientLost ->
                when (event) {
                    FocusEvent.RequestGranted -> {
                        requestPending = false
                        stateField = FocusState.Holding
                        listOf(FocusAction.None)
                    }
                    FocusEvent.RequestFailed -> {
                        requestPending = false
                        // The deferred play: pause again, but the flag
                        // survives so the eventual regain resumes.
                        pauseAgainIfPlaying(isPlaying)
                    }
                    FocusEvent.FocusGained -> {
                        val resume = (stateField as FocusState.TransientLost).resumeOnGain
                        stateField = FocusState.Holding
                        if (resume) listOf(FocusAction.Resume) else listOf(FocusAction.None)
                    }
                    FocusEvent.FocusLost -> addPauseIfPlaying(isPlaying) + abandon()
                    // Already displaced: nested transients stack on the
                    // deeper holder; the unwind still ends at our GAIN.
                    FocusEvent.FocusLostTransient,
                    FocusEvent.FocusLostTransientCanDuck,
                    -> listOf(FocusAction.None)
                }
            is FocusState.TransientLostDucked ->
                when (event) {
                    FocusEvent.RequestGranted -> {
                        requestPending = false
                        stateField = FocusState.Holding
                        // The steal-back displaced the can-duck holder —
                        // the duck's reason is gone.
                        listOf(FocusAction.Unduck)
                    }
                    FocusEvent.RequestFailed -> {
                        requestPending = false
                        pauseAgainIfPlaying(isPlaying)
                    }
                    FocusEvent.FocusGained -> {
                        val resume = (stateField as FocusState.TransientLostDucked).resumeOnGain
                        stateField = FocusState.Holding
                        if (resume) {
                            listOf(FocusAction.Unduck, FocusAction.Resume)
                        } else {
                            listOf(FocusAction.Unduck)
                        }
                    }
                    FocusEvent.FocusLost ->
                        addPauseIfPlaying(isPlaying) + abandonWithUnduck()
                    FocusEvent.FocusLostTransient,
                    FocusEvent.FocusLostTransientCanDuck,
                    -> listOf(FocusAction.None)
                }
        }
    }

    /**
     * The service's teardown (onDestroy and any belt-and-braces exit):
     * abandon from ANY state — restoring a live duck first (the
     * model's volume survives Stop) — and reset every flag. The
     * platform abandon is idempotent, so a repeated teardown only
     * re-emits the harmless [FocusAction.Abandon].
     */
    @Synchronized
    fun teardown(): List<FocusAction> {
        requestPending = false
        pendingOwnPause = false
        val actions =
            when (stateField) {
                FocusState.Ducked, is FocusState.TransientLostDucked -> listOf(FocusAction.Unduck)
                else -> emptyList()
            }
        stateField = FocusState.NoFocus
        return actions + FocusAction.Abandon
    }

    // ------------------------------------------------------------ internals

    /** The exit to NoFocus from an unducked holding shape. */
    private fun abandon(): List<FocusAction> {
        stateField = FocusState.NoFocus
        return listOf(FocusAction.Abandon)
    }

    /** The ducked exit: restore the volume first, then abandon. */
    private fun abandonWithUnduck(): List<FocusAction> {
        stateField = FocusState.NoFocus
        return listOf(FocusAction.Unduck, FocusAction.Abandon)
    }

    /** A regain while granted-but-ducked: restore the volume, stay held. */
    private fun unduckToHolding(): List<FocusAction> {
        stateField = FocusState.Holding
        return listOf(FocusAction.Unduck)
    }

    /** The "pause again" of a denied request while the engine still plays. */
    private fun pauseAgainIfPlaying(isPlaying: Boolean): List<FocusAction> =
        if (isPlaying) {
            pendingOwnPause = true
            listOf(FocusAction.Pause)
        } else {
            listOf(FocusAction.None)
        }

    /**
     * Prepends the full-loss pause when the engine is still playing —
     * used over the abandon exits, whose action lists are built first
     * (pause the noise, then clean up).
     */
    private fun addPauseIfPlaying(isPlaying: Boolean): List<FocusAction> =
        if (isPlaying) {
            pendingOwnPause = true
            listOf(FocusAction.Pause)
        } else {
            emptyList()
        }

    /** The user-took-control rule: a Paused we did not cause disarms the auto-resume. */
    private fun clearResumeFlag() {
        when (stateField) {
            is FocusState.TransientLost ->
                stateField = FocusState.TransientLost(resumeOnGain = false)
            is FocusState.TransientLostDucked ->
                stateField = FocusState.TransientLostDucked(resumeOnGain = false)
            else -> Unit
        }
    }

    companion object {
        /**
         * The DUCK factor (Ruling 1): duck to 20 % of the current model
         * volume. A constant, deliberately NOT exposed in the UI this
         * phase — the factor is a policy tuning knob, constructor-
         * injectable for tests and any future setting.
         */
        const val DEFAULT_DUCK_FACTOR: Float = 0.2f
    }
}

/** The focus events the policy consumes: both request outcomes plus the listener's changes. */
internal enum class FocusEvent {
    /** `requestAudioFocus` answered `AUDIOFOCUS_REQUEST_GRANTED`. */
    RequestGranted,

    /** `requestAudioFocus` answered `AUDIOFOCUS_REQUEST_FAILED` (or anything not a grant). */
    RequestFailed,

    /** `AUDIOFOCUS_GAIN` — a regain after a transient loss (also the grant's listener echo). */
    FocusGained,

    /** `AUDIOFOCUS_LOSS` — permanent; no automatic regain ever follows. */
    FocusLost,

    /** `AUDIOFOCUS_LOSS_TRANSIENT` — pause + resume-when-regained. */
    FocusLostTransient,

    /** `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` — lower the volume, keep playing. */
    FocusLostTransientCanDuck,
}

/**
 * The actions the policy emits, executed in list order by
 * [AudioFocusController]. [None] is the brief's NONE — a cell that
 * demands nothing; it is never combined with other actions. The two
 * additions beyond the brief's list — [RequestFocus] and [Resume] —
 * are the structural carriers of Ruling 1's acquire-on-first-play and
 * resume-on-gain rules, which no other action can express.
 */
internal sealed interface FocusAction {
    /** Nothing to do. */
    data object None : FocusAction

    /** Acquire audio focus (the `AudioFocusRequest` at the platform). */
    data object RequestFocus : FocusAction

    /** `Command::Pause` over the controller command path. */
    data object Pause : FocusAction

    /** Pause now and arm the resume-when-regained flag (the phone-call mirror). */
    data object PauseAndResumeOnGain : FocusAction

    /** `Command::Play` over the controller command path — only ever on a flagged regain. */
    data object Resume : FocusAction

    /** Multiply the current model volume by [factor] through `Command::SetVolume`. */
    data class Duck(
        val factor: Float,
    ) : FocusAction

    /** Restore the volume stored at duck time. */
    data object Unduck : FocusAction

    /** Abandon the `AudioFocusRequest` (idempotent). */
    data object Abandon : FocusAction
}

/**
 * The policy's phases (the matrix rows). `internal` for the matrix
 * suite's state assertions. The [TransientLost] flag records whether
 * the ENGINE was playing at loss time — only a loss-of-playing arms
 * the auto-resume.
 */
internal sealed interface FocusState {
    /** Nothing held (fresh, post-abandon, post-full-loss, post-teardown). */
    data object NoFocus : FocusState

    /** Focus granted, not ducked, no resume flag. */
    data object Holding : FocusState

    /** Focus granted and a duck is live (the engine volume is ducked). */
    data object Ducked : FocusState

    /** Transiently displaced; [resumeOnGain] is the phone-call flag. */
    data class TransientLost(
        val resumeOnGain: Boolean,
    ) : FocusState

    /** Transiently displaced over a live duck — a can-duck that escalated. */
    data class TransientLostDucked(
        val resumeOnGain: Boolean,
    ) : FocusState
}
