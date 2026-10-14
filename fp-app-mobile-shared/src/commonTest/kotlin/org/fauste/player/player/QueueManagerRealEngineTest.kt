package org.fauste.player.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.rust.FausteEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real engine under the queue manager (Ruling 7): a fresh
 * [FausteEngine] per test over unique temp dirs, the queue driven through
 * the Kotlin layer while the assertions watch [QueueManager.playlist] —
 * the same end-to-end exercise as the Rust host suite's queue tests
 * (`ffi_state.rs`), every wait bounded (2 s).
 */
class QueueManagerRealEngineTest {
    private val dirs = RealEngineFixture.TempDirs("queue")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close()
        dirs.cleanup()
    }

    @Test
    fun enqueueReturnsTheEntryIdAndReflectsInThePlaylistFlow() =
        runBlocking {
            val manager = QueueManager(engine, Dispatchers.Default)
            val wav = RealEngineFixture.tinyWav(dirs, "tone.wav")

            val result = manager.enqueue(wav.absolutePath)
            val queuedId =
                when (result) {
                    is EnqueueResult.Queued -> result.id
                    else -> fail("expected Queued, was $result")
                }
            assertTrue(queuedId > 0uL, "entry ids are positive")

            // The flow shows the applied entry: the model-derived title (the
            // file stem, fp-model/src/track.rs:340) and the enqueued path.
            withTimeout(2_000) {
                manager.playlist.first { list ->
                    list.any { it.id == queuedId && it.title == "tone" && it.path == wav.absolutePath }
                }
            }
            manager.close()
        }

    @Test
    fun aFreshManagerSeedsTheEngineQueueOnConstruction() =
        runBlocking {
            for (name in listOf("alpha.wav", "beta.wav")) {
                engine.enqueue(RealEngineFixture.bareTrack(RealEngineFixture.tinyWav(dirs, name).absolutePath))
            }
            withTimeout(2_000) {
                while (engine.getPlaylist().size < 2) delay(20)
            }

            val manager = QueueManager(engine, Dispatchers.Default)
            withTimeout(2_000) { manager.playlist.first { it.size == 2 } }
            assertEquals(listOf("alpha", "beta"), manager.playlist.value.map { it.title })
            manager.close()
        }

    @Test
    fun removeAndMoveReshapeThePlaylistFlow() =
        runBlocking {
            val manager = QueueManager(engine, Dispatchers.Default)
            for (name in listOf("alpha.wav", "beta.wav", "gamma.wav")) {
                val result = manager.enqueue(RealEngineFixture.tinyWav(dirs, name).absolutePath)
                assertTrue(result is EnqueueResult.Queued, "enqueue $name failed: $result")
            }
            withTimeout(2_000) { manager.playlist.first { it.size == 3 } }
            assertEquals(listOf("alpha", "beta", "gamma"), manager.playlist.value.map { it.title })

            val betaId =
                manager.playlist.value
                    .first { it.title == "beta" }
                    .id
            assertEquals(CommandResult.Accepted, manager.remove(betaId))
            withTimeout(2_000) { manager.playlist.first { list -> list.none { it.id == betaId } } }
            assertEquals(listOf("alpha", "gamma"), manager.playlist.value.map { it.title })

            val gammaId =
                manager.playlist.value
                    .first { it.title == "gamma" }
                    .id
            assertEquals(CommandResult.Accepted, manager.move(gammaId, 0u))
            withTimeout(2_000) { manager.playlist.first { list -> list.first().id == gammaId } }
            assertEquals(listOf("gamma", "alpha"), manager.playlist.value.map { it.title })

            manager.close()
        }

    /**
     * The refusal path with a REAL FFI exception crossing the boundary:
     * `remove_entry`/`move_entry` pre-check the id against the model
     * (ffi.rs) and surface `FausteException.NotFound`; the manager maps it
     * to [CommandResult.Failed] instead of throwing, and survives.
     */
    @Test
    fun unknownEntriesAreRefusedWithAFailedResultNotAThrow() =
        runBlocking {
            val manager = QueueManager(engine, Dispatchers.Default)

            val removed = manager.remove(999_999uL)
            assertTrue(removed is CommandResult.Failed, "expected Failed, was $removed")
            assertTrue((removed as CommandResult.Failed).message.contains("no entry"))

            val moved = manager.move(999_999uL, 0u)
            assertTrue(moved is CommandResult.Failed, "expected Failed, was $moved")
            assertTrue((moved as CommandResult.Failed).message.contains("no entry"))

            assertTrue(manager.playlist.value.isEmpty(), "the manager still serves its flow")
            manager.close()
        }

    /**
     * `previous` is the transport grid's Previous button (ffi.rs): a queued
     * command the model no-ops unless playing (reducer.rs:574) — an empty
     * history keeps it a no-op even then. The queue rows are never touched
     * by it.
     */
    @Test
    fun previousIsAcceptedAndLeavesTheQueueIntact() =
        runBlocking {
            val manager = QueueManager(engine, Dispatchers.Default)
            val wav = RealEngineFixture.tinyWav(dirs, "long.wav", 44_100)
            assertTrue(manager.enqueue(wav.absolutePath) is EnqueueResult.Queued)
            withTimeout(2_000) { manager.playlist.first { it.size == 1 } }

            assertEquals(CommandResult.Accepted, manager.previous())
            assertEquals("stopped", engine.getState().status, "a model no-op while stopped")
            assertEquals(1, manager.playlist.value.size)

            engine.play()
            withTimeout(2_000) {
                while (engine.getState().status != "playing") delay(20)
            }
            assertEquals(CommandResult.Accepted, manager.previous())
            assertEquals("playing", engine.getState().status, "an empty history keeps Previous a no-op")
            assertEquals(1, manager.playlist.value.size)

            manager.close()
        }
}
