package org.fauste.player.player

import org.fauste.player.rust.FausteException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The FFI error mapping. `FausteException.Busy` means the conductor's
 * command queue was full and the command was DROPPED — retrying is safe
 * (ffi.rs, `FausteError::Busy`); the other variants are terminal refusals.
 * Real FFI exceptions cross the boundary in the real-engine tests
 * (an unknown entry id is refused with NotFound); these pin the full
 * variant table purely.
 */
class CommandResultTest {
    @Test
    fun busyMapsToTheRetryableOutcome() {
        assertEquals(CommandResult.Busy, CommandResult.fromException(FausteException.Busy()))
    }

    @Test
    fun notFoundAndCoreMapToFailedWithTheirMessage() {
        val notFound = CommandResult.fromException(FausteException.NotFound("no entry 999999"))
        assertTrue(notFound is CommandResult.Failed)
        assertEquals("no entry 999999", (notFound as CommandResult.Failed).message)

        val core = CommandResult.fromException(FausteException.Core("store open failed"))
        assertTrue(core is CommandResult.Failed)
        assertEquals("store open failed", (core as CommandResult.Failed).message)
    }

    @Test
    fun anUnexpectedExceptionMapsToFailedWithItsMessage() {
        val result = CommandResult.fromException(IllegalStateException("the engine object was destroyed"))
        assertTrue(result is CommandResult.Failed)
        assertEquals("the engine object was destroyed", (result as CommandResult.Failed).message)
    }

    @Test
    fun cancellationPropagatesAndIsNeverSwallowedAsFailed() {
        // A cancelled caller must stay cancelled — mapping it to Failed
        // would swallow structured cancellation.
        assertFailsWith<CancellationException> {
            CommandResult.fromException(CancellationException("test cancel"))
        }
    }
}
