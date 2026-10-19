package org.fauste.player.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fauste.player.player.PlaybackStatus
import org.fauste.player.player.PlayerController

/**
 * THE FOCUS WIRING (Phase 5, Ruling 1): owns the platform
 * `AudioFocusRequest`, feeds the [FocusPolicy] from both arrival
 * paths, and executes the policy's actions over THE SAME
 * [PlayerController] command path the UI rides — `play()` / `pause()`
 * / `setVolume()`, never a new state channel — so the UI observes a
 * focus pause exactly as it observes any other pause: through
 * engineState, within one poll period.
 *
 * THE TWO ARRIVAL PATHS:
 * - **The engine observation**: a collector on the service's scope
 *   watches `engineState` through the SAME distinctUntilChanged
 *   discipline the service starter uses. The first Playing
 *   observation acquires focus (Ruling 1: acquired on first Play —
 *   the hook is the service's own observation, because focus lives IN
 *   THE SERVICE and the service must exist to hold it); the Stopped
 *   observation abandons (plus the duck restore — see below); a
 *   Playing observation while displaced re-requests (the honest
 *   request-and-see).
 * - **The platform streams**: the request results and the listener's
 *   focus changes arrive through [FocusRequester]'s two sinks on the
 *   MAIN thread (the production requester posts everything there —
 *   focus callbacks are delivered on the requesting thread's looper,
 *   and the IO-scope threads have none); the controller hops every
 *   delivery into the service scope before it touches the policy or
 *   the engine. No focus event ever runs engine work on its arrival
 *   thread.
 *
 * THE DUCK BOOKKEEPING (the ruling's "must not fight the user's
 * volume slider", judged): the duck MULTIPLIES the model's current
 * volume — `engineState.value.volume` read at duck execution — by the
 * policy's factor, and stores the pre-duck value; every exit
 * (regain, loss, stop, teardown) restores THAT stored value. Why
 * multiply: a duck must be relative — an absolute 0.2 would RAISE a
 * user at 0.1 and over-duck a user at 1.0. Why restore-to-stored: the
 * engine's volume is ONE channel (the model's per-player field), and
 * during the duck its value IS the ducked one, so the pre-duck
 * reading is the only honest restore target. The disclosed tradeoffs:
 * (a) a slider move DURING a duck is clobbered by the restore —
 * can-duck transients are seconds-long, and the alternative
 * (comparing the live volume against the ducked value to "detect" a
 * user change) is float-fragile and can leak the duck permanently;
 * (b) the read is poll-fresh within ≤ one poll period, so a slider
 * move racing the duck command within ~100 ms stores the pre-move
 * value. THE STALE-READ RACE, and its closure: the restore itself is
 * dispatched asynchronously, and the poll carries it into
 * `engineState` only ≤ one poll period later — so a duck landing
 * inside that window (a GAIN followed by a CAN_DUCK: back-to-back
 * navigation prompts) would read the still-ducked polled value, store
 * it as its pre-duck level, and COMPOUND the cut on every repeat
 * (0.8 → 0.16 → 0.032 …). Closed by the pending restore: [unduck]
 * records the value it just commanded, and [duck] consumes that
 * record — authoritative for the very next duck, because the engine's
 * own poll cannot have reflected the restore yet — before ever
 * reading `engineState`; the pending value is set on every restore
 * and consumed by the very next duck. The residual stays the
 * slider-move tradeoff above: a user move between the restore and the
 * next duck reads as the restored value — the same clobber class as
 * (a). The restore is not optional bookkeeping anywhere: the
 * model's volume SURVIVES Stop (`stop_player` never touches
 * `player.volume`), so an unrestored duck would bleed into the next
 * playback at 0.2×.
 *
 * THE RELEASE RACE (why the controller owns a [releaseScope]): the
 * service's onDestroy cancels its scope, but a ducked kill (the
 * system dismissing the service mid-duck) still owes the engine its
 * volume back, and the stop-path observation may lose the race with
 * the teardown. The duck restore on the release path therefore rides
 * a scope the service's cancel does not touch; everything else about
 * release is synchronous and idempotent. After [release] no event
 * path reacts anymore — a late platform delivery lands on a dead
 * collector or the released guard, never on the engine.
 *
 * WHAT THIS CLASS IS NOT: it is not a focus arbiter — the platform
 * owns that; and it is not observable state — the policy's actions
 * are all commands, and the UI renders the engine's own truth.
 */
