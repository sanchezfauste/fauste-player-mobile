package org.fauste.player.service

import android.app.Application
import android.app.Service
import android.content.Intent
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * DECISION + its exit; the real sticky delivery (process death, the
 * system's restart timing, the notification's drawer lifetime) is the
 * on-emulator kill/restart observation in the Task-3 report.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackServiceRestartTest {
    @Test
    fun aStickyRestartQuietlyExitsWithNoForegroundPromotion() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()

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
