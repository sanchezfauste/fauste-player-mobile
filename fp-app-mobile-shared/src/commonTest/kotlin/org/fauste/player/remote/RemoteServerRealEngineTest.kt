package org.fauste.player.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.player.RealEngineFixture
import org.fauste.player.rust.FausteEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real engine behind the wrapper (Ruling 7): the remote the engine
 * really boots (loopback, ephemeral port — `player_core.rs`) surfaces
 * through [RemoteServer] on demand AND via the slow poll, and a
 * destroyed engine stops the poll loop instead of spinning on it.
 */
class RemoteServerRealEngineTest {
    private val dirs = RealEngineFixture.TempDirs("remote-server")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close()
        dirs.cleanup()
    }

    @Test
    fun refreshExposesTheLoopbackBind() =
        runBlocking {
            val server = RemoteServer(engine, Dispatchers.Default)
            try {
                // The remote binds on its own spawned thread (fp-remote's
                // `spawn`), so the first reads can still see Off — the same
                // bounded patience every async-apply in this suite gets.
                val info =
                    withTimeout(2_000) {
                        var record = server.refresh()
                        while (!record.enabled) {
                            delay(20)
                            record = server.refresh()
                        }
                        record
                    }

                assertTrue(info.enabled, "the engine boots its remote (player_core.rs)")
                assertTrue(info.address.isNotEmpty(), "the loopback bind carries an address")
                assertTrue(info.address.startsWith("127.0.0.1"), "the bind is loopback-only: ${info.address}")
                assertEquals(info, server.state.value, "refresh publishes into the flow")
            } finally {
                server.close()
            }
        }

    @Test
    fun theSlowPollPublishesWithoutAnyOnDemandCall(): Unit =
        runBlocking {
            val server = RemoteServer(engine, Dispatchers.Default, pollPeriodMillis = 20)
            try {
                // No refresh(): the poll loop alone must replace the
                // placeholder within a few of its own periods.
                withTimeout(2_000) { server.state.first { it.enabled && it.address.isNotEmpty() } }
            } finally {
                server.close()
            }
        }

    /**
     * The destroyed-engine pin, the wrapper's twin of the controller
     * suite's: `remote_status()` on a closed engine raises UniFFI's
     * `IllegalStateException`, the loop must record it once and stop —
     * [RealEngineFixture.CountingDispatches] proves the stopping: a loop
     * that kept spinning would keep re-arming its delay and dispatching
     * on every period.
     */
    @Test
    fun aDestroyedEngineStopsThePollLoop() =
        runBlocking {
            val counting = RealEngineFixture.CountingDispatches(Dispatchers.Default)
            val server = RemoteServer(engine, counting, pollPeriodMillis = 20)
            try {
                withTimeout(2_000) { server.state.first { it.enabled } }

                engine.close() // the ENGINE, not the wrapper

                // The stop is visible on the failure channel, once and
                // sticky — the loop is dead, nothing can clear it. The
                // loop must first wake from its delay and take the
                // throwing read, so this waits on the flow itself.
                withTimeout(2_000) {
                    server.lastPollFailure.first { it is IllegalStateException }
                }

                // Let any in-flight dispatch land, then measure: the loop
                // hit the destroyed-object error within one period.
                delay(100)
                val atDeath = counting.count
                delay(200) // ten periods — a live loop would dispatch again each one
                assertEquals(atDeath, counting.count, "the poll loop kept dispatching on a dead engine")
                assertTrue(server.state.value.enabled, "the last record stays for the UI")
            } finally {
                server.close() // must not throw on the already-dead loop
            }
        }
}
