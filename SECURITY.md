# Security policy

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub's
[*Report a vulnerability*](https://github.com/sanchezfauste/fauste-player-mobile/security/advisories/new)
form, not in a public issue. Include the version, the device or
emulator image, and the steps or file that trigger the problem. We aim
to acknowledge reports within a week.

Only the latest release is supported with fixes.

## Threat model

Fauste Player Mobile is an offline-first local audio player:

- It connects to **no network services** and sends no telemetry. The
  only socket the app ever opens is the engine's own **loopback HTTP
  remote**, pinned at boot to `127.0.0.1` with an ephemeral port; the
  manifest's `networkSecurityConfig` permits cleartext to loopback
  only, and the `INTERNET` permission exists solely because Android
  gates even loopback sockets behind it. A known, disclosed
  limitation: loopback is **not** per-app isolated on Android, so
  another app on the device can reach the API — the exposure is
  control of playback only (the API operates the player and reads its
  state; file paths are never exposed), and a token flow is a recorded
  follow-up.
- It reads audio files, tags and playlists chosen by the user. These
  are treated as untrusted input, with the desktop project's own
  handling (see the desktop repo's `SECURITY.md`): covers are
  size-limited, tag parsing has allocation limits, state files larger
  than the configured limits are refused, and a malformed file
  degrades to "not available", never to a crash.
- Paths from playlists and imports are only opened for reading and
  copied into the app's private storage. Nothing is executed, and
  nothing is written outside the app's own files, cache and
  external-cache dirs.
- The Rust workspace forbids `unsafe_code`; the **one** sanctioned FFI
  boundary is the AAudio backend's Android module
  (`crates-mobile/fp-backends-mobile/src/aaudio_backend.rs`), where
  every unsafe block carries a `// SAFETY:` comment, the real-time
  callbacks allocate nothing and cannot panic by lint, and the C-API
  surface is limited to AAudio's documented stream functions.
- Panics crossing the UniFFI boundary are caught by the framework's
  `catch_unwind` wrapping and surfaced as exceptions, not aborts; the
  panic hook writes a report into the app's private storage first.
- Crash reports (Rust panics and Kotlin uncaught exceptions) stay on
  the device, in the app's private storage, capped; they are never
  uploaded anywhere.
- No signing keys or secrets live in the repository: release signing
  is property-driven and documented in
  [docs/release/signing.md](docs/release/signing.md), `.gitignore`
  excludes keystore files, and CI builds unsigned on purpose.
- Rust dependencies are checked by `cargo deny` (advisories,
  licences, sources) in CI.
