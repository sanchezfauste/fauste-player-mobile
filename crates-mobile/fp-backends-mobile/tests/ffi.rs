#[cfg(feature = "uniffi")]
mod tests {
    use fp_backends_mobile::ffi::{BackendInfo, EngineConfig, FausteEngine};

    #[test]
    fn a_new_engine_reports_stopped_at_zero() {
        let engine = FausteEngine::new(EngineConfig {
            sample_rate: 48_000,
            buffer_frames: 512,
            data_dir: std::env::temp_dir().display().to_string(),
            cache_dir: std::env::temp_dir().display().to_string(),
        });
        let state = engine.get_state();
        assert_eq!(state.status, "stopped");
        assert_eq!(state.position, 0.0);
    }

    #[test]
    fn every_backend_info_lists_the_null_fallback() {
        let engine = FausteEngine::new(EngineConfig {
            sample_rate: 48_000,
            buffer_frames: 512,
            data_dir: "/tmp".into(),
            cache_dir: "/tmp".into(),
        });
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
