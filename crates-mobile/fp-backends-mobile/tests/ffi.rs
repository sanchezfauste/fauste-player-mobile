#![cfg(feature = "uniffi")]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

mod tests {
    use fp_backends_mobile::ffi::{BackendInfo, EngineConfig, FausteEngine};

    // The Phase-0 tests asserted the inert stub; Phase 2 replaced the stub
    // with the real `PlayerCore`-backed engine. The assertions are unchanged
    // (`stopped` is the remote DTO string of the model's initial
    // `Transport::Stopped`, fp-remote/src/dto.rs:361); the constructor is
    // now fallible because core construction is (thread spawns, the remote
    // bind), hence the `expect`, and each test gets a private temp dir pair
    // because the core holds its store under `data_dir`.
    fn engine(tag: &str) -> std::sync::Arc<FausteEngine> {
        let root = std::env::temp_dir().join(format!("fp-ffi-test-{}-{tag}", std::process::id()));
        let data = root.join("data");
        let cache = root.join("cache");
        std::fs::create_dir_all(&data).ok();
        std::fs::create_dir_all(&cache).ok();
        FausteEngine::new(EngineConfig {
            sample_rate: 48_000,
            buffer_frames: 512,
            data_dir: data.display().to_string(),
            cache_dir: cache.display().to_string(),
        })
        .expect("core construction must succeed on a Null-backend host")
    }

    #[test]
    fn a_new_engine_reports_stopped_at_zero() {
        let engine = engine("stopped");
        let state = engine.get_state();
        assert_eq!(state.status, "stopped");
        assert_eq!(state.position, 0.0);
    }

    #[test]
    fn every_backend_info_lists_the_null_fallback() {
        let engine = engine("backends");
        let ids: Vec<String> = engine
            .get_available_backends()
            .into_iter()
            .map(|b: BackendInfo| b.id)
            .collect();
        assert!(
            ids.iter().any(|id| id == "null"),
            "the null fallback must always be listed"
        );
    }
}
