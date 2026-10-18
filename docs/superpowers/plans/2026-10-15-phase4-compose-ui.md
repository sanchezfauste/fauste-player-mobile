# Fauste Player Mobile — Phase 4: Compose UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The playable app: Nocturne-themed Compose UI over the Phase-3 state layer — PlayerScreen (transport/seek/volume), LibraryScreen (queue list, search, SAF import, tap-to-jump), SettingsScreen (backend picker, remote status) — plus the `set_next` FFI seam that tap-to-play needs, the Android foreground service + MediaSession + lock-screen notification, and an accessibility pass. Ship shape: a phone where you can import audio, see the queue, play/pause/seek/switch tracks, adjust backend/volume, and keep playback alive with the screen off.

**Explicitly deferred to Phase 5 (spec §9 Phase 4 items that need FFI/feature surface that does not exist yet):** PlaylistsScreen (tabs, drag-reorder), CartwallScreen, WaveformView/MeterView/cues/intro-outro (need waveform+analysis FFI), deep links, RemoteScreen (QR/pairing). These are recorded deferrals, not omissions — Phase 5 plans them with their FFI surface.

**Architecture:** Spec §5's UDF: Compose screens subscribe to ViewModel `StateFlow`s and emit intents; ViewModels wrap `PlayerController`/`QueueManager`/repositories (Phase 3, all real-engine-tested). `PlaybackState.fromEngineState` is the mapping layer (plan-mandated consumer). Koin 4.0 provides DI (`koin-compose` for CMP); the spec's `@HiltViewModel` is noise (Phase 3 Ruling 1 already chose Koin).

**Tech Stack:** Compose Multiplatform 1.9.3 (compose BOM 2025.11.00), Koin 4.0.0 + `koin-compose`, Kotlin 2.2.21, the committed UniFFI bindings, Rust 1.98 (Task 2 only).

**Spec:** `docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md` (§5 structure/theme/ViewModel/service, §9 Phase 4)

## Rulings (plan-level)

