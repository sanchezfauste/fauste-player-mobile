package org.fauste.player.ui.strings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import dev.karmakrafts.fluently.LocalizationFile
import kotlinx.coroutines.runBlocking
import org.fauste.player.ui.components.TrackRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * THE I18N SEAM'S CONTRACT (Task 5): the Fluent resolver over the REAL
 * bundles — the same packaged `.ftl` assets the app loads (read here
 * through CMP's resource runtime, proving the packaging end to end, the
 * font lane's sibling), not test fixtures.
 *
 * Pinned:
 * - the es-ES bundle renders idiomatic Spanish for a sample across every
 *   surface (nav, import, transport, settings selectors, up next) — the
 *   locale-resolution proof;
 * - the KEY-MISS LADDER: a key missing from the locale bundle falls back
 *   to en-US; a key missing from BOTH renders the key id itself (never a
 *   crash, never an invented string — FluentStrings' documented rule);
 * - the locale→bundle mapping (es/es-* reads the one es-ES bundle,
 *   everything else reads en-US);
 * - the COMPOSITION half: a composable provided the es-ES resolver
 *   announces Spanish a11y strings (the TrackRow proof) — the seam the
 *   screens consume, exercised the way MainActivity provides it.
 */
@RunWith(RobolectricTestRunner::class)
// A bare Application: this suite owns no Koin and must not start one; the
// composition under test provides its resolver explicitly.
@Config(sdk = [36], application = Application::class)
class FluentStringsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val enUs: FluentStrings = runBlocking { FluentStrings.forLocale(FluentStrings.DEFAULT_LOCALE) }

    private val esEs: FluentStrings = runBlocking { FluentStrings.forLocale("es-ES") }

    // ------------------------------------------------------------------
    // The locale-resolution proof: es-ES renders Spanish, sample set wide
    // ------------------------------------------------------------------

    @Test
    fun theSpanishBundleRendersIdiomaticSpanishAcrossEverySurface() {
        // Nav labels.
        assertEquals("Reproductor", esEs[StringKeys.NAV_PLAYER])
        assertEquals("Biblioteca", esEs[StringKeys.NAV_LIBRARY])
        assertEquals("Ajustes", esEs[StringKeys.NAV_SETTINGS])

        // Library: the import flow, empty states, the permission gate.
        assertEquals("Buscar", esEs[StringKeys.LIBRARY_SEARCH_LABEL])
        assertEquals("Importar", esEs[StringKeys.LIBRARY_IMPORT])
        assertEquals("Importando…", esEs[StringKeys.LIBRARY_IMPORTING])
        assertEquals("Importado: /music/sea.flac", esEs[StringKeys.LIBRARY_IMPORTED, "path" to "/music/sea.flac"])
        assertEquals("Importa música para crear la cola", esEs[StringKeys.LIBRARY_EMPTY_QUEUE])
        assertEquals(
            "Para importar necesitas acceso a tus archivos de audio — concede el permiso y pulsa Importar de nuevo.",
            esEs[StringKeys.LIBRARY_PERMISSION_DENIED],
        )

        // Transport + player: descriptions, states, the up-next line.
        assertEquals("Pista anterior", esEs[StringKeys.CONTROL_PREVIOUS])
        assertEquals("Reproducir", esEs[StringKeys.CONTROL_PLAY])
        assertEquals("Pausar", esEs[StringKeys.CONTROL_PAUSE])
        assertEquals("En reproducción", esEs[StringKeys.CONTROL_PLAYING])
        assertEquals("En pausa", esEs[StringKeys.CONTROL_PAUSED])
        assertEquals("02:41 / 05:01", esEs[StringKeys.TIMECODE, "position" to "02:41", "duration" to "05:01"])
        assertEquals("A continuación: Sea Breeze", esEs[StringKeys.PLAYER_UP_NEXT, "title" to "Sea Breeze"])

        // Settings: the section titles, the honest empties, the selector
        // grammar (availability words pick by boolean, in the row's
        // spoken description too).
        assertEquals("Salida de audio", esEs[StringKeys.SETTINGS_BACKEND_TITLE])
        assertEquals("Control remoto", esEs[StringKeys.SETTINGS_REMOTE_TITLE])
        assertEquals("Activado en 127.0.0.1:52341", esEs[StringKeys.SETTINGS_REMOTE_ON, "address" to "127.0.0.1:52341"])
        assertEquals("Desactivado", esEs[StringKeys.SETTINGS_REMOTE_OFF])
        assertEquals("Disponible", esEs[StringKeys.BACKEND_AVAILABILITY, "available" to true])
        assertEquals("No disponible", esEs[StringKeys.BACKEND_AVAILABILITY, "available" to false])
        assertEquals(
            "AAudio, no disponible, no seleccionado",
            esEs[StringKeys.BACKEND_ROW, "name" to "AAudio", "available" to false, "selected" to false],
        )
        assertEquals(
            "Null output, disponible, seleccionado",
            esEs[StringKeys.BACKEND_ROW, "name" to "Null output", "available" to true, "selected" to true],
        )

        // The queue row's arm-only a11y contract, in Spanish.
        assertEquals("Establecer como pista siguiente", esEs[StringKeys.ROW_ARM])
        assertEquals("En reproducción", esEs[StringKeys.ROW_NOW_PLAYING])
        assertEquals("A CONTINUACIÓN", esEs[StringKeys.ROW_UP_NEXT])
        assertEquals("Establecida como pista siguiente", esEs[StringKeys.ROW_ARMED])

        // The notification channel + stop action.
        assertEquals("Reproducción", esEs[StringKeys.NOTIFICATION_CHANNEL])
        assertEquals("Detener", esEs[StringKeys.NOTIFICATION_STOP])

        // The honest unknown marker — an em dash in both locales.
        assertEquals("—", esEs[StringKeys.UNKNOWN_VALUE])
    }

    @Test
    fun theEnglishSourceBundleRendersTodaysCopy() {
        assertEquals("Player", enUs[StringKeys.NAV_PLAYER])
        assertEquals("Import", enUs[StringKeys.LIBRARY_IMPORT])
        assertEquals("Up next: Sea Breeze", enUs[StringKeys.PLAYER_UP_NEXT, "title" to "Sea Breeze"])
        assertEquals(
            "Null output, available, selected",
            enUs[StringKeys.BACKEND_ROW, "name" to "Null output", "available" to true, "selected" to true],
        )
    }

    // ------------------------------------------------------------------
    // The key-miss ladder (documented): locale → en-US → the key itself
    // ------------------------------------------------------------------

    @Test
    fun aKeyMissingFromEveryBundleRendersTheKeyIdItself() {
        // Never a crash, never an invented string: the raw id is the
        // developer-visible placeholder (Fluently's own `<key>` bracket
        // form is deliberately bypassed — see FluentStrings' KDoc).
        assertEquals("no-such-key", esEs["no-such-key"])
        assertEquals("no-such-key", enUs["no-such-key"])
    }

    @Test
    fun aKeyMissingFromTheLocaleBundleFallsBackToTheEnglishSource() {
        // The real es-ES bundle with one line removed — the honest shape
        // of a not-yet-translated message — over the real en-US source.
        val esWithoutImport =
            LocalizationFile.parse(
                bundleSource("es-ES")
                    .lineSequence()
                    .filterNot { it == "library-import = Importar" }
                    .joinToString("\n"),
            )
        val patched = FluentStrings(esWithoutImport, LocalizationFile.parse(bundleSource("en-US")))

        assertEquals("Import", patched[StringKeys.LIBRARY_IMPORT], "the en-US source copy answers")
        assertEquals("Ajustes", patched[StringKeys.NAV_SETTINGS], "the present keys still render Spanish")
    }

    // ------------------------------------------------------------------
    // The locale→bundle mapping (documented, deliberately coarse)
    // ------------------------------------------------------------------

    @Test
    fun anySpanishLocaleReadsTheOneSpanishBundleAndEverythingElseReadsEnglish() {
        assertEquals("es-ES", FluentStrings.bundleTagFor("es-ES"))
        assertEquals("es-ES", FluentStrings.bundleTagFor("es"))
        assertEquals("es-ES", FluentStrings.bundleTagFor("es-MX"))
        assertEquals("es-ES", FluentStrings.bundleTagFor("es-419"))
        assertEquals("en-US", FluentStrings.bundleTagFor("en-US"))
        assertEquals("en-US", FluentStrings.bundleTagFor("en-GB"))
        assertEquals("en-US", FluentStrings.bundleTagFor("fr-FR"))
        assertEquals("en-US", FluentStrings.bundleTagFor(" PT-br  "), "the mapping trims and lowercases")
    }

    // ------------------------------------------------------------------
    // The composition half: a provided es-ES resolver renders Spanish a11y
    // ------------------------------------------------------------------

    @Test
    fun aComposableUnderTheSpanishResolverAnnouncesSpanishAccessibilityStrings() {
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides esEs) {
                MaterialTheme {
                    TrackRow(
                        title = "Brisa marina",
                        durationSeconds = 0.0,
                        isPlaying = true,
                        isArmed = true,
                        onClick = { },
                    )
                }
            }
        }

        // The merged row announces the Spanish playing state and the
        // armed badge; the unfilled duration renders the marker; the
        // click action carries the arm-only label — all through the SAME
        // provided resolver the app's composition root installs.
        composeRule
            .onNode(hasText("Brisa marina").and(hasContentDescription("En reproducción")))
            .assertExists()
        composeRule
            .onNode(hasText("Brisa marina").and(hasText("A CONTINUACIÓN")))
            .assertExists()
        composeRule
            .onNode(hasText("Brisa marina").and(hasContentDescription("Establecida como pista siguiente")))
            .assertExists()
        composeRule
            .onNode(hasText("Brisa marina").and(hasText("—")))
            .assertExists()
        val clickLabel =
            composeRule
                .onNode(hasText("Brisa marina"))
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.label
        assertEquals("Establecer como pista siguiente", clickLabel)
    }

    /** The real packaged bundle source — through the seam's OWN reader (the CMP path + the documented host fallback). */
    private fun bundleSource(bundleTag: String): String = runBlocking { FluentStrings.readBundleSource(bundleTag) }
}
