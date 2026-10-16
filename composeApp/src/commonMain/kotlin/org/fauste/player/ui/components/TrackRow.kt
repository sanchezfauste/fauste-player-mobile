// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.format.formatClock
import org.fauste.player.ui.theme.NocturneColors
import org.fauste.player.ui.theme.Timecode

/**
 * Stateless queue/library row: track title, duration timecode, and — for
 * the row of the track currently playing — a teal dot in
 * [NocturneColors.Playing]. Pure UI: primitives in, the tap-to-jump
 * intent out; screens own the mapping from their state layer to these
 * parameters.
 *
 * Honest limits: a duration of 0.0 means "unknown, not filled by the
 * services layer yet" and renders an em dash — never a fake "00:00".
 *
 * Accessibility: the whole row is one clickable target (title text is the
 * semantic label); the playing dot carries a "Now playing"
 * contentDescription only while visible, so TalkBack announces state
 * changes, and nothing is announced for a silent row.
 */
@Composable
fun TrackRow(
    title: String,
    durationSeconds: Double = 0.0,
    isPlaying: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The slot always occupies its width so text alignment is stable
        // across playing/paused states; the dot and its semantics appear
        // only while playing.
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .then(
                        if (isPlaying) {
                            Modifier
                                .background(NocturneColors.Playing, CircleShape)
                                .semantics { contentDescription = "Now playing" }
                        } else {
                            Modifier
                        },
                    ),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = true),
        )
        Text(
            text = if (durationSeconds > 0.0) formatClock(durationSeconds) else "—",
            style = MaterialTheme.typography.Timecode,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
