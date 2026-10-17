package org.fauste.player

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import org.fauste.player.platform.audioReadPermission
import org.fauste.player.ui.navigation.AppNavHost
import org.fauste.player.ui.theme.FausteTheme
import org.fauste.player.viewmodel.LibraryViewModel
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
 * The composition itself stays state-in from the ViewModel layer only —
 * [KoinContext] binds FausteApplication's running container, no playback
 * or import logic lives here beyond the launchers.
 */
class MainActivity : ComponentActivity() {
    // Same instance as the LibraryScreen composition's koinViewModel():
    // both resolve through Koin's factory into THIS activity's
    // ViewModelStore under the same default key. First touched in a
    // launcher callback (main thread, light — the VM constructor only
    // holds Lazy handles; the engine never resolves here).
    private val libraryViewModel: LibraryViewModel by viewModel()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KoinContext {
                FausteTheme {
                    AppNavHost(onImportClick = ::startImportFlow)
                }
            }
        }
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
}
