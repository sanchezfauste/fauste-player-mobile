//! UniFFI surface for the mobile app: `FausteEngine`, a thin object over
//! `Arc<PlayerCore>` (Phase 2; grown through Phase 4).
//!
//! Commands reach the conductor as the desktop UI sends them
//! (`fp-app/src/ui/player.rs`, `fp-app/src/ui/table.rs`): `Command::Play` /
//! `Pause` / `Stop` / `Seek` / `SetVolume` / `Previous` carry the MAIN
//! player's id, enqueue is `Command::InsertPaths` appended at the end of the
//! main player's playlist (`fp-app/src/ui/app.rs:623`), the queue edits are
//! `Command::RemoveEntry` / `Command::MoveEntry`
//! (`fp-app/src/ui/table.rs:718-722`, `fp-app/src/ui/table.rs:993`), the
//! queue jump is `Command::SetNext` (`fp-app/src/ui/table.rs:616-626`), and
//! the backend selection rides `Command::UpdateConfig` exactly as the
//! desktop settings picker sends it (`fp-app/src/ui/settings.rs:793`).
//! State reads mirror the desktop Bridge (`fp-app/src/remote.rs:84-130`):
//! the model snapshot and the telemetry, never a blocking call.
//!
//! Status strings mirror the remote DTO (`fp-remote/src/dto.rs:361-363`):
//! `"playing"` / `"paused"` / `"stopped"`.

use std::path::PathBuf;
use std::sync::Arc;

use fp_backends::Availability;
use fp_model::player::Transport;
use fp_model::{AppState, Command, EntryId};
use fp_remote::server::ServerStatus;

use crate::player_core::{PlayerCore, QueueEntry, SetBackendError};

#[derive(uniffi::Record)]
pub struct EngineConfig {
    pub sample_rate: u32,
    pub buffer_frames: u32,
    pub data_dir: String,
    pub cache_dir: String,
}

#[derive(uniffi::Record)]
pub struct Track {
    pub path: String,
    pub title: String,
    pub artist: String,
    pub duration: f64,
    pub sample_rate: u32,
    pub channels: u16,
}

/// One row of the main player's queue: the entry id the remove/move
/// commands address, the track's path, its title (the file stem, as on
/// the desktop — `fp-model/src/track.rs:340`) and its duration in
/// seconds — `0.0` until something measured the track.
#[derive(uniffi::Record)]
pub struct PlaylistEntry {
    pub id: u64,
    pub path: String,
    pub title: String,
    pub duration: f64,
}

/// What the HTTP remote (the app's own API client) is doing: `enabled`
/// follows the server being on — listening, or retrying a failed bind —
/// and `address` is its socket address (`"127.0.0.1:<port>"`, build the
/// API base URL as `http://{address}`) while it listens, empty
/// otherwise. The mobile core forces the server on at loopback with an
/// ephemeral port (`PlayerCore::with_overrides`); the OSC half of the
/// desktop's `RemoteStatus` is not reported because the mobile config
/// leaves it off (`fp-model/src/remote.rs:82-90`).
#[derive(uniffi::Record)]
pub struct RemoteInfo {
    pub enabled: bool,
    pub address: String,
}

#[derive(uniffi::Record)]
pub struct EngineState {
    pub status: String,
    pub position: f64,
    pub duration: f64,
    pub current_track: Option<Track>,
    pub volume: f32,
    pub backend: String,
}

#[derive(uniffi::Record)]
pub struct BackendInfo {
    pub id: String,
    pub name: String,
    pub available: bool,
}

/// The failures the FFI can report. `Busy` means the conductor's command
/// queue was full and the command was dropped (`ConductorHandle::send`
/// returned `false`); retrying is safe.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FausteError {
    #[error("the command queue is full; the command was dropped, retry")]
    Busy,
    #[error("player core error: {0}")]
    Core(String),
    #[error("not found: {0}")]
    NotFound(String),
}

/// The mobile engine: a `PlayerCore` behind the UniFFI object API, driving
/// the MAIN player (`AppState.players[0]`, the only one the default config
/// creates).
#[derive(uniffi::Object)]
pub struct FausteEngine {
    core: Arc<PlayerCore>,
}

/// The first (main) player's id, or a `NotFound` if a hand-edited state held
/// no players (`AppState::new` always creates `config.players.count`,
/// which defaults to 1).
fn main_player(state: &AppState) -> Result<fp_model::PlayerId, FausteError> {
    state
        .players
        .first()
        .map(|p| p.id)
        .ok_or_else(|| FausteError::NotFound("the state has no players".into()))
}

/// Maps a core queue row to the FFI record.
fn map_playlist_entry(row: QueueEntry) -> PlaylistEntry {
    PlaylistEntry {
        id: row.entry.0,
        path: row.path.display().to_string(),
        title: row.title,
        duration: row.duration_secs,
    }
}

