//! The mobile player core: everything the desktop wires at startup minus the
//! UI, MIDI and Services layers — plus, since Task 3b, the Services layer's
//! SAVE half (the desktop's authority: `fp-app/src/services.rs`).
//!
//! Authority: `fp-app/src/main.rs` (`fn run`, around lines 120-160). The
//! wiring order here mirrors it line for line:
//!
//! ```text
//! Store::new(paths, Limits::default())
//! store.load(&default_name)
//! config = loaded.state.config.clone()
//! backends = vec![AaudioBackend]        // Engine appends its Null fallback
//! EngineSettings::from_config(&config)
//! Engine::new(backends, settings, file_opener())
//! Conductor::new(loaded.state, loaded.actions, engine, Instant::now())
//! conductor.spawn(handle)               // -> ConductorHandle on its thread
//! AnalysisCache::new(cache_dir/"analysis")
//! Analyzer::spawn(threads, config.analysis, config.limits, Some(cache))
//! SessionSaver::spawn(handle, store, SESSION_SAVE_DEBOUNCE)  // Task 3b
//! fp_remote::spawn(bridge over the handle)  // loopback, ephemeral port
//! ```
//!
//! # The save half (Task 3b)
//!
//! The desktop saves in its Services thread (`fp-app/src/services.rs`): the
//! session mutation-debounced through `autosave`
//! (`fp-app/src/services.rs:1274-1303`), once more at exit
//! (`fp-app/src/services.rs:635-639`). The mobile has no Services layer, so
//! `SessionSaver` below ports that half on its own thread:
//!
//! * **The mutation signal** is the conductor's model revision
//!   (`Telemetry::model_version`, `fp-engine/src/conductor.rs:106-107`):
//!   every tick that changed the model increments it
//!   (`fp-engine/src/conductor.rs:341-344`). The desktop's services thread
//!   reads the same field each step (`fp-app/src/services.rs:570-572`) —
//!   this is not invented polling of snapshots, it is the conductor's own
//!   versioned-change signal (the one `fp-remote`'s `Playback.revision`
//!   publishes too).
//! * **The save path never touches the RT path.** The thread only ever
//!   READS `ArcSwap` snapshots (`handle.model.load_full()`,
//!   `handle.telemetry.load()` — atomic refcount bumps, the same reads the
//!   remote server's bridge does every request) and writes files. The audio
//!   callbacks never hear about it.
//! * **Debounce** [`SESSION_SAVE_DEBOUNCE`], mirroring the desktop's
//!   `tuning.save_debounce_ms` default.
//!
//! # Drop story
//!
//! There is no explicit `shutdown`: the owned pieces stop themselves, exactly
//! as on the desktop, where the end of `run()` drops locals in reverse order
//! and the exit sequence stops every sender first, then the final save, then
//! the audio (`fp-app/src/main.rs:210-217`: `drop(remote)`, `midi.shutdown()`,
//! `services.shutdown()`, `stop_engine(handle)`). Here the field declaration
//! order gives the same teardown:
//!
//! 1. `_saver` drops first: the save thread stops and is joined AFTER its
//!    final save — which still sees the live conductor (the desktop's
//!    `shutdown()` shape: a fresh model snapshot plus the positions the
//!    engine reports right now, `fp-app/src/services.rs:635-639`).
//! 2. `_analyzer` drops second. `Analyzer::drop` (fp-analysis) sets the shared
//!    stop flag, drops the job senders (closing the queues) and joins every
//!    worker thread.
//! 3. `remote` drops third. `RemoteHandle::drop` (fp-remote) signals the
//!    remote thread and joins it, closing the HTTP event streams and the
//!    listener.
//! 4. `handle` drops last. When the last `Arc<ConductorHandle>` goes,
//!    `ConductorHandle::drop` (fp-engine) sets the stop flag and joins the
//!    `fp-conductor` thread.
//!
//! The `Store` loaded the state at construction and the save thread holds
//! the only share of it afterwards (the load half at boot, the save half on
//! the saver thread — the desktop splits the same way: `main.rs` loads,
//! `Services` saves).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use fp_analysis::analyzer::Analyzer;
use fp_analysis::cache::AnalysisCache;
use fp_backends::{AudioBackend, Availability};
use fp_engine::conductor::{Conductor, ConductorHandle};
use fp_engine::engine::{Engine, EngineSettings};
use fp_engine::worker::file_opener;
use fp_model::player::Transport;
use fp_model::{AppState, Command, EntryId, Limits, PlayerId, TrackId};
use fp_remote::control::{Playback, RemoteControl, WaveformData};
use fp_remote::server::{RemoteHandle, RemoteStatus, ServerStatus};
use fp_store::{AppPaths, Store};

