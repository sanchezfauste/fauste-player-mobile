package org.fauste.player

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fauste.player.di.appModule
import org.fauste.player.di.viewModelModule
import org.fauste.player.player.PlayerController
import org.fauste.player.service.PlaybackService
import org.fauste.player.service.PlaybackServiceStarter
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

/**
 * The process entry: starts Koin with the complete Phase-3 graph
 * ([appModule] — platform contracts, the two dispatcher qualifiers, the
 * lazy engine, the reactive state layer, the repositories) plus
 * composeApp's own [viewModelModule] (the PlayerViewModel).
 *
 * Everything stays lazy here: registering a module creates no instance, so
 * `onCreate` stays cheap. The heavy first resolution of the engine single
 * happens off-main by construction — PlayerViewModel defers it into its
 * first command-dispatcher coroutine (see its KDoc), and the Task-6
 * [PlaybackServiceStarter] below follows the same rule — so no prewarm is
 * needed and none is raced.
 *
 * THE SERVICE START TRIGGER (Task 6): an application-scope collector —
 * the Application outlives every activity and screen, which is exactly
 * the lifetime a playback trigger needs — resolves the controller
 * OFF-MAIN (the graph's documented rule; this coroutine and the
 * PlayerViewModel's pipeline race benignly for the same lazy single)
 * and then arms the starter: every distinct Playing phase of the
 * engine calls [startForegroundService] on [PlaybackService]. The
 * starter's own KDoc documents why the trigger is the ENGINE's
 * observed status, not a play command.
 */
class FausteApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FausteApplication)
            modules(appModule(), viewModelModule)
        }

        appScope.launch {
            val controller = GlobalContext.get().get<PlayerController>()
            PlaybackServiceStarter(controller, appScope) {
                // minSdk 26: the platform startForegroundService exists
                // directly — no compat layer, and the API-26+ obligation to
                // promote promptly is media3's own first update.
                startForegroundService(Intent(this@FausteApplication, PlaybackService::class.java))
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
