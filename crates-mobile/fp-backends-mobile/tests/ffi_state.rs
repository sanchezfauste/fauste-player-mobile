//! Host tests for the real UniFFI engine: `FausteEngine` over `PlayerCore`.
//!
//! The host has no AAudio, so the Engine falls back to the Null backend it
//! appends itself; the model still transitions its transport, which is what
//! these tests poll for. Every wait is bounded (2 s at 10 ms): a finished
//! test is the no-hang evidence, and a stuck model fails instead of hanging.
#![cfg(feature = "uniffi")]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Write;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use fp_backends_mobile::ffi::{EngineConfig, FausteEngine, FausteError, Track};
use fp_backends_mobile::player_core::PlayerCore;
use fp_remote::server::{RemoteStatus, ServerStatus};

/// A unique, empty directory pair under the system temp dir.
struct TempDirs {
    data: PathBuf,
    cache: PathBuf,
}

impl TempDirs {
    fn new(tag: &str) -> Self {
        let root =
            std::env::temp_dir().join(format!("fp-ffi-state-test-{}-{tag}", std::process::id()));
        let dirs = Self {
            data: root.join("data"),
            cache: root.join("cache"),
        };
        std::fs::create_dir_all(&dirs.data).ok();
        std::fs::create_dir_all(&dirs.cache).ok();
        dirs
    }
}

impl Drop for TempDirs {
    fn drop(&mut self) {
        if let Some(root) = self.data.parent() {
            let _ = std::fs::remove_dir_all(root);
        }
    }
}

/// Writes a tiny valid WAV (44-byte header, `samples` silent 16-bit mono
/// samples at 44.1 kHz) so `play` really opens and decodes a source.
fn tiny_wav_as(dir: &std::path::Path, name: &str, samples: usize) -> PathBuf {
    let path = dir.join(name);
    let data_len = (samples * 2) as u32;
    let mut wav: Vec<u8> = Vec::with_capacity(44 + data_len as usize);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&(36 + data_len).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
    wav.extend_from_slice(&1u16.to_le_bytes()); // mono
    wav.extend_from_slice(&44_100u32.to_le_bytes());
    wav.extend_from_slice(&88_200u32.to_le_bytes()); // byte rate
    wav.extend_from_slice(&2u16.to_le_bytes()); // block align
    wav.extend_from_slice(&16u16.to_le_bytes()); // bits
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_len.to_le_bytes());
    wav.extend_from_slice(&vec![0u8; data_len as usize]);
    std::fs::File::create(&path)
        .and_then(|mut f| f.write_all(&wav))
        .expect("cannot write the test WAV");
    path
}

/// The original one: `tone.wav`, 1000 samples (~23 ms).
fn tiny_wav(dir: &std::path::Path) -> PathBuf {
    tiny_wav_as(dir, "tone.wav", 1000)
}

fn engine(dirs: &TempDirs) -> Arc<FausteEngine> {
    FausteEngine::new(EngineConfig {
        sample_rate: 48_000,
        buffer_frames: 512,
        data_dir: dirs.data.display().to_string(),
        cache_dir: dirs.cache.display().to_string(),
    })
    .expect("core construction must succeed on a Null-backend host")
}

