// Filename mandated by the Phase 3 task brief; class lives with the
// wake-lock/lifecycle contract, not its own file.
@file:Suppress("ktlint:standard:filename")

package org.fauste.player.platform

/**
 * Wake-lock handle keeping the CPU alive during playback with the screen off.
 * Idempotent: repeated [acquire] while held and repeated [release] while
 * released are no-ops.
 *
 * The constructor is platform-specific and intentionally not part of the
 * `expect` contract: common code only ever receives an instance from DI.
 */
expect class WakeLockGuard {
    fun acquire()

    fun release()
}
