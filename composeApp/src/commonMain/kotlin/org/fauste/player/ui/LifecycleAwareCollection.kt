package org.fauste.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.StateFlow

/**
 * THE LIFECYCLE-AWARE COLLECTION SEAM (Task 3, Ruling 3) — the
 * screens' single state streams are collected through this helper
 * instead of plain `collectAsState()`.
 *
 * **Why the seam exists** (the Phase-4 final review's battery gap #2):
 * plain `collectAsState()` collects unconditionally and survives
 * ON_STOP — while the keep-awake gate deliberately keeps
 * [org.fauste.player.player.PlayerController.engineState] emitting at
 * 10 Hz while the app is backgrounded AND playing (the playback
 * service's notification is fed by that flow), a backgrounded
 * composition recomposed at up to 10 Hz all night. The fix gates only
 * the collection: ON_STOP (lifecycle below STARTED) stops it;
 * ON_START resumes from the LATEST state (StateFlow conflation — the
 * missed values are skipped, never replayed).
 *
 * **The mechanism choice** (documented per the ruling): on Android the
 * actual delegates to `collectAsStateWithLifecycle` from
 * `androidx.lifecycle:lifecycle-runtime-compose` (2.9.4, the exact
 * lifecycle line already on the classpath via activity-compose — see
 * gradle/libs.versions.toml), whose `repeatOnLifecycle(STARTED)`
 * wiring is the platform-idiomatic gate. The dependency is declared on
 * androidMain only: common code sees this expect, so the screens carry
 * no androidx import and the collection stays swappable per target. A
 * non-Android actual needs that target's own lifecycle definition —
 * none exists today (no second target is configured), so none is
 * written; this note is the honest record of that boundary.
 *
 * **No UDF semantic change**: the surface is StateFlow-only (every
 * screen ViewModel exposes one), and the initial value is the flow's
 * current value — exactly `collectAsState()`'s shape. The state layer
 * itself is untouched: its own background behavior is the poll-idling
 * feature plus the service keep-awake gate (PlayerController's KDoc) —
 * the gate feeds the SERVICE; this gate stops the COMPOSITION from
 * paying for it.
 *
 * Pinned behaviorally by [LifecycleAwareCollectionTest] on the
 * Robolectric lane (ON_STOP freeze + keep-awake feed + ON_START
 * catch-up).
 */
@Composable
expect fun <T> StateFlow<T>.collectUiStateWithLifecycle(): State<T>
