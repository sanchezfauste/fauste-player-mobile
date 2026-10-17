package org.fauste.player.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for TrackRow: title and duration rendering per the
 * honest-limits rule (a 0.0 duration means "unknown" and shows an em
 * dash), the playing indicator's presence per state, the tap-to-jump
 * intent — and the Task-6 accessibility pass's pins:
 *
 * - the ROW IS ONE MERGED TalkBack target: a single node carries the
 *   title AND the duration (the merge `clickable` provides — loose
 *   children would make a TalkBack user swipe per fragment);
 * - the playing state rides the same merged node via the dot's
 *   description;
 * - the click action's label says what the tap really does — "Arm as
 *   next track", the arm-only contract, never play-now;
 * - the row's touch target meets the 48dp accessibility floor.
 *
 * Robolectric unit test on the host JVM (see composeApp/build.gradle.kts
 * for the lane rationale).
 */
@RunWith(RobolectricTestRunner::class)
// A bare Application instead of the manifest's FausteApplication: Robolectric
// would startKoin once per test method, while Koin's static GlobalContext persists
// across same-config methods in the reused sandbox class loader (double-start
// crash) — and none of these suites touch Koin.
@Config(sdk = [36], application = Application::class)
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
    fun theRowIsOneMergedTargetAnnouncingTitleAndDuration() {
        setContent(title = "Cart 01", durationSeconds = 12.5, isPlaying = false)
        composeRule.onNode(hasText("Cart 01")).assertExists()
        // THE MERGE PIN: one node carries BOTH the title and the duration
        // text — TalkBack announces the whole row as a single target.
        composeRule.onNode(hasText("Cart 01").and(hasText("00:12"))).assertExists()
    }

    @Test
    fun unknownDurationRendersEmDashNotZeroTimecode() {
        setContent(title = "Unloaded", durationSeconds = 0.0, isPlaying = false)
        composeRule.onNode(hasText("—")).assertExists()
        composeRule.onNode(hasText("00:00")).assertDoesNotExist()
    }

    @Test
    fun thePlayingStateRidesTheMergedRow() {
        setContent(title = "On air", durationSeconds = 30.0, isPlaying = true)
        // The dot's description is part of the ONE row node, not a
        // separate TalkBack focus.
        composeRule.onNode(hasText("On air").and(hasContentDescription("Now playing"))).assertExists()
    }

    @Test
    fun thePlayingDescriptionAppearsOnlyWhilePlaying() {
        setContent(title = "On air", durationSeconds = 30.0, isPlaying = false)
        composeRule.onNodeWithContentDescription("Now playing").assertDoesNotExist()
    }

    @Test
    fun theRowsClickActionCarriesTheArmOnlyLabel() {
        setContent(title = "Song A", durationSeconds = 30.0, isPlaying = false)
        composeRule.onNode(hasText("Song A")).assert(hasClickAction())
        composeRule.onNode(hasArmAsNextClickLabel()).assertExists()
    }

    @Test
    fun tapEmitsJumpIntent() {
        var taps = 0
        setContent(title = "Song A", durationSeconds = 30.0, isPlaying = false) { taps++ }
        composeRule.onNode(hasText("Song A")).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun theRowsTouchTargetMeetsThe48dpMinimum() {
        setContent(title = "Song A", durationSeconds = 30.0, isPlaying = false)
        composeRule
            .onNode(hasText("Song A"))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    /** The arm-only action's accessibility label — the row's tap is SET_NEXT, never play-now. */
    private fun hasArmAsNextClickLabel(): SemanticsMatcher =
        SemanticsMatcher("onClick action labelled 'Arm as next track'") { node ->
            node.config.getOrNull(SemanticsActions.OnClick)?.label == "Arm as next track"
        }
}
