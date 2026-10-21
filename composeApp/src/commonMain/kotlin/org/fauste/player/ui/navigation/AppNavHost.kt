// Composable factory functions are PascalCase by framework convention;
// ktlint 1.7.1's function-naming rule predates its @Composable awareness,
// so it is suppressed here the same way ui/theme/Theme.kt documents.
@file:Suppress("ktlint:standard:function-naming")

package org.fauste.player.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.fauste.player.ui.screens.LibraryScreen
import org.fauste.player.ui.screens.PlayerScreen
import org.fauste.player.ui.screens.SettingsScreen
import org.fauste.player.ui.strings.LocalStrings
import org.fauste.player.ui.strings.StringKeys

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
 * [onImportClick] is the Library's IMPORT SEAM passed down from the host
 * Activity (MainActivity owns the ActivityResult launchers — permission
 * gate + SAF picker; see LibraryScreen's KDoc for the full contract).
 *
 * Colours come from the composition's FausteTheme (Nocturne scheme);
 * labels come from [AppDestinations].
 *
 * Accessibility: every item carries a text label AND an explicit
 * contentDescription; the icon is decorative, so TalkBack announces the
 * destination name and the tests can address each tab.
 */
@Composable
fun AppNavHost(
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var currentRoute by rememberSaveable { mutableStateOf(AppDestinations.start.route) }
    val current = AppDestinations.fromRoute(currentRoute)
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                AppDestinations.entries.forEach { destination ->
                    // The label resolves ONCE per composition here: the
                    // item's visible text and its contentDescription are
                    // the SAME resolved string, so TalkBack announces
                    // exactly what the eye reads (the Task-5 i18n seam).
                    val label = strings[destination.labelKey]
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = { currentRoute = destination.route },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = label },
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
                AppDestinations.Library -> LibraryScreen(onImportClick = onImportClick)
                AppDestinations.Settings -> SettingsScreen()
            }
        }
    }
}
