// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.collectUiStateWithLifecycle
import org.fauste.player.ui.components.PlayerControls
import org.fauste.player.ui.theme.NocturneColors
import org.fauste.player.viewmodel.PlayerViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Player screen, spec §5's UDF end: [PlayerViewModel]'s single state
 * stream down (via [collectUiStateWithLifecycle] — lifecycle-aware
 * collection: ON_STOP stops the recomposition while the state layer
 * itself keeps running; the helper's KDoc holds the battery contract),
 * intents up (method references into the VM) — NO engine call ever
 * happens in the composition, so nothing here can jank or crash the
 * frame; even the ViewModel is resolved through Koin
 * ([koinViewModel]) by default rather than looked up ad hoc.
 *
 * Honest limits: the title is the state's em dash while no track is on air
 * (never a fake title); the artist line appears only when the FFI carries
 * one; the latest command error renders in the scheme's error colour while
 * non-null (errors are surfaced, never swallowed — and never a crash).
 *
 * The "Up next" line (Phase 5): it renders only while an ARM exists
 * ([PlayerUiState.upNextTitle] non-null) — the armed row's title from the
 * queue flow, or the em dash while the arm cannot be placed in it. Nothing
 * armed → no line: the surface carries no derived-next read, and rendering
 * one would be exactly the play-now promise the arm-only contract forbids.
 *
 * The seek bar reflects [org.fauste.player.viewmodel.PlayerUiState.position]
 * — the pending drag target while one is in flight (instant feedback: the
 * bar tracks the finger), the engine's polled position otherwise; the
 * ViewModel's debounce owns when the engine actually seeks (its KDoc).
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectUiStateWithLifecycle()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.trackTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        )
        state.trackArtist?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
        }
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        PlayerControls(
            isPlaying = state.isPlaying,
            positionSeconds = state.position,
            durationSeconds = state.playback.duration,
            volume = state.volume,
            onPlayPause = viewModel::onPlayPause,
            onPrevious = viewModel::onPrevious,
            onSeek = viewModel::onSeek,
            onVolumeChange = viewModel::onVolume,
        )
        // The up-next line, under the controls: the armed row's title, or
        // the honest em dash while the queue flow cannot place the arm.
        state.upNextTitle?.let { title ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Up next: $title",
                style = MaterialTheme.typography.bodyMedium,
                color = NocturneColors.Warning,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .semantics { contentDescription = "Up next: $title" },
            )
        }
    }
}
