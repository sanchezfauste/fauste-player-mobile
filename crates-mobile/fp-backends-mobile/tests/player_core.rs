//! Host tests for `PlayerCore` construction and lifetime.
//!
//! The host has no AAudio, so the Engine falls back to the Null backend it
//! appends itself; construction and state transitions therefore work here.
//! No-hang evidence: these tests simply run to completion — if the conductor
//! or analyzer threads failed to stop on drop, the test process would hang
//! and the test harness would time out. There is no explicit timeout because
//! a finished test IS the evidence (see the plan, Task 1, step 1).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::path::PathBuf;
use std::sync::Arc;

use fp_backends_mobile::player_core::PlayerCore;

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
