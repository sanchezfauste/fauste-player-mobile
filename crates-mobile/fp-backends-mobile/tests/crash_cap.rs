#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
//! The panic hook (Phase 5): what a deliberate, contained panic produces —
//! a report file in the store's log dir, capped per run, the cap live,
//! nothing deleted — and that the install is ONCE per process (the FFI
//! constructor may be called repeatedly).
//!
//! Its own test binary, exactly like the desktop's crash suites
//! (`fp-app/tests/crash_cap.rs`, `crash_cap_live.rs`): a panic hook is
//! global to the process, so the binary owns exactly ONE test that
//! touches it — no parallel-test pollution. The temp dirs follow the
//! crate's `tests/ffi.rs` pattern (no `tempfile` dependency here).
//!
//! The two phases mirror the desktop's two binaries: phase A is
//! `crash_cap.rs` plus the once-guard pin; phase B is `crash_cap_live.rs`
//! verbatim — a RAW `install_panic_hook` on a fresh directory, because
//! the once-guard forbids re-installing (and a raised cap only writes
//! while fewer panics than the NEW cap have already been attempted this
//! run: `written` counts attempts, `fp-app/src/crash.rs:59`, so the
//! fresh-counter install is the only honest way to pin the raise).

use std::path::{Path, PathBuf};

use fp_backends_mobile::crash::{install_panic_hook, install_panic_hook_once, report_cap};

/// A fresh, empty directory under the system temp dir, named per process
/// and test tag (the `tests/ffi.rs` `engine()` precedent).
fn dir(tag: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("fp-crash-test-{}-{tag}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("temp dir");
    dir
}

/// The `crash-*.txt` reports in `dir` (the desktop's counter helper,
/// `fp-app/tests/crash_cap.rs:14-23`).
fn crash_reports(dir: &Path) -> Vec<PathBuf> {
    std::fs::read_dir(dir)
        .expect("read the log dir")
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.file_name().to_string_lossy().starts_with("crash-"))
        .map(|entry| entry.path())
        .collect()
}

#[test]
fn reports_are_written_once_capped_live_and_never_deleted() {
    // Phase A — the per-run cap, and the once-per-process guard: the
    // FIRST install wins; the second (a higher cap, another directory)
    // must be a no-op. The desktop: `crash_cap.rs` (5 deliberate panics,
    // cap 2, exactly 2 reports).
    let first = dir("first");
    let second = dir("second");
    install_panic_hook_once(first.clone(), 2);
    install_panic_hook_once(second.clone(), 5);
    for _ in 0..5 {
        let _ = std::panic::catch_unwind(|| panic!("deliberate"));
    }
    let written = crash_reports(&first);
    assert_eq!(
        written.len(),
        2,
        "the per-run cap of the FIRST install holds"
    );
    assert!(
        crash_reports(&second).is_empty(),
        "the second install must not have installed another hook"
    );

    // The report format, as the desktop writes it (`fp-app/src/crash.rs:48-58`).
    let report = written.first().expect("at least one report");
    let content = std::fs::read_to_string(report).expect("the report file");
    let header = format!(
        "Fauste Player crash report\nversion: {}\nos: {} {}\nthread: {}\nmessage: deliberate\n",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH,
        std::thread::current().name().unwrap_or("unnamed"),
    );
    assert!(
        content.starts_with(&header),
        "the report must carry the desktop's header, got: {content}"
    );
    let location = content.lines().nth(5).unwrap_or_default();
    assert!(
        location.starts_with("location: ") && location.contains("crash_cap.rs"),
        "the location must point at this test file, got: {location}"
    );

    // Phase B — the live cap, the desktop's `crash_cap_live.rs` verbatim:
    // install with cap 1, raise to 3, five panics write 3; lower to 1,
    // one more panic writes nothing and deletes nothing. The chained
    // phase-A hook also sees these panics but its own cap is exhausted,
    // which doubles as the pin that a lowered cap stays exhausted.
    let third = dir("third");
    let cap = install_panic_hook(third.clone(), 1);
    cap.set(3);
    for _ in 0..5 {
        let _ = std::panic::catch_unwind(|| panic!("again"));
    }
    assert_eq!(crash_reports(&third).len(), 3, "the raised cap is followed");
    cap.set(1);
    let _ = std::panic::catch_unwind(|| panic!("once more"));
    assert_eq!(
        crash_reports(&third).len(),
        3,
        "a lowered cap writes nothing and deletes nothing"
    );
    assert_eq!(
        crash_reports(&first).len(),
        2,
        "the exhausted phase-A hook wrote nothing more"
    );

    // The once-guard's cap is still the one the process reported at
    // install time: reachable, and independent of phase B's replacement.
    assert!(
        report_cap().is_some(),
        "the once-installed cap stays reachable even after a raw re-install"
    );
}
