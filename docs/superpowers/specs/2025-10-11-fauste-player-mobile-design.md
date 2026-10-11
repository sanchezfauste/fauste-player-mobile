# Fauste Player Mobile — Design Spec

**Date**: 2025-10-11  
**Status**: Draft  
**Author**: AI Assistant (per superpowers:brainstorming)  
**Project**: this repository (`fauste-player-mobile`), sibling of the desktop repo `../fauste-player`

---

## 1. Executive Summary

Build a **standalone, high-fidelity audio player for Android** (iOS later) that reuses the existing Rust audio engine (`fp-engine`, `fp-decode`, `fp-backends`, `fp-store`, `fp-model`, `fp-analysis`, `fp-control`, `fp-remote`) via a new mobile audio backend (`fp-backends-mobile` using AAudio), exposed to a **Compose Multiplatform** UI through **UniFFI** FFI bindings, orchestrated by a **Kotlin Multiplatform (KMP) shared module**.

**Core principles** (from `CLAUDE.md`):
- English for all code, docs, specs, commits; answer maintainer in Spanish
- No `unsafe` except documented FFI boundaries
- No `unwrap`/`expect`/`panic` outside tests
- Real-time audio path: zero alloc, zero lock, zero log, zero panic
- Behaviour lives in `fp-model` (pure functions); engine/UI only execute/display
- UI never blocks; TDD + `cargo fmt/clippy/test` before commit
- Conventional Commits + SemVer; release-please owns changelog

---

