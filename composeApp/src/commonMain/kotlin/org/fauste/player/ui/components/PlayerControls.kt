// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.format.formatClock
import org.fauste.player.ui.strings.LocalStrings
import org.fauste.player.ui.strings.StringKeys
import org.fauste.player.ui.theme.Timecode

/**
 * Stateless transport controls: play/pause toggle, previous, seek bar with
 * a position/duration timecode, and a volume slider. Pure UI — playback
 * state goes in, intent callbacks come out. No engine, repository or
 * ViewModel type crosses this boundary; screens map their own state layer
 * ([org.fauste.player.player.PlaybackState]) into these parameters and
 * route the callbacks into PlayerController/QueueManager.
 *
 * The seek slider commits continuously (every drag tick calls [onSeek]);
 * with a state-in/state-out contract the parent simply echoes the new
 * position back, and the Task 3 ViewModel may throttle if the engine
 * wants it.
 *
 * Honest limits: while no track is loaded ([durationSeconds] 0.0 — the
 * duration a track has until a services layer fills it) the timecode shows
 * "00:00 / 00:00" and the seek bar is DISABLED rather than pretending a
 * seekable range exists. Volume always works (0.0..1.0, matching the
 * FFI's f32 set_volume).
 *
 * Accessibility — the Task 6 bar starts here: every actionable control
 * carries a contentDescription, and the play/pause toggle additionally
 * exposes a stateDescription ("Playing"/"Paused") so TalkBack announces
 * the transport state, not just the button's action.
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    positionSeconds: Double,
    durationSeconds: Double,
    volume: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Double) -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            // The timecode arrives pre-formatted by formatClock — the mono
            // readout is media, not prose, and no locale may reorder it;
            // the seam owns only the " / " separator.
            text =
                strings[
                    StringKeys.TIMECODE,
                    "position" to formatClock(positionSeconds),
                    "duration" to formatClock(durationSeconds),
                ],
            style = MaterialTheme.typography.Timecode,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Slider(
            value = seekFraction(positionSeconds, durationSeconds),
            onValueChange = { fraction -> onSeek(fraction * durationSeconds) },
            enabled = durationSeconds > 0.0,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = strings[StringKeys.CONTROL_SEEK] },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    // Transport icons stay LTR-only: media controls describe
                    // the deck, not reading direction, so they are not
                    // AutoMirrored.
                    contentDescription = strings[StringKeys.CONTROL_PREVIOUS],
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier =
                    Modifier.semantics {
                        stateDescription =
                            strings[if (isPlaying) StringKeys.CONTROL_PLAYING else StringKeys.CONTROL_PAUSED]
                    },
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription =
                        strings[if (isPlaying) StringKeys.CONTROL_PAUSE else StringKeys.CONTROL_PLAY],
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Decorative: the volume slider below carries the semantics.
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = volume.coerceIn(0f, 1f),
                onValueChange = onVolumeChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp)
                        .semantics { contentDescription = strings[StringKeys.CONTROL_VOLUME] },
            )
        }
    }
}

/**
 * Slider position as a 0..1 fraction; 0 when the duration is unknown (0.0)
 * so the bar rests at the start instead of rendering NaN — see the honest
 * limits note on [PlayerControls].
 */
private fun seekFraction(
    positionSeconds: Double,
    durationSeconds: Double,
): Float =
    if (durationSeconds > 0.0) {
        (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
