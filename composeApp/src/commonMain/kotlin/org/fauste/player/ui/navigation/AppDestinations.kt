package org.fauste.player.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's destinations, sealed: Player, Library, Settings — the plan's
 * three flat routes. The type carries everything the bottom bar draws:
 * a stable [route] (the string the navigation state saves/restores and the
 * tests address), a human [label] (shown AND announced — the item's
 * contentDescription), and a decorative [icon].
 *
 * Usage reads as `AppDestinations.Player`; the plural type name matches
 * this file's mandated name (the plan's own file list names it
 * AppDestinations.kt).
 */
sealed interface AppDestinations {
    /** The saved navigation state keys on this stable string. */
    val route: String

    /** The label shown in the bottom bar and announced by TalkBack. */
    val label: String

    /** The bottom-bar icon; decorative — the label carries the semantics. */
    val icon: ImageVector

    data object Player : AppDestinations {
        override val route = "player"
        override val label = "Player"
        override val icon = Icons.Filled.PlayArrow
    }

    data object Library : AppDestinations {
        override val route = "library"
        override val label = "Library"
        override val icon = Icons.Filled.LibraryMusic
    }

    data object Settings : AppDestinations {
        override val route = "settings"
        override val label = "Settings"
        override val icon = Icons.Filled.Settings
    }

    companion object {
        /** All destinations, in bottom-bar order. */
        val entries: List<AppDestinations> = listOf(Player, Library, Settings)

        /** The destination a cold launch lands on. */
        val start: AppDestinations = Player

        /** Restores a destination from its route; an unknown string falls back to Player (never a crash). */
        fun fromRoute(route: String): AppDestinations = entries.firstOrNull { it.route == route } ?: Player
    }
}
