package org.fauste.player.remote

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.AppLifecycleState
import org.fauste.player.player.RealEngineFixture
import org.fauste.player.rust.FausteEngine
import org.fauste.player.rust.FausteEngineInterface
import org.fauste.player.rust.RemoteInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE TASK-6 POLL-IDLING FOLD: the remote-status poll — a UI-state
 * feed like the controller's own — must skip its FFI reads while the
 * app is backgrounded, through the SAME [AppLifecycleHolder] the
 * controller idles on, and resume with an immediate fresh tick. The
 * native remote server itself is untouched by any of this: only the
 * Kotlin-side refresh of the [RemoteInfo] record pauses.
 *
 * The evidence mechanics mirror the controller-idling suite exactly
 * (that suite's KDoc records why): the wrapper runs on the RAW
 * [StandardTestDispatcher] so the loop's `delay` honors the test
 * scheduler, and the counting happens at the `remoteStatus` boundary —
 * the FFI hop the requirement names.
 */
class RemoteServerIdlingTest {
    private val dirs = RealEngineFixture.TempDirs("remote-server-idling")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close() // idempotent: the destroyed-engine paths already did
        dirs.cleanup()
    }

    @Test
    fun backgroundIdlesThePollNoEngineCallsWhileBackgrounded() =
        runTest {
            val reads = CountingRemoteStatus(engine)
            val holder = AppLifecycleHolder()
            val server = RemoteServer(reads, StandardTestDispatcher(testScheduler), pollPeriodMillis = POLL_PERIOD, lifecycle = holder)

            // Tick #1 reads, then arms its delay.
            runCurrent()
            val afterFirstTick = reads.count
            assertTrue(afterFirstTick >= 1, "the foreground poll never read the engine")

            // Signal Background mid-delay, then the settle bound: a read
            // already in flight may land, but nothing past this may cross.
            holder.update(AppLifecycleState.Background)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            val settled = reads.count
            assertEquals(afterFirstTick, settled, "a read crossed the FFI past the settle bound after the Background signal")

            repeat(IDLE_HEARTBEATS_TO_PROBE) {
                advanceTimeBy(POLL_PERIOD)
                runCurrent()
            }
            assertEquals(settled, reads.count, "the poll crossed the FFI boundary while backgrounded")

            // Resume: the FIRST wake reads again — the immediate fresh tick.
            holder.update(AppLifecycleState.Foreground)
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            assertEquals(settled + 1, reads.count, "the resume did not read at the loop's first wake")
            server.close()
        }

    @Test
    fun aMissingHolderKeepsTheOriginalAlwaysOnBehavior() =
        runTest {
            val reads = CountingRemoteStatus(engine)
            // No holder (the pre-fold default): the poll keeps reading in
            // any app state — the constructor contract for every consumer
            // that has no lifecycle signal to give.
            val server = RemoteServer(reads, StandardTestDispatcher(testScheduler), pollPeriodMillis = POLL_PERIOD)

            runCurrent()
            val started = reads.count
            assertTrue(started >= 1, "the poll never read the engine")
            advanceTimeBy(POLL_PERIOD)
            runCurrent()
            assertEquals(started + 1, reads.count, "without a holder the poll must keep its periodic rhythm")
            server.close()
        }

    /** Counts the `remoteStatus` calls handed to [delegate] — the FFI-boundary probe. */
    private class CountingRemoteStatus(
        private val delegate: FausteEngineInterface,
    ) : FausteEngineInterface by delegate {
        var count: Int = 0
            private set

        override fun remoteStatus(): RemoteInfo {
            count += 1
            return delegate.remoteStatus()
        }
    }

    private companion object {
        /** The virtual poll period (small so the pins read clearly). */
        const val POLL_PERIOD: Long = 10

        /** Enough idle wakes that a mis-idling loop would have crossed the FFI several times over. */
        const val IDLE_HEARTBEATS_TO_PROBE: Int = 5
    }
}
