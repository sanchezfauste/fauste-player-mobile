// Filename mandated by the Phase 3 task brief; class lives with the
// wake-lock/lifecycle contract, not its own file.
@file:Suppress("ktlint:standard:filename")

package org.fauste.player.platform

/**
 * Plain holder per the `expect` contract's dependency-discipline note: no
 * `androidx.lifecycle` this phase.
 */
actual class AppLifecycleHolder {
    @Volatile
    private var state: AppLifecycleState = AppLifecycleState.Foreground

    actual fun current(): AppLifecycleState = state

    actual fun update(state: AppLifecycleState) {
        this.state = state
    }
}
