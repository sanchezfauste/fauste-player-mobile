# Fauste Player Mobile — Phase 1: AAudio Audio Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Phase-0 `AaudioBackend` skeleton into a real audio backend: `open_output` builds and runs an AAudio stream with a data callback that drives the desktop `Renderer`, errors classify through `StreamErrorSink` (disconnect, XRUN), the desktop conformance checks pass on an Android emulator, and the emulator lane runs in CI. Plus the Phase-0 final-review deferrals (JNA AAR, ktlint pin, named constants, Gradle hygiene).

**Architecture:** The backend opens exactly one output stream on the `"default"` device (AAudio has no enumeration), always in `AAUDIO_FORMAT_PCM_FLOAT` — the data callback hands its buffer straight to the desktop `Renderer` with no conversion. The renderer travels to the callback through a one-slot `rtrb` ring (the cpal pattern: the handoff happens only after the stream opens, so a failed attempt never loses the renderer). The error callback only classifies and reports (real-time safe); a non-RT monitor thread polls `AAudioStream_getXRunCount` for XRUN reporting. DSD is refused (`Native` and `DoP` both — the backend opens float PCM; revisit when DSD-capable Android hardware exists). Everything Android lives behind `cfg(target_os = "android")`; every piece of logic that can be host-tested is a plain function compiled everywhere.

**Tech Stack:** Rust 1.98 (workspace toolchain), `aaudio-sys 0.1`, `rtrb` (workspace dep), `fp-backends` trait, `fp-engine` (integration smoke, dev-dependency), `cargo-ndk 4.x` (`cargo ndk test` on the emulator), Android emulator API 34 x86_64 with KVM.

**Spec:** `docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md` (§3.2 Android Implementation, §9 Phase 1)

## Global Constraints

- Everything from Phase 0: English everywhere; Conventional Commits; no `unwrap`/`expect`/`panic` outside tests; `unsafe` only in `aaudio_backend.rs` Android arms with `// SAFETY:` comments; before every commit run `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings` (both feature states), `cargo test --workspace` (+ `--features uniffi`).
- **The real-time path is now live**: the data callback and the error callback must not allocate, lock, log, or panic — the error callback only classifies and calls `StreamErrorSink::report` (a cheap, lock-free trait method); the data callback only renders, fills silence, and returns.
- All AAudio constants pinned in this plan were verified against the installed NDK r27 header (`~/Android/Sdk/ndk/27.0.12077973/.../aaudio/AAudio.h`) — do not re-derive them from memory.
- Never modify anything under `../fauste-player`; the desktop crates are consumed by path, read-only.
- Emulator work happens locally on this machine (KVM present) and in CI on `ubuntu-latest` (KVM available).

## Review Focus

- **RT-path violation (alloc/lock/log/panic in a callback)** — the highest-severity defect this phase can produce. Pinned by Task 2's host tests (the callback body is plain Rust) and by review of Task 3's thin `extern "C"` wrapper.
- **Constant drift from the NDK** — `AAUDIO_SHARING_MODE_EXCLUSIVE = 0` is counter-intuitive (SHARED = 1); Phase 0 proved memory-guessing wrong once already. Pinned by Task 1's tests against header-verified values.
- **Renderer loss on a failed open** — the handoff must happen only after the stream exists (cpal pattern). Pinned by Task 2's handoff test.
- **Callbacks after drop** — `OutputStream` drop must stop and close the stream and join the monitor thread. Pinned by the conformance suite's post-drop check (Task 4) on the emulator (Task 5).
- **DoP/native DSD silently mangled by the float path** — refused outright in `check_dsd` (ruling below), so no DSD stream can reach the mixer at half-precision by accident.

## Rulings (plan-level, made in plan authorship)

