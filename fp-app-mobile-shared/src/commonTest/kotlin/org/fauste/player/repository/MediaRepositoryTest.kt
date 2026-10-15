package org.fauste.player.repository

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.platform.Storage
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.RealEngineFixture
import org.fauste.player.rust.FausteEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The honesty call for this suite, documented per the brief: the
 * Android `Storage` actual CANNOT run on the host JVM — `Uri.parse` and
 * `contentResolver` are Android framework calls that the mockable
 * android.jar throws on in unit tests — so the copy seam is doubled
 * through the `open` class seam (see the expect's KDoc) and the
 * ENQUEUE half runs against the real engine. What this suite proves is
 * [MediaRepository]'s own contract: a failed copy is never enqueued, a
 * successful one is, and a cancelled import is not a failure. The
 * ContentResolver copy itself is the instrumented layer's test
 * (on-device).
 */
class MediaRepositoryTest {
    private val dirs = RealEngineFixture.TempDirs("media-repo")
    private val engine: FausteEngine = RealEngineFixture.newEngine(dirs)

    @AfterTest
    fun tearDown() {
        engine.close()
        dirs.cleanup()
    }

    /** A copy-seam double: canned answers, zero Android API calls. */
    private class CannedStorage(
        private val answer: (String) -> String?,
    ) : Storage(ContextWrapper(null)) {
        override suspend fun importToAppStorage(uri: String): String? = answer(uri)
    }

    /** A copy-seam double that never finishes — the cancellation probe. */
    private object HangingStorage : Storage(ContextWrapper(null)) {
        override suspend fun importToAppStorage(uri: String): String? = awaitCancellation()
    }

    @Test
    fun aFailedCopyIsNeverEnqueued() =
        runBlocking {
            val controller = PlayerController(engine, Dispatchers.Default)
            val queue = QueueManager(engine, Dispatchers.Default)
            val repository = MediaRepository(CannedStorage { null }, PlaylistRepository(queue, controller))

            val outcome = repository.import("content://media/external/audio/123")

            assertTrue(outcome.isFailure, "a failed copy answers failure")
            // Nothing ever reached the engine: wait past any plausible
            // apply window, then the engine itself must agree.
            delay(200)
            assertTrue(engine.getPlaylist().isEmpty(), "the engine's queue was left untouched")
            assertTrue(queue.playlist.value.isEmpty(), "the manager's flow was left untouched")

            queue.close()
            controller.close()
        }

    @Test
    fun aSuccessfulCopyIsEnqueuedAndThePathAnswers() =
        runBlocking {
            val controller = PlayerController(engine, Dispatchers.Default)
            val queue = QueueManager(engine, Dispatchers.Default)
            // A real file on disk stands in for the copy's output, so the
            // enqueue half is fully honest: the engine can read the path.
            val wav = RealEngineFixture.tinyWav(dirs, "imported.wav")
            val repository = MediaRepository(CannedStorage { wav.absolutePath }, PlaylistRepository(queue, controller))

            val outcome = repository.import("content://picked/1")

            assertEquals(Result.success(wav.absolutePath), outcome)
            withTimeout(2_000) {
                queue.playlist.first { list -> list.any { it.path == wav.absolutePath } }
            }
            assertTrue(
                engine.getPlaylist().any { it.path == wav.absolutePath },
                "the real engine holds the enqueued copy",
            )

            queue.close()
            controller.close()
        }

    @Test
    fun aCancelledCopyPropagatesInsteadOfBecomingAFailure() =
        runBlocking {
            val controller = PlayerController(engine, Dispatchers.Default)
            val queue = QueueManager(engine, Dispatchers.Default)
            val repository = MediaRepository(HangingStorage, PlaylistRepository(queue, controller))

            val job = launch { repository.import("content://picked/2") }
            // Let the child reach the hanging seam, then cancel it: a
            // swallowed cancellation would complete the job normally
            // with a Result.failure — isCancelled pins the difference.
            delay(50)
            job.cancel()
            job.join()
            assertTrue(job.isCancelled, "cancellation propagated through import, not mapped to failure")
            delay(100)
            assertTrue(engine.getPlaylist().isEmpty(), "a cancelled import enqueues nothing")

            queue.close()
            controller.close()
        }
}
