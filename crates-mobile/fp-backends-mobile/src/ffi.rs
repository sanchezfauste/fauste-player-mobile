//! UniFFI surface for the mobile app: `FausteEngine`, a thin object over
//! `Arc<PlayerCore>` (Phase 2).
//!
//! Commands reach the conductor as the desktop UI sends them
//! (`fp-app/src/ui/player.rs`, `fp-app/src/ui/table.rs`): `Command::Play` /
//! `Pause` / `Stop` / `Seek` / `SetVolume` carry the MAIN player's id, and
//! enqueue is `Command::InsertPaths` appended at the end of the main
//! player's playlist (`fp-app/src/ui/app.rs:623`). State reads mirror the
//! desktop Bridge (`fp-app/src/remote.rs:84-130`): the model snapshot and
//! the telemetry, never a blocking call.
//!
//! Status strings mirror the remote DTO (`fp-remote/src/dto.rs:361-363`):
//! `"playing"` / `"paused"` / `"stopped"`.

use std::path::PathBuf;
use std::sync::Arc;

use fp_model::player::Transport;
use fp_model::{AppState, Command};

use crate::player_core::PlayerCore;

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

    /// Phase 3: backend switching. The desktop switches audio systems
    /// through the settings UI (`config.outputs.backend` via
    /// `Command::UpdateConfig`, applied as `EngineAction::ApplyAudioSystem`);
    /// there is no single "switch backend" command to call here, and the
    /// mobile settings UI that would own this is Phase 3. For now this is a
    /// documented no-op.
    pub fn set_backend(&self, backend_id: String) {
        let _ = backend_id;
    }

    /// Appends the track to the end of the main player's playlist via
    /// `Command::InsertPaths` — the same command the desktop UI sends for
    /// "add files to a playlist" (`fp-app/src/ui/app.rs:623`). A missing or
    /// unreadable file is not rejected here: the model marks its file state
    /// when the source fails, as on the desktop.
    pub fn enqueue(&self, track: Track) -> Result<(), FausteError> {
        let handle = self.core.handle();
        let (playlist, index) = {
            let state = handle.model.load();
            let player = state
                .players
                .first()
                .ok_or_else(|| FausteError::NotFound("the state has no players".into()))?;
            let index = state
                .playlists
                .get(player.playlist)
                .map(|p| p.entries.len())
                .ok_or_else(|| FausteError::NotFound("the main playlist is gone".into()))?;
            (player.playlist, index)
        };
        if handle.send(Command::InsertPaths {
            playlist,
            index,
            paths: vec![PathBuf::from(track.path)],
        }) {
            Ok(())
        } else {
            Err(FausteError::Busy)
        }
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

    pub fn get_available_backends(&self) -> Vec<BackendInfo> {
        vec![
            BackendInfo {
                id: "null".into(),
                name: "Null output".into(),
                available: true,
            },
            BackendInfo {
                id: "aaudio".into(),
                name: "AAudio".into(),
                available: cfg!(target_os = "android"),
            },
        ]
    }
}
