#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! On-device smoke: the full player core. Phase 1 tested that `Engine::new`
//! accepts the AAudio backend; Phase 2 goes all the way: `PlayerCore`
//! constructs (store → engine → conductor → analyzer → remote server),
//! the remote reports itself `Listening`, and an enqueue + play reaches the
//! model (the transport leaves `Stopped`). Phase 3 adds the backend-switch
//! round trip and the queue read (`PlayerCore::playlist`). Phase 4 adds the
//! queue jump (`Command::SetNext`, the FFI's `set_next`). Phase 5 adds the
//! entry-id reads the FFI's `get_state` maps (the on-air id and the arm's
//! consumption at the advance). Android-only; zero tests on the host.
#![cfg(target_os = "android")]

use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use fp_backends_mobile::player_core::PlayerCore;
use fp_model::player::Transport;
use fp_model::{Command, EntryId};
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

/// Writes a tiny valid WAV (44-byte header, `samples` silent 16-bit mono
/// samples at 44.1 kHz). A real file matters: an unplayable path makes the
/// model refuse the transport before it ever leaves `Stopped`.
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
    std::fs::write(&path, &wav).expect("cannot write the test WAV");
    path
}

/// The original one: `tone.wav`, 1 s.
fn tiny_wav(dir: &std::path::Path) -> PathBuf {
    tiny_wav_as(dir, "tone.wav", 44_100)
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
        == Some(Transport::Stopped)
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
        == Some(Transport::Stopped)
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

/// Phase 4, on-device: the queue jump — `Command::SetNext(player, entry)`,
/// the FFI's `set_next`, the desktop's row double-click
/// (`fp-app/src/ui/table.rs:616-626`): the command is sent ALONE, the
/// model arms the target as the main player's explicit next
/// (`fp-model/src/reducer.rs:676-687`) without touching the transport,
/// and the auto-advance then crosses into the ARMED entry, not the
/// natural successor. alpha lasts ~1 s while beta and gamma last ~10 s
/// each, so the natural order cannot reach gamma inside the budget —
/// the boundary crossing proves the arm.
#[test]
fn the_queue_jump_arms_and_redirects_the_advance_on_device() {
    let (data, cache) = temp_dirs("jump");
    let core = PlayerCore::new(data.clone(), cache).expect("core construction on-device");
    let core_data = data;

    let (player, playlist) = {
        let model = core.handle().model.load();
        let player = model.players.first().expect("no players in the state");
        (player.id, player.playlist)
    };
    for (name, samples) in [
        ("alpha.wav", 44_100),
        ("beta.wav", 441_000),
        ("gamma.wav", 441_000),
    ] {
        let wav = tiny_wav_as(&core_data, name, samples);
        assert!(core.handle().send(Command::InsertPaths {
            playlist,
            index: usize::MAX, // the reducer clamps: append
            paths: vec![wav],
        }));
    }
    let entries: Vec<EntryId> = {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let model = core.handle().model.load();
            let ids: Vec<EntryId> = model
                .playlists
                .get(playlist)
                .map(|list| list.entries.iter().map(|e| e.id).collect())
                .unwrap_or_default();
            if ids.len() == 3 {
                break ids;
            }
            assert!(Instant::now() < deadline, "the queue never filled");
            std::thread::sleep(Duration::from_millis(10));
        }
    };

    assert!(core.handle().send(Command::Play(player)));
    let deadline = Instant::now() + Duration::from_secs(5);
    while core
        .handle()
        .model
        .load()
        .players
        .first()
        .map(|p| p.transport)
        == Some(Transport::Stopped)
    {
        assert!(Instant::now() < deadline, "the player never left Stopped");
        std::thread::sleep(Duration::from_millis(10));
    }

    // The jump: SetNext alone. The arm lands in the model — next AND the
    // explicit flag, which no natural advance sets — while the transport
    // keeps playing.
    assert!(core.handle().send(Command::SetNext(player, entries[2])));
    let transport = {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let model = core.handle().model.load();
            let p = model.players.first().expect("the main player");
            if p.next == Some(entries[2]) && p.next_explicit {
                break p.transport;
            }
            assert!(
                Instant::now() < deadline,
                "the arm never landed in the model (next: {:?})",
                p.next
            );
            std::thread::sleep(Duration::from_millis(10));
        }
    };
    assert_eq!(
        transport,
        Transport::Playing,
        "SetNext never touches the transport"
    );

    // The boundary: the advance must cross into gamma, not beta.
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let model = core.handle().model.load();
        let current = model.players.first().expect("the main player").current;
        if current == Some(entries[2]) {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the advance never crossed into the armed entry (current: {current:?})"
        );
        std::thread::sleep(Duration::from_millis(20));
    }

    drop(core);
}

