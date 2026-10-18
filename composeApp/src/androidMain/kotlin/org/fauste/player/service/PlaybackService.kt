package org.fauste.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fauste.player.MainActivity
import org.fauste.player.player.PlaybackStatus
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.koin.core.context.GlobalContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * THE FOREGROUND SERVICE (Task 6, Ruling 5): a media3
 * [MediaSessionService] publishing one [MediaSession] whose Player is
 * [FaustePlayerAdapter] — the bridge over the Koin state layer
 * ([PlayerController] + [QueueManager]; the adapter's KDoc has the
 * no-ViewModel design). The lifecycle, verbatim:
 *
 * - **Play → foreground.** The app-side [PlaybackServiceStarter] starts
 *   this service on the engine's first Playing observation. The session
 *   is built, added, and [triggerNotificationUpdate]d; media3's own
 *   notification machinery (the configured [DefaultMediaNotificationProvider]
 *   — the LIBRARY renders the notification, the task's ruling: nothing
 *   is hand-rolled) promotes the service to foreground with
 *   FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK because the adapter reports
 *   playWhenReady=true over a READY state.
 * - **Paused → alive, notification kept** (Ruling 5, like every music
 *   player). media3 posts the paused notification and steps the service
 *   OUT of foreground while the service keeps running; a
 *   POST_NOTIFICATIONS denial hides the drawer notification — the
 *   VERIFIED reality on the API 34 emulator (the earlier "platform
 *   exemption" claim did not hold there and was struck): the session
 *   itself still registers with System UI's MediaSessionStack, so
 *   media controls may still surface, but the drawer notification
 *   does not. The service and playback run on unaffected either way.
 * - **Stop → exit, safely.** The engine's status (paused-by-notification is NOT
 *   this) falling to stopped is observed in
 *   [onUpdateNotificationAsync] — media3's single notification funnel —
 *   which intercepts the update instead of posting an IDLE
 *   notification and runs [stopSelfSafelyAndClearNotification]: the
 *   API 26-30 startForeground obligation is satisfied FIRST (the
 *   review's Important finding — a bare `stopSelf()` after
 *   `startForegroundService` crashes with `RemoteServiceException` on
 *   our minSdk floor if the engine stops between the starter's call
 *   and the first promotion), then the pinned notification id is
 *   cancelled and the service stops. The next Play restarts it
 *   through the same starter.
 *
 * - **The keep-awake gate** (the review's freeze finding): the service
 *   holds the controller's poll open while it runs —
 *   [PlayerController.setPlaybackServiceActive] true from the session's
 *   first update (the service being alive IS the gate's truth — its
 *   notification needs live state whether or not a promotion has
 *   happened yet) and at every foreground promotion media3 requests,
 *   false on the exit path and in [onDestroy] — so the notification's
 *   state stays live with the screen off instead of freezing at the
 *   last foreground snapshot (the poll's idle condition is Background
 *   AND service-inactive; see the controller's KDoc for the design and
 *   its disclosed remote-play-while-stopped limit).
 *
 * **Threading**: onCreate resolves NOTHING synchronously. The Koin
 * graph is resolved on a background scope (the heavy lazy engine single
 * must never be built on the main thread — the graph's documented
 * rule); the session is then built ON the main thread (media3 requires
 * the Player to live on its application looper) and handed to the
 * service via [addSession]. Until then [onGetSession] answers null:
 * a media-button cold start in that window is dropped honestly (the
 * engine is not up; the app UI's next play re-arms everything), and
 * the app-triggered start path needs no session to keep the service
 * alive for the moment the build lands.
 *
 * The notification's actions come from the library too: play/pause and
 * previous ride the adapter's available commands; the Stop button is a
 * [CommandButton] pinned via [MediaSession.setMediaButtonPreferences] —
 * the provider's documented extension point — which the session
 * round-trips back into `Player.stop()` (media3's predefined
 * player-command buttons), so the deck's Stop reaches the engine
 * through the same tested adapter path as every other button.
 *
 * What is NOT here (Phase 5 candidates, honestly out of scope): audio
 * focus handling (the native engine owns its sink; media3 runs focus
 * through ExoPlayer, which this bridge deliberately is not) and
 * Android Auto (no `MediaLibraryService` yet).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var adapter: FaustePlayerAdapter? = null

    @Volatile
    private var mediaSession: MediaSession? = null

    /**
     * The Koin controller single once resolved off-main — the keep-awake
     * gate's handle ([PlayerController.setPlaybackServiceActive]); the
     * service never resolves it synchronously on any main-thread path.
     */
    @Volatile
    private var controller: PlayerController? = null

    /**
     * THE GATE-LEAK RACE GUARD (the final review's folded finding):
     * `Service` contexts have no `isDestroyed()` — that is an
     * Activity-only API (verified against the android.jar) — so this is
     * ours, set FIRST in [onDestroy] and checked at the top of the
     * session-build runnable. Both run on the main looper, so looper
     * ordering (not volatile memory) is what makes the flag sound.
     */
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()

        // The library's own notification provider, configured — channel
        // id/name and the pinned notification id — not replaced: the
        // task's ruling is to use media3's rendering. Must be set before
        // the first session add so the manager never instantiates the
        // default. setSmallIcon pins the app's own glyph (the review's
        // fold-in): media3 would otherwise fall back to its generic
        // `media3_notification_small_icon`, which reads as no app's icon
        // on a lock screen. The app has no launcher icon resource to
        // reuse (the manifest declares none — the platform default is
        // the launcher's), so the notification gets a dedicated simple
        // playback glyph, used here AND by the stop-path's shutdown
        // notification below.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(org.fauste.player.R.string.playback_notification_channel_name)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .build()
                .also { it.setSmallIcon(org.fauste.player.R.drawable.ic_playback_notification) },
        )

        // Off-main: Koin resolution (the lazy engine single's first
        // resolution is heavy — see AppModule's documented rule).
        scope.launch {
            try {
                val resolvedController = GlobalContext.get().get<PlayerController>()
                val queue = GlobalContext.get().get<QueueManager>()
                controller = resolvedController

                // On-main: SimpleBasePlayer's contract pins every Player
                // access to the application looper, and MediaSession is
                // built on that thread.
                mainHandler.post {
                    // The gate-leak race guard (isDestroyed's KDoc): if
                    // onDestroy ran between this post being queued and
                    // executed, building the session here would re-set
                    // setPlaybackServiceActive(true) AFTER onDestroy's
                    // false — the poll would never idle again for the
                    // rest of the process lifetime. A dead service gets
                    // nothing.
                    if (isDestroyed) return@post
                    try {
                        val sessionAdapter = FaustePlayerAdapter(resolvedController, queue, scope)
                        adapter = sessionAdapter
                        val session =
                            MediaSession
                                .Builder(this@PlaybackService, sessionAdapter)
                                .setSessionActivity(mainActivityIntent())
                                .build()
                        mediaSession = session

                        // The notification's Stop button: a predefined
                        // player-command button (ICON_STOP +
                        // COMMAND_STOP) the provider renders from the
                        // preferences and the session routes back to
                        // Player.stop() — the deck's transport stop.
                        session.setMediaButtonPreferences(
                            listOf(
                                CommandButton
                                    .Builder(CommandButton.ICON_STOP)
                                    .setPlayerCommand(Player.COMMAND_STOP)
                                    .setDisplayName(
                                        getString(org.fauste.player.R.string.playback_notification_stop),
                                    ).build(),
                            ),
                        )

                        addSession(session)
                        // The keep-awake gate's second on-flip (the class
                        // KDoc): from the session's first update the
                        // service owns a notification, so the service
                        // being alive is itself the gate's truth — this
                        // covers the never-promoted corner (a play→pause
                        // blip before any promotion leaves a paused,
                        // notification-bearing, backgrounded service
                        // that still needs live state). The promotion
                        // flip in onUpdateNotificationAsync reinforces
                        // it idempotently; the exit paths close it.
                        controller?.setPlaybackServiceActive(true)
                        // The delayed first notification — promotion to
                        // foreground happens here if the engine is
                        // already playing (the starter's reason to run).
                        triggerNotificationUpdate()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The session could not be built (graph down,
                        // engine gone): exit honestly instead of running
                        // a notification-less zombie. Playback itself is
                        // native and keeps going regardless.
                        stopSelf()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Koin/graph resolution failed: same honest exit.
                stopSelf()
            }
        }
    }

    /** The session once built; null during the off-main build window (the class KDoc's honest cold-start note). */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Ruling 5's Stop half, at media3's single notification funnel: a
     * stopped engine tears the service down instead of letting the
     * library post an IDLE notification — through the SAFE stop dance
     * (the class KDoc's API 26-30 obligation). Everything else — play,
     * pause, foreground promotion, the paused notification — is the
     * library's default behavior, passed straight through, and every
     * promotion the library requests holds the controller's keep-awake
     * gate open (the class KDoc): `startInForegroundRequired == true`
     * is media3 asking to promote, which is exactly the service-active
     * moment; the flag stays up through demotions (a paused service
     * keeps running with its notification — that notification still
     * needs live state) and comes down only on the exit paths.
     */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void?> =
        if (adapter?.latestSnapshot?.status == PlaybackStatus.Stopped) {
            stopSelfSafelyAndClearNotification()
            Futures.immediateVoidFuture()
        } else {
            if (startInForegroundRequired) {
                controller?.setPlaybackServiceActive(true)
            }
            super.onUpdateNotificationAsync(session, startInForegroundRequired)
        }

    override fun onDestroy() {
        isDestroyed = true
        // The keep-awake gate's belt-and-braces off: every exit funnels
        // through here (the stop dance's stopSelf, the system's own
        // kills), and a gate left open would keep the controller's poll
        // from ever idling again for the rest of the process.
        controller?.setPlaybackServiceActive(false)
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        adapter = null
        super.onDestroy()
    }

    /**
     * THE SAFE STOP DANCE (the review's Important finding): the starter's
     * `startForegroundService` binds this service to call `startForeground`
     * before it stops — on API 26-30 (our minSdk floor) exiting without
     * ever promoting crashes with `RemoteServiceException`. The scenario
     * the review named: the engine blips Playing → Stopped between the
     * starter's call and the session's first promotion (a quick stop
     * after a pause → play re-fire) — the service exited with the
     * obligation still open.
     *
     * The library's own exit is `MediaSessionService.stopSelfSafely()`, but
     * it is PRIVATE in 1.11.1 (verified in the AAR's bytecode: not
     * callable from a subclass; the public `pauseAllPlayersAndStopSelf()`
     * ends in a BARE `stopSelf()` — it does not do the dance and does not
     * help here). So this mirrors the private method's exact sequence:
     * a minimal shutdown notification (its own
     * `createShutdownNotification`: channel ensured, the app's small
     * icon, only-alert-once, private visibility, not ongoing, and
     * API 31+ `FOREGROUND_SERVICE_IMMEDIATE`), posted via
     * `startForeground` with the `mediaPlayback` type (the platform's
     * `Util.setForegroundServiceNotification` branches: typed on 29+,
     * plain below), then `stopForeground(remove)` — demote AND drop it —
     * then `stopSelf()`. The millisecond-visible notification is the
     * point: it satisfies the obligation the platform is waiting on.
     *
     * One deliberate difference from the library, documented: its API<31
     * branch RETHROWS a promotion `IllegalStateException` after
     * `stopSelf()`; this logs and exits instead. The only ways that
     * exception arrives on our floors are a service start the starter
     * never makes (every start is `startForegroundService`, where the
     * platform REQUIRES this exact promotion) or an API 31+
     * start-not-allowed the library itself also swallows — and a stop
     * path must not trade an exit for a crash.
     *
     * The pinned-id cancel stays for the already-demoted order of the
     * demotion race: a paused service's notification is a plain posted
     * notification by then, reachable only through its id.
     */
    private fun stopSelfSafelyAndClearNotification() {
        mainHandler.post {
            controller?.setPlaybackServiceActive(false)
            try {
                val notification = shutdownNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(PLAYBACK_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(PLAYBACK_NOTIFICATION_ID, notification)
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG, "the stop-path foreground promotion was refused; exiting without it", e)
            }
            NotificationManagerCompat.from(this).cancel(PLAYBACK_NOTIFICATION_ID)
            stopSelf()
        }
    }

    /**
     * The shutdown-notification half of the dance — the mirror of media3's
     * own private `createShutdownNotification` (1.11.1): ensure the
     * playback channel exists (the same id/name the provider uses; the
     * check-then-create is idempotent and never clobbers the provider's
     * or the user's channel settings), then the library's minimal builder
     * shape — the app's small icon, `setOnlyAlertOnce`, private
     * visibility, not ongoing, and on API 31+ the IMMEDIATE
     * foreground-service behavior so the promotion is processed before
     * the stop instead of deferred past it. It is visible for
     * milliseconds at most: `stopForeground(REMOVE)` takes it down.
     */
    private fun shutdownNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(org.fauste.player.R.string.playback_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(org.fauste.player.R.drawable.ic_playback_notification)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }.build()
    }

    /**
     * Tapping the notification opens the app — the only activity there
     * is. `internal` for the host-lane pin: the suite reads the saved
     * intent back from Robolectric's PendingIntent shadow.
     */
    internal fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                // Reuse the live instance, don't stack a new one per tap
                // (the final review's F1 finding): MainActivity is
                // deliberately `standard` (no launchMode — flags are the
                // less invasive fix), so a FLAG-less content intent would
                // push a NEW instance every time the notification is
                // tapped while a backgrounded-but-alive activity exists,
                // and the new.onStart → old.onStop lifecycle order would
                // leave the app-lifecycle holder reading Background while
                // an activity is visible, corrupting the poll-idling
                // signal. CLEAR_TOP | SINGLE_TOP — the standard music-app
                // pattern — routes each tap to the EXISTING instance.
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        /** The media notification's channel — created by the library provider on first post. */
        const val NOTIFICATION_CHANNEL_ID = "playback"

        /**
         * The media notification's id, pinned so teardown can cancel
         * exactly it. The value is media3's own default
         * (DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID).
         */
        const val PLAYBACK_NOTIFICATION_ID = 1001

        /** The service's logcat tag (the stop path's one honest log). */
        const val LOG_TAG = "FaustePlaybackService"
    }
}
