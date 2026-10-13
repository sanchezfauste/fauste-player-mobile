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
use fp_backends::AudioBackend;
use fp_engine::conductor::{Conductor, ConductorHandle};
use fp_engine::engine::{Engine, EngineSettings};
use fp_engine::worker::file_opener;
use fp_model::{AppState, Command, Limits, TrackId};
use fp_remote::control::{Playback, RemoteControl, WaveformData};
use fp_remote::server::{RemoteHandle, RemoteStatus};
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
        let config = state.config.clone();

        // The Engine appends its own NullBackend as the last resort (see
        // `Engine::new`), so on a host without AAudio everything still works.
        let backends: Vec<Arc<dyn AudioBackend>> = vec![Arc::new(AaudioBackend::new())];
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