use crate::AaudioBackend;

/// Desktop: `I18n::new(None).tr("default-playlist-name")`. With no requested
/// language that reads the en-US source bundle, whose value is "Playlist 1"
/// (`fauste-player/crates/fp-app/locales/en-US/main.ftl`, key
/// `default-playlist-name`). Hardcoded here because mobile has no i18n
/// dependency until Phase 4.
const DEFAULT_PLAYLIST_NAME: &str = "Playlist 1";

/// The autosave debounce: the desktop's `tuning.save_debounce_ms` default,
/// 1000.0 (`fp-model/src/config.rs:841`), which its services thread reads
/// from the live config on every step (`fp-app/src/services.rs:1278-1279`).
/// The mobile uses a named constant because nothing on mobile writes
/// `tuning` (no settings surface for it); when one exists, this can become
/// a config read like the desktop's. This ONE value is also the
/// while-playing save interval, as on the desktop, whose `session_due`
/// compares against the same `save_debounce_ms`
/// (`fp-app/src/services.rs:1296-1298`).
pub const SESSION_SAVE_DEBOUNCE: Duration = Duration::from_secs(1);

/// The store's log directory inside `data_dir` — the one place this
/// crate spells the layout that [`PlayerCore::core`]'s `AppPaths` builds
/// (`log_dir` there) and the crash hook writes to (the desktop writes
/// its `crash-*.txt` reports into `paths.log_dir`,
/// `fp-app/src/crash.rs:66`, and the mobile's FFI constructor passes
/// the same directory to `crash::install_panic_hook_once`).
pub fn log_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("logs")
}

/// The running player: save thread, conductor thread, analysis pool and
/// remote server.
pub struct PlayerCore {
    /// Dropped first on teardown: stops the save thread after its final
    /// save (see the module docs — the desktop's exit order).
    _saver: SessionSaver,
    /// Dropped second: stops and joins the analysis workers.
    _analyzer: Analyzer,
    /// Dropped third: stops and joins the remote thread.
    remote: RemoteHandle,
    /// What the remote serves; the same `Arc` the remote thread holds.
    control: Arc<MobileBridge>,
    /// Dropped last: stops and joins the conductor thread once the last
    /// share is released.
    handle: Arc<ConductorHandle>,
    /// The AAudio backend share the engine holds too, kept for probing
    /// availability at call time (`backend_availability`,
    /// `get_available_backends`).
    aaudio: Arc<AaudioBackend>,
}

impl PlayerCore {
    /// Wires store → engine → conductor → analyzer from the KMP-provided
    /// paths, mirroring the desktop startup (see the module docs).
    ///
    /// `AppPaths` is constructed literally (no `Store::new_mobile` exists —
    /// that was a deliberate plan ruling): `data_dir` and `cache_dir` come
    /// from the caller; `config_dir` and `log_dir` sit inside `data_dir`
    /// because mobile has no separate config/log locations.
    pub fn new(data_dir: PathBuf, cache_dir: PathBuf) -> std::io::Result<Arc<PlayerCore>> {
        Self::core(data_dir, cache_dir, None, None, SESSION_SAVE_DEBOUNCE)
    }