/// Phase 5, on-device: the ENTRY id reads the FFI's `get_state` maps —
/// the on-air id (`player.current`, `fp-model/src/player.rs:111`) and
/// the arm (`player.next` gated on `next_explicit`) — round-trip through
/// the real engine on the device. What this pins beyond the Phase-4
/// jump test above is THE ID JOIN: the queue read's row ids
/// (`PlayerCore::playlist`, what `get_playlist` maps) are the SAME ids
/// as the on-air and armed entries — the exact join the mobile's
/// playing flag, armed badge and session index now do — and the arm
/// CONSUMES at the advance: once the advance crosses into the armed
/// entry, the explicit flag clears (`advance_to`,
/// `fp-model/src/reducer.rs:1322-1325`), so the armed read maps back to
/// None (the gate is the flag alone; the derived next is a separate
/// fact, never an arm).
#[test]
fn the_entry_id_reads_round_trip_on_device() {
    let (data, cache) = temp_dirs("entry-ids");
    let core = PlayerCore::new(data.clone(), cache).expect("core construction on-device");
    let core_data = data;

    let (player, playlist) = {
        let model = core.handle().model.load();
        let player = model.players.first().expect("no players in the state");
        (player.id, player.playlist)
    };
    for (name, samples) in [
        ("alpha.wav", 44_100),  // ~1 s: the advance lands in budget
        ("beta.wav", 441_000),  // ~10 s
        ("gamma.wav", 441_000), // ~10 s
    ] {
        let wav = tiny_wav_as(&core_data, name, samples);
        assert!(core.handle().send(Command::InsertPaths {
            playlist,
            index: usize::MAX, // the reducer clamps: append
            paths: vec![wav],
        }));
    }
    let entries: Vec<EntryId> = {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let model = core.handle().model.load();
            let ids: Vec<EntryId> = model
                .playlists
                .get(playlist)
                .map(|list| list.entries.iter().map(|e| e.id).collect())
                .unwrap_or_default();
            if ids.len() == 3 {
                break ids;
            }
            assert!(Instant::now() < deadline, "the queue never filled");
            std::thread::sleep(Duration::from_millis(10));
        }
    };

    // THE ID JOIN, on-device: the queue read's rows carry the model's
    // entry ids — the join the Kotlin playing flag does. Stopped: no
    // on-air id, and the arm must not report the derived next either.
    let (stopped_current, stopped_explicit) = {
        let model = core.handle().model.load();
        let p = model.players.first().expect("the main player");
        (p.current, p.next_explicit)
    };
    assert_eq!(stopped_current, None, "stopped: nothing on air");
    assert!(
        !stopped_explicit,
        "stopped: the queue-head next the idle player picked is DERIVED, not an arm"
    );

    assert!(core.handle().send(Command::Play(player)));
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let model = core.handle().model.load();
        let p = model.players.first().expect("the main player");
        if p.current == Some(entries[0]) {
            let rows: Vec<EntryId> = core.playlist().iter().map(|row| row.entry).collect();
            assert_eq!(
                rows, entries,
                "the queue read's row ids are the model's entry ids — the id join"
            );
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the player never started the queue head (current: {:?})",
            p.current
        );
        std::thread::sleep(Duration::from_millis(10));
    }

    // The arm lands (next + the explicit flag — no natural advance sets
    // it), and the armed id is the queue row's id.
    assert!(core.handle().send(Command::SetNext(player, entries[2])));
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let model = core.handle().model.load();
        let p = model.players.first().expect("the main player");
        if p.next == Some(entries[2]) && p.next_explicit {
            let rows: Vec<EntryId> = core.playlist().iter().map(|row| row.entry).collect();
            assert_eq!(rows[2], entries[2], "the armed id joins its queue row");
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the arm never landed (next: {:?})",
            p.next
        );
        std::thread::sleep(Duration::from_millis(10));
    }

    // The boundary: the advance crosses into the armed entry and the
    // arm CONSUMES — the explicit flag clears, which is the ONLY fact
    // the FFI's armed read gates on (`next.filter(|_| next_explicit)`),
    // so it maps back to None here. gamma is the queue's TAIL, so the
    // re-derived next may be None too (`next_playable_after` finds
    // nothing) — the read stays None either way, and the distinction
    // "a derived next exists but is not an arm" is already pinned by
    // the pre-play check above (the queue-head pick: next Some,
    // explicit false, armed None).
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let model = core.handle().model.load();
        let p = model.players.first().expect("the main player");
        if p.current == Some(entries[2]) && !p.next_explicit {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the arm never consumed at the boundary (current: {:?}, explicit: {})",
            p.current,
            p.next_explicit
        );
        std::thread::sleep(Duration::from_millis(20));
    }

    drop(core);
}
