# Architecture

The mobile app is a thin Android shell around the desktop's Rust audio
engine. Every behavioural crate — `fp-model`, `fp-store`, `fp-decode`,
`fp-engine`, `fp-analysis`, `fp-remote` — is consumed **unchanged**
through a path dependency on the sibling desktop repository; the mobile
repo adds one Rust crate, one Kotlin module and one app module. The
conductor's and engine's semantics are the desktop's own:
see the desktop repo's
[architecture](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/architecture.md).

## Layers

| Layer | Code | Content |
|---|---|---|
| Desktop crates (read-only path deps) | `../fauste-player/crates/*` | The engine, the model, the store, decoding, analysis, the remote server — consumed through `fp-*` workspace dependencies declared once in the root `Cargo.toml` |
| Mobile Rust crate | `crates-mobile/fp-backends-mobile` | The AAudio `AudioBackend`, `PlayerCore` (the wiring the desktop does in `main.rs`, minus UI/MIDI/Services, plus the session save layer), the UniFFI `ffi.rs` surface, the crash panic hook |
| UniFFI bindings | `crates-mobile/fp-backends-mobile/bindings/kotlin` | Generated Kotlin (package `org.fauste.player.rust`), committed, never edited by hand ([FFI boundary](ffi.md)) |
| Shared Kotlin module | `fp-app-mobile-shared` | Platform abstractions (expect/actual), `PlayerController` and `QueueManager` (the reactive state layer), repositories, remote wrappers, the Koin module |
| App module | `composeApp` | Compose Multiplatform UI, ViewModels, navigation, the media3 playback service, i18n, crash handling |

```
composeApp (UI + service)
   │  Koin (appModule + viewModelModule)
fp-app-mobile-shared (state layer: PlayerController, QueueManager, repositories)
   │  UniFFI bindings over JNA (org.fauste.player.rust)
fp-backends-mobile (PlayerCore: ffi.rs over player_core.rs)
   │  workspace path deps
../fauste-player crates (fp-engine, fp-model, fp-store, fp-decode, fp-analysis, fp-remote, fp-backends trait)
   └─ AAudio (Android) or the engine's Null fallback (host)
```

The dependency direction is one-way everywhere: the UI never touches
the engine except through the state layer, the state layer never
touches the engine except through the FFI records, and the FFI never
invents commands — every method maps to the desktop `Command` the
desktop UI itself sends ([FFI boundary](ffi.md)).

## The Cargo workspace

`Cargo.toml` declares the members under `crates-mobile/*` and the
desktop crates as `fp-*` path dependencies into `../fauste-player`, so
a moved or missing desktop checkout fails at parse time. `Cargo.lock`
is committed: builds are reproducible, and a desktop change cannot
alter the mobile build silently between checkouts. The toolchain pin
(`rust-toolchain.toml`, Rust 1.98.1) matches the desktop's.

Workspace lints forbid `unsafe_code` and deny `unwrap`, `expect` and
`panic` outside tests. A `forbid` lint cannot be relaxed locally, so
`fp-backends-mobile` — the one crate that must call AAudio's C API —
carries its **own** `[lints]` table with `unsafe_code = "deny"`, and
only `src/aaudio_backend.rs` allows it, on Android only
(`#![cfg_attr(target_os = "android", allow(unsafe_code))]`), with a
`// SAFETY:` comment on every block. Every other crate inherits the
`forbid` unchanged.

## Polling, not callbacks

The FFI exposes **no callbacks into Kotlin**. State delivery is the
poll loop (Phase 3, Ruling 2): `PlayerController` reads
`get_state()` every 100 ms on a background dispatcher into a
`StateFlow<EngineState>`; the poll is the truth source, and there is
nothing to register, unregister, or leak across the FFI. The ruling is
economic, not permanent — a push surface was always a later refinement
— but everything above the FFI is written against the flow, so the
delivery mechanism stays an implementation detail of the controller.
See [Threading and real time](threading-and-realtime.md) for the loop's
idle gate and failure channel.

## Koin

Dependency injection is **Koin, not Hilt** (Phase 3, Ruling 1; the
spec's `@HiltViewModel` sample was noise). `appModule()` is an
`expect` in commonMain and an `actual` in androidMain; it registers the
platform contracts, the two dispatcher qualifiers (`IoDispatcher`,
`DefaultDispatcher`), the `EngineConfig` derived from the platform
dirs, the **lazy** `FausteEngine` singleton, the state layer, the
remote wrappers and the repositories. Every lifecycle-bearing
definition closes on container shutdown via Koin's `onClose`.

The engine single is lazy because construction is heavy and
non-blocking-exempt: the UniFFI constructor loads the store and spawns
the conductor, the analysis pool and the remote server. The graph's
rule is that **the first resolution happens off the main thread, by
construction** — the ViewModels defer their `Lazy` handles into the
first dispatcher coroutine, the application's service starter resolves
in an application-scope coroutine, and the service resolves in its own
background scope.

## Reused vs mobile-only

| Desktop piece | Mobile treatment |
|---|---|
| `fp-model`, `fp-store`, `fp-decode`, `fp-analysis` | Reused unchanged; the store's `AppPaths` is constructed literally from the platform dirs (no `Store::new_mobile` was ever needed — a plan ruling) |
| `fp-engine` | Reused unchanged; only the backend list differs (`[AaudioBackend]`; the engine appends its own Null fallback) |
| `fp-remote` | Reused unchanged; the config is pinned to enabled/loopback/port-0 at boot ([Remote control API](remote-api.md)) |
| Desktop services thread (autosave) | Ported as the mobile `SessionSaver` thread ([Persistence](persistence.md)) |
| Desktop crash machinery | Ported: the Rust panic hook verbatim, a Kotlin uncaught-exception handler mirroring the report shape |
| egui UI, MIDI (`fp-control`), the media cache, cartwall/playlist screens | Not ported; the recorded feature deferrals are the README's roadmap |
| `fp-backends` cpal backends | Not compiled for Android; the trait is what `fp-backends-mobile` implements ([Backends](backends.md)) |

## The Gradle build

`settings.gradle.kts` includes the two modules. `composeApp`'s
`buildRust` task builds the cdylib for arm64-v8a and x86_64 with
cargo-ndk against the API 26 sysroot and stages the `.so` files under
`src/main/jniLibs` before every build; the shared module's
`buildHostCdylib` builds the **host** cdylib its real-engine tests load
through JNA ([Testing](testing.md)). Version pins and their provenance
live in `gradle/libs.versions.toml`.
