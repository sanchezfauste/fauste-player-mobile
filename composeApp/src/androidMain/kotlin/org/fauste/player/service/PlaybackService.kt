package org.fauste.player.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
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
 *   OUT of foreground while the service keeps running; the
 *   media-session notification is exempt from POST_NOTIFICATIONS by the
 *   platform's own rules, so a denial only ever hides it on OEM launchers
 *   that ignore the exemption — playback is unaffected either way.
 * - **Stop → exit.** The engine's status (paused-by-notification is NOT
 *   this) falling to stopped is observed in
 *   [onUpdateNotificationAsync] — media3's single notification funnel —
 *   which intercepts the update instead of posting an IDLE
 *   notification, cancels the pinned notification id and calls
 *   [stopSelf]: foreground off, service gone. The next Play restarts it
 *   through the same starter.
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

    override fun onCreate() {
        super.onCreate()

        // The library's own notification provider, configured — channel
        // id/name and the pinned notification id — not replaced: the
        // task's ruling is to use media3's rendering. Must be set before
        // the first session add so the manager never instantiates the
        // default.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(org.fauste.player.R.string.playback_notification_channel_name)
                .setNotificationId(PLAYBACK_NOTIFICATION_ID)
                .build(),
        )

        // Off-main: Koin resolution (the lazy engine single's first
        // resolution is heavy — see AppModule's documented rule).
        scope.launch {
            try {
                val controller = GlobalContext.get().get<PlayerController>()
                val queue = GlobalContext.get().get<QueueManager>()

                // On-main: SimpleBasePlayer's contract pins every Player
                // access to the application looper, and MediaSession is
                // built on that thread.
                mainHandler.post {
                    try {
                        val sessionAdapter = FaustePlayerAdapter(controller, queue, scope)
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
     * library post an IDLE notification. Everything else — play, pause,
     * foreground promotion, the paused notification — is the library's
     * default behavior, passed straight through.
     */
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void?> =
        if (adapter?.latestSnapshot?.status == PlaybackStatus.Stopped) {
            stopSelfAndClearNotification()
            Futures.immediateVoidFuture()
        } else {
            super.onUpdateNotificationAsync(session, startInForegroundRequired)
        }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        adapter = null
        super.onDestroy()
    }

    /**
     * The pinned-id cancellation + exit, on the main thread. The cancel
     * covers both orders of the demotion race: still-foreground (the
     * system strips the FGS notification on stopSelf) or already
     * demoted (a plain posted notification only the id can reach).
     */
    private fun stopSelfAndClearNotification() {
        mainHandler.post {
            NotificationManagerCompat.from(this).cancel(PLAYBACK_NOTIFICATION_ID)
            stopSelf()
        }
    }

    /** Tapping the notification opens the app — the only activity there is. */
    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
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
    }
}
