package org.fauste.player

import android.app.Application
import org.fauste.player.di.appModule
import org.fauste.player.di.viewModelModule
import org.koin.android.ext.koin.androidContext
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
 * first command-dispatcher coroutine (see its KDoc), so no prewarm is
 * needed and none is raced.
 */
class FausteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FausteApplication)
            modules(appModule(), viewModelModule)
        }
    }
}
