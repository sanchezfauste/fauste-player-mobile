package org.fauste.player

/**
 * Platform-provided storage locations for the engine, one pair of paths per
 * host platform. The Android `actual` reads `Context.getFilesDir()` and
 * `Context.getCacheDir()`.
 */
expect class PlatformContext {
    val filesDir: String
    val cacheDir: String
}