    /// As `new`, but `sample_rate`/`buffer_frames` (when `Some`) override the
    /// global `outputs.sample_rate`/`outputs.buffer_frames` of the loaded
    /// config before `EngineSettings::from_config` reads them — the FFI's
    /// `EngineConfig` values win at boot. Phase 3's settings UI will own
    /// these instead (via `Command::UpdateConfig`, which rebuilds the engine
    /// settings from the new config).
    pub fn with_overrides(
        data_dir: PathBuf,
        cache_dir: PathBuf,
        sample_rate: Option<u32>,
        buffer_frames: Option<u32>,
    ) -> std::io::Result<Arc<PlayerCore>> {
        Self::core(
            data_dir,
            cache_dir,
            sample_rate,
            buffer_frames,
            SESSION_SAVE_DEBOUNCE,
        )
    }

    /// As `new`, with the autosave timing injected: the debounce a mutation
    /// waits for quiet AND the while-playing save interval — one duration,
    /// because the desktop derives both from the same `save_debounce_ms`
    /// (`fp-app/src/services.rs:1278-1298`). Tests pass milliseconds
    /// instead of the 1 s default so a save lands inside a bounded wait
    /// and the playing interval is observable in milliseconds; production
    /// callers use [`PlayerCore::new`] and its [`SESSION_SAVE_DEBOUNCE`].
    pub fn with_save_timing(
        data_dir: PathBuf,
        cache_dir: PathBuf,
        save_debounce: Duration,
    ) -> std::io::Result<Arc<PlayerCore>> {
        Self::core(data_dir, cache_dir, None, None, save_debounce)
    }

    /// The shared body of the public constructors, differing only in the
    /// engine overrides and the autosave timing.
    fn core(
        data_dir: PathBuf,
        cache_dir: PathBuf,
        sample_rate: Option<u32>,
        buffer_frames: Option<u32>,
        save_debounce: Duration,
    ) -> std::io::Result<Arc<PlayerCore>> {
        let paths = AppPaths {
            config_dir: data_dir.join("config"),
            log_dir: log_dir(&data_dir),
            data_dir,
            cache_dir,
        };
        let store = Arc::new(Store::new(paths.clone(), Limits::default()));
        let loaded = store.load(DEFAULT_PLAYLIST_NAME);
        for warning in &loaded.warnings {
            tracing::warn!(%warning, "while loading the saved state");
        }
        // The remote is ON here where the desktop default is off: the
        // mobile UI is its client, so the player is useless without it. It
        // is pinned to loopback — a phone never serves beyond itself and
        // this phase has no token flow — with an ephemeral port, reported
        // by `remote_status` (the desktop default 7380 could clash with a
        // desktop player, or with a previous run that has not released it).
        let mut state = loaded.state;
        state.config.remote.http.enabled = true;
        if !state
            .config
            .remote
            .http
            .bind_addr()
            .is_some_and(|ip| ip.is_loopback())
        {
            state.config.remote.http.bind = "127.0.0.1".to_owned();
        }
        state.config.remote.http.port = 0;
        if let Some(rate) = sample_rate {
            state.config.outputs.sample_rate = rate;
        }
        if let Some(frames) = buffer_frames {
            state.config.outputs.buffer_frames = frames;
        }
        let config = state.config.clone();

        // The Engine appends its own NullBackend as the last resort (see
        // `Engine::new`), so on a host without AAudio everything still works.
        // The AAudio share is retained for availability probing
        // (`backend_availability`).
        let aaudio: Arc<AaudioBackend> = Arc::new(AaudioBackend::new());
        let backends: Vec<Arc<dyn AudioBackend>> = vec![aaudio.clone()];
        let settings = EngineSettings::from_config(&config);
        let engine = Engine::new(backends, settings, file_opener());
        tracing::info!(backend = %engine.backend_in_use(), "audio backend in use");

        let (conductor, handle) = Conductor::new(state, loaded.actions, engine, Instant::now());
        let handle = Arc::new(conductor.spawn(handle)?);

        let threads = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
            .clamp(1, 8);
        let cache = AnalysisCache::new(paths.cache_dir.join("analysis"));
        let analyzer = Analyzer::spawn(
            threads,
            config.analysis.clone(),
            config.limits.clone(),
            Some(cache),
        )?;

        let control = Arc::new(MobileBridge {
            conductor: handle.clone(),
        });
        let remote = fp_remote::spawn(control.clone())?;

        // Task 3b: the save half of the store contract, on its own thread
        // (never the audio path — see the module docs). Spawned last, so
        // any earlier failure tears the already-spawned pieces down
        // through their own Drops without a saver to stop.
        let saver = SessionSaver::spawn(handle.clone(), store, save_debounce)?;

        Ok(Arc::new(PlayerCore {
            _saver: saver,
            _analyzer: analyzer,
            remote,
            control,
            handle,
            aaudio,
        }))
    }