internal class AudioFocusController(
    private val player: PlayerController,
    private val scope: CoroutineScope,
    private val requester: FocusRequester,
    duckFactor: Float = FocusPolicy.DEFAULT_DUCK_FACTOR,
    /**
     * Where the release path's duck-restore command rides — NOT the
     * service's scope, which onDestroy cancels before the restore
     * could land (see the class KDoc's release race). Injectable for
     * the test lane's scheduler.
     */
    private val releaseScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val policy = FocusPolicy(duckFactor)

    /** Set by [release]: every event path dies at this guard from then on. */
    @Volatile
    private var released = false

    /**
     * The duck-restore bookkeeping: the model volume read at duck
     * time, null while no duck is live. Volatile — the sink hop and
     * the collector are separate coroutines on the scope's pool.
     */
    @Volatile
    private var duckedFromVolume: Float? = null

    /**
     * The restore the controller just COMMANDED — still invisible to
     * the polled `engineState`, which carries it only ≤ one poll
     * period later. [unduck] sets this on every restore dispatch;
     * [duck] consumes it, once, in preference to the polled read:
     * inside that window the polled value can still be the PREVIOUS
     * duck's ducked level, and storing it would compound the cut on
     * every regain (the class KDoc's stale-read race). Volatile, like
     * [duckedFromVolume].
     */
    @Volatile
    private var pendingRestore: Float? = null

    init {
        requester.requestResultSink = { result ->
            // THE RESULT STREAM maps by the request-outcome constants —
            // a namespace that COLLIDES with the listener's: on the
            // platform, AUDIOFOCUS_REQUEST_GRANTED and AUDIOFOCUS_GAIN
            // are BOTH 1 (AUDIOFOCUS_REQUEST_FAILED is 0). The two
            // streams are therefore separated structurally — different
            // sinks — never by int value.
            val event = toRequestResultEvent(result)
            if (event == null) {
                Log.w(LOG_TAG, "an unmapped focus request result ($result) was treated as a denial")
                hop(FocusEvent.RequestFailed)
            } else {
                hop(event)
            }
        }
        requester.focusChangeSink = { change ->
            val event = toFocusChangeEvent(change)
            if (event == null) {
                Log.w(LOG_TAG, "an unmapped focus change ($change) was dropped")
            } else {
                hop(event)
            }
        }
    }

    /**
     * THE HOP: both platform streams fire on the main thread in
     * production; the policy and the engine are only ever touched from
     * the service scope.
     */
    private fun hop(event: FocusEvent) {
        scope.launch {
            if (!released) {
                execute(policy.onEvent(event))
            }
        }
    }

    /**
     * The engine observation half (the class KDoc): distinct status
     * transitions only — the same discipline as the service starter,
     * so a stream of Playing ticks is one transition.
     */
    @Suppress("unused") // the Job is the collector's lifetime: cancelled with the scope in the service's onDestroy
    private val collector =
        scope.launch {
            player.engineState
                .map { engineState -> PlaybackStatus.fromEngine(engineState.status) }
                .distinctUntilChanged()
                .collect { status ->
                    if (!released) {
                        execute(policy.onPlayback(status))
                    }
                }
        }

    /**
     * The service's teardown half (onDestroy and any belt-and-braces
     * exit): abandon — restoring a live duck first — and silence every
     * event path. Idempotent; safe from the main thread (the abandon
     * is a platform binder call, the volume restore an engine command
     * launched onto [releaseScope]).
     */
    fun release() {
        released = true
        execute(policy.teardown(), commandScope = releaseScope)
    }

    // ---------------------------------------------------------------- execute

    private fun execute(
        actions: List<FocusAction>,
        commandScope: CoroutineScope = scope,
    ) {
        actions.forEach { action ->
            when (action) {
                FocusAction.None -> Unit
                FocusAction.RequestFocus -> requester.requestFocus()
                FocusAction.Pause -> commandScope.launch { player.pause() }
                FocusAction.PauseAndResumeOnGain -> commandScope.launch { player.pause() }
                FocusAction.Resume -> commandScope.launch { player.play() }
                is FocusAction.Duck -> duck(action.factor, commandScope)
                FocusAction.Unduck -> unduck(commandScope)
                FocusAction.Abandon -> requester.abandonFocus()
            }
        }
    }

    /** Multiplies the model's current volume by [factor], storing the pre-duck value for the restore. */
    private fun duck(
        factor: Float,
        commandScope: CoroutineScope,
    ) {
        // The belt behind the policy's no-double-duck guard: a stored
        // value means a duck is already live and the multiply must not
        // compound. Unreachable via the policy's own matrix — Duck is
        // never emitted twice without an intervening Unduck — so this
        // is a belt, not a gate.
        if (duckedFromVolume == null) {
            // The pending restore outranks the polled read: the restore
            // was dispatched asynchronously and the engine's own poll
            // cannot have reflected it yet, so the polled value here
            // can still be the previous duck's ducked level — storing
            // it would compound the cut on every regain (0.8 → 0.16 →
            // 0.032 …). Consume-on-read: the pending value answers only
            // the very next duck.
            val current = pendingRestore ?: player.engineState.value.volume
            pendingRestore = null
            duckedFromVolume = current
            commandScope.launch { player.setVolume(current * factor) }
        }
    }

    /** Restores the stored pre-duck volume; a no-op when no duck is live. */
    private fun unduck(commandScope: CoroutineScope) {
        val restore = duckedFromVolume ?: return
        duckedFromVolume = null
        // The restore rides the scope asynchronously — arm the pending
        // restore so the very next duck reads THIS value, not the
        // still-ducked polled one (the class KDoc's stale-read race).
        pendingRestore = restore
        commandScope.launch { player.setVolume(restore) }
    }

    /** The listener's focus-change ints onto the policy's events; an unmapped int answers null (dropped, logged). */
    private fun toFocusChangeEvent(change: Int): FocusEvent? =
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusEvent.FocusGained
            AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.FocusLost
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusEvent.FocusLostTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.FocusLostTransientCanDuck
            else -> null
        }

    /**
     * The request's outcome ints onto the policy's request events. The
     * GRANTED/DELAYED pair both mean the request stood (DELAYED cannot
     * occur for a plain GAIN request; handled for completeness); the
     * unknown-int caller treats anything else as a denial — a request
     * that did not stand must pause a playing engine.
     */
    private fun toRequestResultEvent(result: Int): FocusEvent? =
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED,
            -> FocusEvent.RequestGranted
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> FocusEvent.RequestFailed
            else -> null
        }

    private companion object {
        /** The wiring's logcat tag (the one honest log: the dropped unknown focus change). */
        const val LOG_TAG = "FausteAudioFocus"
    }
}

