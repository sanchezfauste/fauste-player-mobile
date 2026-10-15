package org.fauste.player.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.player.CommandResult
import org.fauste.player.player.EnqueueResult
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.RealEngineFixture
import org.fauste.player.rust.FausteEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real engine behind the repository (Ruling 7): the same end-to-end
 * shape as the queue-manager suite, but every call goes through
 * [PlaylistRepository] — the wrapper must delegate, so the flows it
 * hands out ARE the manager's/controller's flows and the commands ARE
 * theirs. The bounded waits (2 s) prove the round trip without any
 * repository-side re-implementation being possible.
 */
class PlaylistRepositoryRealEngineTest {
    private val dirs = RealEngineFixture.TempDirs("playlist-repo")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close()
        dirs.cleanup()
    }

    @Test
    fun anEnqueueThroughTheRepositoryReachesTheEngineAndItsFlows() =
        runBlocking {
            val controller = PlayerController(engine, Dispatchers.Default)
            val queue = QueueManager(engine, Dispatchers.Default)
            val repository = PlaylistRepository(queue, controller)
            // A 1 s track, not the 23 ms default tone: the controller's
            // 100 ms poll must observe "playing" before auto-advance (the
            // controller suite documents the same trap).
            val wav = RealEngineFixture.tinyWav(dirs, "round.wav", 44_100)

            val result = repository.enqueue(wav.absolutePath)
            val id =
                when (result) {
                    is EnqueueResult.Queued -> result.id
                    else -> fail("expected Queued, was $result")
                }

            // The repository's flow is the manager's flow: the applied
            // entry (title from the file stem) shows there without any
            // repository-side copy.
            withTimeout(2_000) {
                repository.playlist.first { list -> list.any { it.id == id && it.title == "round" } }
            }
            // ...and the engine itself confirms it — no stale wrapper state.
            withTimeout(2_000) {
                while (engine.getPlaylist().none { it.id == id }) delay(20)
            }

            // The transport delegates reach the engine through the same
            // controller whose snapshot engineState exposes. The current
            // track only exists once the player starts (the model sets
            // it on play), so it is read after the transport round.
            assertEquals(CommandResult.Accepted, repository.play())
            withTimeout(2_000) { repository.engineState.first { it.status == "playing" } }
            assertEquals(
                wav.absolutePath,
                repository.engineState.value.currentTrack
                    ?.path,
                "the repository's engineState mirrors the controller's",
            )
            assertEquals(CommandResult.Accepted, repository.stop())
            withTimeout(2_000) { repository.engineState.first { it.status == "stopped" } }

            queue.close()
            controller.close()
        }

    @Test
    fun removeThroughTheRepositoryReshapesBothViews() =
        runBlocking {
            val controller = PlayerController(engine, Dispatchers.Default)
            val queue = QueueManager(engine, Dispatchers.Default)
            val repository = PlaylistRepository(queue, controller)
            val wav = RealEngineFixture.tinyWav(dirs, "gone.wav")

            val id =
                when (val result = repository.enqueue(wav.absolutePath)) {
                    is EnqueueResult.Queued -> result.id
                    else -> fail("expected Queued, was $result")
                }
            withTimeout(2_000) { repository.playlist.first { list -> list.any { it.id == id } } }

            assertEquals(CommandResult.Accepted, repository.remove(id))
            withTimeout(2_000) { repository.playlist.first { list -> list.none { it.id == id } } }
            assertTrue(engine.getPlaylist().none { it.id == id }, "the engine agrees the row is gone")

            queue.close()
            controller.close()
        }
}
