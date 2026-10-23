# Testing

The pyramid: pure logic runs on the host wherever it exists, the real
engine runs on the host JVM wherever it can, and the emulator
escalates only what genuinely needs Android. A fact every lane here
shares: the FFI surface only exists under the `uniffi` feature, and
the engine only opens AAudio on Android — everything else falls back
to the desktop Null backend and still behaves.

| Lane | Command | What it proves |
|---|---|---|
| Rust host tests | `cargo test --workspace` and `cargo test --workspace --features uniffi` | The pure modules (`stream_config`, `stream_params`, `callback`), the core (`player_core`), the FFI (`ffi`, `ffi_state`) — both feature states; the plain state has no FFI module at all |
| The android-target check | `cargo check -p fp-backends-mobile --target aarch64-linux-android` | The `cfg(android)` arms compile (the backend's `open_output`, the callbacks, the conformance and smoke tests) |
| Host-JNA Kotlin tests | `./gradlew :fp-app-mobile-shared:test` | `commonTest` constructs the **real** `FausteEngine` through the committed bindings and drives play/pause/queue/volume end-to-end on the host JVM (Null fallback) |
| Robolectric | `./gradlew :composeApp:testDebugUnitTest` | The Compose UI tests and the service suites run as Robolectric-based Android unit tests on the host JVM |
| On-device Rust suites | `./scripts/emulator-test.sh` | Conformance, engine smoke and decode smoke on a real API 34 emulator |
| The app smoke | a session tool, kept out of the tree | The assembled app: install, launch, drive, notification, focus, crash — against the minified release artifact |

## The host-JNA Kotlin tests

The shared module's `commonTest` runs as Android **unit** tests on the
host JVM with the real cdylib loadable: the Gradle build sets
`jna.library.path` to the Rust workspace's `target/debug`, where the
`buildHostCdylib` task puts `libfp_backends_mobile.so`. The command
that task runs is `cargo build -p fp-backends-mobile --features uniffi`
— the same feature-flagged build CI's drift check runs, and the only
one that exports the FFI surface: the UniFFI scaffolding is cfg-gated
on the feature, so a plain `cargo build -p` would leave the bindings
nothing to call. A second property, `jna.boot.library.path`, points at
the `extractJnaHostDispatch` task's directory holding JNA's own host
`libjnidispatch.so` — the `@aar` artifact the app depends on ships
Android natives under `jni/` only, so on the host JVM JNA cannot
extract its dispatch library from it; the task pulls the plain jar
through a detached configuration (never a classpath) and unzips the
one host native. Neither property leaks into the app: both are set on
the test tasks only.

## Robolectric

CMP's common ui-test API cannot execute on an android local target
and instrumented tests need an emulator, so the Compose component and
screen tests run under Robolectric (4.17) with the androidx
`createComposeRule()` — the very components the app ships, on the
plain Gradle battery. `composeApp/src/androidUnitTest/resources/robolectric.properties`
pins the module default:

```properties
sdk=36
application=android.app.Application
```

The bare `Application` — **not** the manifest's `FausteApplication` —
is the safe default because of **the Koin double-start trap**:
Robolectric creates the Application once per test method while Koin's
static `GlobalContext` persists across methods in the reused sandbox
class loader, so defaulting to the real application (which calls
`startKoin` in `onCreate`) would make the second method of any
`@Config`-less suite crash with Koin's double-start exception. Suites
that want a container own it explicitly — `startKoin`/`stopKoin` per
test — with a scripted graph (`ScriptedEngine`, no cdylib needed).

## The emulator suites

`scripts/emulator-test.sh` is the single provisioning-and-run script
both local and CI use (the `emulator.yml` lane invokes it verbatim, so
the flows cannot drift): install the API 34 `google_apis;x86_64`
system image and the emulator package if absent, create the AVD
`fauste-test` if absent (with the AVD home pinned for both the Java
and the native tools), boot **headless with audio on** (AAudio needs
the virtual sound device; host speakers do not), wait for boot, build
the test harness with `cargo ndk -t x86_64 -P 26 test --no-run`, push
the conformance/engine-smoke/decode-smoke binaries and run them via
`adb shell`, trap-cleaning the emulator on every exit path. KVM is
required — the script hard-fails without a usable `/dev/kvm`. On this
machine, KVM access occasionally needs the one-shot ACL chore
(`sudo -n setfacl -m u:msanchez:rw /dev/kvm`; it does not survive a
reboot, because logind reclaims the node).

## The app smoke

The only lane that drives the **assembled app**, and it is a session
tool kept out of the tree: install the APK, seed the engine's saved
state over `adb root`, launch, then drive playback through the
engine's own loopback HTTP remote (`adb forward` + curl) and check the
live system — the notification and foreground service mid-play, a real
`adb emu gsm call` interrupt pausing playback and the ended call
resuming it, a real notification tap, the stop dance, and
`am crash` → process death → a crash report in `files/crash/` → a
clean relaunch. The release lane ran the full 19-PASS assertion set
against the **R8-minified, test-keystore-signed release APK**
([Release process](release-process.md), which points at the recorded
run). The documented re-run trigger: **any change to the service
startup sequence or the FFI surface**.

## The honest blind spots

Two defects shipped past every host lane above and were caught only by
the on-device smoke — the case for the smoke's existence:

- **The INTERNET permission.** The manifest declared none, and Android
  grants AF_INET sockets — loopback included — only to apps holding the
  permission: the engine's loopback remote silently failed to bind
  on-device, and the app's own `RemoteApi` client could never reach
  it. Every host lane stayed green because the JVM enforces nothing.
- **The boot race.** The service's stop-teardown read the adapter's
  idle *seed* (status Stopped) as engine truth during the
  pre-publish window — `triggerNotificationUpdate` fires at
  session-add while the collector's first publish is two dispatches
  away — and killed the freshly-built service ~50 ms after creation.
  Invisible in Robolectric: the test thread *is* the main looper and
  the pump always publishes first. (Unmasked by the previous fix; a
  third, the background-thread `invalidateState` crash, was the
  review's find on a real device.)

## Conventions

- **TDD** everywhere: watch every new test fail for the expected
  reason first.
- Any `ffi.rs` change regenerates the bindings and keeps the CI drift
  check symmetric ([FFI boundary](ffi.md)).
- Rust changes run fmt, clippy in **both feature states**, tests in
  both states, plus the android-target check; on-device surface changes
  re-run the emulator suites — and the app smoke when the service
  startup or the FFI surface moved.
- No test sleeps to wait for audio: engine effects are observed through
  bounded poll loops (the real-engine Kotlin suites use virtual time
  where the engine is scripted).
