package org.fauste.player.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * THE TEST LIFECYCLE OWNER (Task 3, Ruling 3): the screens collect their
 * state flows through the lifecycle-aware helper, which gates collection
 * on the composition's [androidx.lifecycle.compose.LocalLifecycleOwner]
 * being at least STARTED. The Robolectric compose lane's own test
 * activity is a bare ComponentActivity whose registry is the rule's, not
 * the suite's, to drive — so every screen suite provides THIS owner into
 * the composition and drives it by hand:
 *
 * - [ScreenTestLifecycleOwner.start] (RESUMED) collects — the normal
 *   rendering path every existing assertion waits on;
 * - [ScreenTestLifecycleOwner.stop] (CREATED) dispatches ON_STOP and
 *   must STOP collection — the ON_STOP/ON_START pin in
 *   [LifecycleAwareCollectionTest] drives exactly this pair.
 *
 * A real registry (not a stub) so the helper's `repeatOnLifecycle` wiring
 * is exercised by the same machinery production uses.
 */
class ScreenTestLifecycleOwner(
    initial: Lifecycle.State = Lifecycle.State.RESUMED,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = initial
    }

    /** The foregrounded half: ON_START then ON_RESUME. */
    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** The backgrounded half: ON_PAUSE then ON_STOP (collection stops). */
    fun stop() {
        registry.currentState = Lifecycle.State.CREATED
    }
}