1. **DSD refused on AAudio for Phase 1**: `Native` → `Unsupported` (no AAudio DSD path), `DoP` → `Unsupported` (the backend opens float PCM; DoP needs exact integer words — revisit with DSD-capable Android hardware and an integer exclusive mode). The desktop cpal backend allows DoP+exclusive; this is a deliberate Phase-1 restriction, documented in the module.
2. **XRUN reporting via a monitor thread**: AAudio does not deliver XRUNs to callbacks. A non-RT thread created at open polls `AAudioStream_getXRunCount` every 250 ms and reports `StreamErrorKind::Xrun` when the count increases; it exits on a stop flag and is joined in `Drop`. If the poll returns an error the thread exits silently (the error callback reports the real problem).
3. **Availability stays uncached** (Phase-0 deferral said "cache or document"): a `OnceLock` cache would hide device hotplug; instead `availability()` gets a doc comment stating it probes by opening a throwaway stream and that callers (the engine) invoke it rarely.
4. **Emulator conformance is a required CI gate**, because this plan verifies it locally first on a real emulator boot; if the local run exposes an emulator-audio gap, the implementer reports it instead of weakening the gate.

---

### Task 1: Builder parameter mapping (host-tested, TDD)

**Files:**
- Create: `crates-mobile/fp-backends-mobile/src/stream_params.rs`
- Create: `crates-mobile/fp-backends-mobile/tests/stream_params.rs`
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (add `mod stream_params;`)