1. **The spec's Nocturne palette is the mobile design authority** — NOT the desktop's current `theme.rs` (which has drifted to a lavender accent `#9184d9`; the spec's block is self-contained with exact hex/OKLCH values and a teal `#00BFA6` primary). Implement the spec's palette verbatim; cite this ruling in the Color.kt header so nobody "fixes" it toward the desktop values.
2. **ViewModels via Koin Compose** (`koin-compose`/`koin-compose-viewmodel` for CMP — the implementer verifies exact 4.x artifact coordinates and documents). The spec's `@HiltViewModel` sample is noise.
3. **Fonts**: Inter (UI) + JetBrains Mono (timecode) per spec. Locate the TTFs in `../fauste-player` first (read-only; copying out is reading) — copy into the app with their license notices if present and OFL/Apache-licensed; otherwise fall back to system default + `FontFamily.Monospace` for timecode with a documented note. No downloadable-fonts dependency (offline app).
4. **Tap-to-jump needs `set_next`** — the Phase-3 final review's explicit carry. Task 2 lands it before LibraryScreen consumes it.
5. **Foreground service starts on first Play, stops on Stop** (not Pause — paused audio keeps the notification alive like every music app; document the lifecycle). `mediaPlaybackForegroundServiceType` + the POST_NOTIFICATIONS runtime request (API 33+) are part of Task 6.
6. **The networkSecurityConfig cleartext-loopback fix lands in Task 6** (with the other manifest bits) — the RemoteApi KDoc already flags it.

## Global Constraints

- Phase 0-3 norms: English; Conventional Commits; no `!!`/empty catch/main-thread engine calls; CancellationException rethrow discipline; ktlint 1.7.1 clean; gates per commit: cargo side when Rust changed (fmt/clippy ×2/tests ×2 + emulator when the on-device surface changed), gradle side when Kotlin changed (`:fp-app-mobile-shared:testDebugUnitTest :composeApp:assembleDebug`; the real-engine tests now build their own cdylib via `buildHostCdylib`).
- Bindings regenerate (ktlint 1.7.1 flow, drift-symmetric) whenever `ffi.rs` changes (Task 2).
- Never modify `../fauste-player`.
- Compose hygiene: no engine calls from composables (always the ViewModel), state read at the narrowest scope, `collectAsStateWithLifecycle`-equivalent semantics for the flows.

## Review Focus

- **Engine-call thread discipline** — the standing constraint; the UI layer is the first place it gets violated in practice (composable calling the engine on the UI thread).
- **ViewModel correctness**: intents dispatched on the injected dispatcher; state mapped via `PlaybackState.fromEngineState`; no business logic in composables.
- **`set_next` semantics match the desktop** (cite where the desktop's row activation issues `SetNext` + what it does to the current transport).
- **Service lifecycle soundness**: no notification/service leak on stop; MediaSession state transitions mirror PlaybackState; the service never touches the engine on the main thread.
- **Accessibility**: every control reachable and labeled (contentDescription + stateDescription); font scaling respected (sp units, no fixed-height text clipping).

---

### Task 1: Nocturne theme + base components (Kotlin/Compose)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/org/fauste/player/ui/theme/Color.kt` (replaces the Phase-0 stub Theme.kt contents or extends — judge; the spec's `NocturneColors` block verbatim per Ruling 1), `Typography.kt`, `Theme.kt` (MaterialTheme wrapper, dark-only)
- Create: fonts under `composeApp/src/commonMain/composeResources/` (or the Android resource path — judge per CMP 1.9 conventions; Ruling 3)
- Create: `components/PlayerControls.kt` (play/pause toggle, previous, seek bar with position/duration text in JetBrains Mono, volume slider), `components/TrackRow.kt` (title, duration, playing indicator using `NocturneColors.Playing`)
- Test: Compose UI tests for the components' state rendering (playing vs paused indicator; seek bar reflects position/duration; controls emit the right intents via a test dispatcher) — CMP ui-test on the android target; keep them behavioral (assert rendered semantics), not pixel tests.

- [ ] **Step 1:** Theme + components with the palette/typography per Rulings 1/3; failing UI tests → implement → green.
- [ ] **Step 2:** Gradle battery green.
- [ ] **Step 3: Commit** — `feat(mobile): nocturne theme and base player components`

---

### Task 2: FFI `set_next` + `QueueManager.jump` (Rust + Kotlin, TDD)

**Files:**
- Modify: `crates-mobile/fp-backends-mobile/src/ffi.rs` (+ `player_core.rs` if it needs a passthrough), `tests/ffi_state.rs`, `tests/engine_smoke.rs`
- Modify: `crates-mobile/fp-backends-mobile/bindings/kotlin/fauste_player.kt` (regenerated)
- Modify: `fp-app-mobile-shared/src/commonMain/kotlin/org/fauste/player/player/QueueManager.kt` + its tests

**Interfaces:**
- Investigate first: how the desktop's row activation issues `Command::SetNext(player, entry)` (grep `SetNext` under `../fauste-player/crates/fp-app/src/`) — does it also issue Play, or does SetNext alone start transport? Mirror exactly.
- Produces: `set_next(entry_id) -> Result<(), FausteError>` in the FFI (main player; NotFound on unknown id — the same pre-check shape as `remove_entry`); `QueueManager.jump(id)` with confirm semantics matching its siblings; bindings regenerated (drift-symmetric); engine_smoke extended (jump round on-device).
- The `previous()` KDoc's Phase-4 pointer now resolves — update that sentence to point at the real `set_next`.

- [ ] **Step 1:** Investigation citation in the report; failing Rust host tests → implement → green; regen bindings.
- [ ] **Step 2:** `QueueManager.jump` Kotlin tests against the real engine; emulator run green (the smoke changed).
- [ ] **Step 3: Commit** — `feat(mobile): set_next ffi surface and queue jump`

---

### Task 3: App shell — MainActivity, navigation, PlayerViewModel, PlayerScreen (Kotlin/Compose)

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/org/fauste/player/MainActivity.kt` (Koin start — an `Application` class in androidMain registers `appModule()`; MainActivity hosts the nav)
- Create: `composeApp/src/commonMain/kotlin/org/fauste/player/ui/navigation/AppDestinations.kt` (sealed routes: Player, Library, Settings), `AppNavHost.kt` (bottom-nav or top-bar navigation — judge for 3 screens, document)
- Create: `viewmodel/PlayerViewModel.kt` — Koin Compose ViewModel over PlayerController/QueueManager/PlaylistRepository (Ruling 2); `val uiState: StateFlow<PlaybackUiState>` composed from the controller flow via `PlaybackState.fromEngineState` + currentTrack from the queue; intents: onPlayPause/onSeek/onVolume/onPrevious/onJump (jump lands here for PlayerScreen's "now playing" row, wired in Task 4's list too)
- Create: `screens/PlayerScreen.kt` — PlayerControls wired to the ViewModel, track title/artist placeholder (title-from-stem is what the FFI carries), position/duration text
- Koin module gains the ViewModel registration (androidMain).
- Tests: ViewModel tests (mapping + intent dispatch via a test dispatcher; the real engine for an end-to-end play intent if cheap) + PlayerScreen UI test (transport reflects a fake flow).

- [ ] **Step 1:** Failing tests → implement → green; gradle battery.
- [ ] **Step 2: Commit** — `feat(mobile): app shell navigation player viewmodel and player screen`

---

### Task 4: LibraryScreen — queue list, search, SAF import (Kotlin/Compose)

**Files:**
- Create: `screens/LibraryScreen.kt` + `viewmodel/LibraryViewModel.kt` — the queue rows via TrackRow (QueueManager's flow), a search TextField filtering client-side, tap-to-jump (`QueueManager.jump`), an Import button: permission flow (READ_MEDIA_AUDIO/READ_EXTERNAL_STORAGE via Permissions contract) → `ActivityResultContracts.OpenDocument` → `MediaRepository.import(uri)` → result surfaced as a snackbar/state; empty-state text.
- Modify: nav to reach it; Koin registration.
- Tests: ViewModel tests (filter logic pure; import intent path with a fake picker contract at the VM seam — the VM takes the picked-URI, the Activity owns the picker); UI test for list rendering + filter.

- [ ] **Step 1:** Failing tests → implement → green; gradle battery.
- [ ] **Step 2: Commit** — `feat(mobile): library screen with search and saf import`

---

### Task 5: SettingsScreen + poll idling (Kotlin/Compose)

**Files:**
- Create: `screens/SettingsScreen.kt` + `viewmodel/SettingsViewModel.kt` — backend list (SettingsRepository.backends()) with select action, remote status (RemoteServer flow: enabled + address), volume control (SettingsRepository.setVolume) — all wired via the VM.
- Modify: `PlayerController` — wire the 100 ms poll's start/stop to `AppLifecycleHolder` (the Phase-3 final-review deferral: never idles today). Foreground → poll; Background → idle (and resume state-fresh on Foreground).
- Modify: nav + Koin.
- Tests: ViewModel mapping tests; the lifecycle idling test (holder signal → poll stops/starts — via the controller's test dispatcher counting).

- [ ] **Step 1:** Failing tests → implement → green; gradle battery.
- [ ] **Step 2: Commit** — `feat(mobile): settings screen and lifecycle-aware poll idling`

---

### Task 6: Foreground service + MediaSession + manifest + accessibility (Kotlin/Android)

**Files:**
- Create: `composeApp/src/androidMain/kotlin/org/fauste/player/service/PlaybackService.kt` — spec §5's service pattern modernized: `MediaSessionService` (media3) or the classic `Service` + `MediaSessionCompat`? **Ruling: media3** (`androidx.media3:media3-session` — the current library; `MediaSessionCompat` is legacy). The service observes a UI-facing PlaybackState flow (a small shared bridge the VM publishes), updates the MediaSession + notification (play/pause/stop actions), starts foreground on Play, stops on Stop per Ruling 5.
- Modify: `AndroidManifest.xml` — `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions, service declaration, POST_NOTIFICATIONS, the `networkSecurityConfig` (cleartext to 127.0.0.1, Ruling 6), notification channel.
- Modify: screens/components — accessibility pass: contentDescription on every control (play/pause with stateDescription, seek, volume, rows), minimum touch targets, sp-based typography (verify Task 1 used sp), TalkBack-friendly row semantics.
- Tests: what runs on host JVM honestly (bridge logic, service state transitions with Robolectric if already available — judge and document; otherwise manual-verification checklist in the report). Full battery: gradle lanes + `./scripts/emulator-test.sh` (unchanged Rust — sanity) + `:composeApp:assembleDebug`.
- POST_NOTIFICATIONS runtime request (API 33+) wired in MainActivity alongside the audio-permission flow.

- [ ] **Step 1:** Service + manifest + session; failing tests where honest.
- [ ] **Step 2:** Accessibility pass; full battery.
- [ ] **Step 3: Commit** — `feat(mobile): media session foreground service and accessibility pass`

---

## Deferred to Phase 5 (from the Phase 4 final review — ordered by the reviewer)

1. **Audio focus** — calls/navigation don't duck/pause; needs a real design decision (native sink vs `AudioFocusRequest` in the service), not a patch.
2. **Lifecycle-aware collection** — screens use plain `collectAsState()`; with the keep-awake gate the composition recomposes at up to 10 Hz with the screen off while playing. A `collectAsStateWithLifecycle`-equivalent (expect/actual or `lifecycle-runtime-compose` on Android) is the battery work.
3. **FFI reads that close three disclosed gaps in one change**: `current entry id` (fixes the path-join playing flag + duplicate-path inconsistency Library-vs-session) and `next`/armed (the tap is currently invisible — armed badge).
4. **System-kill-while-playing restart semantics** — `onStartCommand` re-derive × media3's restart behavior.
5. **Remote-driven play to a stopped, backgrounded app produces no notification** — needs an engine event surface or a background heartbeat (fights the idling design by construction).
6. **i18n** — Fluent `.ftl` per spec §4 (en-US source + es-ES), strings currently hardcoded English.
7. **Restart/backlog polish**: throttle-shape extraction (third copy; constants test-locked), test fixture dedups (ScriptedKoinApp/graph modules/NoopStorage ×3), retry intent for failed backend probes (+ re-probe on resume), `RemoteInfo` error field (FFI change), gradle caching in CI (Robolectric ~100 MB/run), a11y nuances (stopped announced "Paused"; seek announces percentage only), PERMISSION_DENIED copy, import-guard atomicity, settle-timeout test paths, key-type regression pin.
8. **API-31+ refused-start**: host-pinned (the catch path) but not emulator-drivable (needs a genuine refused state) — on the manual checklist.

## Deferred beyond Phase 5 (recorded, unbudgeted — the spec §9 Phase-4 features whose FFI/analysis surface does not exist yet)

PlaylistsScreen (tabs, drag-reorder), CartwallScreen, WaveformView/MeterView/cues/intro-outro (need waveform + analysis FFI: cover/peaks surfaced — deferred since Phase 2), deep links, RemoteScreen (QR/pairing). The final review verified nothing half-shipped; these need their FFI surface planned as a future phase.

## Notes for the implementer

- The state layer's KDoc carries the honest limits (volume not observable via SettingsRepository; queue flow stale on HTTP-remote mutations; durations 0.0 until a services layer fills them) — surface those limits in the UI (e.g. duration display falls back to "—" when 0.0), never invent data.
- Do NOT key UI state on `enqueue`'s returned id (Phase-3 carry: concurrent duplicate-path enqueues can both confirm on the newest row).
- `move`'s Kotlin-side clamp before `toInt()` (Phase-3 carry) can land with any Task-4/5 touch of QueueManager.
- All Phase-3 final-review carries are listed in `docs/superpowers/plans/2026-10-14-phase3-kmp-shared.md` §"Deferred to Phase 4" — Tasks 2 (set_next), 5 (poll idling, RemoteInfo error field if trivial) and 6 (networkSecurityConfig) consume the load-bearing ones; the test-polish items (skip-tick test, CountingDispatches dedup, host-triple detection) fold into whichever task touches their files.
