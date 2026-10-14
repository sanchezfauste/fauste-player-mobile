#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! On-device smoke: the full player core. Phase 1 tested that `Engine::new`
//! accepts the AAudio backend; Phase 2 goes all the way: `PlayerCore`
//! constructs (store → engine → conductor → analyzer → remote server),
//! the remote reports itself `Listening`, and an enqueue + play reaches the
//! model (the transport leaves `Stopped`). Phase 3 adds the backend-switch
//! round trip and the queue read (`PlayerCore::playlist`). Android-only;
//! zero tests on the host.
#![cfg(target_os = "android")]

use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use fp_backends_mobile::player_core::PlayerCore;
use fp_model::Command;
use fp_remote::server::RemoteStatus;
use fp_remote::server::ServerStatus;

fn temp_dirs(tag: &str) -> (PathBuf, PathBuf) {
    let root = std::env::temp_dir().join(format!("fp-smoke-{}-{tag}", std::process::id()));
    assert!(
        root.starts_with(std::env::temp_dir()),
        "refusing to remove outside the temp dir"
    );
    let _ = std::fs::remove_dir_all(&root); // stale state from a previous run
    let data = root.join("data");
    let cache = root.join("cache");
    std::fs::create_dir_all(&data).expect("data dir");
    std::fs::create_dir_all(&cache).expect("cache dir");
    (data, cache)
}

/// Writes a tiny valid WAV (44-byte header, 1 s of silent 16-bit mono at
/// 44.1 kHz). A real file matters: an unplayable path makes the model refuse
/// the transport before it ever leaves `Stopped`.
fn tiny_wav(dir: &std::path::Path) -> PathBuf {
    let path = dir.join("tone.wav");
    let data_len = (44_100 * 2) as u32;
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
    std::fs::write(&path, &wav).expect("cannot write the test WAV");
    path
}

/// Polls until the core's HTTP remote is listening (bounded at 5 s).
fn wait_http_listening(core: &PlayerCore) -> SocketAddr {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let RemoteStatus {
            http: ServerStatus::Listening(addr),
            ..
        } = core.remote_status()
        {
            return addr;
        }
        assert!(Instant::now() < deadline, "the remote never listened");
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn the_full_core_serves_the_remote_and_plays() {
    let (data, cache) = temp_dirs("core");
    let core = PlayerCore::new(data.clone(), cache).expect("core construction on-device");
    let core_data = data;

    // The remote (loopback, ephemeral port) comes up shortly after spawn.
    let listening = wait_http_listening(&core);
    assert!(listening.ip().is_loopback(), "loopback only: {listening}");

    // Enqueue on the main player's playlist + play; the model must follow.
    let (player, playlist) = {
        let model = core.handle().model.load();
        let player = model.players.first().expect("no players in the state");
        (player.id, player.playlist)
    };
    // Enqueue a real (silent) WAV on the main player's playlist + play; the
    // transport must leave Stopped.
    let wav = tiny_wav(&core_data);
    assert!(core.handle().send(Command::InsertPaths {
        playlist,
        index: 0,
        paths: vec![wav],
    }));
    assert!(core.handle().send(Command::Play(player)));

    let deadline = Instant::now() + Duration::from_secs(2);
    while core
        .handle()
        .model
        .load()
        .players
        .first()
        .map(|p| p.transport)
        == Some(fp_model::player::Transport::Stopped)
    {
        assert!(Instant::now() < deadline, "the player never left Stopped");
        std::thread::sleep(Duration::from_millis(10));
    }

    drop(core);
}

/// Phase 3, on-device: the backend switch round trips through the real
/// engine — the AAudio the emulator runs and the Null fallback — and the
/// remote server must not move while the config round-trips (the port
/// guard, `PlayerCore::set_backend`). Nothing plays, so the engine
/// applies each switch as soon as its paths are quiet (L11).
#[test]
fn the_backend_switch_round_trips_on_device() {
    let (data, cache) = temp_dirs("backend");
    let core = PlayerCore::new(data, cache).expect("core construction on-device");

    let before = wait_http_listening(&core);
    let in_use = || core.handle().model.load().live.audio_system_in_use.clone();
    let configured = || core.handle().model.load().config.outputs.backend.clone();

    core.set_backend(Some("null"))
        .expect("queue the null switch");
    let deadline = Instant::now() + Duration::from_secs(5);
    while in_use().as_deref() != Some("null") {
        assert!(
            Instant::now() < deadline,
            "the engine never reported the Null backend in use"
        );
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(
        configured(),
        Some("null".to_owned()),
        "the model config carries the selection"
    );

    core.set_backend(Some("aaudio"))
        .expect("queue the aaudio switch");
    let deadline = Instant::now() + Duration::from_secs(5);
    while in_use().as_deref() != Some("aaudio") {
        assert!(
            Instant::now() < deadline,
            "the engine never reported AAudio in use"
        );
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(
        configured(),
        Some("aaudio".to_owned()),
        "the model config carries the selection"
    );

    // The port guard: the address the app's client holds never changed.
    assert_eq!(
        core.remote_status().http,
        ServerStatus::Listening(before),
        "the backend round must not move the remote server"
    );
    drop(core);
}

/// Phase 3, on-device: the queue read (`PlayerCore::playlist`, what the
/// FFI's `get_playlist` maps) lists the enqueued track with its
/// file-stem title, and the remote stays up with a non-empty address
/// while it plays.
#[test]
fn the_queue_read_lists_the_enqueued_track_on_device() {
    let (data, cache) = temp_dirs("queue");
    let core = PlayerCore::new(data.clone(), cache).expect("core construction on-device");
    let core_data = data;

    let listening = wait_http_listening(&core);
    assert!(!listening.to_string().is_empty());

    let (player, playlist) = {
        let model = core.handle().model.load();
        let player = model.players.first().expect("no players in the state");
        (player.id, player.playlist)
    };
    let wav = tiny_wav(&core_data);
    assert!(core.handle().send(Command::InsertPaths {
        playlist,
        index: usize::MAX, // the reducer clamps: append
        paths: vec![wav],
    }));

    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let queue = core.playlist();
        if queue.len() == 1 {
            let row = queue.first().expect("one row");
            assert!(
                row.path.ends_with("tone.wav"),
                "the path: {}",
                row.path.display()
            );
            assert_eq!(row.title, "tone", "the file-stem title");
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the queue never showed the entry"
        );
        std::thread::sleep(Duration::from_millis(10));
    }

    // Play: the model must follow, and the remote must keep its address.
    assert!(core.handle().send(Command::Play(player)));
    let deadline = Instant::now() + Duration::from_secs(2);
    while core
        .handle()
        .model
        .load()
        .players
        .first()
        .map(|p| p.transport)
        == Some(fp_model::player::Transport::Stopped)
    {
        assert!(Instant::now() < deadline, "the player never left Stopped");
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(
        core.remote_status().http,
        ServerStatus::Listening(listening),
        "playing must not move the remote server"
    );
    drop(core);
}
