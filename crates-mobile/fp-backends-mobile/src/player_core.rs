//! The mobile player core: everything the desktop wires at startup minus the
//! UI, MIDI and Services layers.
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
//! fp_remote::spawn(bridge over the handle)  // loopback, ephemeral port
//! ```
//!
//! # Drop story
//!
//! There is no explicit `shutdown`: the owned pieces stop themselves, exactly
//! as on the desktop, where the end of `run()` drops locals in reverse order
//! (services/analyzer before the handle). Here the field declaration order
//! gives the same teardown:
//!
//! 1. `_analyzer` drops first. `Analyzer::drop` (fp-analysis) sets the shared
//!    stop flag, drops the job senders (closing the queues) and joins every
//!    worker thread.
//! 2. `remote` drops second. `RemoteHandle::drop` (fp-remote) signals the
//!    remote thread and joins it, closing the HTTP event streams and the
//!    listener.
//! 3. `handle` drops last. When the last `Arc<ConductorHandle>` goes,
//!    `ConductorHandle::drop` (fp-engine) sets the stop flag and joins the
//!    `fp-conductor` thread.
//!
//! The `Store` is only needed to load the state at construction and is not
//! retained, as on the desktop (Task 2's services layer will own one).

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

use fp_analysis::analyzer::Analyzer;
use fp_analysis::cache::AnalysisCache;
use fp_backends::{AudioBackend, Availability};
use fp_engine::conductor::{Conductor, ConductorHandle};
use fp_engine::engine::{Engine, EngineSettings};
use fp_engine::worker::file_opener;
use fp_model::{AppState, Command, EntryId, Limits, TrackId};
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

/// The running player: conductor thread, analysis pool and remote server.
pub struct PlayerCore {
    /// Dropped first on teardown: stops and joins the analysis workers.
    _analyzer: Analyzer,
    /// Dropped second: stops and joins the remote thread.
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
        Self::with_overrides(data_dir, cache_dir, None, None)
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
        let paths = AppPaths {
            config_dir: data_dir.join("config"),
            log_dir: data_dir.join("logs"),
            data_dir,
            cache_dir,
        };
        let store = Store::new(paths.clone(), Limits::default());
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

        Ok(Arc::new(PlayerCore {
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
