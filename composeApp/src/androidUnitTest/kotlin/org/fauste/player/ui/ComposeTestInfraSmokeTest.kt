package org.fauste.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * Infra probe: proves the Robolectric + createComposeRule lane executes
 * on this project's host JVM before the behavioural component tests lean
 * on it (see composeApp/build.gradle.kts's androidUnitTest note). Kept
 * after the probe because it doubles as the canary for the
 * Robolectric/AGP/JDK combination drifting out of support.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposeTestInfraSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeSemanticsTreeIsInspectable() {
        composeRule.setContent {
            MaterialTheme {
                Text("infra probe")
            }
        }
        composeRule.onNodeWithText("infra probe").assertExists()
    }
}
