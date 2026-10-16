package org.fauste.player.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.rust.FausteEngine
import java.util.concurrent.TimeUnit
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
     * `remove_entry`/`move_entry`/`set_next` pre-check the id against
     * the model (ffi.rs) and surface `FausteException.NotFound`; the
     * manager maps it to [CommandResult.Failed] instead of throwing,
     * and survives.
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

            val jumped = manager.jump(999_999uL)
            assertTrue(jumped is CommandResult.Failed, "expected Failed, was $jumped")
            assertTrue((jumped as CommandResult.Failed).message.contains("no entry"))

            assertTrue(manager.playlist.value.isEmpty(), "the manager still serves its flow")
            manager.close()
        }

    /**
     * The Phase-3 final-review carry: a pathological `toIndex` above
     * `Int.MAX_VALUE` used to wrap negative in the confirm predicate
     * and stall the window the full [QueueManager.CONFIRM_TIMEOUT_MILLIS]
     * — the clamp now runs in the UInt domain
     * (`QueueManager.expectedMoveIndex`), so the move confirms on the
     * first applied read and the entry lands at the end.
     */
    @Test
    fun moveWithAnIntOverflowingIndexClampsAndConfirmsInsideTheWindow() =
        runBlocking {
            val manager = QueueManager(engine, Dispatchers.Default)
            for (name in listOf("alpha.wav", "beta.wav", "gamma.wav")) {
                assertTrue(
                    manager.enqueue(RealEngineFixture.tinyWav(dirs, name).absolutePath) is EnqueueResult.Queued,
                    "enqueue $name failed",
                )
            }
            withTimeout(2_000) { manager.playlist.first { it.size == 3 } }

            val alphaId =
                manager.playlist.value
                    .first { it.title == "alpha" }
                    .id
            val started = System.nanoTime()
            assertEquals(CommandResult.Accepted, manager.move(alphaId, 4_000_000_000u))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(
                elapsedMs < 1_500,
                "a clamped move must not stall the confirm window ($elapsedMs ms)",
            )
            assertEquals(listOf("beta", "gamma", "alpha"), manager.playlist.value.map { it.title })

            manager.close()
        }

    /**
     * `jump` is the desktop's queue tap — `SetNext` alone (ffi.rs
     * `set_next`, `fp-app/src/ui/table.rs:616-626`): the model arms the
     * target as the player's explicit next without touching the
     * transport, the on-air track plays to its end, and the
     * auto-advance then lands on the ARMED entry, not the natural
     * successor. alpha lasts ~1 s while beta/gamma last ~10 s each, so
     * the natural order cannot reach gamma inside the bounded window.
     */
    @Test
    fun jumpArmsTheTargetAndRedirectsTheAutoAdvance() =
        runBlocking {
            val manager = QueueManager(engine, Dispatchers.Default)
            val wavs =
                listOf(
                    RealEngineFixture.tinyWav(dirs, "alpha.wav", 44_100), // ~1 s
                    RealEngineFixture.tinyWav(dirs, "beta.wav", 441_000), // ~10 s
                    RealEngineFixture.tinyWav(dirs, "gamma.wav", 441_000), // ~10 s
                )
            for (wav in wavs) {
                assertTrue(manager.enqueue(wav.absolutePath) is EnqueueResult.Queued, "enqueue failed")
            }
            withTimeout(2_000) { manager.playlist.first { it.size == 3 } }

            val gammaId =
                manager.playlist.value
                    .first { it.title == "gamma" }
                    .id
            engine.play()
            withTimeout(2_000) {
                while (engine.getState().currentTrack?.title != "alpha") delay(20)
            }

            assertEquals(CommandResult.Accepted, manager.jump(gammaId))
            // The jump never reorders or drops rows, and never touches
            // the transport: alpha keeps playing.
            assertEquals(listOf("alpha", "beta", "gamma"), manager.playlist.value.map { it.title })
            assertEquals("playing", engine.getState().status)

            withTimeout(5_000) {
                while (engine.getState().currentTrack?.title != "gamma") delay(20)
            }
            assertEquals(3, manager.playlist.value.size, "the rows survive the jump")

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
