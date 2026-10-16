// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.fauste.player.ui.screens.PlayerScreen

/**
 * The 3-destination app shell.
 *
 * NAVIGATION JUDGMENT (documented, as the plan asks): the three destinations
 * are flat — no arguments, no nested graphs, no deep links — so the
 * navigation is a sealed-route switch over a `rememberSaveable`d route
 * string rather than a navigation-graph library. Material3's bottom bar
 * (NavigationBar) is the standard mobile shape for exactly three peer
 * screens, and androidx.navigation (or its JetBrains multiplatform fork)
 * would add a dependency and a graph DSL to express what this switch already
 * says. Honest limit: the route string survives configuration changes and
 * process death; there is deliberately no back-stack semantics — switching
 * tabs never nests screens. Revisit this ruling the moment a destination
 * needs arguments or an external deep link.
 *
 * Colours come from the composition's FausteTheme (Nocturne scheme);
 * labels come from [AppDestinations].
 *
 * Accessibility: every item carries a text label AND an explicit
 * contentDescription; the icon is decorative, so TalkBack announces the
 * destination name and the tests can address each tab.
 */
@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    var currentRoute by rememberSaveable { mutableStateOf(AppDestinations.start.route) }
    val current = AppDestinations.fromRoute(currentRoute)
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                AppDestinations.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = { currentRoute = destination.route },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                        modifier = Modifier.semantics { contentDescription = destination.label },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            when (current) {
                AppDestinations.Player -> PlayerScreen()
                AppDestinations.Library -> ComingTaskPlaceholder("Library")
                AppDestinations.Settings -> ComingTaskPlaceholder("Settings")
            }
        }
    }
}

/**
 * Honest placeholder for a tab whose screen lands in a later phase task
 * (Task 4 wires the Library's queue list): the destination is reachable and
 * labeled now, and says so instead of faking content.
 */
@Composable
private fun ComingTaskPlaceholder(title: String) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "$title screen arrives in a later task.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