    /// The conductor's command/model handle, for the layers above.
    pub fn handle(&self) -> &Arc<ConductorHandle> {
        &self.handle
    }

    /// The remote-control implementation the server publishes, for tests
    /// and for layers that call the API in-process.
    pub fn remote_control(&self) -> Arc<dyn RemoteControl> {
        self.control.clone()
    }

    /// What the remote servers are doing (listening, off, or the bind
    /// error), for the settings UI.
    pub fn remote_status(&self) -> RemoteStatus {
        self.remote.status()
    }

    /// Switches the default output's backend exactly the way the desktop
    /// settings picker does (`fp-app/src/ui/settings.rs:793`, through
    /// `update`, `fp-app/src/ui/settings.rs:175-182`): one
    /// `Command::UpdateConfig` carrying the current configuration with
    /// only `outputs.backend` changed. `None` is the picker's "system
    /// default" row (`fp-app/src/ui/settings.rs:770-773`). The conductor
    /// applies it asynchronously (the reducer hands the engine an
    /// `UpdateSettings` action, `fp-model/src/reducer.rs:1243-1245`, and
    /// the live layer dispatches the audio-system switch when its paths
    /// are quiet, `fp-model/src/reducer.rs:380` /
    /// `fp-engine/src/engine.rs:901-931`); this returns after queueing,
    /// never blocking.
    ///
    /// # The port guard
    ///
    /// This core pins the HTTP remote to an ephemeral port
    /// (`remote.http.port = 0` in `with_overrides`), but the reducer
    /// re-validates every `UpdateConfig` (`fp-model/src/reducer.rs:1234`),
    /// and validation clamps the port to 1024..
    /// (`fp-model/src/remote.rs:196`). The remote thread follows the
    /// model's `remote.http` and rebinds whenever it changes
    /// (`fp-remote/src/server.rs:204-213`), so a naive clone-and-send
    /// would move the server from its ephemeral port to 1024 within one
    /// 250 ms poll. Before sending, the payload's port is therefore
    /// overwritten with the port the server is *actually* listening on —
    /// a real port survives validation. Two consequences, by design:
    ///
    /// * The first switch after boot makes the remote rebind once — to
    ///   the same address. The stop/restart is brief and the address
    ///   clients hold stays valid; afterwards the model carries the real
    ///   port and later switches do not touch the remote at all.
    /// * While the server is not listening (still starting, or a failed
    ///   bind), the port is left alone and the switch still goes through;
    ///   the model then holds whatever validation made of `0`.
    ///
    /// A backend that is currently unavailable is still accepted, as on
    /// the desktop when a backend is lost: the engine falls back to what
    /// opens and keeps retrying (`choose_backend`,
    /// `fp-engine/src/engine.rs:456-468`). The desktop's settings UI
    /// simply never offers unavailable backends
    /// (`fp-app/src/ui/settings.rs:783-784`); the mobile settings UI gets
    /// that from `backend_availability`.
    pub fn set_backend(&self, backend: Option<&str>) -> Result<(), SetBackendError> {
        match backend {
            Some("null") | Some("aaudio") | None => {}
            Some(other) => return Err(SetBackendError::Unknown(other.to_owned())),
        }
        let handle = self.handle();
        let current = handle.model.load().config.clone();
        let mut config = current.clone();
        config.outputs.backend = backend.map(str::to_owned);
        if let ServerStatus::Listening(addr) = self.remote.status().http {
            config.remote.http.port = addr.port();
        }
        // As the desktop's `update` (`fp-app/src/ui/settings.rs:178`):
        // validate the edit before offering it. The reducer validates
        // again on apply (`fp-model/src/reducer.rs:1234`) — the patched
        // port is what carries the ephemeral binding through that.
        let _ = config.validate();
        // As the desktop, an edit that changed nothing sends nothing
        // (`fp-app/src/ui/settings.rs:179`). The FFI is the only config
        // writer on mobile, so the snapshot this ran on is current.
        if config == current {
            return Ok(());
        }
        if handle.send(Command::UpdateConfig(Box::new(config))) {
            Ok(())
        } else {
            Err(SetBackendError::QueueFull)
        }
    }

