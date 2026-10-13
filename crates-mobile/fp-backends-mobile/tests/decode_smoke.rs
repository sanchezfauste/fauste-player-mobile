//! Decode smoke: `fp-decode` must read a real file wherever the app ships,
//! so this test has no cfg gate — it runs on the host AND, pushed by
//! `scripts/emulator-test.sh`, on Android. It writes a small valid WAV
//! (hand-built RIFF header + non-silent 16-bit samples, mirroring
//! `tests/ffi_state.rs`), probes it, decodes it through `FileDecoder`, and
//! asserts the probe result, the decoded sample count and non-silence.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::Write;
use std::path::PathBuf;

use fp_decode::{FileDecoder, Kind, probe};

/// A unique temp directory for this process's WAV.
fn temp_dir() -> PathBuf {
    let dir = std::env::temp_dir().join(format!("fp-decode-smoke-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("cannot create the temp dir");
    dir
}

/// Writes a small valid WAV at 44.1 kHz mono, `frames` non-silent samples
/// long, and returns its path plus the exact frame count written.
fn wav(dir: &std::path::Path) -> (PathBuf, usize) {
    let path = dir.join("tone.wav");
    let frames: usize = 480;
    let data_len = (frames * 2) as u32;
    let mut bytes: Vec<u8> = Vec::with_capacity(44 + data_len as usize);
    bytes.extend_from_slice(b"RIFF");
    bytes.extend_from_slice(&(36 + data_len).to_le_bytes());
    bytes.extend_from_slice(b"WAVEfmt ");
    bytes.extend_from_slice(&16u32.to_le_bytes());
    bytes.extend_from_slice(&1u16.to_le_bytes()); // PCM
    bytes.extend_from_slice(&1u16.to_le_bytes()); // mono
    bytes.extend_from_slice(&44_100u32.to_le_bytes());
    bytes.extend_from_slice(&88_200u32.to_le_bytes()); // byte rate
    bytes.extend_from_slice(&2u16.to_le_bytes()); // block align
    bytes.extend_from_slice(&16u16.to_le_bytes()); // bits
    bytes.extend_from_slice(b"data");
    bytes.extend_from_slice(&data_len.to_le_bytes());
    // A saw ramp: anything but silence, far from clipping.
    for i in 0..frames as i32 {
        let s = ((i % 96) - 48) * 256;
        bytes.extend_from_slice(&(s as i16).to_le_bytes());
    }
    std::fs::File::create(&path)
        .and_then(|mut f| f.write_all(&bytes))
        .expect("cannot write the test WAV");
    (path, frames)
}

#[test]
fn wav_probes_and_decodes_non_silent() {
    let dir = temp_dir();
    let (path, frames) = wav(&dir);

    let head = std::fs::read(&path).expect("read back the WAV");
    assert_eq!(
        probe(head.get(..16).unwrap_or(&head), Some("wav")),
        Kind::Symphonia,
        "a RIFF/WAVE file decodes through the symphonia backend"
    );

    let mut decoder = FileDecoder::open(&path).expect("FileDecoder::open a valid WAV");
    assert_eq!(decoder.sample_rate(), 44_100);
    assert_eq!(decoder.channels(), 1, "the file is mono before the decode");

    // `next_block` yields interleaved stereo (mono duplicated); count the
    // frames it hands out.
    let mut out: Vec<f32> = Vec::new();
    let mut decoded_frames = 0usize;
    let mut peak = 0.0f32;
    loop {
        out.clear();
        let more = decoder.next_block(&mut out).expect("next_block");
        assert!(!out.is_empty() || !more, "an empty block means done");
        decoded_frames += out.len() / 2;
        for &s in &out {
            peak = peak.max(s.abs());
        }
        if !more {
            break;
        }
    }

    assert_eq!(
        decoded_frames, frames,
        "every written frame comes back (mono duplicated to stereo)"
    );
    assert!(peak > 0.01, "decoded audio is non-silent (peak {peak})");

    let _ = std::fs::remove_dir_all(&dir);
}
