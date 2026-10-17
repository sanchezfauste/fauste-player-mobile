package org.fauste.player.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.player.CommandResult
import org.fauste.player.player.PlayerController
import org.fauste.player.player.RealEngineFixture
import org.fauste.player.remote.RemoteServer
import org.fauste.player.rust.FausteEngine
import org.fauste.player.rust.RemoteInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real engine behind the settings repository (Ruling 7): backend
 * probes and selection are real FFI round trips, volume mirrors the
 * controller's real polled snapshot, remoteState is the very flow
 * [RemoteServer] owns, and the on-demand refresh is a real read whose
 * failure surfaces to its caller. Every wait is bounded (2 s).
 */
class SettingsRepositoryRealEngineTest {
    private val dirs = RealEngineFixture.TempDirs("settings-repo")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close()
        dirs.cleanup()
    }

    private fun newRepository(): SettingsRepository =
        SettingsRepository(
            engine,
            PlayerController(engine, AppLifecycleHolder(), Dispatchers.Default),
            RemoteServer(engine, Dispatchers.Default),
            Dispatchers.Default,
        )

    @Test
    fun theBackendListNamesBothKnownBackendsAndProbesThem() =
        runBlocking {
            val repository = newRepository()

            val backends = repository.backends()
            assertTrue(backends.isNotEmpty(), "the FFI names at least the fallback")

            val fallback = backends.first { it.id == "null" }
            assertTrue(fallback.available, "the null backend always opens")

            assertTrue(backends.any { it.id == "aaudio" }, "the FFI names the device backend too")
        }

    @Test
    fun selectingAKnownBackendIsAcceptedAndAnUnknownOneIsRefused() =
        runBlocking {
            val repository = newRepository()

            // "aaudio" is known but unavailable on the host JVM — still
            // accepted: the engine falls back and keeps retrying.
            assertEquals(CommandResult.Accepted, repository.selectBackend("aaudio"))
            assertEquals(CommandResult.Accepted, repository.selectBackend("null"))

            val refused = repository.selectBackend("no-such-backend")
            assertTrue(refused is CommandResult.Failed, "expected Failed, was $refused")
        }

    @Test
    fun volumeRoundTripsThroughTheControllerMirror() =
        runBlocking {
            val repository = newRepository()
            val wav = RealEngineFixture.tinyWav(dirs, "long.wav", 44_100) // 1 s of audio
            // Warm the engine into playing, mirroring the controller suite:
            // the volume fader is exercised against a live player.
            engine.enqueue(RealEngineFixture.bareTrack(wav.absolutePath))
            engine.play()
            withTimeout(2_000) {
                while (engine.getState().status != "playing") delay(20)
            }

            assertEquals(CommandResult.Accepted, repository.setVolume(0.5f))
            withTimeout(2_000) {
                while (kotlin.math.abs(repository.volume - 0.5f) >= 1e-6f) delay(20)
            }
        }

    @Test
    fun remoteStateIsTheRemoteServersOwnFlow() =
        runBlocking {
            val remote = RemoteServer(engine, Dispatchers.Default)
            val repository =
                SettingsRepository(
                    engine,
                    PlayerController(engine, AppLifecycleHolder(), Dispatchers.Default),
                    remote,
                    Dispatchers.Default,
                )

            val flow: StateFlow<RemoteInfo> = repository.remoteState
            assertSame(remote.state, flow, "the repository hands out the wrapper's flow, not a copy")

            // And the flow carries the real engine's loopback bind. The
            // remote binds on its own spawned thread (fp-remote's
            // `spawn`), so the first refresh can still see Off — bounded
            // patience, like every async-apply wait in this suite.
            val info =
                withTimeout(2_000) {
                    var record = remote.refresh()
                    while (!record.enabled) {
                        delay(20)
                        record = remote.refresh()
                    }
                    record
                }
            assertTrue(info.address.isNotEmpty(), "the loopback bind carries an address")
            assertEquals(info, flow.value, "the repository's flow carries the wrapper's record")
        }

    /**
     * The on-demand refresh a settings screen uses: Accepted on a live
     * read (the record publishes into the very flow [SettingsRepository.remoteState]
     * hands out), Failed with the real cause on a dead engine — whoever
     * asks explicitly deserves the real answer (the [RemoteServer.refresh]
     * contract), mapped like every other command-shaped surface here.
     */
    @Test
    fun refreshRemoteRepublishesAndSurfacesADeadEngineAsFailed() =
        runBlocking {
            val repository = newRepository()

            assertEquals(CommandResult.Accepted, repository.refreshRemote())
            // The refresh published into the flow: the engine's bind is
            // visible. The remote binds on its own spawned thread
            // (fp-remote's `spawn`), so the first read can still see Off —
            // re-refresh within the bounded window, like the sibling test.
            withTimeout(2_000) {
                while (!repository.remoteState.value.enabled) {
                    delay(20)
                    assertEquals(CommandResult.Accepted, repository.refreshRemote())
                }
            }

            engine.close() // the ENGINE, not the repository

            val refused = repository.refreshRemote()
            assertTrue(refused is CommandResult.Failed, "expected Failed, was $refused")
            assertTrue(refused.message.isNotBlank(), "the failure carries the real cause, not a blank")
        }
}
