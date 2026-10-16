package org.fauste.player.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure half of the move confirm predicate:
 * [QueueManager.expectedMoveIndex] clamps in the UInt domain BEFORE
 * `toInt()` — the Phase-3 final-review carry (a pathological `toIndex`
 * above `Int.MAX_VALUE` used to wrap negative and stall the confirm
 * window the full [QueueManager.CONFIRM_TIMEOUT_MILLIS]).
 */
class QueueManagerTest {
    @Test
    fun anIntOverflowingTargetClampsToTheEndBeforeTheIntConversion() {
        assertEquals(2, QueueManager.expectedMoveIndex(4_000_000_000u, 2))
        assertEquals(0, QueueManager.expectedMoveIndex(0u, 2))
        assertEquals(2, QueueManager.expectedMoveIndex(5u, 2))
        assertEquals(2, QueueManager.expectedMoveIndex(2u, 2))
    }
}
