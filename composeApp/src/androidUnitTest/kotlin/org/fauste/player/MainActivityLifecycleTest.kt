package org.fauste.player

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import org.fauste.player.di.DefaultDispatcher
import org.fauste.player.di.IoDispatcher
import org.fauste.player.di.viewModelModule
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.AppLifecycleState
import org.fauste.player.platform.Storage
import org.fauste.player.player.PlayerController
import org.fauste.player.player.QueueManager
import org.fauste.player.player.ScriptedEngine
import org.fauste.player.player.engineState
import org.fauste.player.remote.RemoteServer
import org.fauste.player.repository.MediaRepository
import org.fauste.player.repository.PlaylistRepository
import org.fauste.player.repository.SettingsRepository
import org.fauste.player.rust.FausteEngineInterface
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * THE LIFECYCLE WIRING PIN (the poll-idling feature's Activity half):
 * MainActivity's own onStart/onStop must feed the Koin graph's ONE
 * [AppLifecycleHolder] — the instance the controller observes — because
 * the whole idling feature is silently OFF in production if this wiring
 * misses (wrong bean, wrong holder, forgotten call). This is the one
 * piece of MainActivity that is host-JVM-tested; the import launchers
 * remain the manually-verified Activity machinery (the Task-4
 * precedent), as does the composition's own execution (Robolectric's
 * paused main looper never idles the choreographer — the
 * AppNavHostTest lane is what exercises the shell's composition through
 * the same definitions).
 *
 * The REAL activity is driven through Robolectric's lifecycle
 * controller against a scripted Koin graph started by [ScriptedKoinApp]
 * — the manifest's FausteApplication would resolve the real engine,
 * and no cdylib is on this classpath. The holder's default state is
 * Foreground, so the test arms a DISCRIMINATING Background state first:
 * only a real onStart call flips it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = MainActivityLifecycleTest.ScriptedKoinApp::class)
class MainActivityLifecycleTest {
    @After
    fun tearDown() {
        // Koin's static GlobalContext persists across methods in the reused
        // sandbox loader while Robolectric recreates the Application (and
        // its startKoin) per method — the documented double-start trap.
        stopKoin()
    }

    @Test
    fun onStartAndOnStopFeedTheGraphsLifecycleHolder() {
        val holder = GlobalContext.get().get<AppLifecycleHolder>()
        // The discriminating initial state: the holder's own default is
        // Foreground, so only a real onStart call proves the wiring.
        holder.update(AppLifecycleState.Background)

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup() // onCreate → onStart → onResume

        assertEquals(AppLifecycleState.Foreground, holder.current(), "onStart did not publish Foreground to the holder")

        controller.pause().stop()

        assertEquals(AppLifecycleState.Background, holder.current(), "onStop did not publish Background to the holder")
        controller.destroy()
    }

    /**
     * The Application this suite boots instead of FausteApplication's
     * real graph: the same scripted-engine shape AppNavHostTest proved
     * (the complete state layer over the double, the lifecycle holder,
     * the dispatchers by qualifier) plus the production [viewModelModule]
     * — so every resolution is real and no cdylib is needed.
     */
    class ScriptedKoinApp : Application() {
        override fun onCreate() {
            super.onCreate()
            startKoin {
                modules(scriptedGraphModule(), viewModelModule)
            }
        }

        private fun scriptedGraphModule() =
            module {
                single<FausteEngineInterface> { ScriptedEngine(engineState(status = "stopped")) }
                single { AppLifecycleHolder() }
                single(IoDispatcher) { Dispatchers.IO }
                single(DefaultDispatcher) { Dispatchers.Default }
                single { PlayerController(get(), get(), get(IoDispatcher)) }.onClose { it?.close() }
                single { QueueManager(get(), get(IoDispatcher)) }.onClose { it?.close() }
                single { RemoteServer(get(), get(IoDispatcher)) }.onClose { it?.close() }
                single { PlaylistRepository(get(), get()) }
                single { SettingsRepository(get(), get(), get(), get(IoDispatcher)) }
                single { MediaRepository(NoopStorage(), get()) }
            }

        /** A do-nothing copy seam: this suite never imports, so it never answers. */
        private class NoopStorage : Storage(ContextWrapper(null)) {
            override suspend fun importToAppStorage(uri: String): String? = null
        }
    }
}