/// Polls `get_state().status` until `want` matches or the 2 s budget ends.
fn wait_status(engine: &FausteEngine, want: &str) -> String {
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let status = engine.get_state().status;
        if status == want || Instant::now() >= deadline {
            return status;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// Polls until `probe` holds or the 2 s budget ends.
fn wait_until(what: &str, mut probe: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(2);
    while !probe() {
        assert!(Instant::now() < deadline, "timeout waiting for {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// The address the engine's HTTP remote is listening on, once it is up.
fn wait_remote_listening(engine: &FausteEngine) -> String {
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let info = engine.remote_status();
        if info.enabled && !info.address.is_empty() {
            return info.address;
        }
        assert!(Instant::now() < deadline, "the remote never listened");
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// The same, one level down: the core's bound address.
fn wait_core_listening(core: &PlayerCore) -> SocketAddr {
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        if let RemoteStatus {
            http: ServerStatus::Listening(addr),
            ..
        } = core.remote_status()
        {
            return addr;
        }
        assert!(Instant::now() < deadline, "the remote never listened");
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// A `Track` with every metadata field empty: the model ignores them all
/// (the enqueue contract).
fn bare_track(path: PathBuf) -> Track {
    Track {
        path: path.display().to_string(),
        title: String::new(),
        artist: String::new(),
        duration: 0.0,
        sample_rate: 0,
        channels: 0,
    }
}

#[test]
fn enqueue_play_pause_seek_stop_and_volume() {
    let dirs = TempDirs::new("transport");
    let engine = engine(&dirs);

    let initial = engine.get_state();
    assert_eq!(initial.status, "stopped", "a fresh main player is stopped");
    assert_eq!(initial.position, 0.0);
    assert!(initial.current_track.is_none());

    let wav = tiny_wav(&dirs.data);
    engine
        .enqueue(Track {
            path: wav.display().to_string(),
            title: String::new(),
            artist: String::new(),
            duration: 0.0,
            sample_rate: 0,
            channels: 0,
        })
        .expect("enqueue");

    engine.play().expect("play");
    assert_eq!(wait_status(&engine, "playing"), "playing");

    engine.pause().expect("pause");
    assert_eq!(wait_status(&engine, "paused"), "paused");

    engine.seek(0.01).expect("seek");
    engine.stop().expect("stop");
    assert_eq!(wait_status(&engine, "stopped"), "stopped");

    // Volume round trip: the model stores the player volume it is told.
    engine.set_volume(0.5).expect("set_volume 0.5");
    let deadline = Instant::now() + Duration::from_secs(2);
    while (engine.get_state().volume - 0.5).abs() > f32::EPSILON {
        assert!(Instant::now() < deadline, "the volume never reached 0.5");
        std::thread::sleep(Duration::from_millis(10));
    }
    engine.set_volume(1.0).expect("set_volume 1.0");
    let deadline = Instant::now() + Duration::from_secs(2);
    while (engine.get_state().volume - 1.0).abs() > f32::EPSILON {
        assert!(
            Instant::now() < deadline,
            "the volume never returned to 1.0"
        );
        std::thread::sleep(Duration::from_millis(10));
    }

    drop(engine);
}

#[test]
fn two_engines_construct_in_sequence() {
    let dirs = TempDirs::new("sequence");
    let first = engine(&dirs);
    drop(first);
    let second = engine(&dirs);
    assert_eq!(second.get_state().status, "stopped");
}

/// Phase-2 deferral, the `with_overrides` behavioral pin: the FFI's
/// `EngineConfig` values override the loaded config at boot and land in
/// the model the conductor serves.
#[test]
fn with_overrides_pins_the_boot_config() {
    let dirs = TempDirs::new("overrides");
    let core = PlayerCore::with_overrides(
        dirs.data.clone(),
        dirs.cache.clone(),
        Some(44_100),
        Some(512),
    )
    .expect("core construction must succeed on a Null-backend host");
    let model = core.handle().model.load();
    assert_eq!(model.config.outputs.sample_rate, 44_100);
    assert_eq!(model.config.outputs.buffer_frames, 512);
}

/// `remote_status`: the core forces the HTTP remote on (loopback,
/// ephemeral port, `PlayerCore::with_overrides`); the read must report
/// it enabled with the address it really bound.
#[test]
fn remote_status_reports_the_ephemeral_loopback_server() {
    let dirs = TempDirs::new("remote");
    let engine = engine(&dirs);
    let address = wait_remote_listening(&engine);
    let (host, port) = address.split_once(':').expect("the address is host:port");
    assert_eq!(host, "127.0.0.1", "loopback only: {address}");
    let port: u16 = port.parse().expect("the port is numeric");
    assert!(
        port > 0,
        "the ephemeral port must be bound, not 0: {address}"
    );
}

/// The port-0 guard, end to end. `set_backend` rides `UpdateConfig`,
/// which the reducer re-validates (`fp-model/src/reducer.rs:1234`); a
/// naive round-trip would clamp the model's ephemeral port 0 to 1024
/// (`fp-model/src/remote.rs:196`) and the remote thread, which follows
/// the model config every 250 ms, would rebind there
/// (`fp-remote/src/server.rs:204-213`) — the server would MOVE. The
/// guarded switch must leave its address untouched.
#[test]
fn set_backend_round_trips_without_moving_the_remote() {
    let dirs = TempDirs::new("set-backend");
    let engine = engine(&dirs);
    let before = wait_remote_listening(&engine);

    assert!(
        matches!(
            engine.set_backend("wasapi".into()),
            Err(FausteError::NotFound(_))
        ),
        "an id the core never wired is NotFound"
    );

    engine
        .set_backend("aaudio".into())
        .expect("queue the aaudio switch");
    engine
        .set_backend("null".into())
        .expect("queue the null switch");

    // Watch past several of the remote thread's 250 ms config polls.
    let deadline = Instant::now() + Duration::from_millis(1500);
    while Instant::now() < deadline {
        let info = engine.remote_status();
        assert_eq!(
            info.address, before,
            "a backend switch must not move the remote server"
        );
        std::thread::sleep(Duration::from_millis(25));
    }
}

/// One level down: the switch lands in the model config, and the port
/// guard normalizes the model's ephemeral `0` to the port the server is
/// really bound to — so later config round-trips never rebind it.
#[test]
fn set_backend_lands_in_the_model_and_normalizes_the_port() {
    let dirs = TempDirs::new("backend-model");
    let core = PlayerCore::new(dirs.data.clone(), dirs.cache.clone()).expect("core construction");
    let addr = wait_core_listening(&core);
    assert_eq!(
        core.handle().model.load().config.remote.http.port,
        0,
        "boot keeps the ephemeral port in the model until a config round-trip"
    );

    core.set_backend(Some("null"))
        .expect("queue the null switch");
    wait_until("the backend switch lands in the model config", || {
        let model = core.handle().model.load();
        model.config.outputs.backend == Some("null".to_owned())
            && model.config.remote.http.port == addr.port()
    });
}

/// `get_playlist`: entries appear with their ids, paths and file-stem
/// titles; the duration stays 0.0 while unknown — nothing measures a
/// track on the mobile core until the services layer lands (Task 2).
#[test]
fn get_playlist_lists_the_queue_with_stem_titles_and_durations() {
    let dirs = TempDirs::new("playlist");
    let engine = engine(&dirs);
    assert!(
        engine.get_playlist().is_empty(),
        "a fresh playlist is empty"
    );

    let wav = tiny_wav_as(&dirs.data, "long.wav", 44_100); // 1 s
    engine.enqueue(bare_track(wav)).expect("enqueue");
    wait_until("the enqueued entry appears in the queue", || {
        engine.get_playlist().len() == 1
    });

    let (id, path, title, duration) = {
        let playlist = engine.get_playlist();
        let row = playlist.first().expect("one row");
        (row.id, row.path.clone(), row.title.clone(), row.duration)
    };
    assert!(id > 0, "entry ids are positive");
    assert!(path.ends_with("long.wav"), "the track's path: {path}");
    assert_eq!(
        title, "long",
        "the model derives the title from the file stem"
    );
    // "Duration when known": on today's mobile core nothing measures the
    // track — the desktop's header pass (`fp-app/src/services.rs:1075`)
    // and the analysis submission both ride the services layer, which is
    // Task 2 — so the queue reports the unknown as 0.0.
    assert_eq!(
        duration, 0.0,
        "the duration is unknown until something measured it"
    );

    engine.play().expect("play");
    assert_eq!(wait_status(&engine, "playing"), "playing");
    // Playing does not fill it either (the engine reports no duration
    // event; the model's `SetDuration` comes from the services layer).
    let (id_playing, duration_playing) = {
        let playlist = engine.get_playlist();
        let row = playlist.first().expect("one row while playing");
        (row.id, row.duration)
    };
    assert_eq!(id_playing, id, "playing keeps the entry");
    assert_eq!(duration_playing, 0.0);

    engine.stop().expect("stop");
    assert_eq!(wait_status(&engine, "stopped"), "stopped");
}

/// `remove_entry` / `move_entry`: the desktop's playlist edits reshape
/// the queue; missing entries are refused up front, as the remote API
/// refuses them (`fp-remote/src/api.rs:367-382`), and a move target past
/// the end is clamped to it (`fp-remote/src/api.rs:381`).
#[test]
fn remove_and_move_reshape_the_queue() {
    let dirs = TempDirs::new("reshape");
    let engine = engine(&dirs);
    for name in ["alpha.wav", "beta.wav", "gamma.wav"] {
        let wav = tiny_wav_as(&dirs.data, name, 1000);
        engine.enqueue(bare_track(wav)).expect("enqueue");
    }
    wait_until("three entries queued", || engine.get_playlist().len() == 3);

    let paths = |engine: &FausteEngine| -> Vec<String> {
        engine
            .get_playlist()
            .iter()
            .map(|row| row.path.clone())
            .collect()
    };
    let id_of = |engine: &FausteEngine, suffix: &str| -> u64 {
        engine
            .get_playlist()
            .iter()
            .find(|row| row.path.ends_with(suffix))
            .expect("the entry is queued")
            .id
    };
    let order = |engine: &FausteEngine| -> Vec<String> {
        paths(engine)
            .iter()
            .map(|p| p.rsplit('/').next().expect("a file name").to_owned())
            .collect()
    };
    assert_eq!(
        order(&engine),
        ["alpha.wav", "beta.wav", "gamma.wav"],
        "the queue follows the enqueue order"
    );

    assert!(
        matches!(engine.remove_entry(999_999), Err(FausteError::NotFound(_))),
        "a missing entry is refused"
    );
    let alpha = id_of(&engine, "alpha.wav");
    engine.remove_entry(alpha).expect("remove alpha");
    wait_until("alpha is gone", || engine.get_playlist().len() == 2);
    assert_eq!(order(&engine), ["beta.wav", "gamma.wav"]);

    let gamma = id_of(&engine, "gamma.wav");
    engine.move_entry(gamma, 0).expect("move gamma to the top");
    wait_until("gamma leads the queue", || {
        order(&engine) == ["gamma.wav", "beta.wav"]
    });

    assert!(
        matches!(engine.move_entry(999_999, 0), Err(FausteError::NotFound(_))),
        "moving a missing entry is refused"
    );
    // A target past the end clamps to it: gamma (at 0) moves to the end.
    engine
        .move_entry(gamma, 99)
        .expect("move with a too-large index");
    wait_until("the clamped move put gamma last", || {
        order(&engine) == ["beta.wav", "gamma.wav"]
    });
}

/// `previous`: the desktop transport grid's Previous button
/// (`fp-app/src/ui/player.rs:743-749`). The command queues; the model
/// applies its own rules (a no-op unless playing, `fp-model/src/reducer.rs:574`).
#[test]
fn previous_is_a_queued_transport_command() {
    let dirs = TempDirs::new("previous");
    let engine = engine(&dirs);
    let wav = tiny_wav_as(&dirs.data, "long.wav", 44_100); // 1 s
    engine.enqueue(bare_track(wav)).expect("enqueue");
    wait_until("the entry is queued", || engine.get_playlist().len() == 1);

    engine
        .previous()
        .expect("previous queues while stopped (a model no-op)");
    assert_eq!(engine.get_state().status, "stopped");

    engine.play().expect("play");
    assert_eq!(wait_status(&engine, "playing"), "playing");
    engine.previous().expect("previous queues while playing");
    // An empty history makes it a model no-op: the player keeps playing.
    assert_eq!(wait_status(&engine, "playing"), "playing");
}

/// `get_available_backends`: the wired set, with availability probed at
/// call time — on a host the AAudio probe itself reports Android-only
/// (`AaudioBackend::availability`, `src/aaudio_backend.rs:45-47`), which
/// a platform `cfg!` stub could not.
#[test]
fn available_backends_probe_the_wired_set_at_call_time() {
    let core_dirs = TempDirs::new("backends-core");
    let core = PlayerCore::new(core_dirs.data.clone(), core_dirs.cache.clone())
        .expect("core construction");
    let null = core.backend_availability("null").expect("null is wired");
    assert!(null == fp_backends::Availability::Available);
    let aaudio = core
        .backend_availability("aaudio")
        .expect("aaudio is wired");
    match aaudio {
        fp_backends::Availability::Unavailable(reason) => {
            assert!(
                reason.contains("Android-only"),
                "the host probe reports AAudio is Android-only: {reason}"
            );
        }
        other => panic!("on a host the AAudio probe must say unavailable: {other:?}"),
    }
    assert!(
        core.backend_availability("wasapi").is_none(),
        "an id the core never wired is unknown"
    );

    let engine_dirs = TempDirs::new("backends-engine");
    let engine = engine(&engine_dirs);
    let list = engine.get_available_backends();
    let ids: Vec<String> = list.iter().map(|b| b.id.clone()).collect();
    assert_eq!(ids, ["null".to_owned(), "aaudio".to_owned()]);
    assert!(
        list.iter().any(|b| b.id == "null" && b.available),
        "the Null fallback is always available"
    );
}
