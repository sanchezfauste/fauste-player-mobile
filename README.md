# Fauste Player — Mobile

An offline-first mobile audio player: the playback engine is the Rust core
(the `fauste-player` desktop engine family, ported through the
[`fp-backends-mobile`](crates-mobile/fp-backends-mobile) crate with a real
[AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)
backend), driven from Android through
[UniFFI](https://github.com/mozilla/uniffi-rs)/JNA, with the UI built in
[Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
and playback hosted in a [media3](https://developer.android.com/media/media3)
foreground service.

No third-party services, no network playback, no analytics: the only socket
the app ever opens is a loopback HTTP API (the desktop ecosystem's remote
protocol), reachable on the device alone.

## Features

| Feature | Status | Notes |
|---|---|---|
| AAudio playback engine (Rust core, on-device cdylib) | ✅ shipped | arm64-v8a + x86_64, API 26 sysroot |
| Import via the system file picker (SAF), runtime permission gate | ✅ shipped | copies into app storage, appends to the queue |
| Queue with search; **tap = arm as next** + `UP NEXT` badge | ✅ shipped | the armed track starts at the current track's end or the next Play — never on the tap itself |
| Player transport: play/pause, previous, seek, volume | ✅ shipped | seek/volume with instant UI feedback, engine-committed on release |
| Foreground service + media3 media session + notification | ✅ shipped | play/pause/stop actions; tap returns to the existing instance |
| Audio focus: pause on calls, resume after, duck on brief interrupts | ✅ shipped | focus policy host-pinned + live emulator-verified |
| Session persistence (debounced save) | ✅ shipped | the queue survives a force-kill; transport restores Paused |
| Crash reporting (Rust panic hook + Kotlin uncaught handler) | ✅ shipped | mirrors the desktop's report shape; see below for locations |
| i18n — English (en-US) + Spanish (es-ES), Fluent | ✅ shipped | follows the device locale |
| Settings: backend picker (probe-honest), remote status, volume | ✅ shipped | loopback remote only |
| Release build: R8-minified APK/AAB, signing documented | ✅ shipped | see [docs/release/signing.md](docs/release/signing.md) |
| Playlists screen (tabs, drag-reorder) | 🗺️ roadmap | needs its FFI + services surface planned |
| Cartwall screen | 🗺️ roadmap | same |
| Waveform / meter / cues / intro-outro | 🗺️ roadmap | needs waveform + analysis FFI (cover, peaks) |
| Track durations / tags in UI | 🗺️ roadmap | needs the analysis layer on mobile |
| Deep links | 🗺️ roadmap | |
| In-UI remote screen (QR/pairing) | 🗺️ roadmap | the loopback API exists; the UI does not |

The roadmap items are the recorded deferrals from the Phase-4/5 plans —
nothing half-ships; each needs its FFI surface planned as a future phase.

## Platform matrix

| Platform | Status |
|---|---|
| Android 8.0 (API 26) and newer, arm64-v8a / x86_64 | ✅ supported — the release lane; API 34 emulator in the verification battery |
| iOS / other KMP targets | 🗺️ future — the shared module's `commonMain` stays platform-free, but the engine backend is Android audio today |

## Building

Prerequisites: a JDK (CI pins temurin 21; a local JDK 25 works), the Android
SDK (platform 36, build-tools, NDK `27.0.12077973`), Rust exactly as pinned
in [`rust-toolchain.toml`](rust-toolchain.toml) (1.98.1),
[`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk), and the desktop repository
checked out next to this one (`../fauste-player` — the Rust workspace's path
dependencies read it; it is treated read-only).

```bash
# Rust gates (the CI lane, both feature states)
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo clippy --workspace --all-targets --features uniffi -- -D warnings
cargo test --workspace
cargo test --workspace --features uniffi

# Android: the cdylib for both ABIs is built by the buildRust task and
# staged into composeApp's jniLibs before every build (cargo-ndk).
./gradlew :composeApp:assembleDebug          # debug APK
./gradlew :composeApp:testDebugUnitTest      # Robolectric Compose UI tests
./gradlew :composeApp:assembleRelease        # R8-minified APK (unsigned without signing properties)
./gradlew :composeApp:bundleRelease          # AAB for Play Console (sign when ready)

# The shared module's tests run the REAL engine on the host JVM:
# fp-app-mobile-shared's buildHostCdylib task builds the host cdylib
# (cargo build -p fp-backends-mobile --features uniffi) and stages it
# where the JVM test lane loads it from — no emulator needed.
./gradlew :fp-app-mobile-shared:test
```

Signing: the release build is property-driven and stays green unsigned;
[docs/release/signing.md](docs/release/signing.md) has the keystore commands,
the `gradle.properties` pattern, and the CI-secrets shape.

### On-device verification

- `./scripts/emulator-test.sh` — provisions a headless API 34 AVD (needs
  KVM), builds the Rust conformance / engine-smoke / decode-smoke suites for
  x86_64, pushes and runs them on-device. Same script CI runs.
- The **app smoke** (a session tool, kept out of the tree): installs the APK,
  seeds the engine's saved state over `adb root`, launches, then drives
  playback through the engine's own loopback remote and checks the live
  system — notification, foreground service, the focus stack and a real
  `gsm call` interrupt, the notification tap, the stop dance, and an
  `am crash` → report → clean relaunch cycle. Re-run whenever the service
  startup sequence or the FFI surface changes. The Phase-5 release lane ran
  it against the **R8-minified, test-keystore-signed release APK** — the
  same assertions on the artifact that ships.

## CI lanes (`.github/workflows/`)

| Lane | What it proves |
|---|---|
| `ci.yml` (Rust) | fmt; clippy both feature states; host tests both states; `cargo deny` audit |
| `android.yml` | the Gradle battery (buildRust, shared-module tests, assembleDebug, Robolectric UI tests, **assembleRelease** — R8/packaging green without secrets) + the UniFFI bindings drift check (regenerate + diff, ktlint-pinned) |
| `emulator.yml` | `scripts/emulator-test.sh` on a hosted KVM runner — the on-device Rust suites |

## Crash reports

If the app crashes, look here (app-private storage, `run-as`/root to read on
a debuggable build; on release, `adb` them off a bug report):

- `files/crash/` — JVM/Kotlin uncaught exceptions; one file per crash,
  desktop-mirrored header (version, os, thread, message, location + stack);
  kept at the 20 newest.
- `files/logs/` — Rust panic reports from the engine's panic hook (the
  desktop's own machinery, ported); capped per run, never deleted.

## Documentation

- **[User guide](docs/user/mobile.md)** — install, import, the queue's
  arm-as-next semantics, background/lock-screen behaviour, limitations.
- **[Release signing](docs/release/signing.md)** — keystore creation, the
  property names the build reads, CI secrets, and the no-keystore-committed
  rule.
- **[Measurements](docs/release/measurements.md)** — cold start, memory, CPU
  measured on the emulator with stated methodology, and the hardware battery
  methodology for the spec's 10 h target.
- Design docs and per-phase plans live under
  [docs/superpowers](docs/superpowers) (spec, implementation plans, records).
