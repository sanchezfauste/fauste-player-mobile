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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.components.PlayerControls
import org.fauste.player.viewmodel.PlayerViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Player screen, spec §5's UDF end: [PlayerViewModel]'s single state
 * stream down (via [collectAsState]), intents up (method references into the
 * VM) — NO engine call ever happens in the composition, so nothing here can
 * jank or crash the frame; even the ViewModel is resolved through Koin
 * ([koinViewModel]) by default rather than looked up ad hoc.
 *
 * Honest limits: the title is the state's em dash while no track is on air
 * (never a fake title); the artist line appears only when the FFI carries
 * one; the latest command error renders in the scheme's error colour while
 * non-null (errors are surfaced, never swallowed — and never a crash).
 *
 * The seek bar reflects [org.fauste.player.viewmodel.PlayerUiState.position]
 * — the pending drag target while one is in flight (instant feedback: the
 * bar tracks the finger), the engine's polled position otherwise; the
 * ViewModel's debounce owns when the engine actually seeks (its KDoc).
 */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
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
    }
}
