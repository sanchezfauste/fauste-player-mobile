# Fauste Player Mobile — Phase 0: Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the mobile project skeleton: a Cargo workspace that reuses the desktop crates by path, a new `fp-backends-mobile` crate with the `AudioBackend` trait wired, a Kotlin Multiplatform project (`fp-app-mobile-shared` + `composeApp`) that builds, and a CI pipeline that runs Rust and Android checks on every PR.

**Architecture:** The mobile repo keeps its own workspace but points at the sibling desktop repo's crates with relative path dependencies (`../fauste-player/crates/*`), so the original project is never modified. A new crate `fp-backends-mobile` implements the existing `AudioBackend` trait for AAudio (Android) and AudioEngine (iOS, stub). The Kotlin side is a KMP shared module consumed by a Compose Multiplatform app; UniFFI generates the Kotlin bindings from a `.udl` file. Gradle compiles the Rust `cdylib` for Android targets via a Cargo task and packages the `.so` files into the APK.

**Tech Stack:** Rust 2024 edition (matching the desktop toolchain), `fp-backends` trait, AAudio via `aaudio-sys` (behind `cfg(target_os = "android")`), Kotlin 2.0 + Compose Multiplatform, Gradle 8.5+, Android NDK r27, UniFFI 0.28, GitHub Actions.

**Spec:** `docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md`

## Global Constraints

- English for all code, comments, docs, specs, plans and commits; answer the maintainer in Spanish.
- Never modify anything under the sibling desktop repo `../fauste-player/`; the mobile repo references it by path only.
- No `unsafe` outside documented FFI boundaries. The workspace lints keep `unsafe_code = "forbid"`; a `forbid` lint cannot be locally relaxed, so `fp-backends-mobile` is the one crate that does not inherit workspace lints — it defines its own `[lints]` with `unsafe_code = "deny"` (plus the clippy denies below), and only its FFI module uses `#![allow(unsafe_code)]` with `// SAFETY:` comments.
- No `unwrap`, `expect` or `panic` outside tests (clippy `unwrap_used`, `expect_used`, `panic` denied).
- `fp-backends-mobile` denies `clippy::indexing_slicing` (use `get`).
- The real-time path never allocates, frees, locks, logs or panics.
- Conventional Commits: `feat(mobile): …`, `build(mobile): …`, `ci(mobile): …`.
- Before every commit: `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`.
- Android `minSdk = 26` (AAudio floor), `compileSdk = 34`, `applicationId = "org.fauste.player"`.
- The desktop workspace pins Rust 1.98 (`rust-toolchain.toml`); the mobile workspace uses the same version.

## Review Focus

- **Cross-repo path dependencies break when the desktop repo moves** — the workspace must fail loudly at parse time, not silently build stale code. Pinned by Task 1's `cargo metadata` check.
- **A library update in the desktop repo changes mobile behaviour silently** — `Cargo.lock` must be committed so builds are reproducible. Pinned by Task 1's lockfile check step.
- **The `cdylib` compiled for one Android ABI lands in every APK** — the Gradle Cargo task must produce one `.so` per target and the packaging check must see them all. Pinned by Task 5's `assembleDebug` assertion on `jniLibs` contents.
- **UniFFI-generated bindings drift from the `.udl`** — the build regenerates bindings on every compile and CI diffs them. Pinned by Task 4's generation-in-build requirement.
- **Rust panics crossing the FFI boundary crash the whole app** — the exposed UniFFI methods must catch unwind at the boundary. Pinned by Task 4's `uniffi::setup_scaffolding` panic-handler test.

---

### Task 1: Cargo workspace that reuses the desktop crates

**Files:**
- Create: `Cargo.toml`
- Create: `rust-toolchain.toml`
- Create: `deny.toml`
- Create: `.gitignore`
- Test: `crates-mobile/fp-backends-mobile/Cargo.toml` (compile-only in this task)

**Interfaces:**
- Consumes: the sibling desktop repo `../fauste-player/crates/{fp-model,fp-backends,fp-decode,fp-engine,fp-store,fp-analysis,fp-control,fp-remote}` — the mobile repo root and the desktop repo root sit side by side, so every path into the desktop project is `../fauste-player/crates/<name>`.
- Produces: a workspace whose members can `use fp_backends::AudioBackend;` and whose dependency names are identical to the desktop (`fp-model`, `fp-backends`, …), plus the directory layout `crates-mobile/` for new crates.

