//! The AAudio data callback as plain Rust: everything the real callback does,
//! minus the FFI, so the logic runs — and is tested — on the host. The later
//! `extern "C"` wrapper is a thin unsafe slice cast plus one `fill` call.
//!
//! Unlike the desktop cpal backend, AAudio hands the callback a float buffer
//! directly: there is no sample conversion and no scratch buffer, so `fill`
//! renders into the output slice as it is. The one-slot handoff ring mirrors
//! `cpal_backend.rs`: the renderer is pushed only once the stream exists, so
//! a failed open attempt drops the unused producer and never the renderer.
//!
//! Real-time safety: `fill` runs on AAudio's audio thread, so it allocates
//! nothing, takes no locks, logs nothing and cannot panic — `rtrb` push/pop
//! are wait-free, and `Renderer::render` is contract-bound to the same rules
//! (`fp_backends::Renderer`).

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use fp_backends::Renderer;

use crate::stream_params::AAUDIO_CALLBACK_RESULT_CONTINUE;

/// What a stream's data callback does on every invocation, in plain Rust.
pub struct CallbackCore {
    /// Where the renderer arrives once the stream exists.
    handoff: rtrb::Consumer<Box<dyn Renderer>>,
    /// The renderer in use, from the first successful pop on.
    renderer: Option<Box<dyn Renderer>>,
    /// The largest block called back, for `OutputStream::config`.
    called_frames: Arc<AtomicU32>,
}

impl CallbackCore {
    /// Builds the callback state for one stream. The one-slot handoff ring is
    /// created here, so the caller keeps only the producer: it pushes the
    /// renderer after the stream has opened, and a failed open drops that
    /// producer unused.
    ///
    /// The callback receives the channel count per `fill` call (the
    /// `Renderer::render` trait carries it), and a float-only backend needs
    /// no scratch or conversion state, so there is nothing else to configure.
    pub fn new() -> (rtrb::Producer<Box<dyn Renderer>>, Self) {
        let (producer, consumer) = rtrb::RingBuffer::new(1);
        let core = Self {
            handoff: consumer,
            renderer: None,
            called_frames: Arc::new(AtomicU32::new(0)),
        };
        (producer, core)
    }

    /// One data-callback invocation: record the block size, pick up the
    /// renderer if none is in use yet, then either render into `out`
    /// (interleaved, `channels` per frame) or write silence. Returns
    /// `AAUDIO_CALLBACK_RESULT_CONTINUE` — the Phase-1 callback never stops
    /// the stream itself; error reporting is the error callback's job.
    ///
    /// Real-time safe: no allocation, no locks, no logging, no panics. The
    /// `channels` count is floored at 1 so a broken count cannot turn into a
    /// division by zero.
    pub fn fill(&mut self, out: &mut [f32], channels: usize) -> i32 {
        let channels = channels.max(1);
        let frames = u32::try_from(out.len() / channels).unwrap_or(u32::MAX);
        self.called_frames.fetch_max(frames, Ordering::Relaxed);
        if self.renderer.is_none() {
            self.renderer = self.handoff.pop().ok();
        }
        match self.renderer.as_mut() {
            Some(r) => r.render(out, channels),
            None => out.fill(0.0),
        }
        AAUDIO_CALLBACK_RESULT_CONTINUE
    }

    /// The largest block this callback has seen, in frames, for the stream
    /// struct's `OutputStream::config`.
    pub fn called_frames(&self) -> Arc<AtomicU32> {
        Arc::clone(&self.called_frames)
    }
}
