#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! The desktop `Engine` accepts the AAudio backend — the Phase 1 milestone
//! "Integration with fp-engine::Engine". Android-only; zero tests on the
//! host. Mirrors how `fp-app/src/main.rs` wires `system_backends()` (here:
//! the single AAudio backend) plus the null fallback into `Engine::new`.
#![cfg(target_os = "android")]

use std::sync::Arc;

use fp_backends::AudioBackend;
use fp_backends_mobile::AaudioBackend;
use fp_engine::engine::{Engine, EngineSettings};
use fp_engine::worker::file_opener;
use fp_model::Config;

#[test]
fn the_engine_runs_with_the_aaudio_backend() {
    let backends: Vec<Arc<dyn AudioBackend>> = vec![Arc::new(AaudioBackend::new())];
    let engine = Engine::new(
        backends,
        EngineSettings::from_config(&Config::default()),
        file_opener(),
    );
    // The engine guarantees a backend is in use (the null backend is the
    // last-resort fallback): the id is always non-empty. On a device with a
    // working AAudio that id is "aaudio".
    assert!(!engine.backend_in_use().is_empty());
    drop(engine);
}