- [ ] **Step 1: Write the workspace manifest**

`Cargo.toml` at the repo root:

```toml
[workspace]
resolver = "3"
members = ["crates-mobile/*"]
exclude = ["vendor"]

[workspace.package]
version = "0.1.0"
edition = "2024"
rust-version = "1.98"
publish = false

[workspace.dependencies]
fp-model = { path = "../fauste-player/crates/fp-model" }
fp-backends = { path = "../fauste-player/crates/fp-backends" }
fp-decode = { path = "../fauste-player/crates/fp-decode" }
fp-engine = { path = "../fauste-player/crates/fp-engine" }
fp-store = { path = "../fauste-player/crates/fp-store" }
fp-analysis = { path = "../fauste-player/crates/fp-analysis" }
fp-control = { path = "../fauste-player/crates/fp-control" }
fp-remote = { path = "../fauste-player/crates/fp-remote" }
thiserror = "2.0.21"
tracing = "0.1.44"
crossbeam-channel = "0.5.17"
rtrb = "0.4.0"

[workspace.lints.rust]
unsafe_code = "forbid"

[workspace.lints.clippy]
unwrap_used = "deny"
expect_used = "deny"
panic = "deny"
```

Copy `rust-toolchain.toml` and `deny.toml` from the desktop repo verbatim (same pinned toolchain, same licence/advisory policy), and write a `.gitignore` with `target/`, `Cargo.lock` **not** ignored (lockfile is committed), `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`.

- [ ] **Step 2: Create the first member crate so the workspace resolves**

Create `crates-mobile/fp-backends-mobile/Cargo.toml` and a `src/lib.rs` whose entire body is `//! Mobile audio backends implementing the desktop \`AudioBackend\` trait.` (doc-comment only, no code yet). The manifest:

```toml
[package]
name = "fp-backends-mobile"
description = "Mobile audio output backends (AAudio, AudioEngine) behind the desktop AudioBackend trait"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
publish.workspace = true

[dependencies]
fp-backends.workspace = true
thiserror.workspace = true
tracing.workspace = true

[lints]
workspace = true
```

- [ ] **Step 3: Verify the workspace parses and the desktop crates resolve**

Run: `cargo metadata --no-deps --format-version 1`
Expected: JSON listing `fp-backends-mobile` as the only workspace member, and `cargo check -p fp-backends-mobile` succeeds, which transitively compiles `fp-backends` from the desktop repo.

Run: `cargo check -p fp-backends-mobile`
Expected: `Finished` with zero errors.

- [ ] **Step 4: Verify the safety lints are active**

Run: `cargo clippy --workspace --all-targets -- -D warnings`
Expected: passes (there is no code yet, but the lint config must not error).

- [ ] **Step 5: Commit**

```bash
git add Cargo.toml Cargo.lock rust-toolchain.toml deny.toml .gitignore crates-mobile/
git commit -m "build(mobile): workspace reusing the desktop crates by path"
```

---

### Task 2: `StreamConfig` conversion and error classification (pure logic, TDD)

**Files:**
- Create: `crates-mobile/fp-backends-mobile/src/stream_config.rs`
- Create: `crates-mobile/fp-backends-mobile/tests/stream_config.rs`
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (add `mod stream_config;`)

**Interfaces:**
- Consumes: `fp_backends::{StreamConfig, SampleFormat}` and the AAudio constants (`AAUDIO_FORMAT_PCM_FLOAT`, `AAUDIO_FORMAT_PCM_I32`, `AAUDIO_FORMAT_PCM_I16`, `AAUDIO_OK`), via `aaudio-sys` types re-declared locally as plain `i32`/`u32` constants so the module tests on the host.
- Produces:
  - `pub fn aaudio_format(format: SampleFormat) -> i32` — desktop `SampleFormat` → `aaudio_format_t` value.
  - `pub fn classify_aaudio_error(code: i32) -> StreamErrorKind` — AAudio result codes → desktop `StreamErrorKind`.
  - `pub const AAUDIO_STREAM_STATE_STARTED: i32` and the other state codes the backend polls, as an `AaudioStreamState` enum-free plain module so host tests run without the NDK.

