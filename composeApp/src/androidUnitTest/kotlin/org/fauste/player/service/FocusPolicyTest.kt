package org.fauste.player.service

import org.fauste.player.player.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE FOCUS POLICY'S FULL MATRIX (Ruling 1 as a state machine): every
 * policy state × every focus event, and every policy state × every
 * engine-status transition — pinned as the exact ACTION LISTS the
 * policy emits, plus the resulting state. This suite is the task's
 * core artifact: the service-side [AudioFocusController] is plumbing
 * over THIS table, and its own Robolectric lane pins only the
 * plumbing (the hop, the idempotence, the command dispatch).
 *
 * Every matrix cell runs on a FRESH policy driven through a canonical
 * setup transcript, so each pin is independent — no cell's outcome
 * depends on the cells asserted before it. The state-shape helpers
 * ([holding], [ducked], [transientLost], [transientLostDucked]) are
 * the documented reachability paths for each row.
 *
 * THE STATES (the rows, and how each is reached):
 * - [FocusState.NoFocus] — nothing held (fresh, post-abandon,
 *   post-full-loss), with or without a request in flight;
 * - [FocusState.Holding] — focus granted, not ducked, no resume flag
 *   (playing or paused: pause actions only fire for a PLAYING engine);
 * - [FocusState.Ducked] — a can-duck transient is active (playing or
 *   paused: the duck PRE-ARMS a mid-duck user resume — a paused deck
 *   that resumes mid-prompt must come up ducked, not over the prompt);
 * - [FocusState.TransientLost] — displaced by a transient holder; the
 *   flag records whether the ENGINE was playing at loss time (the
 *   phone-call mirror: only a loss-of-playing arms the auto-resume);
 * - [FocusState.TransientLostDucked] — a can-duck that escalated to a
 *   full transient (prompt → call): the duck SURVIVES the escalation
 *   and is restored at the eventual regain.
 *
 * THE JUDGMENTS THE MATRIX PINS (each documented in [FocusPolicy]'s
 * KDoc and argued in the task report):
 * - full LOSS never auto-resumes (its GAIN cell is inert — and the
 *   platform does not even deliver one: full loss requires a
 *   re-request, which only a fresh Playing observation makes);
 * - a denied re-request PAUSES AGAIN but KEEPS the resume flag — the
 *   user's play intent is deferred to the regain, not cancelled;
 * - a can-duck arriving while already ducked is a NO-OP (the duck is
 *   a multiply of the duck-time volume — re-applying would compound
 *   0.2 × 0.2 = 0.04);
 * - the engine's stop ABANDONS, restoring a live duck FIRST (the
 *   model's volume survives Stop — an unrestored duck would bleed
 *   into the next playback);
 * - the second Paused observation (a pause the policy did not cause)
 *   cancels the resume flag — the user took control.
 */
class FocusPolicyTest {
    // ------------------------------------------------- focus-event cells

    @Test
    fun noFocusWithoutARequestIgnoresEverythingButALateGrant() {
        cell(FocusEvent.RequestGranted, FocusAction.Abandon, resulting = FocusState.NoFocus) { }
        cell(FocusEvent.RequestFailed, FocusAction.None, resulting = FocusState.NoFocus) { }
        cell(FocusEvent.FocusGained, FocusAction.None, resulting = FocusState.NoFocus) { }
        cell(FocusEvent.FocusLost, FocusAction.None, resulting = FocusState.NoFocus) { }
        cell(FocusEvent.FocusLostTransient, FocusAction.None, resulting = FocusState.NoFocus) { }
        cell(FocusEvent.FocusLostTransientCanDuck, FocusAction.None, resulting = FocusState.NoFocus) { }
    }

    @Test
    fun aRequestInFlightIsGrantedToHoldingOrFailedToPauseIfStillPlaying() {
        // Playing → REQUEST in flight → the platform grants.
        cell(
            FocusEvent.RequestGranted,
            FocusAction.None,
            resulting = FocusState.Holding,
            setup = { playing() },
        )
        // The denial while the engine still plays: the honest "pause again".
        cell(
            FocusEvent.RequestFailed,
            FocusAction.Pause,
            resulting = FocusState.NoFocus,
            setup = { playing() },
        )
        // The denial after the user already paused: nothing to undo.
        cell(
            FocusEvent.RequestFailed,
            FocusAction.None,
            resulting = FocusState.NoFocus,
            setup = { playing().engine(PlaybackStatus.Paused) },
        )
    }

