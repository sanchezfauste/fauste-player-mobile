package org.fauste.player.service

import org.fauste.player.player.PlaybackStatus
import org.fauste.player.player.engineState
import org.fauste.player.player.playlistEntry
import org.fauste.player.player.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE SESSION BRIDGE'S PURE HALF — the Task-6 mapping the media3 adapter
 * consumes, pinned WITHOUT any Android or media3 type on the classpath
 * (the adapter glue over SimpleBasePlayer is the Robolectric lane's
 * job; every RULE lives here, the glue only translates).
 *
 * The rules this suite pins (the service's design KDoc carries the why):
 * - the FFI's status strings map through the state layer's own
 *   [org.fauste.player.player.PlaybackStatus.fromEngine] — one source,
 *   including its never-throw fallback to Stopped;
 * - playWhenReady mirrors isPlaying (the engine carries no separate
 *   user-intent field: the transport IS the intent);
 * - a playing/paused engine is media3-READY, a stopped engine IDLE —
 *   but ONLY while the queue has rows: media3's State.build() rejects
 *   a non-empty-state over an empty playlist, and the queue flow CAN
 *   momentarily disagree with the engine (the remote-mutation stale
 *   window), so the clamp is a crash guard, not a state machine;
 * - position/duration cross the seconds→ms boundary by rounding, and
 *   an unknown duration (the FFI's 0.0-until-filled shape) maps to the
 *   UNSET sentinel, never a lying zero;
 * - the current queue row is matched by PATH (the FFI's Track record
 *   carries no id), and an on-air path absent from the queue rows — the
 *   stale-queue corner — reads as INDEX_UNSET, never a guessed row.
 */
class SessionPlaybackSnapshotTest {
    @Test
    fun aPlayingEngineWithAQueueIsReadyAndPlaying() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(
                    status = "playing",
                    position = 61.5,
                    duration = 180.0,
                    volume = 0.75f,
                    track = track(title = "On Air", artist = "Someone", path = "/music/a.flac"),
                ),
                listOf(playlistEntry(id = 1u, title = "Track 1", path = "/music/a.flac", duration = 180.0)),
            )

        assertEquals(PlaybackStatus.Playing, snapshot.status, "the status rides the snapshot for the service's lifecycle signal")
        assertTrue(snapshot.isPlaying, "a playing engine must report isPlaying")
        assertTrue(snapshot.playWhenReady, "the engine carries no intent field: playing IS the intent")
        assertTrue(snapshot.playbackReady, "a playing engine over a queue is READY")
        assertEquals(61_500, snapshot.positionMs, "position must cross the seconds→ms boundary by rounding")
        assertEquals(180_000, snapshot.durationMs)
        assertEquals(0.75f, snapshot.volume, "the engine's linear gain rides the snapshot untouched")
        assertEquals("On Air", snapshot.currentTitle)
        assertEquals("Someone", snapshot.currentArtist)
        assertEquals(0, snapshot.currentQueueIndex, "the on-air path must match its queue row")
    }

    @Test
    fun aPausedEngineIsReadyButNotPlaying() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(
                    status = "paused",
                    track = track(path = "/music/a.flac"),
                ),
                listOf(playlistEntry(id = 1u, path = "/music/a.flac")),
            )

        assertFalse(snapshot.isPlaying)
        assertFalse(snapshot.playWhenReady, "a paused transport is a paused intent, not a pending play")
        assertTrue(snapshot.playbackReady, "paused keeps the session READY — the notification stays (Ruling 5)")
    }

    @Test
    fun aStoppedEngineIsIdle() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(status = "stopped", track = track(path = "/music/a.flac")),
                listOf(playlistEntry(id = 1u, path = "/music/a.flac")),
            )

        assertFalse(snapshot.isPlaying)
        assertFalse(snapshot.playWhenReady)
        assertFalse(snapshot.playbackReady, "stopped maps to the media3 IDLE state")
    }

    @Test
    fun anUnknownStatusStringFallsBackToStoppedLikeTheStateLayer() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(status = "buffering", track = track(path = "/music/a.flac")),
                listOf(playlistEntry(id = 1u, path = "/music/a.flac")),
            )

        assertFalse(snapshot.playbackReady, "the never-throw fallback must hold through the session mapping too")
        assertFalse(snapshot.isPlaying)
    }

    @Test
    fun anUnknownDurationMapsToTheUnsetSentinelNeverAZero() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(status = "playing", position = 10.0, duration = 0.0, track = track(path = "/music/a.flac")),
                listOf(playlistEntry(id = 1u, path = "/music/a.flac", duration = 0.0)),
            )

        assertEquals(
            SessionPlaybackSnapshot.UNSET_DURATION_MS,
            snapshot.durationMs,
            "0.0 is the FFI's not-yet-filled duration, not a real zero",
        )
        assertEquals(
            SessionPlaybackSnapshot.UNSET_DURATION_MS,
            snapshot.queue.single().durationMs,
            "a queue row's unknown duration must also read as UNSET",
        )
    }

    @Test
    fun theOnAirPathMatchesItsRowByPathAndByFirstMatch() {
        val queue =
            listOf(
                playlistEntry(id = 1u, path = "/music/a.flac"),
                playlistEntry(id = 2u, path = "/music/b.flac"),
                playlistEntry(id = 3u, path = "/music/b.flac"), // duplicate path: the first row wins
            )
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(status = "playing", track = track(path = "/music/b.flac")),
                queue,
            )

        assertEquals(1, snapshot.currentQueueIndex, "the FIRST row carrying the on-air path is the current one")
        assertEquals(3, snapshot.queue.size, "the whole queue must ride the snapshot for the session timeline")
        assertEquals(2u, snapshot.queue[1].id)
        assertEquals("Track 2", snapshot.queue[1].title)
    }

    @Test
    fun theOnAirEntryIdWinsOverThePathJoinAndDisambiguatesDuplicates() {
        // The Phase-5 regression this task closes: two rows share a path,
        // and the on-air one is the SECOND — the path join flags/places
        // the first, the id join places the true row.
        val queue =
            listOf(
                playlistEntry(id = 1u, title = "First copy", path = "/music/b.flac"),
                playlistEntry(id = 2u, title = "Second copy", path = "/music/b.flac"),
            )
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(
                    status = "playing",
                    track = track(path = "/music/b.flac"),
                    currentEntryId = 2u,
                ),
                queue,
            )

        assertEquals(1, snapshot.currentQueueIndex, "the ID join places the true on-air row, not the first path match")
        assertEquals(2u, snapshot.queue[1].id)
    }

    @Test
    fun theOnAirIdMissingFromTheQueueReadsAsIndexUnsetNeverAGuessedRow() {
        // The id is authoritative while carried: over a stale queue the
        // on-air id may be gone (remote mutation), and falling back to
        // the path could pick a duplicate-path row that is NOT the
        // on-air entry — INDEX_UNSET instead, never a guess.
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(
                    status = "playing",
                    track = track(path = "/music/b.flac"),
                    currentEntryId = 9u,
                ),
                listOf(playlistEntry(id = 1u, path = "/music/b.flac")),
            )

        assertEquals(
            SessionPlaybackSnapshot.INDEX_UNSET,
            snapshot.currentQueueIndex,
            "the id answered; a path fallback over a stale queue would guess a row",
        )
    }

    @Test
    fun anOnAirPathMissingFromTheQueueReadsAsIndexUnsetButKeepsEngineMetadata() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(status = "playing", track = track(title = "Elsewhere", path = "/music/elsewhere.flac")),
                listOf(playlistEntry(id = 1u, path = "/music/a.flac")),
            )

        assertEquals(
            SessionPlaybackSnapshot.INDEX_UNSET,
            snapshot.currentQueueIndex,
            "a guessed row would lie about what is on air",
        )
        assertEquals(
            "Elsewhere",
            snapshot.currentTitle,
            "the engine's own on-air report survives a queue that cannot place it",
        )
    }

    @Test
    fun aPlayingEngineOverAnEmptyQueueClampsToNotReady() {
        val snapshot =
            SessionPlaybackSnapshot.from(engineState(status = "playing", track = track()), emptyList())

        assertFalse(
            snapshot.playbackReady,
            "media3's State.build() rejects a non-IDLE state over an empty timeline — the clamp is the crash guard",
        )
        assertEquals(0, snapshot.queue.size)
        assertEquals(SessionPlaybackSnapshot.INDEX_UNSET, snapshot.currentQueueIndex)
    }

    @Test
    fun blankOnAirMetadataReadsAsNullsNeverFakePlaceholders() {
        val snapshot =
            SessionPlaybackSnapshot.from(
                engineState(status = "playing", track = track(title = "", artist = "")),
                listOf(playlistEntry(id = 1u)),
            )

        assertNull(snapshot.currentTitle, "a blank title is no title — the notification shows the app, not a fake")
        assertNull(snapshot.currentArtist)
    }

    @Test
    fun noTrackOnAirReadsAsNullMetadataAndUnsetIndex() {
        val snapshot =
            SessionPlaybackSnapshot.from(engineState(status = "stopped"), listOf(playlistEntry(id = 1u)))

        assertNull(snapshot.currentTitle)
        assertNull(snapshot.currentArtist)
        assertEquals(SessionPlaybackSnapshot.INDEX_UNSET, snapshot.currentQueueIndex)
    }

    @Test
    fun theIdleSeedIsTheFreshEngineShape() {
        assertEquals(
            SessionPlaybackSnapshot.from(engineState(status = "stopped"), emptyList()),
            SessionPlaybackSnapshot.idle,
            "the seed the adapter boots with must equal a fresh engine's projection",
        )
    }
}