impl FausteEngine {
    /// Queues `command` for the main player; a full queue maps to `Busy`
    /// (`ConductorHandle::send` → false, `fp-engine/src/conductor.rs:181`).
    fn send_main(
        &self,
        command: impl FnOnce(fp_model::PlayerId) -> Command,
    ) -> Result<(), FausteError> {
        let handle = self.core.handle();
        let player = main_player(&handle.model.load())?;
        if handle.send(command(player)) {
            Ok(())
        } else {
            Err(FausteError::Busy)
        }
    }
}

#[uniffi::export]
impl FausteEngine {
    /// Wires the full player core (see `PlayerCore::new`). The
    /// `sample_rate`/`buffer_frames` fields of `config` override the loaded
    /// configuration at boot (`PlayerCore::with_overrides`); Phase 3's
    /// settings UI will own them afterwards.
    ///
    /// Non-blocking-exempt: construction loads the store, spawns the
    /// conductor thread, the analysis pool and the remote server —
    /// callers must invoke it off the main thread, or the UI pays for a
    /// disk-bound boot (and Android flags it).
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Result<Arc<Self>, FausteError> {
        let core = PlayerCore::with_overrides(
            PathBuf::from(config.data_dir),
            PathBuf::from(config.cache_dir),
            Some(config.sample_rate),
            Some(config.buffer_frames),
        )
        .map_err(|e| FausteError::Core(e.to_string()))?;
        Ok(Arc::new(Self { core }))
    }

    /// `Command::Play` for the main player (`fp-app/src/ui/player.rs:689`).
    /// From stopped it starts the player's `next` entry, which the model
    /// derives from the playlist order after `enqueue`
    /// (`fp-model/src/reducer.rs`, `refresh_next`).
    pub fn play(&self) -> Result<(), FausteError> {
        self.send_main(Command::Play)
    }

    /// `Command::Pause` for the main player (`fp-app/src/ui/player.rs:749`).
    pub fn pause(&self) -> Result<(), FausteError> {
        self.send_main(Command::Pause)
    }

    /// `Command::Stop` for the main player (`fp-app/src/ui/player.rs:742`).
    pub fn stop(&self) -> Result<(), FausteError> {
        self.send_main(Command::Stop)
    }

    /// `Command::Seek(player, seconds)` of the current entry
    /// (`fp-app/src/ui/player.rs:864`).
    pub fn seek(&self, position: f64) -> Result<(), FausteError> {
        self.send_main(|p| Command::Seek(p, position))
    }

    /// `Command::SetVolume(player, volume)` with the linear gain 0.0–1.0
    /// (`fp-app/src/ui/player.rs:502` — the desktop converts its fader with
    /// `gain_from_fader`; the FFI takes the linear value directly).
    pub fn set_volume(&self, volume: f32) -> Result<(), FausteError> {
        self.send_main(|p| Command::SetVolume(p, volume))
    }

    /// Phase 3: backend selection, the desktop settings picker's exact
    /// path — `Command::UpdateConfig` carrying the current configuration
    /// with only `outputs.backend` changed
    /// (`fp-app/src/ui/settings.rs:793`, via `update`,
    /// `fp-app/src/ui/settings.rs:175-182`). Returns after queueing the
    /// command, never blocking; the engine applies the switch when its
    /// paths are quiet (L11, `fp-engine/src/engine.rs:901-931`) — watch
    /// `get_state().backend` for what is in use.
    ///
    /// The switch must not disturb the remote server: see
    /// `PlayerCore::set_backend` for the port guard that keeps the
    /// ephemeral binding through the config round-trip.
    ///
    /// Known ids are `"null"` and `"aaudio"`; anything else is
    /// `NotFound`. A known backend that is currently unavailable is
    /// still accepted — the engine falls back to what opens and keeps
    /// retrying (`fp-engine/src/engine.rs:456-468`), as on the desktop
    /// when a backend is lost; the settings UI simply offers only the
    /// available ones (`get_available_backends`).
    pub fn set_backend(&self, backend_id: String) -> Result<(), FausteError> {
        self.core
            .set_backend(Some(backend_id.as_str()))
            .map_err(|e| match e {
                SetBackendError::Unknown(id) => {
                    FausteError::NotFound(format!("unknown backend {id:?}"))
                }
                SetBackendError::QueueFull => FausteError::Busy,
            })
    }

