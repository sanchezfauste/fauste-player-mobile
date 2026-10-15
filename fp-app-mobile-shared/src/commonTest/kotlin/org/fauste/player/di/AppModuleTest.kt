package org.fauste.player.di

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.PlatformContext
import org.fauste.player.platform.Storage
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.RealEngineFixture
import org.fauste.player.remote.RemoteApi
import org.fauste.player.remote.RemoteServer
import org.fauste.player.repository.MediaRepository
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.repository.SettingsRepository
import org.fauste.player.rust.EngineConfig
import org.fauste.player.rust.FausteEngine
import org.fauste.player.rust.FausteEngineInterface
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Graph tests in two depths:
 *
 * - *Shape* (`moduleDeclaresEveryPlatformContract` and kin): every
 *   definition exists, with no engine constructed — no JNA, no cdylib.
 * - *Depth* (`theWholeGraphResolves...`): with the Rust cdylib on the
 *   unit-test classpath (Ruling 7) and a host-safe context supplying
 *   real temp dirs, the whole graph resolves for real — the engine is a
 *   live `FausteEngine`, and container shutdown closes every
 *   lifecycle-bearing definition via Koin `onClose`.
 *
 * The one definition never resolved here is `WakeLockGuard`: its
 * constructor calls `Context.getSystemService`, which the mockable
 * android.jar throws on in unit tests (`isReturnDefaultValues =
 * false`). Its existence is pinned by the shape tests; its behavior is
 * the instrumented layer's.
 */
class AppModuleTest {
    @AfterTest
    fun tearDown() = stopKoin()

    // Graph introspection (`Module.mappings`) is @KoinInternalApi; tests are
    // a legitimate consumer — the alternative is constructing a native-bound
    // engine on the host JVM.
    @OptIn(KoinInternalApi::class)
    private fun definitions() = appModule().mappings.values.map { it.beanDefinition.primaryType }

    @Test
    fun moduleDeclaresEveryPlatformContract() {
        val primaryTypes = definitions()
        assertTrue(PlatformContext::class in primaryTypes)
        assertTrue(Storage::class in primaryTypes)
        assertTrue(EngineConfig::class in primaryTypes)
        assertTrue(FausteEngine::class in primaryTypes)
        assertTrue(AppLifecycleHolder::class in primaryTypes)
        assertEquals(2, primaryTypes.count { it == CoroutineDispatcher::class })
    }

    @Test
    fun moduleDeclaresTheStateLayerRemoteWrappersAndRepositories() {
        val primaryTypes = definitions()
        assertTrue(FausteEngineInterface::class in primaryTypes)
        assertTrue(PlayerController::class in primaryTypes)
        assertTrue(QueueManager::class in primaryTypes)
        assertTrue(RemoteServer::class in primaryTypes)
        assertTrue(RemoteApi::class in primaryTypes)
        assertTrue(PlaylistRepository::class in primaryTypes)
        assertTrue(SettingsRepository::class in primaryTypes)
        assertTrue(MediaRepository::class in primaryTypes)
    }

    @Test
    fun platformContextResolvesAgainstADeclaredTestContext() {
        val koin =
            koinApplication {
                // ContextWrapper(null) is constructible on the host JVM; we only
                // need the PlatformContext wrapper instance, not working dirs.
                androidContext(ContextWrapper(null))
                modules(appModule())
            }.koin
        assertNotNull(koin.getOrNull<PlatformContext>())
        assertNotNull(koin.getOrNull<AppLifecycleHolder>())
        assertNotNull(koin.getOrNull<Storage>())
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun engineDefinitionIsRegisteredLazyAndNeverBuiltHere() {
        val factories = appModule().mappings.values
        val engineFactory = factories.single { it.beanDefinition.primaryType == FausteEngine::class }
        // Lazy single: nothing has been created at graph-load time.
        assertFalse(engineFactory.isCreated())
    }

    /**
     * The whole public graph resolves with a REAL engine behind it
     * (Ruling 7: `jna.library.path` makes the cdylib loadable in
     * unit tests) — and `koin.close()` really tears the lifecycle down:
     * the `onClose` wiring must destroy the engine, which UniFFI then
     * proves by refusing any further call.
     */
    @Test
    fun theWholeGraphResolvesAgainstARealEngineAndClosesWithTheContainer() {
        val dirs = RealEngineFixture.TempDirs("di-graph")
        val context =
            EngineDirsContext(
                files = dirs.data,
                cache = dirs.cache,
            )
        val koin =
            koinApplication {
                androidContext(context)
                modules(appModule())
            }.koin
        try {
            assertNotNull(koin.getOrNull<PlatformContext>())
            assertNotNull(koin.getOrNull<Storage>())
            assertNotNull(koin.getOrNull<AppLifecycleHolder>())
            assertNotNull(koin.getOrNull<EngineConfig>())

            val engine = koin.get<FausteEngine>()
            assertTrue(koin.get<FausteEngineInterface>() === engine, "the interface binds the same single instance")

            assertNotNull(koin.get<PlayerController>())
            assertNotNull(koin.get<QueueManager>())
            assertNotNull(koin.get<RemoteServer>())
            assertNotNull(koin.get<RemoteApi>())
            assertNotNull(koin.get<PlaylistRepository>())
            assertNotNull(koin.get<SettingsRepository>())
            assertNotNull(koin.get<MediaRepository>())
            assertNotNull(koin.getOrNull<CoroutineDispatcher>(IoDispatcher))
            assertNotNull(koin.getOrNull<CoroutineDispatcher>(DefaultDispatcher))

            // The engine behind the graph is genuinely live: the remote it
            // boots at construction answers with a loopback bind — on its
            // own spawned thread (fp-remote's `spawn`), so the read gets
            // the suite's standard bounded patience.
            val info =
                runBlocking {
                    withTimeout(2_000) {
                        var record = engine.remoteStatus()
                        while (!record.enabled) {
                            delay(20)
                            record = engine.remoteStatus()
                        }
                        record
                    }
                }
            assertTrue(info.enabled, "a live engine reports its remote as up")
            assertTrue(info.address.isNotEmpty())

            koin.close()

            // onClose wiring proven: the container destroyed the engine,
            // and UniFFI refuses calls into a destroyed object.
            val refused = runCatching { engine.getState() }
            assertTrue(refused.isFailure, "container shutdown destroyed the engine")
            assertTrue(
                refused.exceptionOrNull() is IllegalStateException,
                "UniFFI's destroyed-object signal, not some other failure",
            )
        } finally {
            koin.close() // idempotent no-op if already closed
            dirs.cleanup()
        }
    }

    /** A host-JVM-safe context: real temp dirs behind the two getters the graph reads. */
    private class EngineDirsContext(
        private val files: File,
        private val cache: File,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = files

        override fun getCacheDir(): File = cache
    }
}
