package org.fauste.player.player

import org.fauste.player.rust.EngineState
import org.fauste.player.rust.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `PlaybackState.fromEngineState` is a pure projection — no engine, no FFI
 * — pinning every field the UI will draw (spec §5's PlayerUiState trimmed
 * to what the FFI exposes).
 */
class PlaybackStateTest {
    private fun track(title: String) =
        Track(
            path = "/music/$title.wav",
            title = title,
            artist = "an artist",
            duration = 12.5,
            sampleRate = 44_100u,
            channels = 2u,
        )

    @Test
    fun everyEngineStateFieldLandsInTheUiSnapshot() {
        val state =
            EngineState(
                status = "playing",
                position = 3.25,
                duration = 12.5,
                currentTrack = track("tone"),
                volume = 0.5f,
                backend = "null",
            )
        val ui = PlaybackState.fromEngineState(state)
        assertEquals(PlaybackStatus.Playing, ui.status)
        assertEquals(3.25, ui.position)
        assertEquals(12.5, ui.duration)
        assertEquals("tone", ui.currentTrack?.title)
        assertEquals("an artist", ui.currentTrack?.artist)
        assertEquals(12.5, ui.currentTrack?.duration)
        assertEquals(0.5f, ui.volume)
        assertEquals("null", ui.backendName)
    }

    @Test
    fun aTracklessEngineStateMapsToANullCurrentTrack() {
        val state =
            EngineState(
                status = "stopped",
                position = 0.0,
                duration = 0.0,
                currentTrack = null,
                volume = 1.0f,
                backend = "null",
            )
        val ui = PlaybackState.fromEngineState(state)
        assertEquals(PlaybackStatus.Stopped, ui.status)
        assertNull(ui.currentTrack)
        assertEquals(0.0, ui.position)
        assertEquals(1.0f, ui.volume)
    }

    @Test
    fun anUnknownStatusStringStillMapsWithoutThrowing() {
        val state =
            EngineState(
                status = "warping",
                position = 1.0,
                duration = 2.0,
                currentTrack = null,
                volume = 1.0f,
                backend = "null",
            )
        assertEquals(PlaybackStatus.Stopped, PlaybackState.fromEngineState(state).status)
    }

    @Test
    fun theDefaultsMatchAFreshEngine() {
        val ui = PlaybackState()
        assertEquals(PlaybackStatus.Stopped, ui.status)
        assertEquals(0.0, ui.position)
        assertEquals(0.0, ui.duration)
        assertNull(ui.currentTrack)
        assertEquals(1.0f, ui.volume)
        assertEquals("", ui.backendName)
    }
}
