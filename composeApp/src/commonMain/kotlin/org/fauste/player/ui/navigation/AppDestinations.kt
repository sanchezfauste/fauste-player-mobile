package org.fauste.player.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import org.fauste.player.ui.strings.StringKeys

/**
 * The app's destinations, sealed: Player, Library, Settings — the plan's
 * three flat routes. The type carries everything the bottom bar draws:
 * a stable [route] (the string the navigation state saves/restores and the
 * tests address), a [labelKey] (the Fluent message id whose RESOLVED
 * string is shown AND announced — the i18n seam renders it; the Task-5
 * migration turned the label into a key so the bottom bar speaks the
 * user's language), and a decorative [icon].
 *
 * Usage reads as `AppDestinations.Player`; the plural type name matches
 * this file's mandated name (the plan's own file list names it
 * AppDestinations.kt).
 */
sealed interface AppDestinations {
    /** The saved navigation state keys on this stable string. */
    val route: String

    /** The Fluent key of the label shown in the bottom bar and announced by TalkBack. */
    val labelKey: String

    /** The bottom-bar icon; decorative — the label carries the semantics. */
    val icon: ImageVector

    data object Player : AppDestinations {
        override val route = "player"
        override val labelKey = StringKeys.NAV_PLAYER
        override val icon = Icons.Filled.PlayArrow
    }

    data object Library : AppDestinations {
        override val route = "library"
        override val labelKey = StringKeys.NAV_LIBRARY
        override val icon = Icons.Filled.LibraryMusic
    }

    data object Settings : AppDestinations {
        override val route = "settings"
        override val labelKey = StringKeys.NAV_SETTINGS
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
