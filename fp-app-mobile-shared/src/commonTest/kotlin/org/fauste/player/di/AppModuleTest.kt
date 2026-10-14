package org.fauste.player.di

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineDispatcher
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.PlatformContext
import org.fauste.player.rust.EngineConfig
import org.fauste.player.rust.FausteEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Host-JVM honesty note: these run as Android *unit* tests (no device, no
 * native cdylib). So they verify the DI graph's *shape*: every definition
 * exists, and the ones constructible without Android framework behavior or
 * the native library resolve. `FausteEngine` is deliberately NOT resolved —
 * constructing it would touch JNA and the Rust core; that belongs to the
 * Task 3 instrumented layer. `PlatformContext` is resolved against a
 * test-safe base context whose dirs are never dereferenced here.
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
        assertTrue(EngineConfig::class in primaryTypes)
        assertTrue(FausteEngine::class in primaryTypes)
        assertTrue(AppLifecycleHolder::class in primaryTypes)
        assertEquals(2, primaryTypes.count { it == CoroutineDispatcher::class })
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
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun engineDefinitionIsRegisteredLazyAndNeverBuiltHere() {
        val factories = appModule().mappings.values
        val engineFactory = factories.single { it.beanDefinition.primaryType == FausteEngine::class }
        // Lazy single: nothing has been created at graph-load time.
        assertFalse(engineFactory.isCreated())
    }
}
