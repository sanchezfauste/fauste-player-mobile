package org.fauste.player.di

import kotlinx.coroutines.Dispatchers
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.PlatformContext
import org.fauste.player.platform.Storage
import org.fauste.player.platform.WakeLockGuard
import org.fauste.player.rust.EngineConfig
import org.fauste.player.rust.FausteEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

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
        single { FausteEngine(get<EngineConfig>()) }
    }

private const val DEFAULT_SAMPLE_RATE = 48_000u
private const val DEFAULT_BUFFER_FRAMES = 512u
