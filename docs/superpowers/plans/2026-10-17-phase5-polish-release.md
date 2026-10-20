# Fauste Player Mobile — Phase 5: Polish & Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship-quality release: audio focus (calls/navigation duck/pause), the FFI reads that close three disclosed gaps (entry-id playing flag, armed badge), battery-safe lifecycle collection, service restart semantics, crash reporting (Rust panic hook + Kotlin handler, mirroring the desktop's crash-report store), i18n (Fluent, en-US + es-ES per spec §4), and the release deliverables (performance/battery measurements per spec §9, user guide, README, release build + AAB signing documentation). The spec's Phase 4 feature deferrals (Playlists/Cartwall/waveform/deep-links/RemoteScreen) stay recorded beyond this phase (their FFI surface is planned as future work — see the Phase-4 plan's deferral section).

**Architecture:** No new layers — every task hardens what exists: the service gains focus handling; the controller flows gain a lifecycle-aware collector; the FFI grows two read-only surfaces; the crash story mirrors the desktop's existing crash-report machinery; release work is measurement + documentation + build wiring.

**Tech Stack:** unchanged (Rust 1.98, UniFFI 0.28, CMP 1.9.3, media3 1.11.1, Koin 4.0.0). New dependency allowed: a Fluent i18n library for Kotlin (judge: `fluent-kotlin` or equivalent on Maven Central; document the choice — spec §4 names Fluent).

**Spec:** §9 Phase 5 (performance/battery/crash/docs/AAB/README), §4 (i18n), plus the Phase-4 plan's "Deferred to Phase 5" list (the ordered handoff).

## Rulings (plan-level)

