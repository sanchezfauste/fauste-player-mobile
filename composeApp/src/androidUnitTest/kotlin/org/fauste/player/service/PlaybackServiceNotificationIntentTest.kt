package org.fauste.player.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import org.fauste.player.MainActivity
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE FINAL-REVIEW F1 PIN: the notification's content intent must not
 * stack MainActivity instances. MainActivity is deliberately `standard`
 * (no launchMode — the manifest stays untouched; intent flags are the
 * less invasive fix), so a FLAG-less content intent would push a NEW
 * instance per notification tap while a backgrounded-but-alive activity
 * exists — the back stack growing per tap — and the new.onStart →
 * old.onStop lifecycle order leaves the app-lifecycle holder reading
 * Background while an activity is visible, corrupting the poll-idling
 * signal. The standard music-app pattern — CLEAR_TOP | SINGLE_TOP —
 * routes every tap to the EXISTING instance instead (delivered via
 * onNewIntent, never a second instance).
 *
 * The lane: Robolectric (PendingIntent is an Android API), the service
 * built but never lifecycle-started — [PlaybackService.mainActivityIntent]
 * needs only the attached context, not onCreate's Koin machinery, so the
 * world stays a single context with no scripted graph at all.
 *
 * What this lane can honestly pin: the intent's identity — component
 * and flags — read back from the shadow's saved intent. What it cannot:
 * the platform's actual stack behavior, which stays the on-emulator
 * smoke's real-tap check (a tapped notification resumes the SAME
 * ActivityRecord, no second instance) and the human checklist's tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackServiceNotificationIntentTest {
    @Test
    fun theNotificationTapRoutesToTheExistingMainActivityInstance() {
        val service = Robolectric.buildService(PlaybackService::class.java).get()

        val pendingIntent: PendingIntent = service.mainActivityIntent()
        val contentIntent: Intent = shadowOf(pendingIntent).savedIntent

        assertEquals(
            ComponentName(service, MainActivity::class.java),
            contentIntent.component,
            "the content intent must target the app's only activity",
        )
        assertTrue(
            (contentIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0,
            "the content intent must carry FLAG_ACTIVITY_CLEAR_TOP — bring the live instance forward, don't stack a new one",
        )
        assertTrue(
            (contentIntent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0,
            "the content intent must carry FLAG_ACTIVITY_SINGLE_TOP — with CLEAR_TOP it routes the tap to the existing instance",
        )
    }
}
