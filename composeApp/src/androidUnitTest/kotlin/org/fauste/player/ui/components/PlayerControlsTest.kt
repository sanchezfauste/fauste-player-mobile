package org.fauste.player.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural (semantics, not pixel) tests for PlayerControls, running as
 * a Robolectric unit test on the host JVM — see composeApp/build.gradle.kts
 * for why this lane exists. Each test drives the component exactly the way
 * a screen will: state in, intents asserted via a counter, rendering
 * asserted via content descriptions, state descriptions and slider range
 * info. A bare MaterialTheme is enough: the components read only
 * colorScheme/typography defaults, so the assertions stay independent of
 * font resource loading (which FausteThemeTest covers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        isPlaying: Boolean = false,
        positionSeconds: Double = 0.0,
        durationSeconds: Double = 0.0,
        volume: Float = 1.0f,
        onPlayPause: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onSeek: (Double) -> Unit = {},
        onVolumeChange: (Float) -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme {
            PlayerControls(
                isPlaying = isPlaying,
                positionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                volume = volume,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onSeek = onSeek,
                onVolumeChange = onVolumeChange,
            )
        }
    }

    @Test
    fun pausedStateShowsPlayActionAndPausedStateDescription() {
        setContent(isPlaying = false)
        composeRule.onNodeWithContentDescription("Play").assertExists()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
        composeRule.onNode(hasStateDescription("Paused")).assertExists()
    }

    @Test
    fun playingStateShowsPauseActionAndPlayingStateDescription() {
        setContent(isPlaying = true)
        composeRule.onNodeWithContentDescription("Pause").assertExists()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
        composeRule.onNode(hasStateDescription("Playing")).assertExists()
    }

    @Test
    fun playPauseClickEmitsIntent() {
        var clicks = 0
        setContent(isPlaying = false, onPlayPause = { clicks++ })
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun previousClickEmitsIntent() {
        var clicks = 0
        setContent(onPrevious = { clicks++ })
        composeRule.onNodeWithContentDescription("Previous track").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun seekTimecodeRendersPositionAndDuration() {
        setContent(isPlaying = true, positionSeconds = 83.0, durationSeconds = 300.0)
        composeRule.onNodeWithText("01:23 / 05:00").assertExists()
    }

    @Test
    fun seekSliderReflectsPositionFraction() {
        setContent(positionSeconds = 150.0, durationSeconds = 300.0)
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)))
            .assertExists()
    }

    @Test
    fun seekCommitEmitsAbsoluteSeconds() {
        var sought: Double? = null
        setContent(positionSeconds = 150.0, durationSeconds = 300.0, onSeek = { sought = it })
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.25f)
            }
        assertEquals(75.0, sought)
    }

    @Test
    fun volumeSliderReflectsStateAndEmitsChanges() {
        var changed: Float? = null
        setContent(volume = 0.6f, onVolumeChange = { changed = it })
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.6f, 0f..1f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.75f)
            }
        assertEquals(0.75f, changed)
    }

    @Test
    fun unloadedTrackHonestLimits() {
        // duration 0.0 = no track metadata yet: zero timecode, inert seek.
        setContent(positionSeconds = 0.0, durationSeconds = 0.0)
        composeRule.onNodeWithText("00:00 / 00:00").assertExists()
        composeRule.onNodeWithContentDescription("Seek bar").assertIsNotEnabled()
    }
}
