//! Host tests for `PlayerCore` construction and lifetime.
//!
//! The host has no AAudio, so the Engine falls back to the Null backend it
//! appends itself; construction and state transitions therefore work here.
//! No-hang evidence: these tests simply run to completion — if the conductor
//! or analyzer threads failed to stop on drop, the test process would hang
//! and the test harness would time out. There is no explicit timeout because
//! a finished test IS the evidence (see the plan, Task 1, step 1).
//!
//! The Phase-5 persistence tests (Task 3b) pin the save half of the store
//! contract: the core autosaves the session mutation-debounced (the desktop
//! services layer's `autosave`, `fp-app/src/services.rs:1274-1303`, is the
//! authority) and flushes a final save on drop, so a fresh core on the same
//! directories loads the queue the user built — the restart story's truth.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use fp_backends_mobile::player_core::PlayerCore;
use fp_model::Command;
use fp_model::player::Transport;
use fp_store::AppPaths;

/// A unique, empty directory pair under the system temp dir.
struct TempDirs {
    data: PathBuf,
    cache: PathBuf,
}

impl TempDirs {
    fn new(tag: &str) -> Self {
        let root =
            std::env::temp_dir().join(format!("fp-player-core-test-{}-{tag}", std::process::id()));
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

#[test]
fn construction_succeeds_and_exposes_a_conductor_handle() {
    let dirs = TempDirs::new("construct");
    let core = PlayerCore::new(dirs.data.clone(), dirs.cache.clone())
        .expect("construction should succeed on a Null-backend host");
    let handle: &Arc<_> = core.handle();
    let _ = handle;
}

#[test]
fn two_cores_construct_and_drop_in_sequence() {
    let dirs = TempDirs::new("sequence");
    let first = PlayerCore::new(dirs.data.clone(), dirs.cache.clone()).expect("first construction");
    drop(first);
    let second = PlayerCore::new(dirs.data.clone(), dirs.cache.clone())
        .expect("second construction after a clean drop");
    drop(second);
}

#[test]
fn a_second_core_constructs_while_the_first_is_dropped_on_another_thread() {
    let dirs = TempDirs::new("parallel");
    let first = PlayerCore::new(dirs.data.clone(), dirs.cache.clone()).expect("first construction");
    std::thread::scope(|s| {
        s.spawn(move || drop(first));
        let second = PlayerCore::new(dirs.data.clone(), dirs.cache.clone())
            .expect("construction racing another core's drop");
        s.spawn(move || drop(second));
    });
}

/// The store paths the core writes, built exactly the way
/// `PlayerCore::with_overrides` builds them (the module's documented
/// layout: config and logs inside `data_dir`, the state files directly
/// in it — `fp-store/src/paths.rs`).
fn store_paths(data: &Path) -> AppPaths {
    AppPaths {
        config_dir: data.join("config"),
        log_dir: data.join("logs"),
        data_dir: data.to_path_buf(),
        cache_dir: data.join("cache"),
    }
}

/// A tiny valid WAV (44-byte header, `samples` silent 16-bit mono samples
/// at 44.1 kHz) — the engine_smoke suite's shape: a real file matters
/// because the model refuses transports for unplayable paths.
fn tiny_wav(dir: &Path, name: &str, samples: usize) -> PathBuf {
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

/// The main player's playlist id, read from the model.
fn main_playlist(core: &PlayerCore) -> fp_model::PlaylistId {
    let model = core.handle().model.load();
    model
        .players
        .first()
        .expect("a player in the state")
        .playlist
}

/// Polls `cond` until it holds, bounded by `secs` (the suite's established
/// bounded-wait pattern: slow CI runners may take well past the nominal
/// timing, but a hang is still caught).
fn wait_for(what: &str, secs: u64, mut cond: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(secs);
    while !cond() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

/// RED-first pin of Task 3b: an enqueued queue must reach the store files
/// (mutation-debounced, as the desktop's services layer saves) and a FRESH
/// core on the same directories must load it back — the round trip that
/// makes a restart keep the user's queue instead of re-attaching to the
/// defaults.
#[test]
fn an_enqueued_queue_persists_across_a_core_relaunch() {
    let dirs = TempDirs::new("persist");
    let wav = tiny_wav(&dirs.cache, "tone.wav", 44_100);
    let core = PlayerCore::new(dirs.data.clone(), dirs.cache.clone())
        .expect("construction on a Null-backend host");
    let playlist = main_playlist(&core);
    assert!(core.handle().send(Command::InsertPaths {
        playlist,
        index: usize::MAX, // the reducer clamps: append
        paths: vec![wav],
    }));

    // Past the ~1 s debounce the store files must reflect the queue: the
    // playlists file carries the track (its path), the session file the
    // player's binding to that playlist.
    let paths = store_paths(&dirs.data);
    wait_for("the store to persist the queue", 5, || {
        std::fs::read_to_string(paths.playlists_file()).is_ok_and(|text| text.contains("tone.wav"))
            && paths.session_file().exists()
    });

    // The round trip: a fresh core on the same directories loads the queue.
    drop(core);
    let fresh = PlayerCore::new(dirs.data.clone(), dirs.cache.clone())
        .expect("relaunch on the persisted state");
    let queue = fresh.playlist();
    assert_eq!(queue.len(), 1, "the relaunched core must load the queue");
    let row = queue.first().expect("one row");
    assert!(
        row.path.ends_with("tone.wav"),
        "the persisted track's path: {}",
        row.path.display()
    );
    assert_eq!(row.title, "tone", "the file-stem title survives");
    // Never played, so nothing to resume: a restore without a current
    // entry comes back Stopped (`fp-model/src/session.rs:297-301` — a
    // current entry is what makes a restore come back Paused).
    let transport = fresh
        .handle()
        .model
        .load()
        .players
        .first()
        .map(|p| p.transport);
    assert_eq!(
        transport,
        Some(Transport::Stopped),
        "a never-played queue restores Stopped, nothing on air"
    );
}

/// The final save on Drop must flush a queue that never reached the
/// debounce: a debounce far beyond the test's life means NO quiet save can
/// fire, so the only possible writer is the Drop flush — the desktop's
/// unconditional shutdown save (`fp-app/src/services.rs:635-639`) whose
/// order this port mirrors (the save thread joins before the conductor
/// stops, `fp-app/src/main.rs:210-217`).
#[test]
fn dropping_the_core_flushes_a_final_save_within_the_debounce() {
    let dirs = TempDirs::new("final-save");
    let wav = tiny_wav(&dirs.cache, "tone.wav", 44_100);
    let core = PlayerCore::with_save_timing(
        dirs.data.clone(),
        dirs.cache.clone(),
        Duration::from_secs(600),
    )
    .expect("construction with a debounce no quiet save can reach");
    let playlist = main_playlist(&core);
    assert!(core.handle().send(Command::InsertPaths {
        playlist,
        index: usize::MAX, // the reducer clamps: append
        paths: vec![wav],
    }));
    wait_for("the model to hold the entry", 5, || {
        core.playlist().len() == 1
    });
    let before = store_paths(&dirs.data);
    assert!(
        !before.playlists_file().exists(),
        "no quiet save may fire inside the debounce"
    );
    drop(core);
    let text = std::fs::read_to_string(before.playlists_file())
        .expect("the Drop flush must write the playlists file");
    assert!(
        text.contains("tone.wav"),
        "the flushed queue names the track"
    );
    let fresh = PlayerCore::new(dirs.data.clone(), dirs.cache.clone())
        .expect("relaunch on the flushed state");
    assert_eq!(
        fresh.playlist().len(),
        1,
        "a fresh core sees the queue the Drop flush wrote"
    );
}

/// The while-playing interval: during steady playback, with NO model
/// mutation (positions live in the engine telemetry, not the model), the
/// session file must keep advancing at the injected interval — the
/// desktop's `playing && session_due` branch
/// (`fp-app/src/services.rs:1292-1302`). The complement is pinned first:
/// once the enqueue+play mutations' quiet saves have settled, an IDLE
/// core must not write again.
#[test]
fn while_playing_the_session_saves_at_the_injected_interval() {
    let dirs = TempDirs::new("playing-interval");
    // 10 s of audio: playback must outlive the test's observation window.
    let wav = tiny_wav(&dirs.cache, "long.wav", 441_000);
    let debounce = Duration::from_millis(60);
    let core = PlayerCore::with_save_timing(dirs.data.clone(), dirs.cache.clone(), debounce)
        .expect("construction with the injected save timing");
    let playlist = main_playlist(&core);
    assert!(core.handle().send(Command::InsertPaths {
        playlist,
        index: usize::MAX, // the reducer clamps: append
        paths: vec![wav],
    }));
    wait_for("the model to hold the entry", 5, || {
        core.playlist().len() == 1
    });
    let player = {
        let model = core.handle().model.load();
        model.players.first().expect("the main player").id
    };
    assert!(core.handle().send(Command::Play(player)));
    wait_for("the transport to leave Stopped", 5, || {
        core.handle()
            .model
            .load()
            .players
            .first()
            .is_some_and(|p| p.transport != fp_model::player::Transport::Stopped)
    });
    let session = store_paths(&dirs.data).session_file();
    // Let the enqueue+play mutations' quiet saves settle: each dirtied the
    // model, each saves within one debounce, and nothing may mutate after.
    std::thread::sleep(debounce * 4);
    let settled = mtime(&session).expect("session.json written by the quiet saves");

    // The playing branch: at least one more write within a few intervals,
    // though nothing mutated — only `playing && session_due` can write.
    let deadline = Instant::now() + debounce * 8;
    loop {
        if mtime(&session).is_some_and(|at| at > settled) {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the playing save never rewrote session.json"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
    drop(core);
}

/// The complement of the debounce: a SETTLED core writes nothing. At boot
/// the engine reports its placements into the live layer over the first
/// ticks, which legitimately dirties the model once — the desktop saves
/// that too (`saved_version` starts at 0, `fp-app/src/services.rs:492`,
/// so its services thread writes the store within its first second as
/// well). Past that one save there must be silence until the final save
/// on Drop, which is unconditional (the desktop's shutdown save,
/// `fp-app/src/services.rs:635-639`).
#[test]
fn a_settled_core_writes_nothing_until_the_final_save() {
    let dirs = TempDirs::new("idle");
    let debounce = Duration::from_millis(60);
    let core = PlayerCore::with_save_timing(dirs.data.clone(), dirs.cache.clone(), debounce)
        .expect("construction with the injected save timing");
    // Whatever boot changed has been saved by now (several debounces past
    // any quiet save).
    std::thread::sleep(debounce * 8);
    let session = store_paths(&dirs.data).session_file();
    let before = mtime(&session);
    // The silence: several more debounces, no writes at all.
    std::thread::sleep(debounce * 8);
    assert_eq!(
        mtime(&session),
        before,
        "a settled core must not write the store again"
    );
    drop(core);
    let after = mtime(&session).expect("the final save flushed at Drop");
    assert!(
        before.is_none_or(|at| after > at),
        "the Drop flush must be a fresh write (before {before:?}, after {after:?})"
    );
}

/// A file's modification time, if the file exists.
fn mtime(path: &Path) -> Option<std::time::SystemTime> {
    std::fs::metadata(path).ok()?.modified().ok()
}
