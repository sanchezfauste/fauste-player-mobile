# Fauste Player Mobile — Phase 2: Rust Core Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Phase-0 inert `FausteEngine` stub with the real player core: Store on mobile-provided paths, `Engine` + `Conductor` + `Analyzer` spawned, the `fp-remote` server on a local port, and the UniFFI surface (play/pause/stop/seek/set_volume/enqueue/get_state) wired to the conductor — host-tested against the Null backend fallback, on-device smoke on the emulator, decode proven on Android.

**Architecture:** A new `player_core.rs` module owns the wiring (mirroring `../fauste-player/crates/fp-app/src/main.rs:120-160`, minus the UI/Services/MIDI layers that are desktop-only): `Store::new(AppPaths, Limits)` with KMP-provided dirs, `Engine::new([AaudioBackend], settings, file_opener())` (the engine appends its Null fallback), `Conductor::new(...).spawn()` → `Arc<ConductorHandle>`, `Analyzer::spawn(...)` with a cache under the cache dir, and `fp_remote::server::spawn(Arc<dyn RemoteControl>)` with a mobile `RemoteControl` impl. `ffi.rs`'s `FausteEngine` becomes a thin UniFFI object over `Arc<PlayerCore>`: commands via `ConductorHandle::send(Command)`, state via the handle's published `model`/`telemetry` (exactly the reads the desktop's remote `Bridge` does — `fp-app/src/remote.rs:84-105`).

**Tech Stack:** unchanged from Phase 1 (Rust 1.98, workspace path deps, UniFFI 0.28 proc-macro, emulator API 34 via `scripts/emulator-test.sh`).

**Spec:** `docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md` (§9 Phase 2)

## Global Constraints

- Everything from Phase 0/1: English everywhere; Conventional Commits; no `unwrap`/`expect`/`panic` outside tests; `unsafe` only in `aaudio_backend.rs` (unchanged this phase); RT-path rules bind wherever new code touches callback-adjacent paths (none expected); before every commit: `cargo fmt --all --check`, clippy `-D warnings` both feature states, tests ×2.
- **Never modify `../fauste-player`.** The spec's "`Store::new_mobile`" is ALREADY SATISFIED by the existing public API: `fp_store::AppPaths { pub data_dir, pub cache_dir }` + `Store::new(AppPaths, Limits)` (verified: `fp-store/src/paths.rs:9-31`, `store.rs:129`). The mobile side constructs `AppPaths` literally — no desktop change, no wrapper crate. This is a plan-level ruling.
- The UniFFI surface is regenerated (`bindings/kotlin`) whenever `ffi.rs` changes; drift-check symmetry (ktlint 1.7.1) must hold at every commit.
- Commands never block: `ConductorHandle::send` queues (returns false when full — map to a benign log-free outcome; see Task 3).

## Review Focus

- **State reads racing the conductor thread** — `model`/`telemetry` are ArcSwap/atomic reads the conductor thread publishes; the FFI layer must only `load` snapshots and convert, never hold references across threads. Pinned by Task 3's tests.
- **Block-safety of the FFI surface** — UniFFI methods may be called from the Kotlin main thread; nothing in the surface may block on the conductor (send is queued; loads are wait-free). 
- **Wiring fidelity vs the desktop** — constructor order, config source (`store.load` → `EngineSettings::from_config`), analyzer cache path, remote spawn. Pinned by the Task 1/2 host tests mirroring the desktop wiring lines.
- **Bindings drift** — Task 4's regeneration + drift-check symmetry.
- **Decode evidence on Android** — Task 4's decode smoke runs on the emulator harness.

---

### Task 1: `PlayerCore` — construction and lifetime (host-tested)

**Files:**
- Create: `crates-mobile/fp-backends-mobile/src/player_core.rs`
- Create: `crates-mobile/fp-backends-mobile/tests/player_core.rs`
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (`pub mod player_core;`), `Cargo.toml` (deps: `fp-store`, `fp-engine`, `fp-analysis`, `fp-model`, `fp-remote` as normal deps — workspace refs; plus whatever `Store::load` needs)

**Interfaces:**
- Consumes (read these desktop sources first; read-only): `fp-app/src/main.rs:120-160` (the wiring authority), `fp-store/src/paths.rs` + `store.rs` (`AppPaths`, `Store::new`, `store.load(&default_name)`), `fp-engine/src/engine.rs` (`Engine::new`, `EngineSettings::from_config`), `fp-engine/src/worker.rs` (`file_opener`), `fp-engine/src/conductor.rs` (`Conductor::new(state, actions, engine, Instant::now())`, `conductor.spawn(handle) -> io::Result<ConductorHandle>`), `fp-analysis` (`AnalysisCache::new`, `Analyzer::spawn(threads, settings, limits, Some(cache))`), `fp-model` (`Limits::default()`, the loaded state's `config`).
- Produces: `pub struct PlayerCore` with:
  - `pub fn new(data_dir: PathBuf, cache_dir: PathBuf) -> std::io::Result<Arc<PlayerCore>>` — desktop wiring minus UI/MIDI/Services: store → load → config → backends `vec![Arc::new(AaudioBackend::new())]` → Engine → Conductor + spawn → Analyzer (cache under `cache_dir/analysis`, thread count from `std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4)` clamped to a sane 1..=8 range) → keep `Arc<ConductorHandle>` + the analyzer's shutdown handle if it has one + what dropping needs.
  - `pub fn handle(&self) -> &Arc<ConductorHandle>`.
  - `Drop` (or explicit `shutdown`) that stops the spawned threads cleanly in reverse order (mirror the desktop's drop order; document what actually stops what).
- The default playlist name: mirror how the desktop computes it WITHOUT the i18n dependency — read what `I18n::new(None).tr("default-playlist-name")` yields and hardcode that same default string with a comment citing it (the mobile UI layer gets i18n in Phase 4).

- [ ] **Step 1: Failing tests** — `tests/player_core.rs` (host; Null fallback): construct `PlayerCore::new` with `std::env::temp_dir()` subdirs → `Ok`, `handle()` present; construct twice in sequence (second core drops cleanly); a third construction in parallel with a dropped first (thread teardown sanity); drop is clean (no hang — wrap drops in a timeout thread? no — simply asserting the test finishes is the evidence; document that).
- [ ] **Step 2:** RED (module missing), implement, GREEN.
- [ ] **Step 3:** Full gates green; `cargo check --target aarch64-linux-android` clean.
- [ ] **Step 4: Commit** — `feat(backends-mobile): player core wiring store engine conductor analyzer`

---

### Task 2: `RemoteControl` for the core + the local server

**Files:**
- Modify: `crates-mobile/fp-backends-mobile/src/player_core.rs` (impl + spawn)
- Create: `crates-mobile/fp-backends-mobile/tests/remote_server.rs`
- Modify: `Cargo.toml` (`fp-remote` dep if not already)

**Interfaces:**
- Consumes: `fp-remote/src/control.rs:71-85` (the `RemoteControl` trait: `model`, `playback`, `send`, `cover`, `peaks`), `fp-app/src/remote.rs:84-130` (the desktop Bridge impl — the model/playback read pattern to mirror), `fp-remote/src/server.rs` (`spawn(Arc<dyn RemoteControl>) -> io::Result<RemoteHandle>`), `fp-remote/src/events.rs`/`dto.rs` only as needed.
- Produces: `PlayerCore` gains a mobile `RemoteControl` impl: `model` = the handle's published model snapshot (same ArcSwap load as the Bridge), `playback` = the same telemetry read, `send` = `handle.send`; `cover`/`peaks` return `None` for Phase 2 (doc comment: media cache + analysis serving is Phase 3). `PlayerCore::new` spawns the remote server (`fp_remote::server::spawn`) and keeps the `RemoteHandle`; expose `pub fn remote_status(&self) -> RemoteStatus` for later UI use. Verify what port/interface `spawn` binds (read its server.rs) — if it binds non-loopback by default, this task pins it to loopback for the mobile default (the desktop may differ; mobile is a phone).
- Tests (`tests/remote_server.rs`, host): after `PlayerCore::new`, `remote_status` reports serving; a `TcpStream::connect` to the reported port succeeds and a minimal HTTP request gets a response (read `fp-remote/src/http` for a cheap GET path — or assert on `RemoteStatus` fields if a raw request is awkward); a command sent through the trait's `send` returns true; drop stops the server (connect fails after drop).

- [ ] **Step 1: Failing tests → implement → green** (TDD where the surface allows).
- [ ] **Step 2:** Full gates + android-target check.
- [ ] **Step 3: Commit** — `feat(backends-mobile): remote control and local server on the player core`

---

### Task 3: FFI v2 — the real `FausteEngine`

**Files:**
- Modify: `crates-mobile/fp-backends-mobile/src/ffi.rs` (replace the inert stubs)
- Modify: `crates-mobile/fp-backends-mobile/tests/ffi.rs` (keep the two Phase-0 tests green — they assert only the initial state, which still holds)
- Create: `crates-mobile/fp-backends-mobile/tests/ffi_state.rs`
- Modify: `crates-mobile/fp-backends-mobile/tests/engine_smoke.rs` (upgrade to the full core — cfg android stays)

**Interfaces:**
- Consumes: `PlayerCore` (Tasks 1-2), `fp_model::Command` (read `fp-model/src/command.rs` — the exact variants for play/pause/stop/seek/volume/enqueue; also how the desktop UI issues each — grep `Command::` in fp-app/src/ui/), `fp_model`'s state types (`PlayerState` status variants, the playlist entry shape for Track mapping), the Bridge's model/playback reads for `get_state`.
- Produces: `FausteEngine::new(EngineConfig)` constructs `PlayerCore::new(data_dir, cache_dir)` (with sample-rate/buffer overrides threaded into `EngineSettings` the way `from_config` consumers would — read how config fields map; if the loaded config already carries them, prefer the loaded config and note the EngineConfig fields are a Phase-2 no-op until Phase 3's settings UI — document either way). Methods:
  - `play/pause/stop` → the corresponding `Command`s via `handle.send`; map a `false` return (queue full) to `FausteError::Busy`-style result or log-free drop — judge and document (the trait docs say never blocks; dropping is acceptable, a Result is better).
  - `seek(position)` → the seek Command (find its exact shape — seconds? player id?; the mobile FFI operates the MAIN player, not carts, for Phase 2).
  - `set_volume(f32)` → the volume Command (same main-player rule).
  - `enqueue(track: Track)` → the desktop enqueue/open Command for the main playlist (find the exact one the UI uses for "add to playlist"), mapping `Track.path` to a `PathBuf`; title/artist are metadata reads later.
  - `get_state()` → from `handle.model.load_full()`: the main player's `PlayerState` status mapped to the string ("playing"/"paused"/"stopped"/…), current track from the model's playlist/entry, duration/position from telemetry (`playback()`-style reads — mirror Bridge.playback), volume from the model config, backend from the engine's in-use id (expose what PlayerCore/Engine offers — `Engine::backend_in_use`).
  - All methods: no blocking (queued sends, snapshot loads), Result types where failure is real (play/pause/etc. return `Result<(), FausteError>` — define the `FausteError` enum now: `Busy`, `Core(String)`, `NotFound`), `Arc<Self>` unchanged.
- Host tests (`tests/ffi_state.rs`): construct via temp dirs; `get_state()` initial → "stopped"-family status (whatever the model calls an idle main player — assert the real value, not the Phase-0 string); `enqueue` a temp WAV path (generate a tiny valid WAV in the test — a few hundred silent samples, 16-bit PCM — and use it so decode actually runs on `play`); `play()` → Ok; poll `get_state()` (bounded loop, e.g. ≤2 s at 10 ms) until status is the playing family (Null backend still transitions the model); `pause()` → paused family; `seek` on the enqueued track; `stop()`; volume round-trip.
- Emulator smoke (`tests/engine_smoke.rs`): construct the full core on-device, `remote_status` serving, enqueue+play, drop — extends the existing cfg(android) file.

- [ ] **Step 1:** Failing host tests → implement → green (bounded-wait polling keeps them deterministic).
- [ ] **Step 2:** Full gates + android-target check + `./scripts/emulator-test.sh` green (the smoke now exercises the full core — the script's `engine_smoke-*` glob picks it up unchanged).
- [ ] **Step 3: Commit** — `feat(mobile): real uniffi engine over the player core`

---

### Task 4: Bindings regeneration + decode smoke + CI

**Files:**
- Modify: `crates-mobile/fp-backends-mobile/bindings/kotlin/fauste_player.kt` (regenerated)
- Create: `crates-mobile/fp-backends-mobile/tests/decode_smoke.rs` (runs on host AND android)
- Modify: `scripts/emulator-test.sh` (extend the test-binary glob to include `decode_smoke`)
- Modify: `.github/workflows/ci.yml` only if the rust lane needs the new test binary named explicitly (it runs `cargo test --workspace` — it does not; verify)

**Interfaces:**
- Consumes: the Task 3 FFI (regeneration), `fp-decode` (`probe`/`FileDecoder` — read `fp-decode/src/lib.rs`), the ktlint-pinned generation flow (Phase 1 Task 7).
- Produces: regenerated bindings matching the v2 surface (ktlint-formatted, drift-check symmetric); a decode smoke that writes a small valid FLAC or WAV (WAV is the lightest: a hand-built RIFF header + samples — or use a fixture-generation helper if fp-decode's own tests have one to mirror), decodes it through `fp_decode`, and asserts sample counts + non-silence; the emulator harness runs it on-device (proving decode on Android); `./gradlew :fp-app-mobile-shared:test :composeApp:assembleDebug` green with the new bindings.

- [ ] **Step 1:** Decode smoke failing → implement → host green.
- [ ] **Step 2:** Regenerate bindings (documented command + ktlint), commit the regeneration; drift check passes.
- [ ] **Step 3:** `./scripts/emulator-test.sh` green with the extended glob (conformance + engine_smoke + decode_smoke on-device); gradle lanes green.
- [ ] **Step 4: Commit** — `feat(mobile): v2 bindings and decode smoke on android`

---

## Notes for the implementer

- The desktop wiring is the authority; where the desktop passes UI-layer values (i18n name, notices), the mobile uses the computed default with a citing comment.
- `ConductorHandle`'s public field surface (`model`, `telemetry`, and `send`) is what both the desktop Bridge and this phase read — do not invent alternative state channels.
- If `Analyzer::spawn` or the remote `spawn` bind things that make host tests flaky (ports, thread counts), prefer configurable-with-defaults over test-only branches.
- Phase-2 deferrals from Phase 1's final review (NDK-precedence CI line, buildRust cache-key comment, SendPtr, start-error table, called_frames) are NOT this phase's work — they stay on the Phase-1 plan's deferred list.
