// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.collectUiStateWithLifecycle
import org.fauste.player.ui.components.TrackRow
import org.fauste.player.viewmodel.LibraryImport
import org.fauste.player.viewmodel.LibraryViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Library screen, spec §5's UDF end for the queue: [LibraryViewModel]'s
 * single state stream down (via [collectUiStateWithLifecycle] —
 * lifecycle-aware collection; the helper's KDoc holds the battery
 * contract), intents up (method references into the VM) — NO engine or
 * repository call ever happens in the composition.
 *
 * THE IMPORT SEAM (documented, as the plan asks): the Import button does
 * not open the picker itself. It fires [onImportClick], which the host
 * Activity supplies through [org.fauste.player.ui.navigation.AppNavHost]
 * and OWNS the ActivityResult launchers behind — the audio-read runtime
 * permission gate (per the platform contract) and the SAF document
 * picker over all audio MIME subtypes. The picked
 * URI then flows back INTO the ViewModel
 * ([LibraryViewModel.onImportUri]) from the Activity, so the copy +
 * enqueue and every result state live in the VM where they are testable;
 * only the launchers themselves — pure Android UI machinery — are the
 * Activity's part. An instrumented test would be needed to exercise the
 * launchers on-device; the seam's halves (button → callback, URI →
 * import states) are what the host-JVM suites pin.
 *
 * THE ROW TAP IS ARM-ONLY (the Task-2 carry — see LibraryViewModel's
 * KDoc): a tap arms the row as the player's next without touching the
 * transport, so the row promises nothing it does not do — no play-now
 * affordance. Since Phase 5 the armed row shows the "UP NEXT" badge
 * (TrackRow): the arm the tap made, visible without any play-now
 * promise.
 *
 * Honest limits: a 0.0 duration renders an em dash (TrackRow's rule);
 * an empty queue shows the import call-to-action rather than an empty
 * list; a search that matches nothing says so; import results stay
 * until dismissed (a snackbar's timed dismissal hides state a user may
 * still need — a Dismiss affordance is the honest shape).
 */
@Composable
fun LibraryScreen(
    onImportClick: () -> Unit,
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectUiStateWithLifecycle()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            singleLine = true,
            label = { Text("Search") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search the queue" },
        )
        Button(
            onClick = onImportClick,
            enabled = state.import != LibraryImport.Importing,
        ) {
            Text("Import")
        }
        when (val result = state.import) {
            LibraryImport.Idle -> Unit
            LibraryImport.Importing -> Text("Importing…", style = MaterialTheme.typography.bodySmall)
            is LibraryImport.Imported ->
                ImportResultLine(
                    text = "Imported: ${result.path}",
                    onDismiss = viewModel::onDismissImportResult,
                )
            is LibraryImport.Failed ->
                ImportResultLine(
                    text = result.message,
                    onDismiss = viewModel::onDismissImportResult,
                    isError = true,
                )
        }
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            state.queueEntryCount == 0 ->
                EmptyState("Import music to build the queue", Modifier.weight(1f))
            state.rows.isEmpty() ->
                EmptyState("No tracks match the search.", Modifier.weight(1f))
            else ->
                LazyColumn(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) {
                    // Keyed by the row's playlist-flow id — the Phase-3
                    // rule: row identity is never an enqueue call's
                    // returned id. The id's STRING form, because a
                    // LazyColumn key must be Bundle-saveable on Android
                    // and an autoboxed ULong is not (the string is the
                    // same unique, stable identity).
                    items(state.rows, key = { row -> row.id.toString() }) { row ->
                        TrackRow(
                            title = row.title,
                            durationSeconds = row.durationSeconds,
                            isPlaying = row.isPlaying,
                            isArmed = row.isArmed,
                            onClick = { viewModel.onRowTap(row.id) },
                        )
                    }
                }
        }
    }
}

/** The surfaced import result with its explicit Dismiss — state the user controls, not a timer. */
@Composable
private fun ImportResultLine(
    text: String,
    onDismiss: () -> Unit,
    isError: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

/** The centered honest empties: the empty queue's call to action, the empty search's admission. */
@Composable
private fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