1. **Audio focus lives in the service** (`AudioFocusRequest` via media3's `AudioManager` interop or direct `AudioFocusRequest` on API 26+): focus gain → resume-able pause bookkeeping; AUDIOFOCUS_LOSS → pause; LOSS_TRANSIENT → pause + resume flag; LOSS_TRANSIENT_CAN_DUCK → the native engine's volume (via the existing SetVolume path, a duck factor 0.2) — document the duck factor as configurable-constant-not-yet-exposed. The service mediates; the UI observes state as always (no new state channel).
2. **FFI reads v5**: `get_state` gains the current entry id (closes the path-join playing flag + the Library-vs-session duplicate-path inconsistency) and the armed/`next` entry id (the armed badge). One Rust change + regen + Kotlin consumers.
3. **Battery**: screens switch to lifecycle-aware collection (the Robolectric lane must stay honest — judge the expect/actual shape vs `lifecycle-runtime-compose` on Android); the keep-awake gate stays as-is (it feeds the service, which is what the background actually needs).
4. **Restart semantics**: `START_STICKY` + an `onStartCommand` re-derive that rebuilds the session from engineState truth (the engine survived the process? NO — process death kills the engine too; the honest behavior: sticky restart rebuilds from the store's persisted state (which today is minimal — document what restart actually gives: a notification-less service that re-attaches nothing; judge and document honestly — the spec's session persistence is desktop Services-layer work not yet ported).
5. **Crash reporting mirrors the desktop**: read how `../fauste-player` handles crashes (the `report_cap`/`max_crash_reports` config + the store's crash dir; `fp-app/src/main.rs` wiring) — Rust: a `std::panic::set_hook` in the FFI that writes a report file to the store's crash dir (only if the desktop's machinery is reachable read-only); Kotlin: `Thread.setDefaultUncaughtExceptionHandler` → a file under `filesDir` + the default handler chained. NO third-party crash service (offline app).
6. **Release signing is documented, not invented**: wire the release build (minify/R8 config verified green), produce the AAB, and document the signing setup (keystore creation + `gradle.properties` secrets pattern). Do NOT commit any keystore; the maintainer creates the real one. The user guide + README ship in English (spec's i18n covers UI strings, not docs — check the desktop's docs norms).
7. **Measurements, not vibes**: the spec's numbers (cold start < 2 s, memory < 150 MB, CPU idle < 2 %, 10 h screen-off playback) are MEASURED where the emulator allows (logcat timestamps, dumpsys meminfo/cpuinfo, battery-historian-style estimates are NOT real battery numbers — document the methodology honestly; on-device battery life needs hardware the maintainer owns).

## Global Constraints

- Standing battery unchanged: English; Conventional Commits; ktlint 1.7.1 clean; no `!!`/empty catch; CE-rethrow; no main-thread engine calls; bindings regen (ktlint/drift flow) on any `ffi.rs` change; `scripts/emulator-test.sh` + the app-smoke when the service/FFI surface changes (the ledger's re-run trigger); the desktop repo read-only.

---

### Task 1: FFI reads v5 — entry id + armed (Rust + Kotlin, TDD)

**Files:** `crates-mobile/fp-backends-mobile/src/ffi.rs` (+player_core if passthrough needed), `tests/ffi_state.rs` + `tests/engine_smoke.rs`, bindings regen, the Kotlin consumers: `QueueManager`/`LibraryViewModel` (playing flag by id), `PlayerViewModel`/`LibraryScreen` (armed badge), `SessionPlaybackSnapshot` (currentQueueIndex by id).

- [ ] Investigate the model: does `AppState`/`PlayerState` expose the on-air ENTRY id (not just the track path)? Read `fp-model`'s player/live state (`live.rs` — `audio_system_in_use` lives there; check for an entry/queue id field) and the armed `next` + `next_explicit` (Task 2 of Phase 4 verified these in the reducer — `next: Option<EntryId>`, `next_explicit: bool` are readable from the model). Cite what you find; if the on-air id needs derivation (the playlist's entry whose... — judge honestly), document.
- [ ] FFI: `EngineState` gains `currentEntryId: Option<u64>` + `armedEntryId: Option<u64>` (or two methods — judge the surface shape; records preferred). TDD host tests (enqueue → play → currentEntryId matches; set_next → armedEntryId; previous clears/changes it per the reducer semantics verified in Phase 4).
- [ ] Bindings regen (drift-symmetric); emulator run green (smoke extends: the ids round-trip on-device).
- [ ] Kotlin consumers: the Library playing flag joins by id (closes the path-join gap + the duplicate-path inconsistency); the armed badge on the armed row (Library + Player screens — an "up next" indicator using `NocturneColors.Next`... the spec's palette has no Next color — use `Primary`/`Warning` per taste, document); `SessionPlaybackSnapshot.currentQueueIndex` by id.
- [ ] **Commit** — `feat(mobile): entry id and armed reads across the ffi`

### Task 2: Audio focus in the service (Android, TDD-where-host-testable)

**Files:** `PlaybackService` (+ the focus-request wiring), tests; docs per Ruling 1.

- [ ] Focus request on first Play; loss/loss-transient/can-duck handling per Ruling 1 (pause / pause+resume / duck 0.2 via SetVolume); abandonment on Stop/teardown; re-request on user-initiated resume while another holder owns focus (the honest "request and see" — judge).
- [ ] Host-testable: the focus policy as a pure state machine (focusEvent × currentState → action), pinned; the service wiring manually-verified via the smoke (an emulator call-audio interrupt is drivable? `adb emu` has no clean phone-call sim on API 34 — document honestly what the checklist covers).
- [ ] **Commit** — `feat(mobile): audio focus handling in the playback service`

### Task 3: Lifecycle-aware collection + restart semantics (Kotlin)

- [ ] Screens adopt lifecycle-aware collection (Ruling 3; the Robolectric lane must keep passing — judge the mechanism: `lifecycle-runtime-compose` dep on androidMain + an expect/actual `rememberFlowWithLifecycle`-style helper in commonMain).
- [ ] The battery claim measured: with the app backgrounded+playing on the emulator, verify the composition is idle (a recomposition counter test or the profile inspector — judge honestly what's measurable; document).
- [ ] Restart semantics per Ruling 4: `START_STICKY` + `onStartCommand` re-derive, honestly documenting what a sticky restart gives (a service with no engine — process death killed it; the honest behavior is a quiet exit or a session-rebuild when the store/session surface exists — read what the desktop persists; TODAY: document + exit cleanly, no zombie notification).
- [ ] **Commit** — `feat(mobile): lifecycle-aware collection and honest restart semantics`

### Task 3b: Session persistence — the mobile save layer (Rust, TDD)

> Added from Task 3's verified investigation: the desktop autosaves the session mutation-debounced (~1 s, `fp-app/src/services.rs:1274-1303`, `save_debounce_ms: 1000.0`), during playback at intervals, and once at shutdown (`:635-639`); the mobile is LOAD-only — imported queues die with the process. This task ports the save half so the sticky-restart re-attach becomes meaningful and restarts keep the user's queue.

**Files:** `crates-mobile/fp-backends-mobile/src/player_core.rs` (+ tests), `tests/engine_smoke.rs`; read `../fauste-player/crates/fp-store/src/store.rs` (`save`) + the desktop's services.rs save trigger as the authority.

- [ ] Investigate the store's `save` surface (what it takes — the AppState? a session DTO? the desktop's call shape at services.rs:1274-1303) and the conductor's model-revision signal (the ArcSwap revision — is there a monotonically increasing revision the mobile can watch to detect mutations, the way `fp-remote`'s events do? Cite).
- [ ] Implement in PlayerCore: a non-RT save thread (or a task in the runtime): watches model revisions, debounces ~1 s (a named constant citing the desktop's), saves on quiet; a while-playing interval per the desktop's `playing && session_due` branch; a final save on Drop/shutdown (mirror the desktop's shutdown save order — read main.rs's exit order). The save path must never touch the RT path (it reads a snapshot; document).
- [ ] TDD host: enqueue → wait past the debounce → the store files appear/reflect the queue; a fresh PlayerCore on the same dirs loads the queue (the round-trip pin); drop → the final save flushed; while-playing interval pinned with a short test constant (injectable).
- [ ] Emulator: the smoke extends — after enqueue+play, kill the process (the script's SIGKILL), relaunch, assert the queue survived (the restart story's new truth); `./scripts/emulator-test.sh` green.
- [ ] **Commit** — `feat(mobile): debounced session persistence for the player core`

---

### Task 4: Crash reporting (Rust + Kotlin)

- [ ] Investigate the desktop's crash machinery (Ruling 5) — the store's crash dir + `max_crash_reports` cap. Implement the Rust panic hook (FFI init installs it; writes a report file; non-RT path only — a panic hook may allocate freely).
- [ ] Kotlin: `Thread.setDefaultUncaughtExceptionHandler` → file under `filesDir/crash/` + chain the previous handler; the cap honored (prune old files; mirror the desktop's limit semantics).
- [ ] Tests: the hook writes on a deliberate test panic (Rust: `catch_unwind` around a panic in a test — judge; Kotlin: invoke the handler with a synthetic throwable in a Robolectric test); the prune/cap logic pure-tested.
- [ ] **Commit** — `feat(mobile): crash reporting hooks rust and kotlin`

### Task 5: i18n (Kotlin)

- [ ] The Fluent dependency (Ruling from Tech Stack — verify coordinates, document); `.ftl` files en-US (source — port the strings the UI uses today) + es-ES (the maintainer's Spanish — the user-facing strings translated; commit message in English as always, the .ftl content is user-facing Spanish).
- [ ] A `Strings` expect/actual resolving messages (locale from the platform); every hardcoded UI string migrated (the a11y descriptions too); ktlint clean; all tests green (string assertions become key-based).
- [ ] **Commit** — `feat(mobile): fluent i18n with en us and es es`

### Task 6: Release — measurements, docs, build wiring (docs + gradle)

- [ ] Release build green: R8/minify config for Compose + UniFFI/JNA (proguard rules for JNA + the bindings — the JNA AAR ships consumer rules? verify; document what was needed), `assembleRelease` + `bundleRelease` (AAB) green.
- [ ] Signing documentation (Ruling 6): a `docs/release/signing.md` (or the README section) with the keystore creation + `gradle.properties` pattern; NO keystore committed.
- [ ] Measurements (Ruling 7): cold start (logcat timeline on the emulator), memory (`dumpsys meminfo` after import+play), CPU idle (`dumpsys cpuinfo` while stopped vs playing) — a `docs/release/measurements.md` with methodology + emulator numbers + the honest "hardware battery life needs real devices" note vs the spec's 10 h target.
- [ ] User guide `docs/user/mobile.md` (install, import, play, the queue semantics — arm-only tap explained in user terms, remote status, limitations section pulling the disclosed limits).
- [ ] README updated: features table (what's shipped vs planned — the Phase-4 deferred features listed as roadmap), platform matrix, build instructions (cargo + gradle + the cdylib notes), the CI badges description.
- [ ] **Commit** — `docs(mobile): release measurements user guide readme and signing setup`

---

## Notes for the implementer

- The Phase-4 plan's "Deferred to Phase 5" list is the authoritative handoff — its items map: audio focus → T2, lifecycle collection → T3, entry-id/next reads → T1, restart → T3, i18n → T5; the polish bucket lands opportunistically in whichever task touches its files (throttle extraction when a third VM appears — it won't this phase; fixture dedups when a third test consumer appears).
- The emulator app-smoke re-run trigger: any change to the service startup sequence or the FFI surface (T1, T2, T3 restart work).
- KVM: `sudo -n setfacl -m u:msanchez:rw /dev/kvm` when the emulator refuses (non-persistent).
