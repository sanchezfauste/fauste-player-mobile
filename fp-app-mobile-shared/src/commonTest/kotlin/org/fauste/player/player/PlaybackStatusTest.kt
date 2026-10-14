package org.fauste.player.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 1:1 mapping from the FFI status strings. The string set is exactly
 * what `ffi.rs get_state` reports (mirroring the remote DTO,
 * `fp-remote/src/dto.rs:361-363`): `"playing"` / `"paused"` / `"stopped"`.
 */
class PlaybackStatusTest {
    @Test
    fun everyFfiStatusStringMapsToItsTransport() {
        assertEquals(PlaybackStatus.Playing, PlaybackStatus.fromEngine("playing"))
        assertEquals(PlaybackStatus.Paused, PlaybackStatus.fromEngine("paused"))
        assertEquals(PlaybackStatus.Stopped, PlaybackStatus.fromEngine("stopped"))
    }

    @Test
    fun anUnknownStringFallsBackToStoppedInsteadOfThrowing() {
        // A future engine revision or a corrupted read must never crash the
        // poll loop: the fallback is Stopped — a fresh engine's state and
        // the safest to draw (no phantom progress).
        assertEquals(PlaybackStatus.Stopped, PlaybackStatus.fromEngine("fast-forward"))
        assertEquals(PlaybackStatus.Stopped, PlaybackStatus.fromEngine(""))
        // Case-sensitive on purpose: the FFI strings are all lowercase.
        assertEquals(PlaybackStatus.Stopped, PlaybackStatus.fromEngine("Playing"))
    }

    @Test
    fun aNullStatusFallsBackToStopped() {
        assertEquals(PlaybackStatus.Stopped, PlaybackStatus.fromEngine(null))
    }
}