- [ ] **Step 1: Write the failing tests**

`crates-mobile/fp-backends-mobile/tests/stream_config.rs`:

```rust
use fp_backends::{SampleFormat, StreamErrorKind};

/// AAudio format codes from the NDK headers (r27 `AAudio.h`), asserted once
/// so the conversion table cannot drift from the platform values.
#[test]
fn aaudio_format_codes_match_the_ndk_headers() {
    // AAUDIO_FORMAT_UNSPECIFIED = 0, I16 = 1, FLOAT = 2, I24_PACKED = 3,
    // I32 = 4, IEC61937 = 5 in the NDK.
    // The test pins the mapping the backend relies on.
    assert_eq!(fp_backends_mobile::stream_config::AAUDIO_FORMAT_PCM_FLOAT, 2);
    assert_eq!(fp_backends_mobile::stream_config::AAUDIO_FORMAT_PCM_I32, 4);
    assert_eq!(fp_backends_mobile::stream_config::AAUDIO_FORMAT_PCM_I16, 1);
}

#[test]
fn desktop_formats_map_to_their_aaudio_equivalents() {
    use fp_backends_mobile::stream_config::aaudio_format;
    assert_eq!(aaudio_format(SampleFormat::F32), 2);
    assert_eq!(aaudio_format(SampleFormat::I32), 4);
    assert_eq!(aaudio_format(SampleFormat::I16), 1);
    // I24 maps to I32: AAUDIO_FORMAT_PCM_I24_PACKED (3) exists but requires
    // API 31 and minSdk is 26, so the backend converts to I32.
    assert_eq!(aaudio_format(SampleFormat::I24), 4);
}

#[test]
fn aaudio_error_codes_classify_into_desktop_kinds() {
    use fp_backends_mobile::stream_config::classify_aaudio_error;
    // AAUDIO_ERROR_BASE = -900, DISCONNECTED = -899, ILLEGAL_ARGUMENT = -898,
    // INTERNAL = -896, INVALID_STATE = -895, INVALID_HANDLE = -892,
    // UNIMPLEMENTED = -890, TIMEOUT = -885 in the NDK. XRUN is handled via
    // AAUDIO_STREAM_STATE_OVERFLOW in the state machine, not a result code.
    assert_eq!(classify_aaudio_error(-899), StreamErrorKind::DeviceLost);
    assert_eq!(classify_aaudio_error(-885), StreamErrorKind::Other);
    assert_eq!(classify_aaudio_error(0), StreamErrorKind::Other);
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cargo test -p fp-backends-mobile --test stream_config`
Expected: FAIL — `stream_config` module does not exist.

- [ ] **Step 3: Implement the conversion module**

`crates-mobile/fp-backends-mobile/src/stream_config.rs`: define the four format constants as `pub const i32` with the NDK r27 values (`AAUDIO_FORMAT_PCM_I16 = 1`, `AAUDIO_FORMAT_PCM_FLOAT = 2`, `AAUDIO_FORMAT_PCM_I32 = 4`, plus `AAUDIO_FORMAT_PCM_I24_PACKED = 3` in a comment noting it requires API 31 > minSdk 26), then `aaudio_format` as a four-arm `match` over `SampleFormat` (I24 → the I32 code, with a comment that packed 24-bit needs API 31), and `classify_aaudio_error` matching `-899` (`AAUDIO_ERROR_DISCONNECTED`) → `DeviceLost` and everything else → `Other` (the XRUN case is reported from the state callback of the real stream, not from a result code, and gets its arm in the next plan). Add `pub mod stream_config;` to `lib.rs`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cargo test -p fp-backends-mobile --test stream_config`
Expected: 3 passed.

Run: `cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all --check`
Expected: both pass.

- [ ] **Step 5: Commit**

```bash
git add crates-mobile/fp-backends-mobile/
git commit -m "feat(backends-mobile): aaudio stream config and error classification"
```

---

### Task 3: `AaudioBackend` skeleton — enumeration and availability

**Files:**
- Create: `crates-mobile/fp-backends-mobile/src/aaudio_backend.rs`
- Create: `crates-mobile/fp-backends-mobile/tests/aaudio_skeleton.rs`
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (module + re-export)
- Modify: `crates-mobile/fp-backends-mobile/Cargo.toml` (android-only deps + own `[lints]` table replacing `workspace = true`)

**Interfaces:**
- Consumes: `fp_backends::{AudioBackend, Availability, BackendId, DeviceId, DeviceInfo, BackendError}`, `stream_config` from Task 2.
- Produces: `pub struct AaudioBackend` with `pub fn new() -> Self`, implementing `AudioBackend` with `id() = BackendId("aaudio")`. On non-Android hosts every method reports `Availability::Unavailable("AAudio is Android-only")` and `enumerate_devices` returns the same error; the type still compiles everywhere so host tests exercise the host branches. Device enumeration on Android returns exactly one `DeviceInfo` (the default output device, id `"default"`), because AAudio has no device enumeration API — a doc comment states this and points at `AAudioStreamBuilder_setDeviceId` for USB case, deferred.

- [ ] **Step 1: Write the failing tests**

`crates-mobile/fp-backends-mobile/tests/aaudio_skeleton.rs`:

```rust
use fp_backends::AudioBackend;
use fp_backends_mobile::AaudioBackend;

