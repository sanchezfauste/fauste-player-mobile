package org.fauste.player.di

import kotlinx.coroutines.Dispatchers
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.PlatformContext
import org.fauste.player.platform.Storage
import org.fauste.player.platform.WakeLockGuard
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.remote.RemoteApi
import org.fauste.player.remote.RemoteServer
import org.fauste.player.remote.remoteApi
import org.fauste.player.repository.MediaRepository
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.repository.SettingsRepository
import org.fauste.player.rust.EngineConfig
import org.fauste.player.rust.FausteEngine
import org.fauste.player.rust.FausteEngineInterface
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The complete Phase 3 graph: platform contracts, dispatchers, the lazy
 * engine, the reactive state layer over it, the remote wrappers, and
 * the repositories over all of it.
 *
 * **Lifecycle**: every definition with a closeable lifecycle — the
 * engine (UniFFI `close()` releases the Rust object), the controller's
 * poll loop, the queue manager's scope, the remote wrapper's poll loop —
 * registers Koin's `onClose`, so `stopKoin()`/container shutdown is the
 * one place that ends them. The repositories are plain wrappers and
 * hold no lifecycle of their own.
 */
actual fun appModule(): Module =
    module {
        single { PlatformContext(androidContext()) }
        single { Storage(androidContext()) }
        single { WakeLockGuard(androidContext()) }
        single { AppLifecycleHolder() }
        single(IoDispatcher) { Dispatchers.IO }
        single(DefaultDispatcher) { Dispatchers.Default }
        single {
            val platform = get<PlatformContext>()
            EngineConfig(
                sampleRate = DEFAULT_SAMPLE_RATE,
                bufferFrames = DEFAULT_BUFFER_FRAMES,
                dataDir = platform.filesDir,
                cacheDir = platform.cacheDir,
            )
        }

        // Lazy by design: the UniFFI constructor is non-blocking-exempt (see its
        // KDoc), so Koin must only build it on first get(), and that first
        // resolution must happen off the main thread (caller's duty).
        single { FausteEngine(get<EngineConfig>()) }.onClose { it?.close() }

        // The engine's FFI surface, as the state layer and repositories
        // inject it — the same single instance as `FausteEngine` above.
        single<FausteEngineInterface> { get<FausteEngine>() }

        // The reactive state layer: every engine call (poll ticks,
        // commands, queue mutations, the remote-status poll) runs on the
        // I/O dispatcher — they are JNA hops that can do disk work on the
        // Rust side; the CPU dispatcher stays free for real computation.
        // The controller takes the lifecycle holder: its poll idles on
        // the Background signal (MainActivity feeds the holder from its
        // own onStart/onStop — see PlayerController's idling KDoc), and
        // the remote-status poll idles through the SAME holder (the
        // Task-6 fold).
        single {
            PlayerController(get<FausteEngineInterface>(), get<AppLifecycleHolder>(), get(IoDispatcher))
        }.onClose { it?.close() }
        single { QueueManager(get<FausteEngineInterface>(), get(IoDispatcher)) }.onClose { it?.close() }
        single {
            RemoteServer(get<FausteEngineInterface>(), get(IoDispatcher), lifecycle = get<AppLifecycleHolder>())
        }.onClose { it?.close() }

        // The remote HTTP client: HttpURLConnection on Dispatchers.IO.
        single<RemoteApi> { remoteApi() }

        // The repositories: thin wrappers over the state layer, the FFI
        // settings surface and the remote wrapper; no lifecycle of their own.
        single { PlaylistRepository(get<QueueManager>(), get<PlayerController>()) }
        single { SettingsRepository(get<FausteEngineInterface>(), get<PlayerController>(), get<RemoteServer>(), get(IoDispatcher)) }
        single { MediaRepository(get<Storage>(), get<PlaylistRepository>()) }
    }

private const val DEFAULT_SAMPLE_RATE = 48_000u
private const val DEFAULT_BUFFER_FRAMES = 512u
