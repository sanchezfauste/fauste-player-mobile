package org.fauste.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * The ANDROID actual (Task 3, Ruling 3): delegates directly to
 * androidx.lifecycle's [collectAsStateWithLifecycle] — the
 * `repeatOnLifecycle(STARTED)` gate in its standard form, not a
 * re-implementation. 2.9.4 matches the lifecycle line the app already
 * resolves (activity-compose 1.11.0 → lifecycle-runtime 2.9.4), so the
 * dependency adds the compose artifact without a second lifecycle
 * version. The common expect's KDoc holds the battery-gap reasoning
 * and the seam's contract.
 */
@Composable
actual fun <T> StateFlow<T>.collectUiStateWithLifecycle(): State<T> = collectAsStateWithLifecycle()
