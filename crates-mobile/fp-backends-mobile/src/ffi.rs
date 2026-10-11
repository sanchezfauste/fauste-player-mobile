//! UniFFI surface for the mobile app. Phase 0 inert stubs — the real
//! engine (and `Result`-returning methods with a `FausteError`) arrive
//! in Phase 2.

use std::sync::Arc;

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

#[derive(uniffi::Object)]
pub struct FausteEngine {
    // Inert Phase-0 stub state; wired to fp-engine in Phase 2.
    status: String,
    volume: f32,
    backend: String,
}

#[uniffi::export]
impl FausteEngine {
    #[uniffi::constructor]
    pub fn new(config: EngineConfig) -> Arc<Self> {
        // The paths in `config` are consumed by fp-store in Phase 2.
        let _ = config;
        Arc::new(Self {
            status: "stopped".into(),
            volume: 1.0,
            backend: "null".into(),
        })
    }

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn play(&self) {}

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn pause(&self) {}

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn stop(&self) {}

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn seek(&self, position: f64) {
        let _ = position;
    }

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn set_volume(&self, volume: f32) {
        let _ = volume;
    }

    /// Phase 2 no-op: the transport is not wired yet.
    pub fn set_backend(&self, backend_id: String) {
        let _ = backend_id;
    }

    pub fn get_state(&self) -> EngineState {
        EngineState {
            status: self.status.clone(),
            position: 0.0,
            duration: 0.0,
            current_track: None,
            volume: self.volume,
            backend: self.backend.clone(),
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