    /// The availability of a backend this core wired, probed NOW:
    /// `"null"` is the engine's always-present fallback; `"aaudio"`
    /// opens and closes a throwaway stream per call
    /// (`src/aaudio_backend.rs`, deliberately uncached — as the desktop
    /// engine probes when it chooses, `fp-engine/src/engine.rs:456-468`).
    /// `None` for an id that was never wired.
    pub fn backend_availability(&self, id: &str) -> Option<Availability> {
        match id {
            "null" => Some(Availability::Available),
            "aaudio" => Some(self.aaudio.availability()),
            _ => None,
        }
    }

    /// The main player's playlist, each entry resolved through the
    /// library — the queue the mobile UI lists (the FFI's `get_playlist`
    /// maps it; the on-device smoke reads it directly).
    pub fn playlist(&self) -> Vec<QueueEntry> {
        let model = self.handle().model.load();
        let Some(player) = model.players.first() else {
            return Vec::new();
        };
        let Some(list) = model.playlists.get(player.playlist) else {
            return Vec::new();
        };
        list.entries
            .iter()
            .map(|entry| {
                let track = model.track_for_entry(entry.id);
                QueueEntry {
                    entry: entry.id,
                    path: track.map_or_else(PathBuf::new, |t| t.path.clone()),
                    title: track.map_or_else(String::new, |t| t.title.clone()),
                    duration_secs: track.map_or(0.0, |t| t.duration_secs),
                }
            })
            .collect()
    }
}