#[test]
fn the_backend_id_is_aaudio() {
    assert_eq!(AaudioBackend::new().id().0, "aaudio");
}

#[cfg(not(target_os = "android"))]
#[test]
fn on_a_host_the_backend_reports_itself_unavailable() {
    let b = AaudioBackend::new();
    assert!(matches!(
        b.availability(),
        fp_backends::Availability::Unavailable(_)
    ));
}

#[cfg(not(target_os = "android"))]
#[test]
fn on_a_host_enumeration_is_the_same_unavailable() {
    let b = AaudioBackend::new();
    assert!(b.enumerate_devices().is_err());
    assert!(b.default_device().is_none());
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cargo test -p fp-backends-mobile --test aaudio_skeleton`
Expected: FAIL — `AaudioBackend` does not exist.

- [ ] **Step 3: Implement the skeleton**

Add to `Cargo.toml`:

```toml
[target.'cfg(target_os = "android")'.dependencies]
aaudio-sys = "0.1"
```

`src/aaudio_backend.rs`: `pub struct AaudioBackend;` with `pub fn new() -> Self { Self }`. The `AudioBackend` impl is split by `cfg`: the non-Android arms return `Unavailable`/`None`/`Err(BackendError::Unavailable("AAudio is Android-only"))` (three small `#[cfg(not(target_os = "android"))]` methods), and the Android arms call `unsafe` AAudio functions wrapped in small safe helpers, each with a `// SAFETY:` comment. A `forbid` lint cannot be relaxed locally, so this step also replaces the crate's `[lints] workspace = true` (set in Task 2) with its own table — the same denies as the workspace but `unsafe_code = "deny"`, plus `clippy::indexing_slicing = "deny"` from the Global Constraints:

```toml
[lints.rust]
unsafe_code = "deny"

[lints.clippy]
unwrap_used = "deny"
expect_used = "deny"
panic = "deny"
indexing_slicing = "deny"
```

With `deny` instead of `forbid`, `src/aaudio_backend.rs` can carry `#![cfg_attr(target_os = "android", allow(unsafe_code))]` at the top of the file; the module-level exception is documented in the file header as the FFI boundary the spec allows. Every other crate in the workspace keeps `forbid` via inheritance. `open_output` on Android in this task returns `Err(BackendError::Unsupported("not implemented yet"))`; the real stream arrives in the next plan. On Android, `availability()` builds a throwaway `AAudioStreamBuilder` and opens the default stream just long enough to check `AAudioStream_getState` reaches `AAUDIO_STREAM_STATE_OPEN`; `enumerate_devices` returns the single default device with `channels: 2`, `sample_rates: vec![(44_100, 192_000)]`, `exclusive_capable: true`, `rate_switching: false`, `native_dsd: false`. Re-export from `lib.rs` unconditionally: `pub use aaudio_backend::AaudioBackend;` — the single `pub struct AaudioBackend;` compiles on every platform, with host and Android arms split across two `#[cfg]`-gated `impl AudioBackend for AaudioBackend` blocks, so the host tests exercise the host arms of the one type.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cargo test -p fp-backends-mobile`
Expected: all tests pass on the host.

Run: `cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --all --check`
Expected: both pass.

- [ ] **Step 5: Commit**

```bash
git add crates-mobile/fp-backends-mobile/
git commit -m "feat(backends-mobile): AAudio backend skeleton with availability checks"
```

---

### Task 4: UniFFI surface — the `FausteEngine` object and generated Kotlin bindings

**Files:**
- Create: `crates-mobile/fp-backends-mobile/src/ffi.rs`
- Create: `crates-mobile/uniffi-bindgen/Cargo.toml` and `crates-mobile/uniffi-bindgen/src/main.rs` (tiny CLI shim — Cargo cannot run a dependency's binary directly)
- Modify: `crates-mobile/fp-backends-mobile/Cargo.toml` (uniffi feature, `crate-type`)
- Modify: `crates-mobile/fp-backends-mobile/src/lib.rs` (feature-gated scaffolding + `mod ffi`)
- Test: `crates-mobile/fp-backends-mobile/tests/ffi.rs`

**Interfaces:**
- Consumes: nothing from the desktop crates yet — this task defines the FFI surface only; the engine wiring is Phase 2.
- Produces: a `FausteEngine` UniFFI object with `#[uniffi::constructor] new(EngineConfig) -> Arc<Self>`, methods `play()`, `pause()`, `stop()`, `seek(position: f64)`, `set_volume(volume: f32)`, `get_state() -> EngineState`, `get_available_backends() -> Vec<BackendInfo>`, `set_backend(backend_id: String)`, and the records `EngineConfig { sample_rate: u32, buffer_frames: u32, data_dir: String, cache_dir: String }`, `EngineState { status: String, position: f64, duration: f64, current_track: Option<Track>, volume: f32, backend: String }`, `Track { path, title, artist: String, duration: f64, sample_rate: u32, channels: u16 }`, `BackendInfo { id, name: String, available: bool }`. In this task the object is an inert stub: the status stays `"stopped"`, the mutation methods are documented no-ops, and `get_state` returns zero-position state; the audio wiring is Phase 2. Also produces a checked-in `crates-mobile/fp-backends-mobile/bindings/kotlin/fauste_player.kt` in package `org.fauste.player.rust`.
- **Note on UniFFI mode:** the spec's `.udl` mention was illustrative. UniFFI 0.28's proc-macro mode derives the interface from Rust annotations (`uniffi::setup_scaffolding!`, `#[derive(uniffi::Record)]`, `#[derive(uniffi::Object)]`, `#[uniffi::export]`) — there is no `.udl` file; the Kotlin bindings are generated from the compiled library metadata (`--library` mode).

- [ ] **Step 1: Write the failing test**

`crates-mobile/fp-backends-mobile/tests/ffi.rs` (host-side; UniFFI scaffolding compiles as a normal Rust module):

```rust
#[cfg(feature = "uniffi")]
mod tests {
    use fp_backends_mobile::ffi::{BackendInfo, EngineConfig, FausteEngine};

    #[test]
    fn a_new_engine_reports_stopped_at_zero() {
        let engine = FausteEngine::new(EngineConfig {
            sample_rate: 48_000,
            buffer_frames: 512,
            data_dir: std::env::temp_dir().display().to_string(),
            cache_dir: std::env::temp_dir().display().to_string(),
        });
        let state = engine.get_state();
        assert_eq!(state.status, "stopped");
        assert_eq!(state.position, 0.0);
    }

    #[test]
    fn every_backend_info_lists_the_null_fallback() {
        let engine = FausteEngine::new(EngineConfig {
            sample_rate: 48_000,
            buffer_frames: 512,
            data_dir: "/tmp".into(),
            cache_dir: "/tmp".into(),
        });
        let ids: Vec<String> = engine
            .get_available_backends()
            .into_iter()
            .map(|b: BackendInfo| b.id)
            .collect();
        assert!(ids.iter().any(|id| id == "null"), "the null fallback must always be listed");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cargo test -p fp-backends-mobile --features uniffi --test ffi`
Expected: FAIL — feature `uniffi` does not exist yet.

- [ ] **Step 3: Implement the proc-macro surface and the CLI shim**

`crates-mobile/fp-backends-mobile/Cargo.toml` gains:

```toml
[lib]
crate-type = ["lib", "cdylib"]

[features]
uniffi = ["dep:uniffi"]

[dependencies]
uniffi = { version = "0.28", optional = true }
```

(`lib` keeps host builds and `cargo test` working; `cdylib` is what the Android app packages and what `--library`-mode generation reads.)

The CLI shim, `crates-mobile/uniffi-bindgen/Cargo.toml`:

```toml
[package]
name = "uniffi-bindgen"
description = "uniffi-bindgen CLI shim for this workspace"
version.workspace = true
edition.workspace = true
rust-version.workspace = true
publish.workspace = true

[dependencies]
uniffi = { version = "0.28", features = ["cli"] }

[lints]
workspace = true
```

`crates-mobile/uniffi-bindgen/src/main.rs`:

```rust
//! `uniffi-bindgen` CLI shim: exposes the generator as `cargo run -p uniffi-bindgen`.

fn main() {
    uniffi::uniffi_bindgen_main();
}
```

`src/lib.rs` gains (both items feature-gated):

```rust
#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!("fauste_player");

#[cfg(feature = "uniffi")]
pub mod ffi;
```

`src/ffi.rs` (the whole module sits behind the feature gate from `lib.rs`) — records with `#[derive(uniffi::Record)]`, the engine with `#[derive(uniffi::Object)]`, all methods in one `#[uniffi::export] impl FausteEngine` block:

```rust
//! UniFFI surface for the mobile app. Phase 0 inert stubs — the real
//! engine (and `Result`-returning methods with a `FausteError`) arrive
//! in Phase 2.

use std::sync::Arc;

#[derive(uniffi::Record)]
pub struct EngineConfig {
    pub sample_rate: u32,
    pub buffer_frames: u32,
    pub data_dir: String,
    pub cache_dir: String,
}

#[derive(uniffi::Record)]
pub struct Track {
    pub path: String,
    pub title: String,
    pub artist: String,
    pub duration: f64,
    pub sample_rate: u32,
    pub channels: u16,
}

#[derive(uniffi::Record)]
pub struct EngineState {
    pub status: String,
    pub position: f64,
    pub duration: f64,
    pub current_track: Option<Track>,
    pub volume: f32,
    pub backend: String,
}

#[derive(uniffi::Record)]
pub struct BackendInfo {
    pub id: String,
    pub name: String,
    pub available: bool,
}

#[derive(uniffi::Object)]
pub struct FausteEngine {
    // Inert Phase-0 stub state; wired to fp-engine in Phase 2.
    status: String,
    volume: f32,
    backend: String,
}

#[uniffi::export]
impl FausteEngine {
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Arc<Self> {
        // The paths in `config` are consumed by fp-store in Phase 2.
        let _ = config;
        Self {
            status: "stopped".into(),
            volume: 1.0,
            backend: "null".into(),
        }
    }

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn play(&self) {}

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn pause(&self) {}

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn stop(&self) {}

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn seek(&self, position: f64) {
        let _ = position;
    }

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn set_volume(&self, volume: f32) {
        let _ = volume;
    }

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn set_backend(&self, backend_id: String) {
        let _ = backend_id;
    }

    pub fn get_state(&self) -> EngineState {
        EngineState {
            status: self.status.clone(),
            position: 0.0,
            duration: 0.0,
            current_track: None,
            volume: self.volume,
            backend: self.backend.clone(),
        }
    }

    pub fn get_available_backends(&self) -> Vec<BackendInfo> {
        vec![
            BackendInfo {
                id: "null".into(),
                name: "Null output".into(),
                available: true,
            },
            BackendInfo {
                id: "aaudio".into(),
                name: "AAudio".into(),
                available: cfg!(target_os = "android"),
            },
        ]
    }
}
```

No manual `catch_unwind` is needed: UniFFI's scaffolding catches panics inside exposed methods and surfaces them to the foreign side instead of aborting the process.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cargo test -p fp-backends-mobile --features uniffi`
Expected: all tests pass, including the two new ones.

Run: `cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings` (with and without `--features uniffi`)
Expected: both pass in both feature states.

- [ ] **Step 5: Generate the Kotlin bindings and check them in**

```bash
cargo build -p fp-backends-mobile --features uniffi
cargo run -p uniffi-bindgen -- generate \
    --library target/debug/libfp_backends_mobile.so \
    --language kotlin \
    --kotlin-package-name org.fauste.player.rust \
    --out-dir crates-mobile/fp-backends-mobile/bindings/kotlin
```

Expected: a single `crates-mobile/fp-backends-mobile/bindings/kotlin/fauste_player.kt` (one file per namespace) declaring `package org.fauste.player.rust` and containing `data class EngineConfig`, `data class Track`, `data class EngineState`, `data class BackendInfo` and `class FausteEngine`. Commit it; Task 5 wires it into the KMP module via `kotlin.srcDir`, and CI (Task 6) regenerates and diffs.

- [ ] **Step 6: Commit**

```bash
git add crates-mobile/
git commit -m "feat(mobile): uniffi engine surface and generated kotlin bindings"
```

---

### Task 5: Kotlin Multiplatform project skeleton (`fp-app-mobile-shared` + `composeApp`)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `fp-app-mobile-shared/build.gradle.kts` (+ `src/commonMain`, `src/androidMain` stubs)
- Create: `composeApp/build.gradle.kts`, `composeApp/src/androidMain/kotlin/org/fauste/player/MainActivity.kt`, `composeApp/src/commonMain/kotlin/org/fauste/player/ui/theme/Theme.kt`, `composeApp/src/androidMain/AndroidManifest.xml`, `composeApp/src/androidMain/res/values/themes.xml`
- Test: `fp-app-mobile-shared/src/commonTest/kotlin/org/fauste/player/SmokeTest.kt`

**Interfaces:**
- Consumes: the Kotlin bindings from Task 4 — `crates-mobile/fp-backends-mobile/bindings/kotlin/fauste_player.kt` in package `org.fauste.player.rust`, wired into the module with `kotlin.srcDir` (Kotlin does not require the directory layout to match the package).
- Produces: `./gradlew` builds the app; a `PlatformContext` expect/actual pair with `val filesDir: String`, `val cacheDir: String` (actual reads `Context.getFilesDir()`/`getCacheDir()`); a `SmokeTest` that constructs the real `FausteEngine` through the generated bindings and asserts the initial state is `"stopped"` (host JVM via the desktop-built cdylib through JNA is out of scope — this test runs as an Android unit test with `testOptions.unitTests.isReturnDefaultValues = false` and the UniFFI JNI library registered; if the JNI library cannot load in a plain unit test, the test is `@Ignore`-tagged `androidTest` and the common test asserts only the data-class round-trip — the implementer picks the variant that actually runs in `./gradlew check` and records the choice in the commit message).

- [ ] **Step 1: Write the failing smoke test**

`fp-app-mobile-shared/src/commonTest/kotlin/org/fauste/player/SmokeTest.kt`:

```kotlin
package org.fauste.player

import org.fauste.player.rust.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun anEngineConfigCarriesThePathsUntouched() {
        val config = EngineConfig(
            sampleRate = 48_000u,
            bufferFrames = 512u,
            dataDir = "/data/data/org.fauste.player/files",
            cacheDir = "/data/data/org.fauste.player/cache",
        )
        assertEquals(48_000u, config.sampleRate)
        assertEquals("/data/data/org.fauste.player/files", config.dataDir)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fp-app-mobile-shared:commonTest` (or `:fp-app-mobile-shared:testDebugUnitTest` on the host)
Expected: FAIL — the Gradle project does not exist yet / the `rust` package is unresolved.

- [ ] **Step 3: Create the Gradle project**

`gradle/libs.versions.toml` pins: kotlin `2.0.0`, compose `1.6.10` (BOM `androidx.compose:compose-bom:2024.06.00`), AGP `8.5.0`, uniffi `0.28.0`, koin `4.0.0`. `settings.gradle.kts` includes `:fp-app-mobile-shared` and `:composeApp`. `fp-app-mobile-shared` is a KMP module with `androidTarget()` that adds the Task 4 bindings to `commonMain` via `kotlin.srcDir("../crates-mobile/fp-backends-mobile/bindings/kotlin")`. `composeApp` is `com.android.application` + `org.jetbrains.kotlin.plugin.compose`, `namespace "org.fauste.player"`, `applicationId "org.fauste.player"`, `minSdk 26`, `compileSdk 34`, and a `MainActivity` whose `setContent { FausteTheme { Text("Fauste Player") } }` uses a `Theme.kt` that wraps `MaterialTheme` with the Nocturne `Background = Color(0xFF0D0D0D)`/`Primary = Color(0xFF00BFA6)` constants from the spec. A Gradle task `buildRust` runs `cargo ndk -t arm64-v8a -t x86_64 build --release -p fp-backends-mobile --features uniffi` and copies `target/*/release/libfp_backends_mobile.so` into `composeApp/src/main/jniLibs/<abi>/`; `preBuild.dependsOn buildRust`.

- [ ] **Step 4: Run the test and the build to verify they pass**

Run: `./gradlew :fp-app-mobile-shared:test`
Expected: `SmokeTest` passes.

Run: `./gradlew :composeApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`, and `composeApp/build/outputs/apk/debug/` contains an APK whose `jniLibs` holds a `.so` for every target in the Cargo task (`unzip -l … | grep libfp_backends_mobile` lists them).

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ fp-app-mobile-shared/ composeApp/
git commit -m "build(mobile): kmp shared module and compose app skeleton with cargo ndk"
```

---

### Task 6: CI workflows — Rust and Android lanes

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.github/workflows/android.yml` (or a job in `ci.yml`; one file with two jobs is fine)
- Modify: none.

**Interfaces:**
- Consumes: the commands from Tasks 1–5.
- Produces: PR CI that runs `cargo fmt --all --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace` (host), `cargo test --workspace --features uniffi`, `cargo deny check`, `./gradlew :fp-app-mobile-shared:test :composeApp:assembleDebug`, and a bindings-drift check (`cargo build -p fp-backends-mobile --features uniffi`, then `cargo run -p uniffi-bindgen -- generate --library target/debug/libfp_backends_mobile.so --language kotlin --kotlin-package-name org.fauste.player.rust --out-dir <tmp>`, diffed against `crates-mobile/fp-backends-mobile/bindings/kotlin`).

- [ ] **Step 1: Write the workflow**

`ci.yml`: on `pull_request` and `push` to `main`. Job `rust` (ubuntu-latest): checkout, `dtolnay/rust-toolchain@stable`, the five cargo commands above with `CARGO_TERM_COLOR: always`; cache `~/.cargo` with `Swatinem/rust-cache@v2`. Job `android` (ubuntu-latest): checkout, `actions/setup-java@v4` (zulu 17), Android SDK with NDK 27.0 + CMake via the `android-actions/setup-android@v3` step or Gradle's own SDK manager, then `./gradlew …buildRust :fp-app-mobile-shared:test :composeApp:assembleDebug`, then the bindings-drift step, then `gradle/actions/wrapper-validation@v3`.

- [ ] **Step 2: Verify the workflow locally where possible**

Run every command the workflow runs, on the host, in order; each must exit 0.

- [ ] **Step 3: Commit**

```bash
git add .github/
git commit -m "ci(mobile): rust and android checks on every pull request"
```

- [ ] **Step 4: Open the PR**

Push the branch, `gh pr create` with a Conventional Commit title (`feat(mobile): phase 0 foundation — workspace, aaudio skeleton, kmp app, ci`) and a body linking the spec, then watch `gh pr checks --watch`. The plan is done when CI is green on the PR.

---

## Notes for the implementer

- The desktop repo's `opus-decoder` `[patch.crates-io]` entry applies to the desktop workspace, not this one. If `cargo deny check` flags `opus-decoder` licences or advisories here, mirror the patch by adding the same `[patch.crates-io]` pointing at `../fauste-player/vendor/opus-decoder` — that references the vendor directory without modifying it.
- Android instrumented tests (emulator) are deliberately **not** in Phase 0 CI; they arrive with Phase 1, when there is audio to exercise. Host-lane `cargo test` covers the host branches in the meantime.
- If `aaudio-sys 0.1` does not compile against NDK r27 headers, check the crate's documented NDK floor first and prefer bumping the crate over vendoring bindings.
