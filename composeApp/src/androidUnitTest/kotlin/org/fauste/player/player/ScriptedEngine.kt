package org.fauste.player.player

import kotlinx.coroutines.flow.MutableStateFlow
import org.fauste.player.rust.BackendInfo
import org.fauste.player.rust.EngineState
import org.fauste.player.rust.FausteEngineInterface
import org.fauste.player.rust.FausteException
import org.fauste.player.rust.PlaylistEntry
import org.fauste.player.rust.RemoteInfo
import org.fauste.player.rust.Track
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

/**
 * A scripted [FausteEngineInterface] double for composeApp's ViewModel and
 * screen suites: the REAL state layer (PlayerController, QueueManager,
 * PlaylistRepository) runs over it untouched, so the tests exercise the
 * ViewModel's own contracts — mapping, intent dispatch, the seek throttle —
 * through the genuine polling pipeline instead of mocking the layer that
 * Phase 3 already tested. The alternative, a real-engine end-to-end VM test,
 * was judged NOT cheap here: it would need the host cdylib + JNA search
 * properties wired into composeApp's unit-test tasks (the shared module's
 * suite already covers that layer against the real engine).
 *
 * Threading: the double is safe under both lanes — the virtual-time
 * ViewModel tests drive everything from one test scheduler, while the
 * Robolectric screen tests let the controller poll from real
 * `Dispatchers.IO` threads — so counters/lists are thread-safe and the
 * error injectors are volatile.
 *
 * Unscripted surface: methods no suite uses fail LOUDLY (`error(...)`), so
 * an accidental engine call surfaces as a test failure, never a silent
 * default.
 */
class ScriptedEngine(
    initialState: EngineState,
) : FausteEngineInterface {
    /** What [getState] reads; tests script it to move the "engine". */
    val state: MutableStateFlow<EngineState> = MutableStateFlow(initialState)

    /** What [getPlaylist] reads; the queue rows, scripted per test. */
    val playlist: MutableStateFlow<List<PlaylistEntry>> = MutableStateFlow(emptyList())

    val playCalls = AtomicInteger(0)
    val pauseCalls = AtomicInteger(0)
    val seeks = CopyOnWriteArrayList<Double>()
    val setVolumes = CopyOnWriteArrayList<Float>()
    val setNextCalls = CopyOnWriteArrayList<ULong>()
    val previousCalls = AtomicInteger(0)

    /** Thrown by [play] while non-null — injects [FausteException.Busy] etc. */
    @Volatile
    var playError: Exception? = null

    /** Thrown by [pause] while non-null. */
    @Volatile
    var pauseError: Exception? = null

    /** Thrown by [seek] while non-null (the seek is then NOT applied). */
    @Volatile
    var seekError: Exception? = null

    override fun getState(): EngineState = state.value

    override fun getPlaylist(): List<PlaylistEntry> = playlist.value

    override fun play() {
        playCalls.incrementAndGet()
        playError?.let { throw it }
    }

    override fun pause() {
        pauseCalls.incrementAndGet()
        pauseError?.let { throw it }
    }

    /**
     * Records and APPLIES the seek: a command the conductor accepts lands
     * within a bounded window in the real engine, so the double reflects
     * it immediately for the poll loop to pick up.
     */
    override fun seek(position: Double) {
        seeks.add(position)
        seekError?.let {
            throw it
        }
        state.value = state.value.copy(position = position)
    }

    override fun setVolume(volume: Float) {
        setVolumes.add(volume)
        state.value = state.value.copy(volume = volume)
    }

    override fun setNext(entryId: ULong) {
        setNextCalls.add(entryId)
    }

    override fun previous() {
        previousCalls.incrementAndGet()
    }

    override fun stop() = error("stop is not scripted in this double")

    override fun enqueue(track: Track) = error("enqueue is not scripted in this double")

    override fun removeEntry(entryId: ULong) = error("removeEntry is not scripted in this double")

    override fun moveEntry(
        entryId: ULong,
        toIndex: UInt,
    ) = error("moveEntry is not scripted in this double")

    override fun setBackend(backendId: String) = error("setBackend is not scripted in this double")

    override fun getAvailableBackends(): List<BackendInfo> = error("getAvailableBackends is not scripted in this double")

    override fun remoteStatus(): RemoteInfo = error("remoteStatus is not scripted in this double")
}

/** Test fixture: an FFI snapshot with the defaults a fresh engine reports. */
internal fun engineState(
    status: String,
    position: Double = 0.0,
    duration: Double = 0.0,
    track: Track? = null,
    volume: Float = 1.0f,
): EngineState = EngineState(status, position, duration, track, volume, "null")

/** Test fixture: a model-shaped track (the FFI carries the stem-derived title). */
internal fun track(
    title: String = "",
    artist: String = "",
    path: String = "/music/one.flac",
    duration: Double = 0.0,
): Track = Track(path, title, artist, duration, 48_000u, 2u)