    /// Appends the track to the end of the main player's playlist via
    /// `Command::InsertPaths` — the same command the desktop UI sends for
    /// "add files to a playlist" (`fp-app/src/ui/app.rs:623`). A missing or
    /// unreadable file is not rejected here: the model marks its file state
    /// when the source fails, as on the desktop.
    ///
    /// Every metadata field of `track` except `path` is ignored: the
    /// model derives the title from the file stem
    /// (`fp-model/src/track.rs:340`) and every other fact from the file
    /// itself, as on the desktop when files are dropped onto a playlist.
    ///
    /// The insert index is `usize::MAX`: the reducer clamps it to the
    /// playlist length at apply time (`fp-model/src/playlist.rs`,
    /// `insert`), which is what makes "append" race-free — reading the
    /// length here instead would race the conductor's own applies, and
    /// two quick enqueues would land in reverse order.
    pub fn enqueue(&self, track: Track) -> Result<(), FausteError> {
        let handle = self.core.handle();
        let playlist = {
            let state = handle.model.load();
            let player = state
                .players
                .first()
                .ok_or_else(|| FausteError::NotFound("the state has no players".into()))?;
            player.playlist
        };
        if handle.send(Command::InsertPaths {
            playlist,
            index: usize::MAX,
            paths: vec![PathBuf::from(track.path)],
        }) {
            Ok(())
        } else {
            Err(FausteError::Busy)
        }
    }

    /// The main player's queue (`PlayerCore::playlist`), one row per
    /// entry: the id the remove/move commands address, the track's path,
    /// its file-stem title and its duration while known. The list
    /// follows the model: it reflects enqueues, removals and moves once
    /// the conductor has applied them.
    pub fn get_playlist(&self) -> Vec<PlaylistEntry> {
        self.core
            .playlist()
            .into_iter()
            .map(map_playlist_entry)
            .collect()
    }

    /// `Command::RemoveEntry(entry)` — the desktop's context-menu remove
    /// (`fp-app/src/ui/table.rs:993`, which refuses it while the entry
    /// is on air; the model enforces its own rules on apply). A missing
    /// entry is refused up front, exactly like the remote API does
    /// (`fp-remote/src/api.rs:367-370`).
    pub fn remove_entry(&self, entry_id: u64) -> Result<(), FausteError> {
        let handle = self.core.handle();
        let entry = EntryId(entry_id);
        if handle.model.load().playlists.entry(entry).is_none() {
            return Err(FausteError::NotFound(format!("no entry {entry_id}")));
        }
        if handle.send(Command::RemoveEntry(entry)) {
            Ok(())
        } else {
            Err(FausteError::Busy)
        }
    }

    /// `Command::MoveEntry { entry, to, index }` — the desktop's
    /// drag-reorder (`fp-app/src/ui/table.rs:718-722`); `to` is the main
    /// player's playlist, the only one this surface shows. The target
    /// index is clamped to the playlist length, exactly like the remote
    /// API (`fp-remote/src/api.rs:377-381`). The model compensates a
    /// backward same-list move for the removal
    /// (`fp-model/src/playlist.rs`, `move_entry`), so `to_index` is the
    /// position the entry lands at.
    pub fn move_entry(&self, entry_id: u64, to_index: u32) -> Result<(), FausteError> {
        let handle = self.core.handle();
        let entry = EntryId(entry_id);
        let playlist = {
            let state = handle.model.load();
            if state.playlists.entry(entry).is_none() {
                return Err(FausteError::NotFound(format!("no entry {entry_id}")));
            }
            let Some(player) = state.players.first() else {
                return Err(FausteError::NotFound("the state has no players".into()));
            };
            let len = state
                .playlists
                .get(player.playlist)
                .map_or(0, |list| list.entries.len());
            let index = usize::try_from(to_index).unwrap_or(usize::MAX).min(len);
            (player.playlist, index)
        };
        if handle.send(Command::MoveEntry {
            entry,
            to: playlist.0,
            index: playlist.1,
        }) {
            Ok(())
        } else {
            Err(FausteError::Busy)
        }
    }

    /// `Command::SetNext(player, entry)` — the desktop's queue tap. A row
    /// double-click sends exactly this command, ALONE
    /// (`fp-app/src/ui/table.rs:616-626`); only the context menu's "Play
    /// now" pairs it with transport commands (`Stop` while paused +
    /// `SetNext` + `Play`, `fp-app/src/ui/table.rs:889-895`) — a
    /// different gesture this surface deliberately does not compose.
    ///
    /// The model arms the entry as the main player's explicit next and
    /// clears any pending start (`fp-model/src/reducer.rs:676-687`);
    /// the transport is never touched: what is on air plays to its end
    /// and the conductor starts the armed entry at the boundary (the
    /// engine's own `StartNextAt` transition), a stopped player stays
    /// stopped — a later `play` starts the armed entry
    /// (`fp-engine/tests/conductor.rs:773-775`) — and setting the
    /// on-air entry makes it play once more (rule 27a,
    /// `fp-engine/tests/conductor.rs:707`). A missing entry is refused
    /// up front, the same pre-check as `remove_entry` (and the remote
    /// API's plan, `fp-remote/src/api.rs:156-160`).
    pub fn set_next(&self, entry_id: u64) -> Result<(), FausteError> {
        let handle = self.core.handle();
        let entry = EntryId(entry_id);
        if handle.model.load().playlists.entry(entry).is_none() {
            return Err(FausteError::NotFound(format!("no entry {entry_id}")));
        }
        self.send_main(|p| Command::SetNext(p, entry))
    }

