# CLAUDE.md — guide for AI agents working on Fauste Player Mobile

This is the **canonical** guide for agents (Claude Code and any other).
[`AGENTS.md`](AGENTS.md) points here, and the two must never diverge: edit
this file and keep `AGENTS.md` a pointer.

Fauste Player Mobile is the Android app around the Rust audio engine of
the desktop project, consumed by path dependencies from the sibling
repository at `../fauste-player`. Read [`README.md`](README.md) for the
overview and [`docs/technical/README.md`](docs/technical/README.md) for
how it works.

## Non-negotiable rules

1. **Language.**
   - All code, identifiers, comments, docs, specs, plans and commit messages
     are in **English**.
   - Answer the maintainer in the language they write in (usually Spanish).
   - UI strings are Fluent messages;
     `composeApp/src/commonMain/composeResources/files/locales/en-US/main.ftl`
     is the source. Every new or changed string goes into **every** locale
     file (en-US and es-ES today), and the key-completeness test fails the
     suite if either bundle drifts.
2. **Confidentiality.** Never mention other playout or radio-automation
   products (commercial or open source) anywhere: code, comments, docs,
   specs, plans, commit messages, issues, PRs. Describe behaviour in its own
   terms.
3. **Conventions over invention.** Use established, widely accepted names
   (audio: source, bus, mixer, cue, segue, xrun; Android: service, media
   session, audio focus; Conventional Commits, SemVer, Keep a Changelog).
   Do not invent terms for concepts that already have names.
4. **The desktop repo is read-only.** `../fauste-player` is consumed
   through the workspace's path dependencies and never modified — no code
   changes, no doc fixes, not even formatting. The mobile repo carries its
   own copies or citations instead. CI checks the desktop repo out itself;
   locally the sibling checkout must sit at `../fauste-player`.
5. **Real-time safety.** Code that runs on an audio callback — the AAudio
   data and error callbacks, the desktop mixer they drive — never
   allocates, frees, locks, logs, does I/O or panics. The renderer travels
   to the AAudio callback through a one-slot ring, pushed only after the
   stream opens; XRUNs are reported by a non-RT monitor thread.
6. **Safety lints.**
   - `unsafe_code` is forbidden workspace-wide. `fp-backends-mobile` is the
     one sanctioned exception mechanism: it sets its own `deny`-level lints
     and only `src/aaudio_backend.rs` allows unsafe code, on Android only,
     every block carrying a `// SAFETY:` comment. Nowhere else.
   - `unwrap`, `expect` and `panic` are denied outside tests (Rust).
   - Kotlin: no `!!`, no empty catch blocks, `CancellationException` is
     always rethrown first (it **is** an `IllegalStateException` on the
     JVM), and **no engine call ever runs on the main thread** — the lazy
     engine single's first resolution included (the Koin graph's rule).
7. **The FFI contract.** Every exposed method maps to the desktop
   `Command` the desktop UI itself sends, with the citation in the doc
   comment; state reads mirror the desktop remote Bridge. **Any change to
   `ffi.rs` regenerates the committed Kotlin bindings** (ktlint 1.7.1
   flow) in the same commit — doc comments included: UniFFI hashes them
   into the load-time checksums, so stale bindings fail at first call.
8. **State delivery is polling.** `PlayerController` polls `get_state()`
   at 100 ms into a `StateFlow`; there is no callback surface. Commands
   return `Busy` when the conductor's queue is full — the command was
   dropped and retrying is safe; never spin or block on it.
9. **Never crash on bad data.** Untrusted input (audio, tags, playlists,
   state files) degrades to "not available" with a log line, as on the
   desktop.
10. **Nothing goes on air by itself** after a start, restart or crash: the
    restore is Paused-at-position, never Playing.
11. **No secrets or keystores are committed.** Release signing reads four
    optional Gradle properties and stays green unsigned; see
    [docs/release/signing.md](docs/release/signing.md). `.gitignore`
    excludes `*.jks`/`*.keystore`.
