# Persistence

The mobile uses the desktop store unchanged — the same files, the same
atomic writes, the same backups and lenient loading; the desktop's
[persistence](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/persistence.md)
is the reference for all of that. The mobile-specific part is where
the files live and the layer that keeps them current: until Phase 5
the core was LOAD-only and imported queues died with the process;
Task 3b ported the save half, so the queue now survives a kill.

## Where the files are

`PlayerCore` constructs `AppPaths` **literally** from the two paths
the platform hands the FFI constructor: `data_dir` (the app's
`filesDir`) holds `config/`, `logs/` and the store's data files;
`cache_dir` holds the analysis cache (`cache/analysis`). The plan
ruling: no `Store::new_mobile` was ever added to the desktop — the
existing `AppPaths` + `Store::new(AppPaths, Limits)` public API was
already sufficient. The store loads with the default playlist name
hardcoded as `"Playlist 1"`, citing the desktop's en-US source bundle
value (`default-playlist-name`) — the mobile has no i18n dependency in
the Rust core.

## The save layer (`SessionSaver`)

The desktop saves in its Services thread: mutation-debounced autosave,
a while-playing session interval, and one final save at exit. The
mobile has no Services layer, so a dedicated thread ports that half:

- **The mutation signal is `Telemetry::model_version`** — the
  conductor's own revision, incremented by every tick that changed the
  model. This is not invented snapshot polling: it is the same
  versioned-change signal the desktop's services thread reads each
  step and `fp-remote`'s `Playback.revision` publishes. The version is
  read **before** the snapshot, so a save can never record a version
  newer than the data it wrote.
- **Debounce `SESSION_SAVE_DEBOUNCE` (1 s)** — the desktop's
  `tuning.save_debounce_ms` default, cited; a named constant because
  nothing on mobile writes `tuning` (no settings surface for it yet).
  A mutation starts the clock; once the model has been quiet for the
  debounce, everything is saved and the revision is recorded —
  recorded only once the files are written, so a failed save is
  retried by the next mutation.
- **While playing, the session alone is saved every debounce anyway**
  (the desktop's `playing && session_due` branch): positions move
  without touching the model, so the session file is the only thing
  that can track them.
- **The final save runs on Drop**: the save thread is joined after it,
  and it still sees the live conductor — the desktop's exit order
  (senders stop, then the final save with the positions the engine
  reports right now, then the audio). The teardown order is the field
  declaration order: saver → analyzer → remote → the conductor handle.
- **The save path never touches the RT path**: the thread only reads
  ArcSwap snapshots (atomic refcount bumps, the same reads the remote
  bridge does per request) and writes files; a failed save is logged
  and life continues, as on the desktop.
- The thread's wake cadence scales with the debounce (a quarter of it,
  clamped to 10–250 ms): the 1 s default polls at 4 Hz, and
  test-injected short debounces get proportionally fine ones.

## The restore truth

On start, the store's restore rebuilds the player **paused at its last
saved position** — an interrupted track comes back Paused, a
never-played queue comes back stopped, and **nothing is ever restored
as Playing** (`fp-model/src/session.rs:134-135` states it outright;
`EngineAction::LoadPaused` at `:246` enforces it). A sticky service restart after a
process kill re-attaches to exactly this state and exits quietly: the
fresh process cannot be playing, and no zombie notification is posted.

## The SIGKILL window

The queue survives a force-kill **up to the last save** — at most one
debounce window of mutation can be lost (the while-playing interval
bounds position loss the same way), and a clean exit loses nothing
(the final save runs before the engine stops). The honest wording
matters: "the queue survives a force-kill" is bounded by the debounce,
not absolute.

## What is not ported

- Cover/peaks/waveform serving (`MobileBridge::cover`/`peaks` answer
  `None`; the media-cache + analysis-serving surface is a recorded
  deferral for the waveform UI).
- The desktop's live `tuning`/`limits` settings surface — the debounce
  and the crash-report cap are boot-time constants until a settings UI
  exists for them.
- The desktop's crash-cap follow-from-config: the mobile hook installs
  with `Limits::default().max_crash_reports` (20) once per process
  (`install_panic_hook_once`), and reports land in `data_dir/logs/`
  as on the desktop. The Kotlin half writes uncaught-exception reports
  to `filesDir/crash/`, pruned to the same 20-file cap.
