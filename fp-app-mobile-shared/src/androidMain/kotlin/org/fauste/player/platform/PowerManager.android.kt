// Filename mandated by the Phase 3 task brief; class lives with the
// wake-lock/lifecycle contract, not its own file.
@file:Suppress("ktlint:standard:filename")

package org.fauste.player.platform

import android.content.Context
import android.os.PowerManager

actual class WakeLockGuard(
    context: Context,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    actual fun acquire() {
        if (wakeLock?.isHeld == true) return
        wakeLock =
            powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    @Synchronized
    actual fun release() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private companion object {
        const val WAKE_LOCK_TAG = "fauste:playback"

        // Ten minutes, re-acquired on track change; bounds the leak if
        // release() is never reached (crash path).
        const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
    }
}
