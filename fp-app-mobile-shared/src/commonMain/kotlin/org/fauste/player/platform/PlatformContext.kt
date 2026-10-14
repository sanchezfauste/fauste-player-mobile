package org.fauste.player.platform

/**
 * Platform-provided storage locations for the engine, one pair of paths per
 * host platform. The engine's `data_dir`/`cache_dir` in [EngineConfig] come
 * from here. The Android `actual` reads `Context.getFilesDir()` and
 * `Context.getCacheDir()`.
 *
 * The constructor is platform-specific and intentionally not part of the
 * `expect` contract: common code only ever receives an instance from DI.
 */
expect class PlatformContext {
    val filesDir: String
    val cacheDir: String
}
