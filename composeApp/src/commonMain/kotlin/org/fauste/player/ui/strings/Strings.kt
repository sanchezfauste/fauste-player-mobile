package org.fauste.player.ui.strings

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.runBlocking

/**
 * The app's translation seam (Task 5, i18n): a Fluent message id plus its
 * arguments in, the locale-rendered string out. Composables never hardcode
 * user-facing copy — they resolve it through here, so every visible
 * string lives in the bundles under
 * `composeResources/files/locales/{en-US,es-ES}/main.ftl`, and a new
 * locale is a new `.ftl` file plus a [FluentStrings] mapping entry.
 *
 * The two overloads mirror how the copy is used: a bare key for static
 * strings, key + named arguments for parameterised ones (Fluent calls
 * them variables: `library-imported = Imported: {$path}`).
 */
interface Strings {
    /** Resolves [key] in the current locale (en-US fallback, then the key itself — see [FluentStrings]). */
    operator fun get(key: String): String

    /** Resolves [key] with named arguments; values map onto Fluent variables ([FluentStrings.bind]). */
    operator fun get(
        key: String,
        vararg args: Pair<String, Any?>,
    ): String
}

/**
 * Every message id the bundles carry, as compile-time constants — the
 * single inventory the code and the `.ftl` files must agree on. Composables
 * and tests reference these (never raw strings), so a renamed key is a
 * compile error, not a silent untranslated render.
 */
object StringKeys {
    // Navigation (AppDestinations)
    const val NAV_PLAYER: String = "nav-player"
    const val NAV_LIBRARY: String = "nav-library"
    const val NAV_SETTINGS: String = "nav-settings"

    // Library screen
    const val LIBRARY_SEARCH_LABEL: String = "library-search-label"
    const val LIBRARY_SEARCH_DESCRIPTION: String = "library-search-description"
    const val LIBRARY_IMPORT: String = "library-import"
    const val LIBRARY_IMPORTING: String = "library-importing"
    const val LIBRARY_IMPORTED: String = "library-imported"
    const val LIBRARY_DISMISS: String = "library-dismiss"
    const val LIBRARY_EMPTY_QUEUE: String = "library-empty-queue"
    const val LIBRARY_EMPTY_SEARCH: String = "library-empty-search"

    // Messages the view models surface through the state layer
    const val PLAYER_BUSY: String = "player-busy"
    const val LIBRARY_PERMISSION_DENIED: String = "library-permission-denied"
    const val LIBRARY_IMPORT_FAILED: String = "library-import-failed"

    // Player screen
    const val PLAYER_UP_NEXT: String = "player-up-next"
    const val UNKNOWN_VALUE: String = "unknown-value"

    // Transport controls
    const val CONTROL_SEEK: String = "control-seek"
    const val CONTROL_PREVIOUS: String = "control-previous"
    const val CONTROL_PLAY: String = "control-play"
    const val CONTROL_PAUSE: String = "control-pause"
    const val CONTROL_PLAYING: String = "control-playing"
    const val CONTROL_PAUSED: String = "control-paused"
    const val CONTROL_VOLUME: String = "control-volume"
    const val TIMECODE: String = "timecode"

    // Settings screen
    const val SETTINGS_BACKEND_TITLE: String = "settings-backend-title"
    const val SETTINGS_NO_BACKENDS: String = "settings-no-backends"
    const val SETTINGS_REMOTE_TITLE: String = "settings-remote-title"
    const val SETTINGS_REMOTE_ON: String = "settings-remote-on"
    const val SETTINGS_REMOTE_OFF: String = "settings-remote-off"
    const val SETTINGS_REFRESH: String = "settings-refresh"
    const val SETTINGS_VOLUME_TITLE: String = "settings-volume-title"
    const val SETTINGS_APPLYING: String = "settings-applying"
    const val BACKEND_AVAILABILITY: String = "backend-availability"
    const val BACKEND_ROW: String = "backend-row"

    // Queue row (TrackRow)
    const val ROW_ARM: String = "row-arm"
    const val ROW_NOW_PLAYING: String = "row-now-playing"
    const val ROW_UP_NEXT: String = "row-up-next"
    const val ROW_ARMED: String = "row-armed"

    // Notification (channel name + stop action label)
    const val NOTIFICATION_CHANNEL: String = "notification-channel"
    const val NOTIFICATION_STOP: String = "notification-stop"
}

/**
 * The composition's access point for [Strings]. The app's ONLY production
 * composition entry ([org.fauste.player.MainActivity]) provides the
 * locale-aware resolver built from [currentLocaleTag]; the default below
 * exists for previews and the host-JVM test suites, which render without
 * a provider and assert the en-US source copy — it is the en-US bundle,
 * deliberately NOT the platform locale, so tests are deterministic on any
 * host (a Spanish dev machine must not turn an English assertion red).
 */
val LocalStrings: ProvidableCompositionLocal<Strings> =
    staticCompositionLocalOf {
        DefaultStrings
    }

// The un-provided default, loaded once per process on first read. Runs
// inside composition when nobody provided — fine there (previews/tests),
// and never on the production path (MainActivity provides before any
// screen composes). runBlocking is the honest bridge: the resource read
// is a suspend CMP API, but the CompositionLocal contract must answer
// synchronously.
private val DefaultStrings: Strings by lazy {
    runBlocking { FluentStrings.forLocale(FluentStrings.DEFAULT_LOCALE) }
}
