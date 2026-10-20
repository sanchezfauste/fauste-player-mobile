//! Crash reports (spec §9), the desktop's machinery ported (Ruling 5): a
//! panic writes a report file to the store's log directory, then the
//! previous hook runs as usual. At most `max_reports` files are written
//! per run (the cap starts at `limits.max_crash_reports`,
//! `fp-model/src/config.rs` — desktop default 20), so a panic that
//! repeats (and is contained) cannot fill the disk; later panics are
//! only logged. Nothing is ever deleted, exactly as on the desktop
//! (`fp-app/tests/crash_cap_live.rs`: "none written, none deleted").
//!
//! Desktop authority: `fp-app/src/crash.rs` (whole file — this is a
//! verbatim port of its `install_panic_hook`/`ReportCap`), installed by
//! `fp-app/src/main.rs:93-96` BEFORE `run` (so the construction path is
//! covered too — the FFI constructor below preserves that order), its
//! cap kept in step with the live config by the desktop's services
//! thread (`fp-app/src/services.rs:684-685`); the mobile has no live
//! `limits` surface, so the boot default stands until one exists (the
//! same ruling as `SESSION_SAVE_DEBOUNCE`).
//!
//! Where reports go: the desktop writes `crash-{nanos}.txt` into
//! `paths.log_dir` (`fp-app/src/crash.rs:66`); the mobile's store uses
//! `data_dir/logs` for that directory
//! (`PlayerCore::core`'s `AppPaths`, `log_dir` helper), so the hook
//! writes into `data_dir/logs/` — on device `<filesDir>/logs/`, since
//! the app passes `filesDir` as `data_dir`
//! (`fp-app-mobile-shared/.../AppModule.android.kt`).
//!
//! # Once per process
//!
//! The desktop installs the hook from `main`, which runs once. The
//! mobile's install site is the FFI constructor
//! (`ffi.rs`, `FausteEngine::new`), which tests call repeatedly —
//! [`install_panic_hook_once`] guards the install with a `OnceLock`:
//! the FIRST call installs (its `log_dir` wins — on device there is one
//! `filesDir`; a later call with a different directory is a no-op),
//! later calls only fetch the cap. A future settings surface can follow
//! the desktop's live cap through [`report_cap`].
//!
//! # This is not real-time code — and must still run everywhere
//!
//! A panic hook may allocate and do I/O freely (`std::panic`'s
//! contract), and panics from ANY thread land here, including a
//! hypothetical panic on the audio thread. Every fallible operation is
//! `let _`-ignored so the hook itself cannot panic (no
//! panics-in-the-panic-hook recursion; the crate's lints deny
//! `unwrap`/`expect`/`panic` outright), and a failed write just means
//! no report — the previous hook still runs.
//!
//! # The abort-mode truth (what actually gets captured)
//!
//! * This workspace sets no `[profile]` panic strategy at all (root
//!   `Cargo.toml`), so every build — host tests and the `cargo ndk
//!   build --release` cdylib alike (`composeApp/build.gradle.kts`) —
//!   uses Cargo's default `panic = "unwind"`. There is no
//!   `panic = "abort"` (and no UniFFI-set equivalent) anywhere to
//!   document around; if one were ever configured, the hook still runs
//!   first: the runtime invokes the hook before unwinding or aborting.
//! * A panic unwinding THROUGH an exported UniFFI function never
//!   reaches an `extern "C"` boundary: uniffi 0.28 wraps every call in
//!   `catch_unwind` (`uniffi_core/src/ffi/rustcalls.rs:177`) and
//!   converts it to `RustCallStatusCode::UnexpectedError`, which the
//!   generated Kotlin raises as `InternalException`
//!   (`bindings/kotlin/fauste_player.kt`, `uniffiCheckCallStatus`).
//!   The hook has already run at that point — the report is written
//!   and the process survives.
//! * The one boundary with no Rust-side catcher is the AAudio callback,
//!   invoked from C: a panic there aborts the process once the unwind
//!   escapes `extern "C"` (Rust's abort-on-C-unwind rule) — but only
//!   AFTER this hook has run, so the report still lands. The crate's
//!   `panic`-denying lints make that path a non-possibility by
//!   construction anyway.

use std::backtrace::Backtrace;
use std::fmt::Write as _;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// How many crash reports a run may write; shared with the panic hook so
/// that it can follow `limits.max_crash_reports` if a live settings
/// surface ever appears (the desktop's shape, `fp-app/src/crash.rs`).
/// A cap lowered below what was already written stops further reports;
/// none is deleted.
#[derive(Debug, Clone)]
pub struct ReportCap(Arc<AtomicUsize>);

impl ReportCap {
    pub fn set(&self, max: usize) {
        self.0.store(max, Ordering::Release);
    }
}

pub fn install_panic_hook(log_dir: PathBuf, max_reports: usize) -> ReportCap {
    let cap = ReportCap(Arc::new(AtomicUsize::new(max_reports)));
    let max = cap.0.clone();
    let previous = std::panic::take_hook();
    let written = AtomicUsize::new(0);
    std::panic::set_hook(Box::new(move |info| {
        let message = info
            .payload()
            .downcast_ref::<&str>()
            .map(|s| (*s).to_owned())
            .or_else(|| info.payload().downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "non-string panic payload".to_owned());
        let location = info
            .location()
            .map(|l| format!("{}:{}", l.file(), l.line()))
            .unwrap_or_default();
        let thread = std::thread::current()
            .name()
            .unwrap_or("unnamed")
            .to_owned();
        let mut report = String::new();
        let _ = writeln!(report, "Fauste Player crash report");
        let _ = writeln!(report, "version: {}", env!("CARGO_PKG_VERSION"));
        let _ = writeln!(
            report,
            "os: {} {}",
            std::env::consts::OS,
            std::env::consts::ARCH
        );
        let _ = writeln!(report, "thread: {thread}");
        let _ = writeln!(report, "message: {message}");
        let _ = writeln!(report, "location: {location}");
        if written.fetch_add(1, Ordering::AcqRel) < max.load(Ordering::Acquire) {
            let _ = writeln!(report, "\n{}", Backtrace::force_capture());
            let stamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0);
            let _ = std::fs::create_dir_all(&log_dir);
            let _ = std::fs::write(log_dir.join(format!("crash-{stamp}.txt")), report);
        }
        tracing::error!(%thread, %location, "panic: {message}");
        previous(info);
    }));
    cap
}

/// The process's installed cap, once [`install_panic_hook_once`] has run.
static INSTALLED: OnceLock<ReportCap> = OnceLock::new();

/// [`install_panic_hook`], but at most once per process (see the module
/// docs): the first call installs the hook — its `log_dir` and
/// `max_reports` stand for the process — and later calls are no-ops.
/// Thread-safe: the guard is a `OnceLock`, so concurrent FFI
/// constructions race to exactly one install.
pub fn install_panic_hook_once(log_dir: PathBuf, max_reports: usize) {
    INSTALLED.get_or_init(|| install_panic_hook(log_dir, max_reports));
}

/// The cap the hook installed by [`install_panic_hook_once`] follows, if
/// one was installed in this process.
pub fn report_cap() -> Option<ReportCap> {
    INSTALLED.get().cloned()
}
