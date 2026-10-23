# Fauste Player Mobile — Technical documentation

For developers and maintainers. The binding design is the spec,
[`docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md`](../superpowers/specs/2025-10-11-fauste-player-mobile-design.md),
and the six implementation plans in
[`docs/superpowers/plans`](../superpowers/plans) record how each phase
was built and the rulings taken on the way. These pages describe what is
**implemented** and how, and point out where the implementation defers
part of the spec.

The mobile app reuses the desktop engine crates unchanged, through path
dependencies on the sibling repository `../fauste-player` (read-only).
These pages therefore cover the **mobile-specific layer** — the AAudio
backend, the player core, the UniFFI boundary, the Kotlin state layer,
the Compose UI and the release process — and point to the desktop
repo's own technical documentation
(`../fauste-player/docs/technical/`) for the shared crates' internals:
the conductor's and engine's semantics
([architecture](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/architecture.md)),
the mixer's real-time rules
([threading](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/threading-and-realtime.md)),
the store's files and formats
([persistence](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/persistence.md)),
and the HTTP API itself
([remote API](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/remote-api.md)).
Nothing below duplicates them.

| Document | Content |
|---|---|
| [Architecture](architecture.md) | The layer stack: desktop crates by path → `fp-backends-mobile` → UniFFI → the shared Kotlin module → the app; polling-not-callbacks; Koin; reused vs mobile-only |
| [FFI boundary](ffi.md) | The UniFFI surface's evolution (v1–v5), the port-0 guard, Busy/NotFound, the armed gate, bindings regeneration and the checksum coupling |
| [Threading and real time](threading-and-realtime.md) | The AAudio callback and its handoff, the Kotlin poll loop and its idle gate, the service's main-thread publish |
| [Backends](backends.md) | The AAudio backend: the conformance suite, the XRUN monitor, availability probing, `set_backend`, why DSD is refused, the Null fallback |
| [Testing](testing.md) | The pyramid: host cargo tests, the android-target check, host-JNA Kotlin tests, Robolectric, the emulator suites, the app smoke and its finds |
| [Persistence](persistence.md) | The session save layer: the watched model version, the debounce, the while-playing interval, the final save, the restore truth |
| [Release process](release-process.md) | R8 and the JNA keep set, signing, the AAB, the measurements methodology, the release smoke |
| [Remote control API](remote-api.md) | The loopback remote: pinned enabled/loopback/port-0, the Kotlin client, what it can do, the disclosed limits |
| [User interface](ui.md) | The Compose layer: UDF ViewModels, the throttle, lifecycle-aware collection, the media3 session, audio focus, the Nocturne theme, Fluent i18n |

## Reading order for a new engineer

1. [Architecture](architecture.md) — what exists and where the
   boundaries are.
2. The spec's §1–§5 (the layer diagram and the responsibility table).
3. [Backends](backends.md) — the one piece of the audio path that is
   mobile code.
4. [FFI boundary](ffi.md) and
   [Threading and real time](threading-and-realtime.md) — the rules
   every change to the Rust↔Kotlin seam must keep.
5. [Testing](testing.md) — which lanes run what, and which lane to add
   a test to.
6. [User interface](ui.md), [Persistence](persistence.md),
   [Remote control API](remote-api.md) as the work touches them.
7. [Release process](release-process.md) before shipping anything.

The root [`CLAUDE.md`](../../CLAUDE.md) holds the working rules
(language, lints, gates) that apply to every change.