    /// `Command::Previous(player)` for the main player — the desktop
    /// transport grid's Previous button
    /// (`fp-app/src/ui/player.rs:743-749`): it crossfades back to the
    /// last entry the player left (R24, `fp-model/src/reducer.rs:563`)
    /// and is a model no-op unless the player is playing. There is no
    /// `Next` command to expose — advancing is the engine's own
    /// transition (`StartNextAt`); queue-jump-ahead is `set_next`
    /// — `Command::SetNext(player, entry)`, the desktop row
    /// activation's exact command
    /// (`fp-app/src/ui/table.rs:616-626`, `fp-remote/src/api.rs:156-160`).
    pub fn previous(&self) -> Result<(), FausteError> {
        self.send_main(Command::Previous)
    }

    /// What the HTTP remote is doing (`PlayerCore::remote_status`):
    /// the record the settings/status UI draws — see `RemoteInfo`.
    pub fn remote_status(&self) -> RemoteInfo {
        let status = self.core.remote_status();
        let (enabled, address) = match status.http {
            ServerStatus::Off => (false, String::new()),
            ServerStatus::Listening(addr) => (true, addr.to_string()),
            ServerStatus::Error(_) => (true, String::new()),
        };
        RemoteInfo { enabled, address }
    }

    /// The snapshot the mobile UI draws: status from the main player's
    /// transport, the current track from the model, the position from the
    /// telemetry (exactly the desktop Bridge's `playback` read,
    /// `fp-app/src/remote.rs:100-113`), duration from the model's track,
    /// volume from the player state (linear gain) and the backend id the
    /// engine reported in use (`live.audio_system_in_use`, mirrored from
    /// `EngineEvent::AudioSystemInUse`; `"null"` until the first report,
    /// matching the engine's last-resort fallback).
    pub fn get_state(&self) -> EngineState {
        let handle = self.core.handle();
        let model = handle.model.load_full();
        let telemetry = handle.telemetry.load();
        let Some(player) = model.players.first() else {
            return EngineState {
                status: "stopped".into(),
                position: 0.0,
                duration: 0.0,
                current_track: None,
                volume: 1.0,
                backend: model
                    .live
                    .audio_system_in_use
                    .clone()
                    .unwrap_or_else(|| "null".into()),
            };
        };
        let status = match player.transport {
            Transport::Stopped => "stopped",
            Transport::Playing => "playing",
            Transport::Paused => "paused",
        };
        let current_track = player
            .current
            .and_then(|entry| model.track_for_entry(entry))
            .map(|t| Track {
                path: t.path.display().to_string(),
                title: t.title.clone(),
                artist: t.artist.clone(),
                duration: t.duration_secs,
                sample_rate: t.format.map(|f| f.sample_rate).unwrap_or(0),
                channels: t
                    .format
                    .map(|f| u16::try_from(f.channels).unwrap_or(0))
                    .unwrap_or(0),
            });
        let position = telemetry
            .players
            .iter()
            .find(|(id, _)| *id == player.id)
            .and_then(|(_, p)| p.position_secs)
            .unwrap_or(0.0);
        EngineState {
            status: status.into(),
            position,
            duration: current_track.as_ref().map(|t| t.duration).unwrap_or(0.0),
            current_track,
            volume: player.volume,
            backend: model
                .live
                .audio_system_in_use
                .clone()
                .unwrap_or_else(|| "null".into()),
        }
    }

    /// The wired backends, with availability probed at call time
    /// (`PlayerCore::backend_availability`): `"null"` is the engine's
    /// always-present fallback; `"aaudio"` opens and closes a throwaway
    /// AAudio stream per call (`src/aaudio_backend.rs`), like the
    /// desktop engine probes when it chooses a backend
    /// (`fp-engine/src/engine.rs:456-468`). Ids and names match what
    /// `set_backend` accepts.
    pub fn get_available_backends(&self) -> Vec<BackendInfo> {
        const WIRED: [(&str, &str); 2] = [("null", "Null output"), ("aaudio", "AAudio")];
        WIRED
            .iter()
            .map(|(id, name)| BackendInfo {
                id: (*id).to_owned(),
                name: (*name).to_owned(),
                available: self
                    .core
                    .backend_availability(id)
                    .is_some_and(|a| a == Availability::Available),
            })
            .collect()
    }
}