## 2. Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                    Compose Multiplatform UI                        │
│  composeApp/src/commonMain/kotlin/org/fauste/player/ui/*           │
│  • Screens, ViewModels, Navigation, Theming (Nocturne design)      │
│  • Fluent i18n (locales/en-US/main.ftl + es-ES/main.ftl)          │
└─────────────────────────────┬──────────────────────────────────────┘
                              │ Kotlin expect/actual + UniFFI (JNI)
┌─────────────────────────────▼──────────────────────────────────────┐
│                  KMP Shared Module (Kotlin)                        │
│  fp-app-mobile-shared/src/commonMain/kotlin/org/fauste/player/     │
│  • Platform abstractions: storage, lifecycle, permissions, power   │
│  • UniFFI-generated bindings to Rust (zero-boilerplate FFI)        │
│  • Business logic: playlists, queue, settings, remote control      │
│  • Repositories (Playlist, Media, Settings) → Rust fp-store        │
└─────────────────────────────┬──────────────────────────────────────┘
                              │ FFI (C-ABI, UniFFI)
┌─────────────────────────────▼──────────────────────────────────────┐
│                     Rust Core Workspace (cdylib)                   │
│  Cargo workspace: crates/ (shared with desktop) + crates-mobile/   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐ │
│  │ fp-model     │ │ fp-store     │ │ fp-decode    │ │ fp-engine  │ │
│  │ (reused 100%)│ │ (reused 100%)│ │ (reused 100%)│ │ (reused 95%)│ │
│  └──────────────┘ └──────────────┘ └──────────────┘ └────────────┘ │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────┐ │
│  │ fp-analysis  │ │ fp-control   │ │ fp-remote    │ │fp-backends-│ │
│  │ (reused 100%)│ │ (reused 100%)│ │ (reused 100%)│ │mobile (NEW)│ │
│  └──────────────┘ └──────────────┘ └──────────────┘ └────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

### Responsibility Boundaries

| Layer | Responsibility | Technology |
|-------|----------------|------------|
| **UI** | Screens, navigation, themes, user input | Compose Multiplatform (Kotlin) |
| **KMP Shared** | ViewModels, state, platform abstractions, JNI glue | Kotlin (expect/actual) |
| **Rust Core** | Audio engine, decoding, storage, playlists, analysis | Rust (workspace crates + `fp-backends-mobile`) |

---

## 3. Rust Crate Adaptation

### 3.1 Crates Reused Unchanged (0 modifications)

| Crate | Version | Mobile Use | Notes |
|-------|---------|------------|-------|
| `fp-model` | workspace | 100% | State, commands, reducer, config, sessions — pure Rust |
| `fp-store` | workspace | 100% | Persistence, migrations, backups — needs `Store::new_mobile(data_dir, cache_dir)` |
| `fp-decode` | workspace | 100% | Decoding (symphonia, DSD, WavPack, APE, Opus) |
| `fp-engine` | workspace | 95% | Mixer, sources, buses, conductor — only backend changes |
| `fp-analysis` | workspace | 100% | Metadata, signal analysis, cache — pure CPU |
| `fp-control` | workspace | 100% | MIDI control surfaces — optional (future Bluetooth MIDI) |
| `fp-remote` | workspace | 100% | HTTP/JSON, SSE, OSC — works identically on mobile |

**Only change in `fp-store`**: Add `Store::new_mobile(data_dir: PathBuf, cache_dir: PathBuf) -> Result<Store>` that bypasses `directories::ProjectDirs` and uses paths provided by KMP `PlatformContext`.

### 3.2 New Crate: `fp-backends-mobile`

**Location**: `crates-mobile/fp-backends-mobile/`

**Dependencies** (workspace-rooted; the paths into the sibling desktop repo live once in the root `Cargo.toml`):
```toml
# Root Cargo.toml, workspace.dependencies
fp-model = { path = "../fauste-player/crates/fp-model" }
fp-backends = { path = "../fauste-player/crates/fp-backends" }
# … the other desktop crates the same way

# crates-mobile/fp-backends-mobile/Cargo.toml
[dependencies]
fp-backends.workspace = true
fp-model.workspace = true
thiserror.workspace = true
crossbeam-channel.workspace = true
rtrb.workspace = true
tracing.workspace = true

[target.'cfg(target_os = "android")'.dependencies]
aaudio-sys = "0.2"
ndk = "0.3"
jni = { version = "0.21", default-features = false }

[target.'cfg(target_os = "ios")'.dependencies]
audio-engine = "0.3"
objc2 = { version = "0.6", features = ["alloc", "Foundation", "AVFAudio"] }
```

**Structure**:
```
fp-backends-mobile/
├── Cargo.toml
├── src/
│   ├── lib.rs                    # exports, feature-gated backends
│   ├── aaudio_backend.rs         # Android: AAudioStream + callback
│   ├── audio_engine_backend.rs   # iOS: AVAudioEngine + render block
│   ├── stream_config.rs          # StreamConfig ↔ AAudio/AVAudioEngine conversion
│   └── error.rs                  # Mobile-specific BackendError variants
├── tests/
│   └── conformance.rs            # Inherits tests from fp-backends/tests/conformance.rs
└── uniffi/                       # .udl for backend selection (optional)
```

#### Android Implementation (AAudio)

Key points in `aaudio_backend.rs`:
- `AAudioStreamBuilder` → configure sample rate, channel count, format (`AAUDIO_FORMAT_PCM_FLOAT`), buffer frames
- Data callback: `|stream, user_data, audio_data, num_frames| { renderer.render(...) }` — runs on high-priority thread
- Error callback: classifies `AAUDIO_ERROR_XRUN`, `AAUDIO_ERROR_DISCONNECTED`, `AAUDIO_ERROR_REALTIME_DENIED` → `StreamErrorSink::report`
- Exclusive mode: `AAUDIO_SHARING_MODE_EXCLUSIVE` when `StreamConfig::exclusive = true`
- Real-time safety: pre-allocated scratch buffer, `OutputSample::from_f32` conversion (same as `cpal_backend.rs`), zero alloc in callback

#### iOS Implementation (AudioEngine) — Future

Similar pattern using `AVAudioEngine` + `AVAudioSourceNode` render block, exposed via UniFFI to Swift.

### 3.3 Feature Flag "mobile"

In root `Cargo.toml`:
```toml
[workspace.dependencies]
mobile = []
```

Crates needing conditional compilation (e.g., `fp-store` paths) use `#[cfg(feature = "mobile")]`.

### 3.4 UniFFI Interface Definition

```idl
// crates-mobile/fp-backends-mobile/uniffi/fauste_player.udl
namespace fauste.player;

interface Engine {
    constructor(config: EngineConfig);
    method play();
    method pause();
    method stop();
    method enqueue(track: Track);
    method seek(position: f64);
    method set_volume(volume: f32);
    method get_state() -> EngineState;
    method get_available_backends() -> List<BackendInfo>;
    method set_backend(backend_id: String);
};

dictionary EngineConfig {
    sample_rate: u32 = 48000;
    buffer_frames: u32 = 512;
    data_dir: String;
    cache_dir: String;
};

dictionary EngineState {
    status: String;  // "playing" | "paused" | "stopped" | "buffering"
    position: f64;
    duration: f64;
    current_track: Track?;
    volume: f32;
    backend: String;
};

dictionary Track {
    path: String;
    title: String;
    artist: String;
    duration: f64;
    sample_rate: u32;
    channels: u16;
};

dictionary BackendInfo {
    id: String;
    name: String;
    available: Boolean;
};
```

Generation:
```bash
cargo build --package fp-backends-mobile --features uniffi
uniffi-bindgen generate crates-mobile/fp-backends-mobile/uniffi/fauste_player.udl \
    --language kotlin --out-dir fp-app-mobile-shared/src/commonMain/kotlin/org/fauste/player/rust
uniffi-bindgen generate ... --language swift --out-dir iosApp/FaustePlayerRust
```

---

## 4. KMP Shared Module (`fp-app-mobile-shared`)

### Structure

```
fp-app-mobile-shared/
├── build.gradle.kts
├── src/
│   ├── commonMain/
│   │   ├── kotlin/org/fauste/player/
│   │   │   ├── platform/
│   │   │   │   ├── PlatformContext.kt          # expect class
│   │   │   │   ├── Storage.kt                  # expect/actual paths
│   │   │   │   ├── Lifecycle.kt                # expect/actual
│   │   │   │   ├── Permissions.kt              # expect/actual
│   │   │   │   └── PowerManager.kt             # expect/actual
│   │   │   ├── rust/                           # UniFFI bindings (generated)
│   │   │   │   ├── FausteEngine.kt
│   │   │   │   ├── EngineConfig.kt
│   │   │   │   ├── EngineState.kt
│   │   │   │   ├── Track.kt
│   │   │   │   └── BackendInfo.kt
│   │   │   ├── model/                          # Mirrors fp-model (serializable)
│   │   │   │   ├── Playlist.kt
│   │   │   │   ├── PlaylistEntry.kt
│   │   │   │   ├── Cart.kt
│   │   │   │   └── Config.kt
│   │   │   ├── repository/
│   │   │   │   ├── PlaylistRepository.kt       # Load/save via Rust fp-store
│   │   │   │   ├── MediaRepository.kt          # MediaCache via Rust fp-remote
│   │   │   │   └── SettingsRepository.kt       # Config persist/load
│   │   │   ├── player/
│   │   │   │   ├── PlayerController.kt         # Commands → Rust engine
│   │   │   │   ├── QueueManager.kt             # Playlist queue logic
│   │   │   │   └── PlaybackState.kt            # StateFlow<EngineState>
│   │   │   ├── remote/
│   │   │   │   ├── RemoteServer.kt             # fp-remote HTTP/SSE/OSC
│   │   │   │   └── RemoteApi.kt                # Client for desktop control
│   │   │   └── di/
│   │   │       └── AppModule.kt                # Koin module (expect)
│   │   └── resources/locales/
│   │       ├── en-US/main.ftl                  # Source locale (Fluent)
│   │       └── es-ES/main.ftl                  # Spanish translation
│   ├── androidMain/
│   │   └── kotlin/org/fauste/player/platform/
│   │       ├── PlatformContext.kt              # actual (Context.filesDir, etc.)
│   │       ├── Storage.kt                      # actual (MediaStore, SAF)
│   │       ├── Lifecycle.kt                    # actual (LifecycleObserver)
│   │       ├── Permissions.kt                  # actual (ActivityCompat)
│   │       ├── PowerManager.kt                 # actual (WakeLock)
│   │       └── AppModule.kt                    # actual (Koin Android)
│   └── iosMain/ (stub)
│       └── kotlin/org/fauste/player/platform/
│           └── ... (actual implementations)
├── uniffi/
│   └── fauste_player.udl
└── gradle.properties
```

### Key Patterns

- **UniFFI bindings**: Generated in `commonMain/kotlin/org/fauste/player/rust/` — never edited by hand
- **Fluent i18n**: `fluent-kotlin` library parsing `.ftl` files; `en-US` source, `es-ES` hand-translated
- **No UI in shared**: Only logic, state, repositories, platform abstractions
- **Koin DI**: `expect` in commonMain, `actual` in androidMain

---

## 5. Compose Multiplatform UI (`composeApp`)

### Structure

```
composeApp/
├── build.gradle.kts
├── src/
│   ├── commonMain/
│   │   ├── kotlin/org/fauste/player/ui/
│   │   │   ├── theme/
│   │   │   │   ├── Color.kt              # Nocturne palette (OKLCH → sRGB)
│   │   │   │   ├── Typography.kt         # Inter + JetBrains Mono
│   │   │   │   ├── Shapes.kt
│   │   │   │   └── Theme.kt              # MaterialTheme wrapper
│   │   │   ├── components/
│   │   │   │   ├── PlayerControls.kt     # Play/Pause, Prev/Next, SeekBar, Volume
│   │   │   │   ├── TrackRow.kt           # Library/Playlist row
│   │   │   │   ├── WaveformView.kt       # Overview + zoom (Canvas)
│   │   │   │   ├── MeterView.kt          # VU meter (real-time from Rust)
│   │   │   │   ├── CartButton.kt         # Cartwall button
│   │   │   │   ├── BackendPicker.kt      # Audio backend selector
│   │   │   │   └── common/               # Button, Card, Dialog, Sheet
│   │   │   ├── screens/
│   │   │   │   ├── LibraryScreen.kt      # Tracks list, search, filters, import
│   │   │   │   ├── PlayerScreen.kt       # Full player, waveform, cues, cartwall
│   │   │   │   ├── PlaylistsScreen.kt    # Playlist tabs, drag-reorder, cartwall
│   │   │   │   ├── SettingsScreen.kt     # Audio, UI, Remote, Analysis, Carts
│   │   │   │   ├── CartwallScreen.kt     # Grid of carts, tap-to-play
│   │   │   │   └── RemoteScreen.kt       # QR code, status, paired devices
│   │   │   ├── navigation/
│   │   │   │   ├── AppNavHost.kt         # Compose Navigation graph
│   │   │   │   ├── AppDestinations.kt    # Sealed class routes
│   │   │   │   └── DeepLinkHandler.kt    # Playlist import from file manager
│   │   │   ├── viewmodel/
│   │   │   │   ├── LibraryViewModel.kt
│   │   │   │   ├── PlayerViewModel.kt
│   │   │   │   ├── PlaylistsViewModel.kt
│   │   │   │   ├── SettingsViewModel.kt
│   │   │   │   └── CartwallViewModel.kt
│   │   │   └── util/
│   │   │       ├── FluentLocalization.kt # Fluent .ftl → Compose strings
│   │   │       ├── MediaScanner.kt       # MediaStore query (Android actual)
│   │   │       └── ImageLoader.kt        # Coil/AsyncImage for covers
│   │   └── resources/drawable/           # Vector icons (Phosphor)
│   ├── androidMain/
│   │   ├── kotlin/org/fauste/player/
│   │   │   ├── MainActivity.kt           # ComponentActivity, lifecycle, permissions
│   │   │   ├── FausteApplication.kt      # Application, Koin init, crash handler
│   │   │   └── ForegroundService.kt      # Media playback service
│   │   ├── AndroidManifest.xml
│   │   └── res/
│   │       ├── values/themes.xml         # Material3 theme
│   │       ├── xml/                      # Media notification, file provider
│   │       └── raw/                      # Fallback assets
│   └── iosMain/ (stub)
│       └── ...
├── gradle/libs.versions.toml
└── proguard-rules.pro
```

### Nocturne Design System (ported from `fp-app/ui/theme.rs`)

```kotlin
// theme/Color.kt
object NocturneColors {
    val Background = Color(0xFF0D0D0D)      // oklch(0.12 0 0)
    val Surface = Color(0xFF161616)         // oklch(0.18 0 0)
    val SurfaceVariant = Color(0xFF1E1E1E)  // oklch(0.24 0 0)
    val OnBackground = Color(0xFFE8E8E8)    // oklch(0.92 0 0)
    val OnSurface = Color(0xFFD0D0D0)       // oklch(0.85 0 0)
    val OnSurfaceVariant = Color(0xFF9E9E9E)// oklch(0.65 0 0)
    val Primary = Color(0xFF00BFA6)         // oklch(0.72 0.18 185) — Fauste teal
    val PrimaryVariant = Color(0xFF009E8A)  // oklch(0.62 0.15 185)
    val OnPrimary = Color(0xFF001A18)       // oklch(0.12 0.02 185)
    val Error = Color(0xFFE57373)           // oklch(0.68 0.22 25)
    val Success = Color(0xFF81C784)         // oklch(0.72 0.18 145)
    val Warning = Color(0xFFFFB74D)         // oklch(0.82 0.16 75)
    val Playing = Primary
    val Paused = OnSurfaceVariant
    val CuePoint = Color(0xFFFFD54F)        // Yellow cue markers
    val IntroOutro = Color(0xFFCE93D8)      // Purple intro/outro
    val WaveformPlayed = Primary.copy(alpha = 0.7f)
    val WaveformUnplayed = OnSurfaceVariant.copy(alpha = 0.4f)
    val WaveformCue = CuePoint
}
```

Typography: `Inter` (UI) + `JetBrains Mono` (timecode, technical) — same as desktop.

### ViewModel Pattern (Unidirectional Data Flow)

```kotlin
// viewmodel/PlayerViewModel.kt
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val queueManager: QueueManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    
    val uiState: StateFlow<PlayerUiState> = MutableStateFlow(PlayerUiState()).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState()
    )
    
    fun onPlayPause() = viewModelScope.launch { playerController.togglePlayPause() }
    fun onSeek(position: Double) = viewModelScope.launch { playerController.seek(position) }
    fun onVolumeChange(volume: Float) = viewModelScope.launch { playerController.setVolume(volume) }
    fun onNext() = viewModelScope.launch { queueManager.next() }
    fun onPrevious() = viewModelScope.launch { queueManager.previous() }
    fun onCuePointTapped(cue: CuePoint) = viewModelScope.launch { playerController.seek(cue.position) }
    
    init {
        viewModelScope.launch {
            playerController.engineState
                .map { mapEngineStateToUiState(it) }
                .collect { uiState.value = it }
        }
    }
}

data class PlayerUiState(
    val status: PlaybackStatus = PlaybackStatus.Stopped,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val currentTrack: Track? = null,
    val waveformData: WaveformData? = null,
    val cuePoints: List<CuePoint> = emptyList(),
    val volume: Float = 1.0f,
    val backendName: String = "",
    val error: String? = null,
)
```

### Foreground Service (Android) — Continuous Playback

```kotlin
// androidMain/ForegroundService.kt
@AndroidEntryPoint
class PlaybackService : Service(), LifecycleObserver, PlayerController.Callback {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var playerController: PlayerController
    
    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "FaustePlayer").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or 
                     MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0f, 1f).build())
            setCallback(MediaControllerCallback())
        }
        playerController = Koin.inject()
        playerController.addCallback(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }
    
    override fun onStateChanged(state: EngineState) {
        updateNotification(state)
        updateMediaSession(state)
    }
    
    override fun onTrackChanged(track: Track?) {
        updateMetadata(track)
    }
    
    private fun updateNotification(state: EngineState) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.currentTrack?.title ?: "Fauste Player")
            .setContentText(state.currentTrack?.artist ?: "Ready")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(loadCoverBitmap(state.currentTrack?.coverPath))
            .addAction(previousAction)
            .addAction(playPauseAction(state.status))
            .addAction(nextAction)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
}
```

**Permissions (Android 14+)**: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`

---

## 6. Build System (Gradle + Cargo)

### Version Catalog (`gradle/libs.versions.toml`)

```toml
[versions]
kotlin = "2.0.0"
compose = "1.6.10"
agp = "8.5.0"
koin = "4.0.0"
uniffi = "0.28.0"
coil = "2.6.0"
accompanist = "0.32.0"
cargo = "0.15.0"

[libraries]
kotlin-stdlib = { group = "org.jetbrains.kotlin", name = "kotlin-stdlib", version.ref = "kotlin" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose" }
# ... etc

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
koin = { id = "io.insert-koin", version.ref = "koin" }
cargo = { id = "dev.icerock.mobile.multiplatform-cargo", version.ref = "cargo" }
```

### ComposeApp Build Config

```kotlin
// composeApp/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.koin)
    alias(libs.plugins.cargo)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":fp-app-mobile-shared"))
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(libs.koin.core)
                implementation(libs.coil.compose)
                implementation(libs.accompanist.permissions)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.koin.android)
                implementation(androidx.activity.compose)
                implementation(androidx.lifecycle.viewmodel.compose)
                implementation(androidx.media3.session)
            }
        }
    }
}

android {
    namespace = "org.fauste.player"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.fauste.player"
        minSdk = 26  // AAudio requires API 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packagingOptions { jniLibs.useLegacyPackaging = true }
}

cargo {
    targets = ["aarch64-linux-android", "x86_64-linux-android"]
    features = ["mobile"]
    // Compiles fp-backends-mobile and copies .so to composeApp/src/main/jniLibs/
}
```

---

## 7. Testing Strategy

| Layer | Approach | Tools |
|-------|----------|-------|
| **Rust unit/integration** | `cargo test --workspace --all-targets` | Existing + new `fp-backends-mobile` tests |
| **Conformance** | `fp-backends/tests/conformance.rs` against `AaudioBackend` | Emulator API 26+ |
| **Engine** | `OfflineBackend` + `MobileOfflineBackend` (new) | Drive time explicitly, no sleep |
| **Kotlin unit** | `kotlin.test` + `kotlinx.coroutines.test` | ViewModels, repositories |
| **Compose UI** | `createComposeRule` + `FakeEngineHandle` | Screens, components |
| **Android instrumented** | `androidTest` on emulator | Integration with real `AaudioBackend` |

**TDD Rule**: Every new test fails first (`superpowers:test-driven-development`).

---

## 8. CI Pipeline (`.github/workflows/ci.yml`)

```yaml
jobs:
  rust:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo fmt --all --check
      - run: cargo clippy --workspace --all-targets -- -D warnings
      - run: cargo test --workspace
      - run: cargo deny check

  android:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4 (zulu, 17)
      - uses: android-actions/setup-android@v3 (ndk: r27)
      - run: ./gradlew :composeApp:assembleDebug :fp-app-mobile-shared:test
      - run: ./gradlew :composeApp:connectedAndroidTest (emulator API 26, 28, 34)

  # iOS job added when implemented
```

---

## 9. Implementation Phases

### Phase 0: Foundation (Week 1-2)
- Unified Cargo workspace + `fp-backends-mobile` skeleton
- KMP project skeleton (`fp-app-mobile-shared` + `composeApp`)
- CI: Rust + Android workflows green
- UniFFI setup with stub `.udl`

### Phase 1: Audio Backend Android (Week 3-5)
- `AaudioBackend` implementation (open, enumerate, config, callbacks)
- Conformance tests pass on emulator API 26+
- Integration with `fp-engine::Engine`
- StreamErrorSink: XRUN, disconnect, realtime classification
- Exclusive mode (`AAUDIO_SHARING_MODE_EXCLUSIVE`)

### Phase 2: Rust Core Integration (Week 6-7)
- `fp-store::Store::new_mobile()` working
- `fp-decode` decodes FLAC/MP3/Opus/DSD on Android
- Full `Conductor` + `Engine` + `Analyzer` spawn
- `fp-remote` HTTP/SSE/OSC server on local port
- UniFFI Engine API: play, pause, seek, enqueue, get_state

### Phase 3: KMP Shared + ViewModels (Week 8-9)
- Platform abstractions actual implementations (Android)
- Repositories (Playlist, Settings, Media) via Rust
- PlayerController, QueueManager, PlaybackState (StateFlow)
- RemoteServer + RemoteApi (fp-remote)
- Koin DI wired

### Phase 4: Compose UI (Week 10-13)
- Theme, base components (PlayerControls, TrackRow, MeterView, WaveformView)
- LibraryScreen: list, search, filters, SAF import
- PlayerScreen: waveform, cues, intro/outro, cartwall overlay
- PlaylistsScreen: tabs, drag-reorder, cartwall grid
- SettingsScreen: audio, UI, Remote, Analysis, Carts
- CartwallScreen: grid, tap-play, level, cue, color
- Navigation + Deep Links
- Foreground Service + MediaSession + Lock screen
- Accessibility: TalkBack, content descriptions, font scaling

### Phase 5: Polish & Release (Week 14-15)
- Performance: cold start < 2s, memory < 150MB, CPU idle < 2%
- Battery: 10h playback (screen off)
- Crash reporting (Rust panic hook + Kotlin handler)
- User guide `docs/user/mobile.md` + screenshots
- Signed AAB + Play Console internal test
- README.md updated (features, platform table, install)

---

## 10. Risk Matrix

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| AAudio latency/jitter on older devices | Medium | High | Fallback to `NullBackend` + warning UI; test matrix Samsung/Pixel/Xiaomi |
| UniFFI JNI crashes on background thread | Low | Critical | `uniffi::unexpected_c_panic = "abort"`; stress test 1000 calls |
| Compose Multiplatform iOS immaturity | High (future) | Medium | Stub `iosMain`; focus 100% Android now |
| `directories` crate paths on Android scoped storage | Medium | Medium | `PlatformContext.filesDir` → `Context.filesDir` (no SAF for DB) |
| Cargo-Gradle sync issues in CI | Medium | High | Cache `~/.cargo` + `~/.gradle`; fixed NDK version |

---

## 11. Definition of Done (per Phase)

```
Phase N done when:
☐ All milestones marked ✓
☐ cargo fmt --all --check passes
☐ cargo clippy --workspace --all-targets -- -D warnings passes
☐ cargo test --workspace passes (including new tests)
☐ ./gradlew check passes (ktlint, detekt, compose compiler)
☐ ./gradlew connectedAndroidTest passes (emulator API 26, 28, 34)
☐ PR opened with Conventional Commit title
☐ CI green on Linux (Rust) + Android (emulator)
☐ Code review approved (superpowers:requesting-code-review)
☐ Merged to main with gh pr merge --merge --delete-branch
```

---

## 12. Directory Structure (Final)

```
fauste-player-mobile/              # this repo (sibling of ../fauste-player)
├── Cargo.toml                    # Workspace: crates-mobile/* + path deps on ../fauste-player/crates/*
├── crates-mobile/
│   ├── fp-backends-mobile/       # NEW: AAudio / AudioEngine backend
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── aaudio_backend.rs
│   │   │   ├── audio_engine_backend.rs
│   │   │   └── stream_config.rs
│   │   ├── tests/
│   │   │   └── conformance.rs
│   │   └── uniffi/
│   │       └── fauste_player.udl
│   └── fp-app-mobile-shared/     # KMP module (Kotlin)
│       ├── build.gradle.kts
│       ├── src/
│       │   ├── commonMain/kotlin/org/fauste/player/
│       │   ├── androidMain/kotlin/...
│       │   └── iosMain/kotlin/...
│       └── uniffi/
├── composeApp/                   # Compose Multiplatform UI (Android app)
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/org/fauste/player/ui/
│       └── androidMain/...
├── iosApp/                       # iOS app (future stub)
├── gradle/
│   └── libs.versions.toml
├── settings.gradle.kts
├── build.gradle.kts
├── docs/
│   ├── superpowers/specs/2025-10-11-fauste-player-mobile-design.md
│   └── superpowers/plans/
├── .github/workflows/
└── README.md
```

---

## 13. Open Questions (Resolved in Brainstorming)

1. **Use case** → Standalone player (local files) ✓
2. **Platform priority** → Android first, iOS later ✓
3. **Rust reuse** → Maximum (compile crates to cdylib via JNI/FFI) ✓
4. **UI framework** → Compose Multiplatform ✓
5. **FFI strategy** → UniFFI (codegen, supports Android/iOS) ✓

---

*End of Spec*