/**
 * THE PLATFORM SEAM (host-testability, judged honestly): the real
 * `AudioManager` focus stack cannot be driven from the JVM — Robolectric
 * does not arbitrate focus — so the controller depends on this narrow
 * interface instead. [PlatformAudioFocusRequester] is the production
 * half; the test lane scripts it.
 *
 * TWO STREAMS, DELIBERATELY: the platform's request-outcome constants
 * and listener-change constants share one int namespace WITH A
 * COLLISION — `AUDIOFOCUS_REQUEST_GRANTED` and `AUDIOFOCUS_GAIN` are
 * both 1 (verified against the android.jar; `AUDIOFOCUS_REQUEST_FAILED`
 * is 0) — so the two kinds of delivery are separated structurally, by
 * sink, and can never be confused by value.
 */
internal interface FocusRequester {
    /**
     * Where the request outcomes land — the return value of
     * `requestAudioFocus`, verbatim. Production delivers on the MAIN
     * thread; the controller hops each delivery into the service scope.
     */
    var requestResultSink: ((Int) -> Unit)?

    /**
     * Where the listener's focus changes land — `AUDIOFOCUS_GAIN`,
     * `AUDIOFOCUS_LOSS`, `..._LOSS_TRANSIENT`,
     * `..._LOSS_TRANSIENT_CAN_DUCK`, verbatim. Production delivers on
     * the MAIN thread; the controller hops each delivery into the
     * service scope.
     */
    var focusChangeSink: ((Int) -> Unit)?

    /** Requests `AUDIOFOCUS_GAIN` focus; the outcome arrives via [requestResultSink]. */
    fun requestFocus()

    /** Abandons the request. Idempotent; safe from any thread. */
    fun abandonFocus()
}

/**
 * The production [FocusRequester]: one `AudioFocusRequest` (API 26+
 * floor — the `AudioFocusRequest.Builder` + `AudioAttributes` of
 * USAGE_MEDIA / CONTENT_TYPE_MUSIC, Ruling 1) requested on first Play
 * and abandoned on Stop/teardown.
 *
 * THREADING: both the request and the abandon are POSTED to the main
 * looper. `requestAudioFocus` delivers its listener callbacks on the
 * requesting thread's looper, and the service's IO-scope threads have
 * none — requesting from main is the classic, well-trodden pattern,
 * and it also orders an abandon after any in-flight request from the
 * same source. The main looper is process-level, so a posted abandon
 * still runs after the service's onDestroy — the release path's
 * belt-and-braces half. The [AudioAttributes] pin the request to the
 * media domain so the platform's ducking and interruption policies
 * classify the app like every other music player.
 *
 * A note on the listener echo: on AOSP the initial grant is
 * communicated through the request's RETURN value, not the listener;
 * some OEM builds nonetheless also fire the listener with GAIN right
 * after a grant — the policy absorbs it (a GAIN while holding with no
 * resume flag is [FocusAction.None]).
 */
internal class PlatformAudioFocusRequester(
    context: Context,
) : FocusRequester {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    override var requestResultSink: ((Int) -> Unit)? = null

    @Volatile
    override var focusChangeSink: ((Int) -> Unit)? = null

    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private val listener =
        AudioManager.OnAudioFocusChangeListener { change -> focusChangeSink?.invoke(change) }

    /** Built once on the first request, reused for the request/abandon cycles of every playback phase. */
    @Volatile
    private var request: AudioFocusRequest? = null

    override fun requestFocus() {
        mainHandler.post {
            val focusRequest =
                request ?: AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener, mainHandler)
                    .build()
                    .also { request = it }
            requestResultSink?.invoke(audioManager.requestAudioFocus(focusRequest))
        }
    }

    override fun abandonFocus() {
        mainHandler.post {
            // No request was ever built → there is nothing to abandon;
            // the platform's own abandon of an unheld request is a
            // no-op either way.
            val focusRequest = request ?: return@post
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }
}
