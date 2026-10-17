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
 * default. The Settings suites script the settings surface
 * (`getAvailableBackends`, `setBackend`, `remoteStatus`) — counters and
 * error injectors above; `stop`/`removeEntry`/`moveEntry` stay loud.
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
    val enqueueCalls = CopyOnWriteArrayList<Track>()
    val backendProbes = AtomicInteger(0)
    val setBackendCalls = CopyOnWriteArrayList<String>()
    val remoteStatusCalls = AtomicInteger(0)

    /** What [getAvailableBackends] reads; the Settings suite scripts the probed list. */
    val backends: MutableStateFlow<List<BackendInfo>> = MutableStateFlow(emptyList())

    /** What [remoteStatus] reads; scripted per test (the seed mirrors [RemoteInfo]'s). */
    val remote: MutableStateFlow<RemoteInfo> = MutableStateFlow(RemoteInfo(enabled = false, address = ""))

    /** Thrown by [play] while non-null — injects [FausteException.Busy] etc. */
    @Volatile
    var playError: Exception? = null

    /** Thrown by [pause] while non-null. */
    @Volatile
    var pauseError: Exception? = null

    /** Thrown by [seek] while non-null (the seek is then NOT applied). */
    @Volatile
    var seekError: Exception? = null

    /** Thrown by [setNext] while non-null (the arm is then NOT accepted). */
    @Volatile
    var setNextError: Exception? = null

    /** Thrown by [setVolume] while non-null (the volume is then NOT applied). */
    @Volatile
    var setVolumeError: Exception? = null

    /** Thrown by [setBackend] while non-null (the switch is then NOT applied). */
    @Volatile
    var setBackendError: Exception? = null

    /** Thrown by [getAvailableBackends] while non-null (the probe then answers nothing). */
    @Volatile
    var backendsError: Exception? = null

    /** Thrown by [remoteStatus] while non-null (the record is then NOT applied). */
    @Volatile
    var remoteStatusError: Exception? = null

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
        setVolumeError?.let { throw it }
        state.value = state.value.copy(volume = volume)
    }

    override fun setNext(entryId: ULong) {
        setNextCalls.add(entryId)
        setNextError?.let { throw it }
    }

    override fun previous() {
        previousCalls.incrementAndGet()
    }

    override fun stop() = error("stop is not scripted in this double")

    /**
     * Records and APPLIES the enqueue: appends a [PlaylistEntry] with the
     * next minted id, mirroring the real conductor's apply (the id exists
     * once the insert lands — the [QueueManager] confirm loop reads it back
     * immediately). The title follows the model's enqueue contract — the
     * FILE STEM, not the Track's blank title field (ffi.rs `enqueue`) —
     * so row-title assertions see what production would show.
     */
    override fun enqueue(track: Track) {
        enqueueCalls.add(track)
        val entry =
            PlaylistEntry(
                id = nextEnqueueId.incrementAndGet().toULong(),
                path = track.path,
                title = fileStem(track.path),
                duration = track.duration,
            )
        playlist.value = playlist.value + entry
    }

    override fun removeEntry(entryId: ULong) = error("removeEntry is not scripted in this double")

    override fun moveEntry(
        entryId: ULong,
        toIndex: UInt,
    ) = error("moveEntry is not scripted in this double")

    /**
     * Records and APPLIES the backend switch, seek's shape: the real
     * engine reports the new backend in `get_state()` within a bounded
     * window of accepting `set_backend`, so the double reflects it
     * immediately for the poll loop to pick up (fallback behavior on
     * unavailable backends is engine-side, Phase-3-tested — the double
     * reports verbatim).
     */
    override fun setBackend(backendId: String) {
        setBackendCalls.add(backendId)
        setBackendError?.let { throw it }
        state.value = state.value.copy(backend = backendId)
    }

    /** The probed list, straight from the scripted [backends]; each probe counts. */
    override fun getAvailableBackends(): List<BackendInfo> {
        backendProbes.incrementAndGet()
        backendsError?.let { throw it }
        return backends.value
    }

    /** The scripted [remote] record; each read counts (the poll + the on-demand refresh). */
    override fun remoteStatus(): RemoteInfo {
        remoteStatusCalls.incrementAndGet()
        remoteStatusError?.let { throw it }
        return remote.value
    }

    private val nextEnqueueId = AtomicInteger(0)

    /** The model's stem-derived title: last path segment without extension. */
    private fun fileStem(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')
}

/** Test fixture: an FFI snapshot with the defaults a fresh engine reports. */
internal fun engineState(
    status: String,
    position: Double = 0.0,
    duration: Double = 0.0,
    track: Track? = null,
    volume: Float = 1.0f,
    backend: String = "null",
): EngineState = EngineState(status, position, duration, track, volume, backend)

/** Test fixture: a model-shaped track (the FFI carries the stem-derived title). */
internal fun track(
    title: String = "",
    artist: String = "",
    path: String = "/music/one.flac",
    duration: Double = 0.0,
): Track = Track(path, title, artist, duration, 48_000u, 2u)

/**
 * Test fixture: a queue row as the FFI's boot refresh would read it. The
 * id is explicit — the Phase-3 rule: a row's identity is the playlist
 * flow's id, never the enqueue call's returned one.
 */
internal fun playlistEntry(
    id: ULong,
    title: String = "Track $id",
    path: String = "/music/$id.flac",
    duration: Double = 0.0,
): PlaylistEntry = PlaylistEntry(id, path, title, duration)
