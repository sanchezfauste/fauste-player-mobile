package org.fauste.player.di

import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

/** Qualifier for the I/O-bound [CoroutineDispatcher] (`Dispatchers.IO` on Android). */
val IoDispatcher: Qualifier = named("ioDispatcher")

/** Qualifier for the CPU-bound [CoroutineDispatcher] (`Dispatchers.Default`). */
val DefaultDispatcher: Qualifier = named("defaultDispatcher")

/**
 * Root application module. The Android actual registers [PlatformContext],
 * the two dispatchers above, the [EngineConfig] derived from the platform
 * dirs, and the `FausteEngine` singleton.
 *
 * **Engine construction is heavy and non-blocking-exempt** (its UniFFI
 * constructor loads the store and spawns threads): the engine `single` is
 * therefore intentionally lazy — Koin creates it on first `get()`. Every
 * caller MUST trigger that first resolution off the main thread (e.g. via
 * the [DefaultDispatcher] dispatcher); the factory itself does not
 * `runBlocking`.
 */
expect fun appModule(): Module