12. **Test first (TDD).** Watch every new test fail for the expected reason
    before writing the code; a bug fix starts with a reproducing test.

## Commands

```sh
# The Rust gate battery (run all of it before every commit that touched Rust)
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo clippy --workspace --all-targets --features uniffi -- -D warnings   # both feature states
cargo test --workspace
cargo test --workspace --features uniffi
cargo check -p fp-backends-mobile --target aarch64-linux-android           # the Android arms compile
cargo deny check                                                           # after dependency changes

# The Gradle battery (before every commit that touched Kotlin)
./gradlew :fp-app-mobile-shared:testDebugUnitTest :composeApp:testDebugUnitTest :composeApp:assembleDebug
./gradlew buildRust                       # just the cdylib for both ABIs (cargo-ndk, API 26 sysroot)

# Bindings regeneration — required on ANY ffi.rs change; ktlint 1.7.1 must be on PATH
cargo build -p fp-backends-mobile --features uniffi
cargo run -p uniffi-bindgen -- generate --library target/debug/libfp_backends_mobile.so \
    --language kotlin --out-dir <tmp>     # then copy over crates-mobile/fp-backends-mobile/bindings/kotlin

# On-device Rust suites (conformance, engine smoke, decode smoke; API 34, KVM required)
./scripts/emulator-test.sh                # re-run when the on-device surface changed
# The app smoke (a session tool, out of the tree): re-run when the service
# startup sequence or the FFI surface changed, and before any release.

# Release artifacts (unsigned without the four RELEASE_* properties)
./gradlew :composeApp:assembleRelease :composeApp:bundleRelease
```

Only commit when the whole battery for the touched side passes.

## Workflow and skills

The project uses the **superpowers** skills in Claude Code. Invoke a skill
whenever it applies:

| When | Skill |
|---|---|
| New feature, phase or behaviour change | `superpowers:brainstorming` → update the spec in `docs/superpowers/specs/` |
| Turning a spec into work | `superpowers:writing-plans` → `docs/superpowers/plans/YYYY-MM-DD-phaseN-<topic>.md` (English) |
| Implementing a plan | `superpowers:executing-plans` (inline, the default here) or `superpowers:subagent-driven-development` |
| Any code | `superpowers:test-driven-development`: watch every new test fail first |
| Any bug or unexpected output | `superpowers:systematic-debugging` |
| Before saying "done" | `superpowers:verification-before-completion` |
| End of a plan | `superpowers:requesting-code-review` with a fresh reviewer; fix Critical and Important findings test-first, defer the Minor ones in the plan's ledger |
| Merging | `superpowers:finishing-a-development-branch` |

Progress ledgers live in `.superpowers/sdd/<plan>/progress.md`
(git-ignored). Record every deviation from a plan as
`Ruling: <decision> — <why> — <cost if wrong>`.

### Pull requests

Every change reaches `main` through a pull request on GitHub; nothing is
merged locally.

1. Branch from an up-to-date `main`: `feat/…`, `fix/…`, `docs/…`,
   `ci/…`, `chore/…`.
2. Commit with the gates for the touched side green (see Commands).
3. Push the branch and open a PR whose title is a Conventional Commit
   summary.
4. Wait for CI (three lanes: `ci.yml` Rust, `android.yml` Gradle +
   bindings drift check, `emulator.yml` the on-device suites). A red check
   is fixed on the branch, never bypassed.
5. When the plan's review is done and CI is green, merge, then update the
   local `main`.

### Documentation is part of every change

A change is not done until everything that describes it says the same:

- `README.md` (features table — shipped vs roadmap, platform matrix,
  build instructions);
