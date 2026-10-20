package org.fauste.player.service

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE RESTART-SEMANTICS SEAM (Task 3, Ruling 4): what a START_STICKY
 * service owes after the system killed its process and restarted it
 * with a NULL intent — and what it owes an EXPLICIT start.
 *
 * - **Sticky restart (null intent):** the process death killed the
 *   native engine; the fresh process cannot be playing (the mobile core
 *   loads the store's LAST PERSISTED state — which nothing on mobile
 *   ever writes, `player_core.rs`'s "The `Store` is only needed to load
 *   the state at construction and is not retained" — so the honest
 *   answer is the quiet exit: `stopSelf`, NO foreground promotion (the
 *   startForegroundService obligation does not exist on a system
 *   restart, and API 31+ would refuse the promotion from the
 *   background), no zombie notification (the pinned id is cancelled —
 *   the pre-kill notification can outlive the process in the drawer).
 * - **Explicit start (non-null intent):** the app starter's or media
 *   routing's path — the service stays up for the session build and
 *   returns START_STICKY so the system restarts it after future kills.
 *
 * The lane: Robolectric with the service attached but never
 * lifecycle-started (`buildService(...).get()`, the
 * PlaybackServiceNotificationIntentTest precedent) — onStartCommand's
 * decision seam needs no Koin machinery, and onCreate's off-main build
 * must not race the assertions. What the unit lane honestly pins is the
 * DECISION + its exit — the pinned-id cancel included, recorded by the
 * notification-manager shadow (the pre-kill notification is posted on
 * id 1001 and must be gone after the quiet exit); the real sticky
 * delivery (process death, the system's restart timing, the
 * notification's drawer lifetime) is the
 * on-emulator kill/restart observation in the Task-3 report.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackServiceRestartTest {
    @Test
    fun aStickyRestartQuietlyExitsWithNoForegroundPromotion() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()
        val drawer = shadowOf(service.getSystemService(NotificationManager::class.java))

        // The drawer zombie the quiet exit exists to bury: the killed
        // process's notification on the pinned playback id
        // (PlaybackService.PLAYBACK_NOTIFICATION_ID — media3's
        // DEFAULT_NOTIFICATION_ID, 1001) can outlive its process,
        // pointing at a session that died with it. Post it first —
        // the pin needs something in the drawer to cancel.
        NotificationManagerCompat.from(service).notify(
            1001,
            NotificationCompat
                .Builder(service, "playback")
                .setSmallIcon(org.fauste.player.R.drawable.ic_playback_notification)
                .build(),
        )
        assertNotNull(
            drawer.getNotification(1001),
            "the pre-kill notification must be in the drawer for the pin to bite",
        )

        // The system's restart signature: onCreate ran, the intent is
        // null ("the service was killed and is being restarted").
        val result = service.onStartCommand(null, 0, 41)

        assertEquals(Service.START_STICKY, result, "a sticky restart must keep the sticky contract")
        assertTrue(
            shadowOf(service).isStoppedBySelf,
            "nothing can be playing in a process that just booted — the sticky restart must exit",
        )
        assertNull(
            shadowOf(service).lastForegroundNotification,
            "the quiet exit must not run the stop-path foreground dance — a system restart carries no startForegroundService obligation",
        )
        assertNull(
            drawer.getNotification(1001),
            "the quiet exit must cancel the pinned id — the pre-kill notification can outlive the process in the drawer, and pointing at a session that died with the old process is a zombie",
        )
    }

    @Test
    fun anExplicitStartIsStickyAndKeepsTheServiceUp() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()

        // The app starter's shape: a plain explicit intent
        // (FausteApplication's startForegroundService call).
        val result = service.onStartCommand(Intent(service, PlaybackService::class.java), 0, 41)

        assertEquals(Service.START_STICKY, result, "the explicit start keeps the sticky contract")
        assertFalse(
            shadowOf(service).isStoppedBySelf,
            "an explicit start is the session-build path — the service must stay up",
        )
    }
}
