# Fauste Player Mobile — Phase 3: KMP Shared + ViewModels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Kotlin shared module becomes the real middle layer: the FFI surface grows what a player app needs (queue/playlist operations, real backend selection, remote status), the platform expect/actual set is completed (Android), `PlayerController`/`QueueManager`/`PlaybackState` expose the engine as `StateFlow`s (UDF), repositories wrap the Rust services, Koin wires the graph, and the Kotlin tests exercise the REAL Rust core on the host JVM.

**Architecture:** Unidirectional data flow, spec §5: UI (Phase 4) subscribes to `StateFlow`s and sends intents to ViewModels; ViewModels call `PlayerController`/`QueueManager`; those talk to `FausteEngine` (UniFFI) only. State delivery is **polling** this phase (ruling 2 below): `PlayerController` polls `get_state()` on a background dispatcher into a `StateFlow<EngineState>`; no UniFFI callback surface yet. The shared module has only `androidTarget`, and its `commonTest` runs as Android unit tests on the host JVM — with `jna.library.path` pointed at the Rust host cdylib (`target/debug`), those tests drive the real engine end-to-end (Null-backend fallback, exactly like the Rust host tests).

**Tech Stack:** Kotlin 2.2.21 (KMP), Koin 4.0.0 (already in `gradle/libs.versions.toml`), UniFFI bindings 0.28 (regenerated this phase), coroutines (`kotlinx-coroutines-core` — add to catalog), Rust 1.98 unchanged.

**Spec:** `docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md` (§4 shared-module structure, §5 ViewModel pattern, §9 Phase 3)

## Rulings (plan-level)

