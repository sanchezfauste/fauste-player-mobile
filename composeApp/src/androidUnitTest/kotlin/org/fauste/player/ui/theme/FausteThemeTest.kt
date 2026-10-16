package org.fauste.player.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Wires the whole theme end to end: FausteTheme must compose the Nocturne
 * scheme, the NocturneTypography slot remap and the bundled Inter font
 * resources (composeResources/font/, Ruling 3) without blowing up — the
 * font bytes load through the app's packaged resources, so this also
 * proves the resource packaging the whole UI layer depends on. Behavioural
 * only: colours are asserted structurally (the scheme exposes the
 * Nocturne values), not by reading pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FausteThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themeComposesContentWithWiredScheme() {
        composeRule.setContent {
            FausteTheme {
                Text("nocturne probe")
            }
        }
        composeRule.onNodeWithText("nocturne probe").assertExists()
    }

    @Test
    fun themeExposesNocturnePaletteThroughTheScheme() {
        var captured: ColorScheme? = null
        composeRule.setContent {
            FausteTheme {
                captured = MaterialTheme.colorScheme
                Text("palette probe")
            }
        }
        composeRule.onNodeWithText("palette probe").assertExists()
        // assertNotNull narrows the nullable capture without !! (house rule).
        val scheme = assertNotNull(captured)
        assertEquals(NocturneColors.Primary, scheme.primary)
        assertEquals(NocturneColors.Background, scheme.background)
        assertEquals(NocturneColors.Surface, scheme.surface)
        assertEquals(NocturneColors.OnSurface, scheme.onSurface)
        assertEquals(NocturneColors.SurfaceVariant, scheme.surfaceVariant)
        assertEquals(NocturneColors.OnSurfaceVariant, scheme.onSurfaceVariant)
        assertEquals(NocturneColors.Error, scheme.error)
    }
}
