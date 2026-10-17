package org.fauste.player.platform

enum class AppLifecycleState {
    Foreground,
    Background,
}

/**
 * Minimal foreground/background signal surface. Deliberately NOT backed by
 * `ProcessLifecycleOwner`: that would pull in `androidx.lifecycle` for one
 * observer, which is poor dependency discipline this early. This is a plain
 * holder; the app wires it to real lifecycle events in Phase 4 (the
 * poll-idling consumer landed with Phase 4 Task 5: MainActivity's
 * onStart/onStop → holder → PlayerController).
 *
 * The constructor is platform-specific and intentionally not part of the
 * `expect` contract: common code only ever receives an instance from DI.
 */
expect class AppLifecycleHolder {
    fun current(): AppLifecycleState

    fun update(state: AppLifecycleState)
}