    @Test
    fun holdingWhilePlayingPausesOnLossAndFlagsOnTransient() {
        cell(FocusEvent.RequestGranted, FocusAction.None, resulting = FocusState.Holding, setup = { holding(true) })
        cell(FocusEvent.RequestFailed, FocusAction.None, resulting = FocusState.Holding, setup = { holding(true) })
        // The request-grant listener echo — a GAIN with nothing paused.
        cell(FocusEvent.FocusGained, FocusAction.None, resulting = FocusState.Holding, setup = { holding(true) })
        cell(FocusEvent.FocusLost, FocusAction.Pause, FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { holding(true) })
        cell(
            FocusEvent.FocusLostTransient,
            FocusAction.PauseAndResumeOnGain,
            resulting = FocusState.TransientLost(resumeOnGain = true),
            setup = { holding(true) },
        )
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.Duck(0.2f),
            resulting = FocusState.Ducked,
            setup = { holding(true) },
        )
    }

    @Test
    fun holdingWhilePausedNeverPausesAgainButStillDucks() {
        // Full loss while paused: the ruling's pause already holds.
        cell(FocusEvent.FocusLost, FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { holding(false) })
        // A transient while PAUSED sets NO resume flag — auto-resuming a
        // deck the user never had playing would be a phantom play.
        cell(
            FocusEvent.FocusLostTransient,
            FocusAction.None,
            resulting = FocusState.TransientLost(resumeOnGain = false),
            setup = { holding(false) },
        )
        // The duck still pre-arms (a mid-duck resume comes up ducked).
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.Duck(0.2f),
            resulting = FocusState.Ducked,
            setup = { holding(false) },
        )
        cell(FocusEvent.FocusGained, FocusAction.None, resulting = FocusState.Holding, setup = { holding(false) })
    }

    @Test
    fun duckedRestoresOnGainPausesAndRestoresOnLossAndNeverDoubles() {
        cell(FocusEvent.FocusGained, FocusAction.Unduck, resulting = FocusState.Holding, setup = { ducked(true) })
        cell(
            FocusEvent.FocusLost,
            FocusAction.Pause,
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { ducked(true) },
        )
        cell(
            FocusEvent.FocusLostTransient,
            FocusAction.PauseAndResumeOnGain,
            resulting = FocusState.TransientLostDucked(resumeOnGain = true),
            setup = { ducked(true) },
        )
        // A second can-duck while already ducked must NOT compound the
        // multiply — the duck cell for the already-ducked row.
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.None,
            resulting = FocusState.Ducked,
            setup = { ducked(true) },
        )
        cell(FocusEvent.RequestGranted, FocusAction.None, resulting = FocusState.Ducked, setup = { ducked(true) })
        cell(FocusEvent.RequestFailed, FocusAction.None, resulting = FocusState.Ducked, setup = { ducked(true) })
    }

    @Test
    fun duckedWhilePausedPreArmsTheMidDuckResume() {
        // Already paused: the full loss restores the volume, no pause.
        cell(FocusEvent.FocusLost, FocusAction.Unduck, FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { ducked(false) })
        cell(
            FocusEvent.FocusLostTransient,
            FocusAction.None,
            resulting = FocusState.TransientLostDucked(resumeOnGain = false),
            setup = { ducked(false) },
        )
        cell(FocusEvent.FocusGained, FocusAction.Unduck, resulting = FocusState.Holding, setup = { ducked(false) })
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.None,
            resulting = FocusState.Ducked,
            setup = { ducked(false) },
        )
    }

    @Test
    fun aFlaggedTransientResumesOnlyOnGainAndTheFlagIsConsumed() {
        cell(FocusEvent.FocusGained, FocusAction.Resume, resulting = FocusState.Holding, setup = { transientLost(true) })
        // The re-request outcomes from the displaced state (the user
        // resumed during the phone call — the honest request-and-see):
        // granted → keep playing, the flag dies.
        cell(
            FocusEvent.RequestGranted,
            FocusAction.None,
            resulting = FocusState.Holding,
            setup = { transientLost(true).playing() },
        )
        // Denied → pause again, but the deferred play survives.
        cell(
            FocusEvent.RequestFailed,
            FocusAction.Pause,
            resulting = FocusState.TransientLost(resumeOnGain = true),
            setup = { transientLost(true).playing() },
        )
        // Nested displacement: the deeper holder wins; the regain still
        // comes when the stack unwinds. Ducking is for holders.
        cell(
            FocusEvent.FocusLostTransient,
            FocusAction.None,
            resulting = FocusState.TransientLost(resumeOnGain = true),
            setup = { transientLost(true) },
        )
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.None,
            resulting = FocusState.TransientLost(resumeOnGain = true),
            setup = { transientLost(true) },
        )
        // The escalation to a full loss kills the flag — no auto-resume,
        // and no pause either: the transient already paused us.
        cell(FocusEvent.FocusLost, FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { transientLost(true) })
    }

    @Test
    fun anUnflaggedTransientNeverResumes() {
        // Ruling 1: GAIN resumes ONLY if the flag is set — never on a
        // plain regain after a loss of an already-paused deck.
        cell(FocusEvent.FocusGained, FocusAction.None, resulting = FocusState.Holding, setup = { transientLost(false) })
        cell(FocusEvent.RequestGranted, FocusAction.None, resulting = FocusState.Holding, setup = { transientLost(false) })
        cell(FocusEvent.RequestFailed, FocusAction.None, resulting = FocusState.TransientLost(resumeOnGain = false), setup = {
            transientLost(false)
        })
        // The denied re-request while the flagless transient plays:
        // pause again (the flagless row of the deferred-play cell).
        cell(
            FocusEvent.RequestFailed,
            FocusAction.Pause,
            resulting = FocusState.TransientLost(resumeOnGain = false),
            setup = { transientLost(false).playing() },
        )
        cell(FocusEvent.RequestGranted, FocusAction.None, resulting = FocusState.Holding, setup = { transientLost(false).playing() })
        cell(FocusEvent.FocusLost, FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { transientLost(false) })
        cell(
            FocusEvent.FocusLostTransient,
            FocusAction.None,
            resulting = FocusState.TransientLost(resumeOnGain = false),
            setup = { transientLost(false) },
        )
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.None,
            resulting = FocusState.TransientLost(resumeOnGain = false),
            setup = { transientLost(false) },
        )
    }

    @Test
    fun aDuckedTransientRegainsWithRestoreAndResumeTogether() {
        // The transient-during-duck cell: prompt → call → call ends.
        cell(
            FocusEvent.FocusGained,
            FocusAction.Unduck,
            FocusAction.Resume,
            resulting = FocusState.Holding,
            setup = { transientLostDucked(true) },
        )
        // The steal-back from the can-duck holder (the user hit play
        // over a navigation prompt and the system sided with them):
        // granted → restore the volume, the engine already plays.
        cell(
            FocusEvent.RequestGranted,
            FocusAction.Unduck,
            resulting = FocusState.Holding,
            setup = { transientLostDucked(true).playing() },
        )
        // Denied while ducked: pause again, stay ducked (the prompt
        // still holds), the deferred play survives.
        cell(
            FocusEvent.RequestFailed,
            FocusAction.Pause,
            resulting = FocusState.TransientLostDucked(resumeOnGain = true),
            setup = { transientLostDucked(true).playing() },
        )
        // Full loss while paused-and-ducked: restore only.
        cell(
            FocusEvent.FocusLost,
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { transientLostDucked(true) },
        )
        cell(
            FocusEvent.FocusLost,
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { transientLostDucked(false) },
        )
        cell(
            FocusEvent.FocusGained,
            FocusAction.Unduck,
            resulting = FocusState.Holding,
            setup = { transientLostDucked(false) },
        )
        cell(
            FocusEvent.FocusLostTransientCanDuck,
            FocusAction.None,
            resulting = FocusState.TransientLostDucked(resumeOnGain = true),
            setup = { transientLostDucked(true) },
        )
    }

    // ---------------------------------------------------- playback cells

    @Test
    fun theFirstPlayRequestsFocusAndHoldingResumesNeedNothing() {
        playbackCell(
            PlaybackStatus.Playing,
            FocusAction.RequestFocus,
            resulting = FocusState.NoFocus,
            setup = { },
        )
        // A second Playing observation while the request is in flight
        // must not fire a second platform request.
        playbackCell(
            PlaybackStatus.Playing,
            FocusAction.None,
            resulting = FocusState.NoFocus,
            setup = { playing() },
        )
        playbackCell(PlaybackStatus.Paused, FocusAction.None, resulting = FocusState.NoFocus, setup = { })
        playbackCell(PlaybackStatus.Stopped, FocusAction.None, resulting = FocusState.NoFocus, setup = { })
        playbackCell(PlaybackStatus.Playing, FocusAction.None, resulting = FocusState.Holding, setup = { holding(false) })
        playbackCell(PlaybackStatus.Playing, FocusAction.None, resulting = FocusState.Ducked, setup = { ducked(false) })
        // The displaced state re-requests on a user resume (playing or
        // not — the request-and-see is the user's intent to hear audio).
        playbackCell(
            PlaybackStatus.Playing,
            FocusAction.RequestFocus,
            resulting = FocusState.TransientLost(resumeOnGain = true),
            setup = { transientLost(true) },
        )
        playbackCell(
            PlaybackStatus.Playing,
            FocusAction.RequestFocus,
            resulting = FocusState.TransientLostDucked(resumeOnGain = true),
            setup = { transientLostDucked(true) },
        )
        playbackCell(
            PlaybackStatus.Playing,
            FocusAction.RequestFocus,
            resulting = FocusState.TransientLost(resumeOnGain = false),
            setup = { transientLost(false) },
        )
    }

    @Test
    fun theEngineStopAbandonsFromEveryHoldingShapeRestoringALiveDuck() {
        // Holding: plain abandon (the model keeps its volume — nothing
        // to restore).
        playbackCell(
            PlaybackStatus.Stopped,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { holding(true) },
        )
        // Ducked: RESTORE FIRST — the model's volume survives Stop, so
        // an unrestored duck would bleed into the next playback.
        playbackCell(
            PlaybackStatus.Stopped,
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { ducked(true) },
        )
        playbackCell(
            PlaybackStatus.Stopped,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { transientLost(true) },
        )
        playbackCell(
            PlaybackStatus.Stopped,
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { transientLostDucked(true) },
        )
    }

    @Test
    fun aPauseObservationIsInertEverywhereExceptTheTransientFlag() {
        playbackCell(PlaybackStatus.Paused, FocusAction.None, resulting = FocusState.NoFocus, setup = { })
        playbackCell(PlaybackStatus.Paused, FocusAction.None, resulting = FocusState.Holding, setup = { holding(true) })
        playbackCell(PlaybackStatus.Paused, FocusAction.None, resulting = FocusState.Ducked, setup = { ducked(true) })
        // Our own transient pause, observed: the pause is consumed, the
        // flag KEPT (the phone-call mirror).
        playbackCell(
            PlaybackStatus.Paused,
            FocusAction.None,
            resulting = FocusState.TransientLost(resumeOnGain = true),
            setup = { holding(true).event(FocusEvent.FocusLostTransient) },
        )
    }

    @Test
    fun aUserPauseDuringTheTransientCancelsTheAutoResume() {
        // The SECOND Paused observation — one the policy did not cause,
        // reachable through the policy's raw input (the service-side
        // collector dedupes, so a pause-on-paused is invisible there;
        // pinned here so the raw contract stays honest) — is the user
        // taking control: the flag dies and the regain is inert.
        val h = h().holding(true)
        h.event(FocusEvent.FocusLostTransient).acts(FocusAction.PauseAndResumeOnGain)
        h.engine(PlaybackStatus.Paused).acts(FocusAction.None)
        h.assertState(FocusState.TransientLost(resumeOnGain = true))
        h.engine(PlaybackStatus.Paused).acts(FocusAction.None)
        h.assertState(FocusState.TransientLost(resumeOnGain = false))
        h.event(FocusEvent.FocusGained).acts(FocusAction.None).state(FocusState.Holding)
    }

    @Test
    fun aFastRegainOutrunsTheOwnPauseObservation() {
        // LOSS_TRANSIENT → the pause command is in flight → the GAIN
        // lands BEFORE the engine's Paused is observed: the policy
        // resumes, and the subsequent Playing observation silently
        // retires the outstanding own-pause bookkeeping.
        val h = h().holding(true)
        h.event(FocusEvent.FocusLostTransient).acts(FocusAction.PauseAndResumeOnGain)
        h.event(FocusEvent.FocusGained).acts(FocusAction.Resume).state(FocusState.Holding)
        h.engine(PlaybackStatus.Playing).acts(FocusAction.None)
        h.event(FocusEvent.FocusGained).acts(FocusAction.None).state(FocusState.Holding)
    }

    // ---------------------------------------------------------------- teardown

    @Test
    fun teardownAbandonsFromEveryStateAndRestoresALiveDuck() {
        teardownCell(FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { })
        teardownCell(FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { holding(true) })
        teardownCell(FocusAction.Abandon, resulting = FocusState.NoFocus, setup = { transientLost(true) })
        teardownCell(
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { ducked(true) },
        )
        teardownCell(
            FocusAction.Unduck,
            FocusAction.Abandon,
            resulting = FocusState.NoFocus,
            setup = { transientLostDucked(true) },
        )
    }

    @Test
    fun teardownIsIdempotentAndEndsTheRequestCycle() {
        val h = h().ducked(true)
        h.teardown().acts(FocusAction.Unduck, FocusAction.Abandon)
        h.teardown().acts(FocusAction.Abandon)
        // Even with a request back in flight, the teardown kills the
        // cycle — a late grant for it is then the late-grant cell
        // (an Abandon, pinned above).
        h.engine(PlaybackStatus.Playing).acts(FocusAction.RequestFocus)
        h.teardown().acts(FocusAction.Abandon)
        h.event(FocusEvent.RequestGranted).acts(FocusAction.Abandon).state(FocusState.NoFocus)
    }

    // ------------------------------------------------------------- the factor

    @Test
    fun theDuckFactorIsTheDocumentedConstantAndFlowsIntoTheAction() {
        assertEquals(
            0.2f,
            FocusPolicy.DEFAULT_DUCK_FACTOR,
            "the DUCK factor: 0.2, a constant documented as configurable-not-yet-exposed",
        )
        val custom = FocusPolicy(duckFactor = 0.5f)
        custom.onPlayback(PlaybackStatus.Playing)
        custom.onEvent(FocusEvent.RequestGranted)
        assertEquals(listOf(FocusAction.Duck(0.5f)), custom.onEvent(FocusEvent.FocusLostTransientCanDuck))
    }

    // ---------------------------------------------------------------- harness

    /**
     * One matrix cell: a fresh policy driven through [setup], then the
     * [event], asserting the exact action list and the resulting state.
     */
    private fun cell(
        event: FocusEvent,
        vararg expected: FocusAction,
        resulting: FocusState,
        setup: Harness.() -> Unit,
    ) {
        val h = h()
        h.setup()
        h.event(event).acts(*expected).state(resulting)
    }

    /** The playback-input twin of [cell]. */
    private fun playbackCell(
        status: PlaybackStatus,
        vararg expected: FocusAction,
        resulting: FocusState,
        setup: Harness.() -> Unit,
    ) {
        val h = h()
        h.setup()
        h.engine(status).acts(*expected).state(resulting)
    }

    /** The teardown twin of [cell]. */
    private fun teardownCell(
        vararg expected: FocusAction,
        resulting: FocusState,
        setup: Harness.() -> Unit,
    ) {
        val h = h()
        h.setup()
        h.teardown().acts(*expected).state(resulting)
    }

    /** One policy under test plus fluent transcript helpers. */
    private class Harness {
        val policy = FocusPolicy()

        fun assertState(expected: FocusState) = assertEquals(expected, policy.state)

        /** Feeds one engine-status observation (the collector's deduped transitions). */
        fun engine(status: PlaybackStatus): Harness {
            lastActions = policy.onPlayback(status)
            return this
        }

        /** Feeds one platform focus event (a request result or a listener change). */
        fun event(event: FocusEvent): Harness {
            lastActions = policy.onEvent(event)
            return this
        }

        fun teardown(): Harness {
            lastActions = policy.teardown()
            return this
        }

        /** Asserts the previously fed input produced exactly [expected]. */
        fun acts(vararg expected: FocusAction): Harness {
            assertEquals(expected.toList(), lastActions, "the actions for the last input")
            return this
        }

        fun state(expected: FocusState): Harness {
            assertState(expected)
            return this
        }

        private var lastActions: List<FocusAction> = emptyList()
    }

    /** [Harness.engine] on the playing path. */
    private fun Harness.playing(): Harness = engine(PlaybackStatus.Playing)

    /** Grants focus over the playing transcript: Playing → REQUEST → GRANTED. */
    private fun Harness.holding(playing: Boolean): Harness {
        engine(PlaybackStatus.Playing)
        policy.onEvent(FocusEvent.RequestGranted)
        if (!playing) engine(PlaybackStatus.Paused)
        return this
    }

    /** A can-duck transient over [holding]: the duck is live. */
    private fun Harness.ducked(playing: Boolean): Harness {
        holding(playing)
        policy.onEvent(FocusEvent.FocusLostTransientCanDuck)
        return this
    }

    /** A full transient loss over [holding]; [flag] picks the engine's play state at loss. */
    private fun Harness.transientLost(flag: Boolean): Harness {
        holding(playing = flag)
        policy.onEvent(FocusEvent.FocusLostTransient)
        if (flag) policy.onPlayback(PlaybackStatus.Paused) // our own pause, observed
        return this
    }

    /** A can-duck that escalated to a full transient: the duck survives. */
    private fun Harness.transientLostDucked(flag: Boolean): Harness {
        ducked(playing = flag)
        policy.onEvent(FocusEvent.FocusLostTransient)
        if (flag) policy.onPlayback(PlaybackStatus.Paused)
        return this
    }

    private fun h() = Harness()
}