/// The save thread's stop signal and join handle. Dropping it stops the
/// thread and joins it AFTER its final save, so a `PlayerCore` teardown
/// never returns with unsaved state — the mobile mirror of the desktop's
/// `services.shutdown()` running before the engine stops
/// (`fp-app/src/main.rs:216-217`; the final save itself is
/// `fp-app/src/services.rs:635-639`).
struct SessionSaver {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl SessionSaver {
    fn spawn(
        handle: Arc<ConductorHandle>,
        store: Arc<Store>,
        debounce: Duration,
    ) -> std::io::Result<Self> {
        let stop = Arc::new(AtomicBool::new(false));
        let flag = stop.clone();
        let thread = std::thread::Builder::new()
            .name("fp-session-save".to_owned())
            .spawn(move || save_loop(handle, store, flag, debounce))?;
        Ok(Self {
            stop,
            thread: Some(thread),
        })
    }
}

impl Drop for SessionSaver {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// The autosave bookkeeping, mirroring the desktop Services fields it
/// replaces (`saved_version`, `dirty_since`, `last_session_save`,
/// `fp-app/src/services.rs:431-434` around `saved_version: 0`).
struct SaveBookkeeping {
    /// The model revision whose state is on disk.
    saved_version: u64,
    /// When the oldest unsaved mutation was first seen.
    dirty_since: Option<Instant>,
    /// When the session file was last written (the while-playing clock).
    last_session_save: Option<Instant>,
}

/// The save thread's body: a plain non-RT thread. It NEVER touches the
/// audio path — it reads `ArcSwap` snapshots (atomic refcount bumps, the
/// same reads the remote bridge does per request) and writes files. A save
/// that fails is logged and life continues, as on the desktop (every store
/// error there is `tracing::error!`'d, never propagated,
/// `fp-app/src/services.rs:1305-1330`); the revision keeps moving on
/// further mutations, so the next change retries the write.
fn save_loop(
    handle: Arc<ConductorHandle>,
    store: Arc<Store>,
    stop: Arc<AtomicBool>,
    debounce: Duration,
) {
    // The desktop initialises `saved_version: 0` (`fp-app/src/services.rs:492`)
    // and so does this port, exactly: the conductor boots at revision 0 —
    // the restore actions the load produced run in `Conductor::new` before
    // the first tick (`fp-engine/src/conductor.rs:236-243`) and the revision
    // only moves in `tick` (`fp-engine/src/conductor.rs:341-344`) — so "0"
    // means "nothing has changed yet". What DOES legitimately change at
    // boot, the engine reporting its placements into the live layer over
    // the first ticks, is saved here too — the same way the desktop's
    // services thread saves it within its first second.
    let mut saved = SaveBookkeeping {
        saved_version: 0,
        dirty_since: None,
        last_session_save: None,
    };
    let poll = poll_interval(debounce);
    loop {
        if stop.load(Ordering::Acquire) {
            break;
        }
        // The version is read before the snapshot, as on the desktop: the
        // snapshot saved can only be newer than the version recorded as
        // saved, never older (`fp-app/src/services.rs:570-572`).
        let version = handle.telemetry.load().model_version;
        let state = handle.model.load_full();
        autosave(&store, &handle, &mut saved, &state, version, debounce);
        std::thread::sleep(poll);
    }
    // The final save: a fresh snapshot with the positions the engine
    // reports right now. The conductor is still running — this thread is
    // joined from `SessionSaver`'s Drop, which runs before the conductor
    // handle drops (the module docs' teardown order), mirroring the
    // desktop's exit: senders stop, then the final save, then the audio
    // (`fp-app/src/main.rs:210-217`).
    let state = handle.model.load_full();
    save_all(&store, &handle, &state);
}

/// One autosave round: the port of the desktop's `autosave`
/// (`fp-app/src/services.rs:1274-1303`). A mutation starts the debounce
/// clock; once the model has been QUIET for `debounce`, everything is
/// saved and the revision is recorded — recorded only once the files are
/// written, so a failed save is retried when the next mutation moves the
/// revision (`fp-app/src/services.rs:1284-1285`). When nothing is dirty
/// but something PLAYS, the session alone is saved every `debounce`
/// anyway: positions move without touching the model, so the session
/// file is the only thing that can track them
/// (`fp-app/src/services.rs:1292-1302`, the `playing && session_due`
/// branch).
fn autosave(
    store: &Store,
    handle: &ConductorHandle,
    saved: &mut SaveBookkeeping,
    state: &AppState,
    version: u64,
    debounce: Duration,
) {
    let now = Instant::now();
    if version != saved.saved_version && saved.dirty_since.is_none() {
        saved.dirty_since = Some(now);
    }
    if saved
        .dirty_since
        .is_some_and(|since| now.saturating_duration_since(since) >= debounce)
    {
        save_all(store, handle, state);
        saved.saved_version = version;
        saved.dirty_since = None;
        saved.last_session_save = Some(now);
        return;
    }
    let playing = state
        .players
        .iter()
        .any(|p| p.transport == Transport::Playing);
    let session_due = saved
        .last_session_save
        .is_none_or(|last| now.saturating_duration_since(last) >= debounce);
    if playing && session_due {
        save_session(store, handle, state);
        saved.last_session_save = Some(now);
    }
}

/// Saves every store file, the desktop's `save_all`
/// (`fp-app/src/services.rs:1305-1316`): each failure is logged and the
/// rest still run — a bad cart file must not cost the queue.
fn save_all(store: &Store, handle: &ConductorHandle, state: &AppState) {
    if let Err(e) = store.save_config(state) {
        tracing::error!(error = %e, "saving the configuration failed");
    }
    if let Err(e) = store.save_playlists(state) {
        tracing::error!(error = %e, "saving the playlists failed");
    }
    if let Err(e) = store.save_carts(state) {
        tracing::error!(error = %e, "saving the cart pages failed");
    }
    save_session(store, handle, state);
}

/// Saves the session file, the desktop's `save_session`
/// (`fp-app/src/services.rs:1318-1331`): the positions come from the
/// conductor's telemetry right now, and a failure is logged, not raised.
fn save_session(store: &Store, handle: &ConductorHandle, state: &AppState) {
    let telemetry = handle.telemetry.load();
    let position_of = |player: PlayerId| {
        telemetry
            .players
            .iter()
            .find(|(id, _)| *id == player)
            .and_then(|(_, t)| t.position_secs)
            .unwrap_or(0.0)
    };
    if let Err(e) = store.save_session(state, position_of) {
        tracing::error!(error = %e, "saving the session failed");
    }
}

/// How often the save thread wakes to check the revision. The desktop's
/// services thread runs its whole step every 50 ms (`PERIOD`,
/// `fp-app/src/services.rs:26`); this thread only compares an integer and
/// maybe bumps an `Arc` between saves, so its cadence scales with the
/// debounce instead: a quarter of it, clamped to [10 ms, 250 ms]. The 1 s
/// desktop default polls at 4 Hz — a phone-friendly cadence — while a
/// test-injected short debounce gets a proportionally fine one, which
/// also keeps the final save on Drop within a fraction of the debounce.
fn poll_interval(debounce: Duration) -> Duration {
    Duration::from_secs_f64((debounce.as_secs_f64() / 4.0).clamp(0.01, 0.25))
}

/// Why a backend switch was refused.
#[derive(Debug, thiserror::Error)]
pub enum SetBackendError {
    /// The id is not one this core wired (`"null"`, `"aaudio"`).
    #[error("unknown backend id: {0}")]
    Unknown(String),
    /// The conductor's command queue was full; the command was dropped,
    /// retrying is safe.
    #[error("the command queue is full; the command was dropped, retry")]
    QueueFull,
}

/// One row of the main player's playlist, as a queue UI shows it: the
/// entry id (what the remove/move commands address) plus what the
/// library knows about its track — the title is the file stem, as on
/// the desktop (`fp-model/src/track.rs:340`), and the duration is 0.0
/// until something measured the track (the analyzer, or the source
/// once it plays).
#[derive(Debug, Clone, PartialEq)]
pub struct QueueEntry {
    pub entry: EntryId,
    pub path: PathBuf,
    pub title: String,
    pub duration_secs: f64,
}

/// The mobile `RemoteControl`: the desktop Bridge (`fp-app/src/remote.rs`)
/// minus its `cover`/`peaks`, which read the media and analysis caches —
/// media cache + analysis serving is Phase 3, so both return `None` for
/// now.
struct MobileBridge {
    conductor: Arc<ConductorHandle>,
}

impl RemoteControl for MobileBridge {
    fn model(&self) -> Arc<AppState> {
        self.conductor.model.load_full()
    }

    fn playback(&self) -> Playback {
        let t = self.conductor.telemetry.load();
        Playback {
            revision: t.model_version,
            players: t
                .players
                .iter()
                .filter_map(|(id, p)| p.position_secs.map(|s| (*id, s)))
                .collect(),
            carts: t
                .carts
                .iter()
                .map(|(id, c)| (*id, c.position_secs))
                .collect(),
        }
    }

    fn send(&self, command: Command) -> bool {
        self.conductor.send(command)
    }

    fn cover(&self, _track: TrackId) -> Option<Vec<u8>> {
        // Phase 3: serve from the media cache / analysis cache.
        None
    }

    fn peaks(&self, _track: TrackId) -> Option<WaveformData> {
        // Phase 3: serve from the media cache / analysis cache.
        None
    }
}
