# Contributing

Thank you for helping. This page is the short version; [`CLAUDE.md`](CLAUDE.md)
has the full rules, which apply to people and agents alike.

## Workflow

1. **Discuss first** for anything beyond a small fix: open an issue.
   Behaviour changes update the
   [design spec](docs/superpowers/specs/2025-10-11-fauste-player-mobile-design.md);
   larger work becomes a phase plan in
   [docs/superpowers/plans](docs/superpowers/plans).
2. **Branch** from `main`: `feat/<topic>`, `fix/<topic>`, `docs/<topic>`.
3. **Test first.** Write the failing test, watch it fail for the right
   reason, then make it pass. A bug fix starts with a test that reproduces
   it.
4. **Check locally — the battery for the side you touched:**
   ```sh
   # Rust
   cargo fmt --all --check
   cargo clippy --workspace --all-targets -- -D warnings
   cargo clippy --workspace --all-targets --features uniffi -- -D warnings
   cargo test --workspace
   cargo test --workspace --features uniffi
   cargo check -p fp-backends-mobile --target aarch64-linux-android
   cargo deny check        # when dependencies change

   # Kotlin
   ./gradlew :fp-app-mobile-shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:assembleDebug

   # On-device surface changes
   ./scripts/emulator-test.sh
   ```
   A change to `ffi.rs` also regenerates the committed Kotlin bindings
   (ktlint 1.7.1 flow) in the same commit; the service/FFI surface moving
   re-runs the app smoke.
5. **Update the docs in the same PR**: the README's feature table, the
   user guide (`docs/user/`), the technical docs (`docs/technical/`), the
   spec when behaviour changes, and `CLAUDE.md` when the workflow changes.
   A deferral is recorded in the plan's deferrals section, never silently
   dropped.
6. **Commit** with [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)
   (`type(scope): summary`; scopes `mobile`, `backends-mobile`, `ci`,
   `docs`).
7. **Open a PR.** Its title is a Conventional Commit too. It is merged
   once the three CI lanes are green (Rust, Gradle + bindings drift,
   emulator). The desktop sibling repo must be checked out at
   `../fauste-player` for every build — the workspace's path dependencies
   read it, and it is never modified.

## Code rules

- English everywhere in code, comments, docs and commits. Use established,
  widely accepted names for concepts; do not invent terms.
- No `unsafe` outside the documented FFI module
  (`crates-mobile/fp-backends-mobile/src/aaudio_backend.rs`, Android arms,
  `// SAFETY:` comments); no `unwrap`, `expect` or `panic` outside tests.
- Kotlin: no `!!`, no empty catch blocks, `CancellationException` always
  rethrown first, and no engine call on the main thread.
- Nothing on the real-time path allocates, frees, locks, logs or panics.
- Never mention other playout or radio-automation products in code, docs
  or commits.
- Every user-visible string goes into the en-US Fluent bundle (the source)
  and every other locale bundle (`es-ES` today).
- No secrets or keystores: release signing reads optional Gradle
  properties and stays green unsigned
  ([docs/release/signing.md](docs/release/signing.md)).

## Releases

Maintainers tag from `main` and run the release verification: the
assembled artifact (R8-minified) is driven end-to-end by the app smoke,
and the measurements are recorded in
[docs/release/measurements.md](docs/release/measurements.md). A merged
phase gets its entries in `CHANGELOG.md`. See
[Release process](docs/technical/release-process.md).
