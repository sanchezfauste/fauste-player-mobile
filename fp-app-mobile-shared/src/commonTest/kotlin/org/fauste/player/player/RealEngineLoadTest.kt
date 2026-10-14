package org.fauste.player.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Ruling-7 load proof: the host-JVM unit tests construct the REAL
 * FausteEngine (Rust cdylib via JNA; Null-backend fallback off-Android) —
 * no stub, no fake. If this test fails, the cdylib wiring in the shared
 * `build.gradle.kts` is broken and every other test in this package is
 * meaningless.
 */
class RealEngineLoadTest {
    @Test
    fun theRealEngineConstructsAndReadsStateOnTheHostJvm() {
        val dirs = RealEngineFixture.TempDirs("load")
        try {
            RealEngineFixture.newEngine(dirs).use { engine ->
                // The boot state of a fresh core, exactly like the Rust host
                // test `enqueue_play_pause_seek_stop_and_volume` asserts.
                assertEquals("stopped", engine.getState().status)
            }
        } finally {
            dirs.cleanup()
        }
    }
}
