package org.fauste.player.service

import org.fauste.player.player.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE SERVICE LIFECYCLE POLICY (Ruling 5, as a total function): Play
 * promotes/starts the foreground service, Paused keeps it alive with its
 * notification (like every music player), Stopped tears it down. The
 * policy is a pure mapping so BOTH consumers share one rule — the
 * Application-side starter (StartForeground → startForegroundService)
 * and the service's notification funnel (StopService → exit foreground
 * + stopSelf) — and a scripted status sequence pins the transitions the
 * service's lifecycle KDoc documents.
 */
class PlaybackServiceTriggerTest {
    @Test
    fun playingStartsTheForegroundService() {
        assertEquals(ServiceTrigger.StartForeground, serviceTriggerFor(PlaybackStatus.Playing))
    }

    @Test
    fun pausedKeepsTheServiceAliveWithItsNotification() {
        assertEquals(ServiceTrigger.KeepAlive, serviceTriggerFor(PlaybackStatus.Paused))
    }

    @Test
    fun stoppedTearsTheServiceDown() {
        assertEquals(ServiceTrigger.StopService, serviceTriggerFor(PlaybackStatus.Stopped))
    }

    /** The full Ruling-5 lifecycle as one scripted transcript: play → pause → play → stop. */
    @Test
    fun aPlayPausePlayStopTranscriptProducesTheRuling5Sequence() {
        val transcript =
            listOf(
                PlaybackStatus.Stopped,
                PlaybackStatus.Playing,
                PlaybackStatus.Paused,
                PlaybackStatus.Playing,
                PlaybackStatus.Stopped,
            ).map(::serviceTriggerFor)

        assertEquals(
            listOf(
                ServiceTrigger.StopService, // the pre-play state: the starter skips it; the service is not running
                ServiceTrigger.StartForeground,
                ServiceTrigger.KeepAlive,
                ServiceTrigger.StartForeground, // resume after pause re-arms the (already-running) service
                ServiceTrigger.StopService,
            ),
            transcript,
        )
    }
}