1. **Koin, not Hilt.** Spec §4 "Key Patterns" + the Phase 3 milestone both say Koin; the `@HiltViewModel` annotation in §5's sample is spec noise. `AppModule` is `expect` in commonMain, `actual` in androidMain.
2. **State delivery = polling.** `PlayerController` polls `get_state()` (default 100 ms, injectable) on `Dispatchers.Default` into a `StateFlow`. A UniFFI callback/push surface is a later-phase refinement; document the choice in the controller's docs. Position display may interpolate client-side later; the poll is the truth source.
3. **Queue semantics come from the desktop, not invention.** Investigate `fp-model`'s `Command` set for next/previous/remove/move and how the desktop UI drives playlist order; wire exactly what exists. If explicit next/previous Commands don't exist, `QueueManager` composes them from the entries the engine exposes (read how the desktop's auto-advance works first — mirror it, don't invent).
4. **i18n `.ftl` resources deferred to Phase 4** (UI-facing; no consumer exists yet in this phase).
5. **MediaRepository = file import only** (SAF `content://` → copy into `filesDir/import` → return a real path for `enqueue`). Covers/peaks/waveform data need the analysis pipeline (MediaCache) and belong with their consumers in Phase 4.
6. **RemoteApi is a minimal expect/actual HTTP client** (`HttpURLConnection` on Android; common interface) for the "control a desktop fauste-player" feature; no Ktor dependency this phase. RemoteServer (this device's own server) is a thin wrapper over the FFI's `remote_status`.
7. **Kotlin tests drive the real cdylib on the host JVM**: the unit-test task sets `jna.library.path` to the Rust `target/debug` dir so `commonTest` constructs `FausteEngine` with temp dirs and exercises play/pause/queue for real — the strongest end-to-end validation of the bindings outside the emulator.

## Global Constraints

- Phase 0/1/2 norms unchanged: English everywhere; Conventional Commits; no `unwrap`/`expect`/`panic` outside tests (Rust) — Kotlin: no empty catch, no `!!`, no blocking main-thread calls; gates before every commit: cargo fmt/clippy (both feature states) + `cargo test --workspace` ×2 when Rust changed; `./gradlew :fp-app-mobile-shared:testDebugUnitTest :composeApp:assembleDebug` when Kotlin changed; `scripts/emulator-test.sh` when the on-device surface changed.
- Bindings regenerate (ktlint 1.7.1 flow, byte-symmetric with the CI drift check) whenever `ffi.rs` changes.
- Never modify `../fauste-player`.

## Review Focus

- **FFI surface additions match desktop semantics** (queue/playlist commands exactly as the desktop issues them — the Task 3 FFI-v2 review verified transport commands this way; hold the new ones to the same bar).
- **Poll loop hygiene**: the poll must never leak across cancellation, must survive `Busy` results silently (retry next tick), and must not spin when the engine is dropped (structured concurrency).
- **Thread discipline on the Kotlin side**: every `FausteEngine` call off the main thread; the constructor is documented non-blocking-exempt (Phase-2 deferral note — call it off-main anyway).
- **Bindings symmetry** after regeneration.
- **The Phase-2 deferred footguns stay visible**: `port = 0` vs `RemoteConfig::validate` (any `UpdateConfig` round-trip must not clamp the ephemeral port — Task 1's `set_backend` path must not re-validate config in a way that moves the server).

---

### Task 1: FFI surface v3 — queue, backend selection, remote status (Rust, TDD)

**Files:**
- Modify: `crates-mobile/fp-backends-mobile/src/ffi.rs`, `player_core.rs` (as needed for the new reads)
- Modify: `crates-mobile/fp-backends-mobile/tests/ffi_state.rs`, `tests/engine_smoke.rs`
- Modify: `crates-mobile/fp-backends-mobile/bindings/kotlin/fauste_player.kt` (regenerated)
- Modify: `scripts/emulator-test.sh` only if a new on-device test binary appears (name it within the existing globs if possible)

**Interfaces:**
- Investigate first (read-only): `../fauste-player/crates/fp-model/src/command.rs` (the full `Command` set — which of next/previous/remove/move/queue operations exist), the desktop UI's playlist manipulation (grep `Command::` under `fp-app/src/ui/` — the drag-reorder and delete paths), the auto-advance mechanics (how the engine advances at track end), and `Command::UpdateConfig` (how the desktop switches `audio_system`).
- Produces (in `ffi.rs`, each TDD-host-tested then wired):
  - `set_backend(id: String) -> Result<(), FausteError>` — REAL: the desktop's config-update path (`UpdateConfig` with the audio-system change), or the exact command the desktop settings UI uses. If the engine re-opens the stream asynchronously, the method returns after the command is queued (never blocks).
  - Queue/playlist surface per investigation, minimally: `get_playlist() -> Vec<PlaylistEntry>` (id, path, title-from-stem if the model exposes it, duration when known) and the remove/move commands the desktop has; next/previous only if the desktop has them (otherwise document their absence — QueueManager composes in Task 3). New record `PlaylistEntry` (uniffi::Record).
  - `remote_status() -> RemoteInfo` (record: enabled, address string) from `PlayerCore::remote_status()`.
  - `get_available_backends` becomes real: availability probed at call time (retain an `Arc<AaudioBackend>` in `PlayerCore` for probing; the engine's Null fallback stays implicit).
  - Phase-2 deferrals owed here: the `with_overrides` behavioral pin test (`44_100` override observed in `model.config.outputs.sample_rate`), the `enqueue` metadata doc line, the `FausteEngine::new` non-blocking doc note.
- Verify the `port = 0` footgun: the `set_backend`/config path must not round-trip the config through `RemoteConfig::validate` in a way that clamps the mobile server's ephemeral port (read `fp-model/src/remote.rs:196` and how `UpdateConfig` is applied in the conductor). If the desktop path re-validates, guard it (document; if unavoidable, report BLOCKED with the exact chain).
- Regen bindings (ktlint flow) + drift symmetry; extend `engine_smoke.rs` (on-device): set_backend round, get_playlist after enqueue, remote_status non-empty.

- [ ] **Step 1:** Investigation notes in the report (command inventory with citations).
- [ ] **Step 2:** Failing host tests → implement → green; gates + android-target check.
- [ ] **Step 3:** Regen bindings, drift check; `./scripts/emulator-test.sh` green.
- [ ] **Step 4: Commit** — `feat(mobile): ffi queue surface, real backend selection, remote status`

---

### Task 2: Platform expect/actual set + Koin skeleton (Kotlin)

**Files:**
- Modify: `fp-app-mobile-shared/src/commonMain/kotlin/org/fauste/player/PlatformContext.kt` (+ the existing android actual) — extend to the full contract: app dirs (`filesDir`, `cacheDir`), a way to run on main/background (injectable dispatchers live in DI, not here).
- Create: `platform/Storage.kt`, `platform/Permissions.kt`, `platform/PowerManager.kt`, `platform/Lifecycle.kt` (commonMain expect) + `androidMain` actuals (`Context`-bound: SAF copy helper `importToAppStorage(uri): String?`, permission string sets `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE` by SDK, wake-lock wrapper, lifecycle-observer hooks).
- Create: `di/AppModule.kt` expect (commonMain) + actual (androidMain): Koin module registering `PlatformContext`, dispatchers, and the `FausteEngine` factory (data/cache dirs from `PlatformContext`; constructor off-main is the caller's job — the factory runs on `Dispatchers.Default`).
- Modify: `gradle/libs.versions.toml` + the shared `build.gradle.kts` (koin-core commonMain, koin-android androidMain, coroutines; the catalog already reserves `koin = "4.0.0"`).
- Tests (`commonTest`): the Koin module loads and resolves `PlatformContext` + the engine factory against fakes/Android unit-test defaults (whatever runs on the host JVM without a device — judge and document).

- [ ] **Step 1:** expect/actual contracts defined; failing tests; implement; green.
- [ ] **Step 2:** `./gradlew :fp-app-mobile-shared:testDebugUnitTest` + `:composeApp:assembleDebug` green.
- [ ] **Step 3: Commit** — `feat(mobile): platform abstractions and koin skeleton in shared`

---

### Task 3: PlayerController, QueueManager, PlaybackState (Kotlin, TDD against the real engine)

**Files:**
- Create: `player/PlaybackStatus.kt` (mapping from the FFI status string set to a Kotlin enum — mirror the Rust DTO's strings exactly, cite `ffi.rs`), `player/PlayerController.kt`, `player/QueueManager.kt`, `player/PlaybackState.kt` under commonMain.
- Modify: the shared `build.gradle.kts` test wiring — `jna.library.path` for the unit-test task pointing at the Rust host cdylib dir (Ruling 7) so `commonTest` can construct the real `FausteEngine`.
- Tests (`commonTest`): construct the real engine (temp dirs, generated WAV bytes like the Rust tests'); enqueue → playlist reflects it (`get_playlist`); play → `PlaybackStatus.Playing` observed via the controller's flow within a bounded window (2 s, 10 ms polls); pause/stop/seek/volume round-trips; queue remove/move/next/previous per Task 1's surface; poll loop cancels cleanly (cancellation test with `runTest`); `Busy` result → the flow survives and the next tick proceeds.
- `PlayerController`: injectable poll period + `CoroutineDispatcher`; commands as `suspend fun` returning the FFI result mapped; every engine call on the injected dispatcher.
- `QueueManager`: playlist `StateFlow` refreshed after mutations (and on controller state changes if the surface allows); next/previous per Task 1's finding (Ruling 3).

- [ ] **Step 1:** Failing tests → implement → green (real-FFI tests are the RED/GREEN evidence).
- [ ] **Step 2:** Gradle lanes green; the Rust side untouched this task (bindings from Task 1 are consumed as-is).
- [ ] **Step 3: Commit** — `feat(mobile): player controller queue manager and playback flows`

---

### Task 4: Repositories, RemoteApi, RemoteServer wrapper, full DI (Kotlin)

**Files:**
- Create: `repository/PlaylistRepository.kt` (enqueue/remove/move/list via the controller/FFI — a thin, honest wrapper), `repository/SettingsRepository.kt` (backend list/select via the FFI; volume; the config surface that exists), `repository/MediaRepository.kt` (import path via `Storage` expect — Ruling 5).
- Create: `remote/RemoteServer.kt` (wraps `remote_status()` → a `StateFlow` of the server address for UI), `remote/RemoteApi.kt` (expect) + android actual (`HttpURLConnection`; `suspend fun fetchState(host, port): String?` minimal, off-main dispatcher).
- Modify: `di/AppModule.kt` — the complete graph: platform, engine, controller, queue, repositories, remote.
- Tests: repositories against the real engine (host); RemoteApi actual against a local `ServerSocket` stub (no network); the Koin graph resolves every public type.
- Verification: `./gradlew :fp-app-mobile-shared:testDebugUnitTest :composeApp:assembleDebug` green; `./scripts/emulator-test.sh` green (no Rust change expected — a sanity re-run).

- [ ] **Step 1:** Failing tests → implement → green.
- [ ] **Step 2:** Full gradle battery + emulator sanity run.
- [ ] **Step 3: Commit** — `feat(mobile): repositories remote wrappers and complete koin graph`

---

## Deferred to Phase 4 (from the Phase 3 final review)

- **FFI `set_next` + a `QueueManager` jump/`next` composition** — the queue-UI's tap-to-play seam (the desktop's row-activation rides `Command::SetNext`); explicitly plan it, don't discover it in Phase 4's UI work.
- **Settings-saver/state-restore must normalize the remote port** on save/load (a saved ephemeral 0 clamps to 1024 at load-time validation and moves the server) — the degraded-window address-move fix rides this same work.
- Skip-tick poll branch test (transient throw → loop survives → `lastPollFailure` clears on the next success — trivial via the `FausteEngineInterface` seam).
- Gradle: derive the JNA host dispatch path from `os.name`/`os.arch` (currently hardcoded linux-x86-64 — fails loudly elsewhere, blocks mac/ARM contributors).
- Dedup `CountingDispatches` (`PlayerControllerRealEngineTest` should adopt `RealEngineFixture`'s).
- `QueueManager.move`: clamp `toIndex` before `toInt()` (a pathological > Int.MAX index stalls the confirm window the full 2 s).
- Do NOT key Phase-4 UI state on `enqueue`'s returned id (concurrent duplicate-path enqueues can both confirm on the newest row — read row ids from the playlist flow).
- Wire the controller's 100 ms poll idling to `AppLifecycleHolder` (currently never idles — runs while stopped and backgrounded).
- `RemoteInfo`: consider an error field (a bind-failure retry currently reads as enabled-with-empty-address).
- `networkSecurityConfig` permitting cleartext to 127.0.0.1 for on-device `RemoteApi.fetchState`.
- Volume observability: surface a volume flow in the UI layer (the repository exposes only a snapshot, documented).
- Consume `PlaybackState.fromEngineState` for the ViewModel mapping (tested, plan-mandated, currently unconsumed — by design until Phase 4).
- Decide the queue-refresh story for HTTP-remote mutations before the remote-control feature becomes user-visible (the QueueManager flow goes stale on them until the next local mutation — documented caveat).
- Stale desktop line citations in KDoc (ffi.rs + bindings): refresh opportunistically as Phase 4 touches each one.

## Notes for the implementer

- The Kotlin side must treat the bindings as read-only generated code; nothing outside `bindings/kotlin/` edits them.
- `kotlinx.coroutines` version: pick what Koin 4.0.0 / Kotlin 2.2.21 align with (the catalog addition is yours; document the choice in the catalog comment).
- The desktop UI greps are the authority for command semantics — the Phase 2 review verified transport commands against `fp-app/src/ui/player.rs` etc.; do the same for playlist/queue.
- If `commonTest` cannot load the host cdylib (JNA path issues), STOP and report the exact loader error rather than mocking the engine away — the real-FFI test layer is this phase's core value.
