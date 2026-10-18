package org.fauste.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import org.fauste.player.platform.AppLifecycleHolder
import org.fauste.player.platform.AppLifecycleState
import org.fauste.player.platform.audioReadPermission
import org.fauste.player.ui.navigation.AppNavHost
import org.fauste.player.ui.theme.FausteTheme
import org.fauste.player.viewmodel.LibraryViewModel
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.KoinContext

/**
 * The app shell's Android host — and, for the Library's import flow, the
 * owner of the two pieces that only an Activity can own: the
 * ActivityResult launchers (see LibraryScreen's seam KDoc for the
 * contract):
 *
 * - [ActivityResultContracts.RequestPermission] for the audio-read
 *   permission the platform contract names (`READ_MEDIA_AUDIO` on
 *   API 33+, `READ_EXTERNAL_STORAGE` below — [audioReadPermission]),
 *   requested on the first Import tap only while ungranted. A refusal
 *   surfaces the ViewModel's honest message — never a crash, never a
 *   silent dead button.
 * - [ActivityResultContracts.OpenDocument] (the SAF picker, launched
 *   with the MIME wildcard for all audio subtypes): the picked URI is
 *   handed to the SAME [LibraryViewModel] the Library composition
 *   resolves — both go through Koin into this activity's
 *   ViewModelStore, so they are one instance — and its
 *   [LibraryViewModel.onImportUri] owns the copy + enqueue from there.
 *
 * Honest note: the SAF picker itself grants read access to the picked
 * document without any storage permission; the permission gate implements
 * the plan's Permissions contract (and is what a folder-scanning library
 * will need). The gate is the flow, the pick is its reward.
 *
 * - [ActivityResultContracts.RequestPermission] for POST_NOTIFICATIONS
 *   (API 33+, Task 6): asked once on entering the app, per the
 *   honest-degradation rule above — a denial never blocks playback,
 *   and the launcher's callback deliberately ignores the answer.
 *
 * The composition itself stays state-in from the ViewModel layer only —
 * [KoinContext] binds FausteApplication's running container, no playback
 * or import logic lives here beyond the launchers.
 *
 * THE APP LIFECYCLE SIGNAL (the poll-idling wiring — the Activity's
 * half, and deliberately its WHOLE half): onStart/onStop feed
 * [AppLifecycleHolder] — the Phase-3 plain box, per Lifecycle.kt's
 * dependency-discipline note (NO `androidx.lifecycle` /
 * ProcessLifecycleOwner; the Activity's own overrides ARE the signal).
 * The controller observes the same holder on its own dispatcher, so
 * the Activity touches ONLY this cheap plain object here — resolving
 * the engine-backed controller on the main thread would violate the
 * graph's documented off-main rule, and the holder keeps one source of
 * truth for every consumer (the controller now; the playback service
 * in Task 6). Backgrounding therefore idles the poll's UI-state
 * updates, NOT playback: the engine's audio path is native and keeps
 * playing — see PlayerController's idling KDoc.
 */
class MainActivity : ComponentActivity() {
    // Same instance as the LibraryScreen composition's koinViewModel():
    // both resolve through Koin's factory into THIS activity's
    // ViewModelStore under the same default key. First touched in a
    // launcher callback (main thread, light — the VM constructor only
    // holds Lazy handles; the engine never resolves here).
    private val libraryViewModel: LibraryViewModel by viewModel()

    // The lifecycle holder: a plain Koin single (nothing engine-backed
    // in its dependency path), so resolving it on the main thread in
    // onStart/onStop is exactly the cheap operation it looks like.
    private val lifecycleHolder: AppLifecycleHolder by inject()

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchAudioPicker()
            } else {
                libraryViewModel.onImportPermissionDenied()
            }
        }

    private val openAudioDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { libraryViewModel.onImportUri(it.toString()) }
        }

    /**
     * The POST_NOTIFICATIONS runtime request (API 33+, Task 6). Honest
     * degradation, deliberately nothing more: the callback does nothing
     * with the answer — a denial means no drawer notification while
     * playback and the foreground service run on unaffected (the
     * verified reality on the API 34 emulator: the earlier "the
     * media-session notification is exempt by the platform's own
     * rules" claim did not hold there — the session still registers
     * with System UI's MediaSessionStack, so media controls may
     * surface, but the drawer notification does not appear). Asked
     * once per process: Android's own denial memory ("don't ask
     * again") suppresses re-asking, so a per-process ask cannot nag.
     */
    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationsPermission()
        setContent {
            KoinContext {
                FausteTheme {
                    AppNavHost(onImportClick = ::startImportFlow)
                }
            }
        }
    }

    /** Feeds the holder the foreground half of the app-lifecycle signal (the class KDoc's wiring). */
    override fun onStart() {
        super.onStart()
        lifecycleHolder.update(AppLifecycleState.Foreground)
    }

    /** Feeds the holder the background half — the poll idles (playback does not; the class KDoc). */
    override fun onStop() {
        super.onStop()
        lifecycleHolder.update(AppLifecycleState.Background)
    }

    /**
     * The Import seam's Activity half: the permission gate first (only
     * while ungranted), then the pick. A null URI — the user backed out
     * of the picker — is simply no import; the launchers' UI machinery is
     * the manually-verified part (an instrumented test's job).
     */
    private fun startImportFlow() {
        if (checkSelfPermission(audioReadPermission) == PackageManager.PERMISSION_GRANTED) {
            launchAudioPicker()
        } else {
            requestAudioPermission.launch(audioReadPermission)
        }
    }

    private fun launchAudioPicker() {
        openAudioDocument.launch(arrayOf("audio/*"))
    }

    /**
     * The non-blocking pass from the notification-permission KDoc: ask
     * on entering the app, only while API 33+ and ungranted — the
     * denial path is simply the request having happened.
     */
    private fun maybeRequestNotificationsPermission() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationsPermission.launch(permission)
        }
    }
}
