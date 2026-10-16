package org.fauste.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.fauste.player.ui.navigation.AppNavHost
import org.fauste.player.ui.theme.FausteTheme
import org.koin.compose.KoinContext

/**
 * The app shell's Android host: [FausteApplication] has already started
 * Koin, so the activity binds the running container into the composition
 * ([KoinContext] — gives `koinViewModel()` its scope directly, without
 * koin-compose's fallback warning) and renders the Nocturne-themed
 * navigation shell ([AppNavHost]). No playback logic lives here either —
 * the composition is state-in from the ViewModel layer only.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KoinContext {
                FausteTheme {
                    AppNavHost()
                }
            }
        }
    }
}
