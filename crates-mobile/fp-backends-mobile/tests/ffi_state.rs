//! Host tests for the real UniFFI engine: `FausteEngine` over `PlayerCore`.
//!
//! The host has no AAudio, so the Engine falls back to the Null backend it
//! appends itself; the model still transitions its transport, which is what
//! these tests poll for. Every wait is bounded (2 s at 10 ms): a finished
//! test is the no-hang evidence, and a stuck model fails instead of hanging.
#![cfg(feature = "uniffi")]
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use fp_backends_mobile::ffi::{EngineConfig, FausteEngine, Track};

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

/// Writes a tiny valid WAV (44-byte header, 1000 silent 16-bit mono samples
/// at 44.1 kHz) so `play` really opens and decodes a source.
fn tiny_wav(dir: &std::path::Path) -> PathBuf {
    let path = dir.join("tone.wav");
    let samples: usize = 1000;
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
