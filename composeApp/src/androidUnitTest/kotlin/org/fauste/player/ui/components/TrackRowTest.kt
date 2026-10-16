package org.fauste.player.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for TrackRow: title and duration rendering per the
 * honest-limits rule (a 0.0 duration means "unknown" and shows an em
 * dash), the playing indicator's presence per state, and the tap-to-jump
 * intent. Robolectric unit test on the host JVM (see
 * composeApp/build.gradle.kts for the lane rationale).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrackRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        title: String,
        durationSeconds: Double,
        isPlaying: Boolean,
        onClick: () -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme {
            TrackRow(
                title = title,
                durationSeconds = durationSeconds,
                isPlaying = isPlaying,
                onClick = onClick,
            )
        }
    }

    @Test
    fun rendersTitleAndFormattedDuration() {
        setContent(title = "Cart 01", durationSeconds = 12.5, isPlaying = false)
        composeRule.onNodeWithText("Cart 01").assertExists()
        composeRule.onNodeWithText("00:12").assertExists()
    }

    @Test
    fun unknownDurationRendersEmDashNotZeroTimecode() {
        setContent(title = "Unloaded", durationSeconds = 0.0, isPlaying = false)
        composeRule.onNodeWithText("—").assertExists()
        composeRule.onNodeWithText("00:00").assertDoesNotExist()
    }

    @Test
    fun playingIndicatorVisibleWhilePlaying() {
        setContent(title = "On air", durationSeconds = 30.0, isPlaying = true)
        composeRule.onNodeWithContentDescription("Now playing").assertExists()
    }

    @Test
    fun playingIndicatorAbsentWhilePaused() {
        setContent(title = "On air", durationSeconds = 30.0, isPlaying = false)
        composeRule.onNodeWithContentDescription("Now playing").assertDoesNotExist()
    }

    @Test
    fun tapEmitsJumpIntent() {
        var taps = 0
        setContent(title = "Song A", durationSeconds = 30.0, isPlaying = false) { taps++ }
        composeRule.onNodeWithText("Song A").performClick()
        assertEquals(1, taps)
    }
}