**Interfaces:**
- Consumes: `fp_backends::{BackendError, DeviceId, StreamConfig, StreamErrorKind}`, `crate::stream_config` constants.
- Produces:
  - Pinned AAudio constants (header-verified): `AAUDIO_DIRECTION_OUTPUT = 0`, `AAUDIO_SHARING_MODE_EXCLUSIVE = 0`, `AAUDIO_SHARING_MODE_SHARED = 1`, `AAUDIO_PERFORMANCE_MODE_NONE = 10`, `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12`, `AAUDIO_USAGE_MEDIA = 1`, `AAUDIO_CONTENT_TYPE_MUSIC = 2`, `AAUDIO_CALLBACK_RESULT_CONTINUE = 0`, `AAUDIO_CALLBACK_RESULT_STOP = 1`, and `pub const AAUDIO_ERROR_DISCONNECTED: i32 = -899` (the Phase-0 deferred named constant, re-exported for `stream_config`).
  - `pub fn sharing_mode(exclusive: bool) -> i32` — `EXCLUSIVE` when true, `SHARED` otherwise.
  - `pub fn performance_mode() -> i32` — always `LOW_LATENCY` for Phase 1 (media playback wants the small buffers). Note: `AAudioStreamBuilder_setUsage`/`setContentType` are deliberately NOT called — both are API 28+ and the values we wanted are the platform defaults (usage defaults to MEDIA; contentType is a routing hint); skipping them keeps the API-26 link floor.
  - `pub fn check_device(device: &DeviceId) -> Result<(), BackendError>` — only `DeviceId("default")` opens; anything else is `DeviceNotFound(device.clone())`.
  - `pub fn check_dsd(config: &StreamConfig) -> Result<(), BackendError>` — `None` ok; `Native`/`Dop` → `Unsupported` (see Ruling 1; each arm's message says why).
  - `pub fn buffer_attempts(requested: u32, exact: bool) -> Vec<Option<u32>>` — the buffer sizes to try on `setBufferSizeInFrames`: `[Some(requested)]` when `exact_buffer`, `[Some(requested), None]` otherwise (`None` = leave the device's own default).

- [ ] **Step 1: Write the failing tests** — `tests/stream_params.rs`: pin every constant above (assert each value), `sharing_mode` both arms, `performance_mode() == 12`, `check_device` accepts `"default"` and refuses `"usb"` with `DeviceNotFound`, `check_dsd` accepts `None` and refuses both `Native` and `Dop` with `Unsupported` (each message asserted to contain the reason), and `buffer_attempts` for the four combinations (exact/None, exact/some, non-exact both).
- [ ] **Step 2:** `cargo test -p fp-backends-mobile --test stream_params` — FAIL (module missing).
- [ ] **Step 3:** Implement `src/stream_params.rs` exactly as the Interfaces block describes (constants first, then the four functions; doc comments reference the NDK header values). Replace `stream_config::classify_aaudio_error`'s magic `-899` with the named constant (Phase-0 deferral) — `use crate::stream_params::AAUDIO_ERROR_DISCONNECTED` there, keeping the match arm behavior identical.
- [ ] **Step 4:** The test passes (all constants + behaviors); full gates green (`fmt`, clippy both feature states, `cargo test --workspace` ×2).
- [ ] **Step 5: Commit** — `feat(backends-mobile): aaudio builder parameter mapping`

---

### Task 2: Data-callback core as plain Rust (host-tested, TDD)

**Files:**
- Create: `crates-mobile/fp-backends-mobile/src/callback.rs`
- Create: `crates-mobile/fp-backends-mobile/tests/callback.rs`
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (add `mod callback;`)

**Interfaces:**
- Consumes: `fp_backends::{Renderer, StreamErrorSink}`, `rtrb` (add to the crate's `[dependencies]` as `rtrb.workspace = true`).
- Produces: `pub struct CallbackCore` — everything the real data callback does, minus the FFI:
  - `pub fn new(channels: u16, scratch_frames_hint: u32) -> (rtrb::Producer<Box<dyn Renderer>>, Self)`
  - `pub fn fill(&mut self, out: &mut [f32], channels: usize) -> i32` — pops the renderer from a one-slot ring on first call (never blocks; `None` → `out.fill(0.0)`), renders through the popped renderer, records the largest `out.len()/channels` seen into `called_frames: Arc<AtomicU32>` (for `OutputStream::config`), returns `AAUDIO_CALLBACK_RESULT_CONTINUE`. Real-time safe: no allocation, no locks, no logging, no panics — `rtrb` push/pop are wait-free, `Renderer::render` is contract-bound the same way.
  - `pub fn called_frames(&self) -> Arc<AtomicU32>` for the stream struct.
- The real `extern "C"` wrapper in Task 3 is a thin `unsafe` slice cast plus one `fill` call — all logic lives here, host-tested.

- [ ] **Step 1: Write the failing tests** — `tests/callback.rs`:
  - a pushed renderer's samples arrive in `fill`'s output (use a fixed-ramp `Renderer` like the desktop tests'),
  - before the handoff, `fill` writes silence and returns CONTINUE,
  - the renderer is delivered exactly once: construct via `new`, push after the first `fill` (the "stream exists, renderer arrives" order), and confirm subsequent fills render,
  - `called_frames` tracks the largest block seen across two different-sized fills.
- [ ] **Step 2:** `cargo test -p fp-backends-mobile --test callback` — FAIL (module missing).
- [ ] **Step 3:** Implement `src/callback.rs`. The one-slot handoff ring is created inside `new` (mirror `cpal_backend.rs:511`: `let (tx, rx) = rtrb::RingBuffer::new(1);`), so a failed open attempt drops the unused producer and never the renderer.
- [ ] **Step 4:** Tests pass; full gates green.
- [ ] **Step 5: Commit** — `feat(backends-mobile): aaudio data callback core with one-slot renderer handoff`

---

### Task 3: `open_output` on Android — the FFI wiring

**Files:**
- Modify: `crates-mobile/fp-backends-mobile/src/aaudio_backend.rs` (the real stream replaces the `Unsupported` stub; host arms unchanged)
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (export `callback` items if needed)
- Test: verified by Task 4's suite; this task's check is `cargo check --target aarch64-linux-android` (clean, no warnings)

**Interfaces:**
- Consumes: `stream_params` (Task 1), `callback::CallbackCore` (Task 2), `stream_config::classify_aaudio_error`, `aaudio-sys 0.1` (verified symbol surface: `AAudio_createStreamBuilder`, `AAudioStreamBuilder_setSampleRate/setChannelCount/setFormat/setBufferCapacityInFrames/setSharingMode/setPerformanceMode/setDataCallback/setErrorCallback/openStream/delete`, `AAudioStream_requestStart/requestStop/close/getState/getBufferSize/getBufferCapacity/getXRunCount`). Errata (post-execution): `setUsage`/`setContentType` were dropped (API 28+; defaults match intent), there is no builder-level `setBufferSizeInFrames` in aaudio-sys 0.1 (the size is set on the stream after open), and the data callback's `channels` arrives per `fill`, not at construction.
- Produces (Android arms only):
  - `open_output` implementing the trait: `check_device` + `check_dsd` gates; builder setup (sample rate, channels, `AAUDIO_FORMAT_PCM_FLOAT`, sharing mode, `LOW_LATENCY`, `AAUDIO_USAGE_MEDIA`, `AAUDIO_CONTENT_TYPE_MUSIC`); data/error callbacks registered; `openStream` result mapped by a host-testable `open_error(code) -> BackendError` table (`AAUDIO_ERROR_DISCONNECTED` → `DeviceNotFound`, `AAUDIO_ERROR_ILLEGAL_ARGUMENT` → `Unsupported`, `AAUDIO_ERROR_UNAVAILABLE`/`AAUDIO_ERROR_INVALID_STATE` → `Busy`, rest → `Backend(msg)`); `setBufferSizeInFrames` walked over `buffer_attempts` (exact → refuse when the actual size differs); `requestStart`; return `Box<dyn OutputStream>`.
  - `struct AaudioStream` (implements `OutputStream`): `config()` mirrors the requested config with `buffer_frames` = actual `getBufferSize` (or requested before any callback), `sample_format()` = `F32`, `dsd()` = `None` (refused upstream). `Drop`: set the monitor stop flag, `requestStop`, join the monitor thread, then `close` (both `// SAFETY:` commented) — the monitor must be joined BEFORE `close` so it can never poll a closed stream.
  - The `extern "C"` data callback: `// SAFETY:` slice construction from `audio_data`/`num_frames` (raw pointer + non-zero length checked), one `CallbackCore::fill`, return its result.
  - The `extern "C"` error callback: classify (`classify_aaudio_error`) and `errors.report(kind)` — nothing else (RT-safe by contract).
  - The XRUN monitor thread (Ruling 2): 250 ms poll of `AAudioStream_getXRunCount`; on increase report `StreamErrorKind::Xrun`; exit on stop flag or poll error.
  - `pub fn open_error(code: i32) -> BackendError` lives in `stream_params` or the backend module **outside** the android cfg with host tests pinning the mapping (it is pure).
- [ ] **Step 1: Write the failing host test** for `open_error`'s mapping (each arm).
- [ ] **Step 2:** Test fails; then implement `open_error` + tests pass.
- [ ] **Step 3:** Implement the Android `open_output` + stream struct + callbacks + monitor per the Interfaces block. Every `unsafe` block carries a `// SAFETY:` comment; the file header (Phase 0) already documents the module as the sanctioned FFI boundary. No allocation/lock/log in either callback.
- [ ] **Step 4:** `cargo check -p fp-backends-mobile --target aarch64-linux-android` — exit 0, no warnings. Host gates still green (`fmt`, clippy both states, tests ×2 — host arms untouched).
- [ ] **Step 5: Commit** — `feat(backends-mobile): real aaudio output stream with error and xrun reporting`

---

### Task 4: Conformance suite + engine smoke (cfg android)

**Files:**
- Create: `crates-mobile/fp-backends-mobile/tests/conformance.rs`
- Create: `crates-mobile/fp-backends-mobile/tests/engine_smoke.rs`
- Modify: `crates-mobile/fp-backends-mobile/Cargo.toml` (`[dev-dependencies] fp-engine.workspace = true`, `crossbeam-channel` if the smoke needs it)

**Interfaces:**
- Consumes: the desktop conformance checks (`../fauste-player/crates/fp-backends/tests/conformance.rs` — the authority for what "conforms" means; mirror its `conform()` shape, do not import it), `fp_engine::Engine` (`Engine::new(Vec<Arc<dyn AudioBackend>>, EngineSettings, SourceOpener)` — read `../fauste-player/crates/fp-engine/src/engine.rs` and `fp-app/src/main.rs` for how to build the two last arguments; keep the smoke minimal).
- Produces:
  - `tests/conformance.rs`: the desktop `conform()` ported for `AaudioBackend` — availability `Available`, enumeration lists ≥1 named device, default device listed, open succeeds on the default device, `config()` sanity (`channels >= 2`, `sample_rate > 0`), renderer called with >4 800 frames within 3 s, **no callbacks after drop**, the device reopens. `#[cfg(target_os = "android")]` on the whole test (host: 0 tests). The `Counting` renderer and `Ignore` sink port verbatim; the `wait()` helper likewise.
  - `tests/engine_smoke.rs`: `#[cfg(target_os = "android")]` test constructing `Engine::new(vec![Arc::new(AaudioBackend::new())], settings, opener)` and asserting the engine reports the aaudio backend in use (or, if the emulator refuses exclusive/low-latency, that `Engine` still constructs and falls back — assert what the engine guarantees: construction succeeds and `backend_in_use()` is non-empty). Drop the engine. This pins the Phase-1 milestone "Integration with fp-engine::Engine".

- [ ] **Step 1:** Write both test files (they cannot run on the host — this task's RED is a compile check: `cargo test -p fp-backends-mobile --no-run` compiles them for the host where they reduce to 0 tests, and `cargo check --target aarch64-linux-android` must compile them for Android).
- [ ] **Step 2:** Both checks pass; full host gates green.
- [ ] **Step 3: Commit** — `test(backends-mobile): aaudio conformance suite and engine smoke`

---

### Task 5: Emulator provisioning + the on-device run

**Files:**
- Create: `scripts/emulator-test.sh` (executable; documented, repeatable)
- Modify: `.gitignore` if the AVD home needs an entry (use `~/.android/avd` defaults — nothing in-repo)

**Interfaces:**
- Consumes: the Task 4 suite; `sdkmanager`/`avdmanager`/`adb` from `~/Android/Sdk`; `cargo ndk test` (cargo-ndk 4.1.2 — verify the subcommand exists with `cargo ndk test --help`; if it does not, the script falls back to `cargo ndk build` of the test harness + `adb push` + `adb shell LD_LIBRARY_PATH=... run`, and records which path it took).
- Produces: `scripts/emulator-test.sh` that, end to end: installs `system-images;android-34;google_apis;x86_64` if absent, creates AVD `fauste-test` if absent, boots it headless **without `-no-audio`** (AAudio needs the virtual sound device; document that host audio is not required), waits for `adb wait-for-device` + boot completion, runs the Task 4 tests on the emulator (plain, no cargo features), and shuts the emulator down. The script exits non-zero on any failure and prints which check failed.

- [ ] **Step 1:** Write the script (no TDD possible — its first run IS the test).
- [ ] **Step 2:** Run it. Expected: conformance passes on the emulator (AAudio on the emulator's virtual device supports the shared low-latency path at 48 kHz stereo), engine smoke passes. If AAudio refuses something (exclusive mode is commonly unavailable on emulators — the suite opens shared), the failure is evidence: fix the suite or the backend per the trait contract, never the expectation. If the emulator exposes no usable AAudio device at all, STOP and report (Ruling 4).
- [ ] **Step 3:** Record the run's outcome (times, any emulator quirks) in the commit body; host gates green.
- [ ] **Step 4: Commit** — `test(mobile): aaudio conformance green on the emulator`

---

### Task 6: CI emulator lane

**Files:**
- Create: `.github/workflows/emulator.yml`
- Modify: none (the existing lanes stay untouched)

**Interfaces:**
- Consumes: `scripts/emulator-test.sh`'s proven flow; `reactivecircus/android-emulator-runner@v2` (the standard GitHub-Action emulator runner; ubuntu-latest has KVM).
- Produces: a workflow triggered on `pull_request` and `push` to `main` that checks out this repo AND the desktop sibling (`sanchezfauste/fauste-player`, `ref: master`, `path: ../fauste-player` — same as the Phase-0 fix), installs Rust 1.98.1 + the two Android targets + cargo-ndk + JDK (temurin 21), then uses the emulator-runner (api-level 34, target `google_apis`, arch `x86_64`, headless) to run the conformance suite inside the booted emulator — reusing `scripts/emulator-test.sh`'s test phase verbatim where possible so local and CI cannot drift. `permissions: contents: read` (Phase-0 deferral — also add this block to `ci.yml` and `android.yml` here, the Phase-6 housekeeping pulled forward).
- [ ] **Step 1:** Write the workflow mirroring the locally proven flow exactly.
- [ ] **Step 2:** Verify every command it runs was run locally in Task 5 (or has local evidence from earlier phases); YAML parse check.
- [ ] **Step 3: Commit** — `ci(mobile): emulator conformance lane`

---

### Task 7: Phase-0 final-review deferrals (housekeeping)

**Files:**
- Modify: `fp-app-mobile-shared/build.gradle.kts` (JNA → the AAR that ships Android natives; verify the coordinates on Maven Central — JNA 5.x publishes an AAR packaging of `net.java.dev.jna:jna`; use the version catalog; remove the TODO while keeping a one-line comment on why the AAR matters)
- Modify: `.github/workflows/ci.yml` (pin ktlint in the android job before the drift check, per Phase-0 Ruling 9)
- Modify: `crates-mobile/fp-backends-mobile/bindings/kotlin/` (regenerate once with ktlint available locally, commit the formatted output — the drift check then regenerates formatted bytes)
- Modify: `composeApp/build.gradle.kts` (`buildRust` declared `inputs`/`outputs` — the cargo manifest, `src/**`, `uniffi.toml` as inputs, the two staged `.so` as outputs — and hoist the `rootProject.file`/`projectDirectory` `File` values to configuration time for configuration-cache friendliness; prefer cargo-ndk NDK auto-detection: only set `ANDROID_NDK_HOME` when it is not already present in the environment)
- Modify: `crates-mobile/fp-backends-mobile/src/aaudio_backend.rs` (`availability()` doc comment per Ruling 3: probe cost + callers)

- [ ] **Step 1:** JNA AAR: verify coordinates (Maven Central listing for `net.java.dev.jna:jna` AAR packaging), switch the `androidMain` dependency, run `./gradlew :fp-app-mobile-shared:test` (compiles the bindings against the AAR) and `:composeApp:assembleDebug`.
- [ ] **Step 2:** ktlint: install locally (single executable download to /tmp/opencode), regenerate the bindings via the documented command, confirm the only diff is formatting, commit.
- [ ] **Step 3:** `buildRust` hygiene + NDK auto-detect; then `./gradlew buildRust :composeApp:assembleDebug` green twice in a row (second run exercises the cache).
- [ ] **Step 4:** `availability()` doc comment; host gates green.
- [ ] **Step 5: Commit** — `build(mobile): phase 0 final-review deferrals (jna aar, ktlint pin, buildRust caching)`

---

## Deferred to Phase 2 (from the Phase 1 final review)

- CI NDK precedence: the runner's pre-exported `ANDROID_NDK_HOME` silently defeats the `ndk;27.0.12077973` pin in `android.yml` — one-line workflow env fix (or drop the pin).
- `buildRust` cache key: add the explaining comment that neither the NDK identity nor the `../fauste-player` sources are inputs (a local `UP-TO-DATE` after a desktop pull is possible; CI is safe via fresh checkouts).
- `usize` laundering of the `!Send` stream handle in the monitor spawn → a grep-able `SendPtr` struct with a scoped `unsafe impl Send`.
- `requestStart` failures reuse the open-oriented `open_error` table (start-time `INVALID_STATE` surfaces as `Busy`) — split the mapping when transport error reporting matters.
- `CallbackCore::called_frames` is production-dead (tests only; `config()` uses the open-time readback) — wire it into `OutputStream::config` or remove it when the engine's config reporting lands.
- Exact-mode streams set capacity == requested size (zero double-buffer headroom) — consider capacity ≥ 2× size when device tuning begins.
- Pinned-but-unused AAudio constants (`AAUDIO_DIRECTION_OUTPUT`, `AAUDIO_USAGE_MEDIA`, `AAUDIO_CONTENT_TYPE_MUSIC`, `AAUDIO_CALLBACK_RESULT_STOP`) stay for the Phase-2 runtime-symbol work; do not mistake them for drift.
- First CI action after the repo gets a remote: trigger the emulator lane via `workflow_dispatch` and watch the first runner-backed run (the single locally-unverifiable element).

## Notes for the implementer

- The desktop `cpal_backend.rs` is the porting reference for the handoff/scratch/`frames_in_use` patterns — read it before Task 3 (path: `../fauste-player/crates/fp-backends/src/cpal_backend.rs`). Do not copy its integer-conversion machinery: the AAudio backend is float-only and needs none of it.
- `aaudio-sys 0.1` binds everything listed in Task 3's Interfaces (verified in Phase 0 against its published source). If a symbol is genuinely missing, STOP and report rather than re-declaring FFI types.
- The emulator's AAudio is real AAudio (MMAP-backed on API 29+ images); exclusive mode usually fails on emulators — that is why the conformance suite opens shared and only the *request* path of `exclusive = true` is exercised via `check_dsd`/`sharing_mode` unit tests.
- `EngineSettings`/`SourceOpener` construction for the smoke: read how `../fauste-player/crates/fp-app/src/main.rs` builds them; construct the smallest valid values. If `Engine::new` immediately spawns threads that need real audio, assert what it guarantees (construction + non-empty backend) and drop it promptly.