- the user guide in `docs/user/` and the technical docs in
  `docs/technical/` (this set covers the mobile layer only; shared-crate
  internals stay in the desktop repo's chapters);
- the spec in `docs/superpowers/specs/` when behaviour changes, and the
  phase plan (including its deferrals section — a deferral is recorded,
  never silently dropped);
- this file (and `AGENTS.md` if the pointer changes) when the workflow,
  commands or layout change;
- UI strings in every locale file.

`CHANGELOG.md` is maintained by release-please: every push to `main`
keeps a `chore: release X.Y.Z` pull request up to date, and merging it
writes the changelog section, bumps `version.txt` (the app version's
single source), the release-please manifest and the Rust workspace
version, tags `vX.Y.Z` and attaches the tag's unsigned AAB to the
GitHub release — see `docs/technical/release-process.md`.

## Commits

- Conventional Commits: `type(scope): summary`. Types: `feat`, `fix`,
  `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`,
  `revert`. Scopes in use: `mobile` (the app/shared layer),
  `backends-mobile` (the Rust crate), `ci`, `docs`.
- The body explains *why*, and cites the desktop authority for any
  semantics carried over (file and line). Agent commits end with the
  co-author trailer the harness provides.

## Where things are

| Path | Content |
|---|---|
| `crates-mobile/fp-backends-mobile/src` | `aaudio_backend.rs` (the FFI boundary), `callback.rs` (the RT callback core), `stream_config.rs`/`stream_params.rs` (pure mappings), `player_core.rs` (the wiring + the session saver), `ffi.rs` (the UniFFI surface), `crash.rs` (the panic hook) |
| `crates-mobile/fp-backends-mobile/bindings/kotlin` | The generated bindings (package `org.fauste.player.rust`) — committed, never edited by hand |
| `crates-mobile/uniffi-bindgen` | The generator CLI shim |
| `fp-app-mobile-shared/src` | The Kotlin state layer: `platform/` expect/actuals, `player/` (controller, queue, state), `repository/`, `remote/`, `di/` |
| `composeApp/src` | `commonMain` UI (screens, components, viewmodel, theme, strings, navigation), `androidMain` (MainActivity, Application, the media3 service stack, crash handling) |
| `docs/superpowers/specs`, `docs/superpowers/plans` | The design spec and the six phase plans with their rulings and deferrals |
| `docs/technical/` | This technical set (the mobile layer; links to the desktop repo's chapters for the shared crates) |
| `docs/user/`, `docs/release/` | The user guide; signing, measurements |
| `scripts/emulator-test.sh` | The on-device Rust suites: provisioning + build + push + run |
| `.github/workflows/` | The three CI lanes |
| `../fauste-player` | The desktop repo — **read-only**; the workspace's path dependencies |

## Design source

The UI follows the **Nocturne design block in this repo's spec** (§
"Nocturne Design System") — not the desktop's current palette, which has
drifted from it. The spec's hex/oklch values are copied verbatim into
`composeApp/src/commonMain/kotlin/org/fauste/player/ui/theme/Color.kt`,
whose header cites the ruling: renaming or re-hexing an entry is a spec
change, not a cleanup.

## Testing notes

- The real-engine Kotlin tests (`:fp-app-mobile-shared:testDebugUnitTest`)
  load the **host cdylib** through JNA: the `buildHostCdylib` task builds
  it (`cargo build -p fp-backends-mobile --features uniffi` — the plain
  build exports no FFI surface) and the test tasks get
  `jna.library.path`/`jna.boot.library.path`. No emulator needed.
- Robolectric lanes: the module default Application is the bare
  `android.app.Application` (`robolectric.properties`) because of Koin's
  double-start trap — a suite that wants a container owns it with
  `startKoin`/`stopKoin` per test.
- The emulator lanes need KVM. If the run refuses, the non-persistent ACL
  chore is `sudo -n setfacl -m u:msanchez:rw /dev/kvm` (logind reclaims
  the node at reboot/session end, so it may need re-arming).
- The app smoke drives the assembled app over the engine's loopback
  remote. It caught what every host lane missed (the missing INTERNET
  permission; the service boot race) — keep it in the loop for any
  service/FFI-surface change.
