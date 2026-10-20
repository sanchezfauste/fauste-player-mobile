// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.collectUiStateWithLifecycle
import org.fauste.player.viewmodel.SettingsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Settings screen, spec §5's UDF end for the backend picker, the
 * remote status and the volume: [SettingsViewModel]'s single state
 * stream down (via [collectUiStateWithLifecycle] — lifecycle-aware
 * collection; the helper's KDoc holds the battery contract), intents up
 * (method references into the VM) — NO engine or repository call ever
 * happens in the composition.
 *
 * THE BACKEND ROWS ARE HONEST ABOUT AVAILABILITY: a row shows the
 * probed name + availability and is DISABLED while unavailable — the
 * desktop settings picker's rule (ffi.rs `set_backend`: "the settings
 * UI simply offers only the available ones"), even though the engine
 * would still ACCEPT a known-but-unavailable id and keep retrying it.
 * The selected marker follows the ENGINE's own in-use report, never the
 * last tap, so a switch in its apply window (or an engine fallback)
 * reads as what actually plays, not what was asked.
 *
 * Honest limits, never invented data: an empty backends list says so
 * (the probe has not answered — [error] carries the why when it
 * failed); the remote section shows the enabled flag and the socket
 * address, an em dash while the address is blank (the FFI's
 * retrying-bind shape — an error field is the Phase-5 FFI candidate);
 * the volume slider shows the pending drag target while one is in
 * flight (instant feedback) with an "Applying…" label for the
 * commit-and-confirm tail — the VM's throttle owns when the engine is
 * actually told (its KDoc).
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectUiStateWithLifecycle()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Output backend",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (state.backends.isEmpty()) {
            Text(
                text = "No backends reported.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.backends.forEach { backend ->
                BackendRow(
                    name = backend.name,
                    available = backend.available,
                    selected = backend.id == state.selectedBackendId,
                    onSelect = { viewModel.onSelectBackend(backend.id) },
                )
            }
        }
        Text(
            text = "Remote",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text =
                    if (state.remote.enabled) {
                        "On at ${state.remote.address.ifBlank { "—" }}"
                    } else {
                        "Off"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::onRefreshRemote) {
                Text("Refresh")
            }
        }
        Text(
            text = "Volume",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Slider(
            value = state.sliderVolume.coerceIn(0f, 1f),
            onValueChange = viewModel::onVolume,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Volume" },
        )
        if (state.pendingVolume != null) {
            Text(
                text = "Applying…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * One backend row: the radio selected-marker, the probed name and its
 * availability, disabled while unavailable (the picker's rule — see
 * the screen KDoc). The row itself carries the click (the RadioButton's
 * own onClick is null so a tap lands in ONE place) and a spoken
 * description that reads name, availability and selected state in one
 * string — TalkBack and the tests address rows by it.
 */
@Composable
private fun BackendRow(
    name: String,
    available: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = available, onClick = onSelect)
                .padding(vertical = 8.dp)
                .semantics {
                    val availability = if (available) "available" else "unavailable"
                    val selection = if (selected) "selected" else "not selected"
                    contentDescription = "$name, $availability, $selection"
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = available)
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (available) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = if (available) "Available" else "Unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
