# AGENTS.md

The canonical guide for AI agents working on this repository is
**[`CLAUDE.md`](CLAUDE.md)**. Read it before changing anything; this file only
repeats the essentials for agents that do not follow links.

- English for all code, comments, docs, specs, plans and commits; answer the
  maintainer in their language. UI strings go through Fluent; en-US is the
  source and every string goes into every locale file.
- Never mention other playout or radio-automation products anywhere.
- Use established names; do not invent terms.
- `../fauste-player` (the desktop repo) is **read-only**: path dependencies
  read it, nothing ever writes it.
- No `unsafe` outside the one documented FFI module
  (`aaudio_backend.rs`, Android arms, `// SAFETY:` comments). No
  `unwrap`, `expect` or `panic` outside tests. Kotlin: no `!!`, no empty
  catch, `CancellationException` rethrown first, no engine call on the
  main thread.
- The real-time audio path never allocates, locks, logs or panics.
- Any change to `ffi.rs` regenerates the committed Kotlin bindings in the
  same commit (ktlint 1.7.1 flow; doc comments bump UniFFI's checksums).
- Test first (TDD). Before committing, run the battery for the side you
  touched: Rust — fmt, clippy and tests in **both feature states**, the
  aarch64 target check, `cargo deny`; Kotlin —
  `./gradlew :fp-app-mobile-shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:assembleDebug`;
  on-device surface changes — `./scripts/emulator-test.sh`, and the app
  smoke when the service startup or the FFI surface changed.
- Conventional Commits (`type(scope)`: scopes `mobile`, `backends-mobile`,
  `ci`, `docs`). `CHANGELOG.md` is maintained by release-please
  (release pull requests derived from the commits).
- No secrets or keystores committed; signing stays property-driven and
  green unsigned.
- Keep `docs/user` and `docs/technical` in sync with behaviour; the
  technical set covers the mobile layer and links to the desktop repo's
  chapters for the shared crates.

If this file and `CLAUDE.md` ever disagree, `CLAUDE.md` wins; fix this file.
