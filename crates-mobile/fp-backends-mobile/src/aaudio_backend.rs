//! The AAudio backend skeleton: availability probing and device enumeration.
//!
//! This module is the crate's single sanctioned FFI boundary. The workspace
//! lints forbid `unsafe_code`, which cannot be relaxed locally, so this crate
//! carries its own `deny`-level lints table and this file alone allows unsafe
//! code, on Android only, where the `aaudio-sys` calls live. Every `unsafe`
//! block below documents its invariants with a `// SAFETY:` comment.
#![cfg_attr(target_os = "android", allow(unsafe_code))]

use std::sync::Arc;

use fp_backends::{
    AudioBackend, Availability, BackendError, BackendId, DeviceId, DeviceInfo, OutputStream,
    Renderer, StreamConfig, StreamErrorSink,
};

/// The stable identifier of this backend.
const BACKEND_ID: &str = "aaudio";

/// Message reported on hosts where AAudio does not exist.
#[cfg(not(target_os = "android"))]
const HOST_UNAVAILABLE: &str = "AAudio is Android-only";

/// AAudio output behind the desktop `AudioBackend` trait.
pub struct AaudioBackend;

impl AaudioBackend {
    pub fn new() -> Self {
        Self
    }
}

impl Default for AaudioBackend {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(not(target_os = "android"))]
impl AudioBackend for AaudioBackend {
    fn id(&self) -> BackendId {
        BackendId(BACKEND_ID.to_owned())
    }

    fn availability(&self) -> Availability {
        Availability::Unavailable(HOST_UNAVAILABLE.to_owned())
    }

    fn enumerate_devices(&self) -> Result<Vec<DeviceInfo>, BackendError> {
        Err(BackendError::Unavailable(HOST_UNAVAILABLE.to_owned()))
    }

    fn default_device(&self) -> Option<DeviceId> {
        None
    }

    fn open_output(
        &self,
        _device: &DeviceId,
        _config: StreamConfig,
        _renderer: Box<dyn Renderer>,
        _errors: Arc<dyn StreamErrorSink>,
    ) -> Result<Box<dyn OutputStream>, BackendError> {
        Err(BackendError::Unavailable(HOST_UNAVAILABLE.to_owned()))
    }
}

#[cfg(target_os = "android")]
mod android_impl {
    use super::*;
    use crate::stream_params::DEFAULT_DEVICE_ID;

    /// Metadata of the single default output device AAudio reports.
    ///
    /// AAudio has no device enumeration API: there is exactly one entry, the
    /// default output device. Picking a specific device (e.g. USB audio) would
    /// use `AAudioStreamBuilder_setDeviceId` with an id from the Java
    /// AudioManager; that is deferred to a later plan.
    fn default_device_info() -> DeviceInfo {
        DeviceInfo {
            id: DeviceId(DEFAULT_DEVICE_ID.to_owned()),
            name: "Default output".to_owned(),
            detail: None,
            channels: 2,
            sample_rates: vec![(44_100, 192_000)],
            buffer_frames: None,
            exclusive_capable: true,
            rate_switching: false,
            native_dsd: false,
        }
    }

    /// Opens a throwaway default stream just long enough to confirm AAudio is
    /// usable on this device, then closes it.
    fn probe_availability() -> Availability {
        use std::ptr;

        let mut builder: *mut aaudio_sys::AAudioStreamBuilder = ptr::null_mut();
        // SAFETY: `builder` is a valid out-pointer to initialized stack memory;
        // the returned builder (on AAUDIO_OK) is deleted below on every path.
        let result = unsafe { aaudio_sys::AAudio_createStreamBuilder(&raw mut builder) };
        if result != aaudio_sys::OK || builder.is_null() {
            return Availability::Unavailable(format!(
                "AAudio cannot create a stream builder (error {result})"
            ));
        }
        let builder = builder;

        let available = (|| {
            let mut stream: *mut aaudio_sys::AAudioStream = ptr::null_mut();
            // SAFETY: `builder` is a live builder from `AAudio_createStreamBuilder`;
            // `stream` is a valid out-pointer to stack memory.
            let result =
                unsafe { aaudio_sys::AAudioStreamBuilder_openStream(builder, &raw mut stream) };
            if result != aaudio_sys::OK || stream.is_null() {
                return Availability::Unavailable(format!(
                    "AAudio cannot open the default output stream (error {result})"
                ));
            }
            // SAFETY: `stream` is a live stream returned by `openStream`, closed
            // exactly once below.
            let state = unsafe { aaudio_sys::AAudioStream_getState(stream) };
            // SAFETY: `stream` is live and has not been closed before.
            let _ = unsafe { aaudio_sys::AAudioStream_close(stream) };
            if state == crate::stream_config::AAUDIO_STREAM_STATE_OPEN {
                Availability::Available
            } else {
                Availability::Unavailable(format!(
                    "AAudio stream opened in an unexpected state ({state})"
                ))
            }
        })();

        // SAFETY: `builder` is live and deleted exactly once, here.
        let _ = unsafe { aaudio_sys::AAudioStreamBuilder_delete(builder) };
        available
    }

    impl AudioBackend for AaudioBackend {
        fn id(&self) -> BackendId {
            BackendId(BACKEND_ID.to_owned())
        }

        /// Probes AAudio on every call by opening (and closing) a throwaway
        /// stream. The probe is deliberately uncached: callers are rare
        /// (settings screens, backend selection) and hotplug correctness
        /// beats saving one open/close round-trip.
        fn availability(&self) -> Availability {
            probe_availability()
        }

        fn enumerate_devices(&self) -> Result<Vec<DeviceInfo>, BackendError> {
            match self.availability() {
                Availability::Available => Ok(vec![default_device_info()]),
                Availability::Unavailable(reason) => Err(BackendError::Unavailable(reason)),
            }
        }

        fn default_device(&self) -> Option<DeviceId> {
            match self.availability() {
                Availability::Available => Some(DeviceId(DEFAULT_DEVICE_ID.to_owned())),
                Availability::Unavailable(_) => None,
            }
        }

        fn open_output(
            &self,
            device: &DeviceId,
            config: StreamConfig,
            renderer: Box<dyn Renderer>,
            errors: Arc<dyn StreamErrorSink>,
        ) -> Result<Box<dyn OutputStream>, BackendError> {
            crate::stream_params::check_device(device)?;
            crate::stream_params::check_dsd(&config)?;
            open_stream(&config, renderer, errors)
        }
    }

    use std::ptr;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;

    use fp_backends::SampleFormat;

    use crate::callback::CallbackCore;
    use crate::stream_config::classify_aaudio_error;
    use crate::stream_params::{buffer_attempts, open_error, performance_mode, sharing_mode};

    /// How often the XRUN monitor polls `AAudioStream_getXRunCount`. Not a
    /// real-time thread, so sleeping and polling is all it does.
    const XRUN_POLL_INTERVAL: Duration = Duration::from_millis(250);

    /// The context both AAudio callbacks share, passed as `user_data`.
    /// Owned by a `Box` whose raw pointer is published to AAudio via
    /// `AAudioStreamBuilder_setDataCallback`; ownership returns to Rust in
    /// `AaudioStream::drop`, after `AAudioStream_close` guarantees no
    /// callback can still be running.
    struct StreamCtx {
        /// The data callback's renderer handoff and fill logic.
        core: CallbackCore,
        /// Where the error callback and the XRUN monitor report.
        errors: Arc<dyn StreamErrorSink>,
        /// Interleaved channels per frame, fixed at build time.
        channels: usize,
    }

    /// A running AAudio output stream. Dropping it stops the stream.
    struct AaudioStream {
        /// The live AAudio stream handle, owned exclusively by this struct.
        stream: *mut aaudio_sys::AAudioStream,
        /// Raw pointer to the boxed `StreamCtx` shared with the callbacks.
        ctx: *mut StreamCtx,
        /// The requested config, with `buffer_frames` already replaced by the
        /// size the device actually runs.
        config: StreamConfig,
        /// Signals the XRUN monitor thread to exit.
        monitor_stop: Arc<AtomicBool>,
        /// The XRUN monitor thread; `None` if it failed to spawn.
        monitor: Option<std::thread::JoinHandle<()>>,
    }

    // SAFETY: `AaudioStream` owns its raw handles exclusively. The stream
    // handle is only touched by the owner thread (open/drop) and by AAudio's
    // own callback threads plus the monitor while the stream is live; `drop`
    // serializes them (stop flag → join → close) before the handle dies, so
    // no cross-thread data race on the handles is possible.
    unsafe impl Send for AaudioStream {}

    impl OutputStream for AaudioStream {
        fn config(&self) -> StreamConfig {
            self.config
        }

        /// The backend opens `AAUDIO_FORMAT_PCM_FLOAT` only.
        fn sample_format(&self) -> SampleFormat {
            SampleFormat::F32
        }
    }

    impl Drop for AaudioStream {
        fn drop(&mut self) {
            // Tell the monitor to stop, then stop the audio flow. AAudio
            // guarantees callbacks end before `close` returns.
            self.monitor_stop.store(true, Ordering::Relaxed);
            // SAFETY: `self.stream` is a live stream owned by this struct;
            // `requestStop` is asynchronous and may be called while callbacks
            // are still running.
            let _ = unsafe { aaudio_sys::AAudioStream_requestStop(self.stream) };
            // Join the monitor before the stream handle dies, so it can never
            // poll a closed stream.
            if let Some(monitor) = self.monitor.take() {
                let _ = monitor.join();
            }
            // SAFETY: `self.stream` is live and closed exactly once, here;
            // after `close` returns AAudio will never invoke the callbacks.
            let _ = unsafe { aaudio_sys::AAudioStream_close(self.stream) };
            // SAFETY: no callback or monitor can run after `close`, so
            // reclaiming the context box cannot race the audio threads; the
            // pointer came from `Box::into_raw` in `open_stream` and is
            // reclaimed exactly once, here.
            drop(unsafe { Box::from_raw(self.ctx) });
        }
    }

    /// The AAudio data callback: render `num_frames` interleaved f32 into
    /// `audio_data`. Real-time safe: no allocation, no locks, no logging, no
    /// panics — it delegates to `CallbackCore::fill`, which keeps the same
    /// contract.
    unsafe extern "C" fn data_callback(
        _stream: *mut aaudio_sys::AAudioStream,
        user_data: *mut std::ffi::c_void,
        audio_data: *mut std::ffi::c_void,
        num_frames: i32,
    ) -> i32 {
        if audio_data.is_null() || num_frames <= 0 {
            return aaudio_sys::CALLBACK_CONTINUE;
        }
        // SAFETY: `user_data` is the `Box<StreamCtx>` published via
        // `setDataCallback`; AAudio never runs callbacks concurrently and the
        // box is reclaimed only after `close`, so it is live here.
        let ctx = unsafe { &mut *user_data.cast::<StreamCtx>() };
        let frames = usize::try_from(num_frames).unwrap_or(0);
        let Some(len) = frames.checked_mul(ctx.channels) else {
            return aaudio_sys::CALLBACK_CONTINUE;
        };
        // SAFETY: the stream was opened with `FORMAT_PCM_FLOAT`, so AAudio
        // hands us `num_frames * channels` contiguous f32 samples, valid for
        // writes until the callback returns.
        let out = unsafe { std::slice::from_raw_parts_mut(audio_data.cast::<f32>(), len) };
        ctx.core.fill(out, ctx.channels)
    }

    /// The AAudio error callback: classify the code and report it — nothing
    /// else. Real-time safe by contract, and never calls `requestStop` or
    /// `close` (AAudio forbids both here); stream teardown on device loss is
    /// the conductor's job, via the sink.
    unsafe extern "C" fn error_callback(
        _stream: *mut aaudio_sys::AAudioStream,
        user_data: *mut std::ffi::c_void,
        error: i32,
    ) {
        // SAFETY: same `StreamCtx` lifetime argument as the data callback;
        // here the access is read-only.
        let ctx = unsafe { &*user_data.cast::<StreamCtx>() };
        ctx.errors.report(classify_aaudio_error(error));
    }

    /// Polls `AAudioStream_getXRunCount` (Ruling 2): AAudio reports underruns
    /// only through this counter, so a non-real-time thread samples it every
    /// 250 ms and reports an `Xrun` per increase. Exits on the stop flag or a
    /// poll error (the error callback reports a real stream failure).
    fn monitor_xruns(
        stream: *mut aaudio_sys::AAudioStream,
        stop: Arc<AtomicBool>,
        errors: Arc<dyn StreamErrorSink>,
    ) {
        let mut last: Option<i32> = None;
        while !stop.load(Ordering::Relaxed) {
            std::thread::sleep(XRUN_POLL_INTERVAL);
            if stop.load(Ordering::Relaxed) {
                break;
            }
            // SAFETY: `stream` outlives this thread — `AaudioStream::drop`
            // sets the stop flag and joins this thread before `close`.
            let xruns = unsafe { aaudio_sys::AAudioStream_getXRunCount(stream) };
            if xruns < 0 {
                break;
            }
            if last.is_some_and(|prev| xruns > prev) {
                errors.report(fp_backends::StreamErrorKind::Xrun);
            }
            last = Some(xruns);
        }
    }

    /// Configures a fresh builder for `config` and the callbacks. The builder
    /// is owned by the caller, which deletes it exactly once.
    unsafe fn configure_builder(
        builder: *mut aaudio_sys::AAudioStreamBuilder,
        config: &StreamConfig,
        capacity: Option<u32>,
        ctx: *mut StreamCtx,
    ) {
        let i32_or_unspecified = |v: u32| i32::try_from(v).unwrap_or(aaudio_sys::UNSPECIFIED);
        // SAFETY: `builder` is a live builder from `AAudio_createStreamBuilder`
        // and every setter takes plain values; `ctx` stays valid because the
        // box is reclaimed only after the stream is closed.
        unsafe {
            aaudio_sys::AAudioStreamBuilder_setSampleRate(
                builder,
                i32_or_unspecified(config.sample_rate),
            );
            aaudio_sys::AAudioStreamBuilder_setChannelCount(
                builder,
                i32_or_unspecified(u32::from(config.channels)),
            );
            aaudio_sys::AAudioStreamBuilder_setFormat(builder, aaudio_sys::FORMAT_PCM_FLOAT);
            aaudio_sys::AAudioStreamBuilder_setSharingMode(builder, sharing_mode(config.exclusive));
            aaudio_sys::AAudioStreamBuilder_setPerformanceMode(builder, performance_mode());
            aaudio_sys::AAudioStreamBuilder_setUsage(builder, aaudio_sys::USAGE_MEDIA);
            aaudio_sys::AAudioStreamBuilder_setContentType(builder, aaudio_sys::CONTENT_TYPE_MUSIC);
            aaudio_sys::AAudioStreamBuilder_setDataCallback(
                builder,
                Some(data_callback),
                ctx.cast(),
            );
            aaudio_sys::AAudioStreamBuilder_setErrorCallback(
                builder,
                Some(error_callback),
                ctx.cast(),
            );
            if let Some(capacity) = capacity {
                aaudio_sys::AAudioStreamBuilder_setBufferCapacityInFrames(
                    builder,
                    i32_or_unspecified(capacity),
                );
            }
        }
    }

    /// Opens, sizes and starts the stream, then hands the renderer to the
    /// callback. On any failure after the context box is published, the box
    /// is reclaimed before returning (no callback can be running yet, because
    /// callbacks start only with `requestStart`).
    fn open_stream(
        config: &StreamConfig,
        renderer: Box<dyn Renderer>,
        errors: Arc<dyn StreamErrorSink>,
    ) -> Result<Box<dyn OutputStream>, BackendError> {
        let (mut producer, core) = CallbackCore::new();
        // The monitor's sink is cloned before the box is handed to AAudio:
        // between `Box::into_raw` and the post-`close` reclaim, only AAudio
        // callbacks may touch the box, so the opening thread must not take
        // even a shared reference into it (the data callback may already be
        // holding `&mut` from the RT thread).
        let monitor_errors = Arc::clone(&errors);
        let ctx = Box::new(StreamCtx {
            core,
            errors,
            channels: usize::from(config.channels.max(1)),
        });
        let ctx = Box::into_raw(ctx);

        let result = try_open(config, ctx).and_then(|(stream, buffer_frames)| {
            // The renderer enters the one-slot ring only once the stream is
            // up; a failed open dropped the producer unused instead.
            if let Err(renderer) = producer.push(renderer) {
                drop(renderer);
                // SAFETY: `stream` is live (started, but `close` drains any
                // running callbacks); closed exactly once.
                let _ = unsafe { aaudio_sys::AAudioStream_close(stream) };
                return Err(BackendError::Backend(
                    "renderer handoff failed: the ring was not empty".to_owned(),
                ));
            }
            let stop = Arc::new(AtomicBool::new(false));
            let monitor = std::thread::Builder::new()
                .name("aaudio-xrun-monitor".to_owned())
                .spawn({
                    let stop = Arc::clone(&stop);
                    // SAFETY: the raw handle crosses the thread boundary as
                    // `usize`; the monitor joins before the stream is closed.
                    let stream = stream as usize;
                    move || {
                        monitor_xruns(
                            stream as *mut aaudio_sys::AAudioStream,
                            stop,
                            monitor_errors,
                        )
                    }
                })
                .ok();
            Ok(Box::new(AaudioStream {
                stream,
                ctx,
                config: StreamConfig {
                    buffer_frames,
                    ..*config
                },
                monitor_stop: stop,
                monitor,
            }) as Box<dyn OutputStream>)
        });
        if result.is_err() {
            // SAFETY: every error path closed any stream it opened (an open
            // failure never produced one) — and `close` drains callbacks, so
            // none can be running even on the push-failure path, where a
            // started stream may already have run silence fills. With no
            // callbacks live, reclaim the context box exactly once.
            drop(unsafe { Box::from_raw(ctx) });
        }
        result
    }

    /// Walks `buffer_attempts`: open with the requested capacity first, then
    /// (unless `exact_buffer`) with the device's own default. With `exact`,
    /// refuse when the actual buffer size differs from the request.
    fn try_open(
        config: &StreamConfig,
        ctx: *mut StreamCtx,
    ) -> Result<(*mut aaudio_sys::AAudioStream, u32), BackendError> {
        let attempts = buffer_attempts(config.buffer_frames, config.exact_buffer);
        let mut last_err = BackendError::Backend("AAudio: no buffer size attempted".to_owned());
        for (index, capacity) in attempts.iter().enumerate() {
            let is_last = index + 1 == attempts.len();
            match open_attempt(config, *capacity, ctx) {
                Ok((stream, actual)) => {
                    if config.exact_buffer {
                        // Exact mode needs a trustworthy readback: an error
                        // from `getBufferSizeInFrames` is a refusal, not a
                        // pass against the requested size.
                        let Ok(actual) = u32::try_from(actual) else {
                            // SAFETY: `stream` is live and closed exactly once.
                            let _ = unsafe { aaudio_sys::AAudioStream_close(stream) };
                            return Err(BackendError::Unsupported(
                                "AAudio could not report the actual buffer size, so the exact \
                                 request cannot be confirmed"
                                    .to_owned(),
                            ));
                        };
                        if actual != config.buffer_frames {
                            // SAFETY: `stream` is live and closed exactly once.
                            let _ = unsafe { aaudio_sys::AAudioStream_close(stream) };
                            return Err(BackendError::Unsupported(format!(
                                "AAudio gave a buffer of {actual} frames, not the exact {} requested",
                                config.buffer_frames
                            )));
                        }
                        return Ok((stream, actual));
                    }
                    // Non-exact: an unreadable size falls back to the
                    // request — a device that refuses a fixed readback must
                    // still play rather than leave the bus silent.
                    let buffer_frames = u32::try_from(actual).unwrap_or(config.buffer_frames);
                    return Ok((stream, buffer_frames));
                }
                Err(err) => {
                    last_err = err;
                    if is_last {
                        return Err(last_err);
                    }
                }
            }
        }
        Err(last_err)
    }

    /// One open attempt: build, configure, open, set the buffer size. On
    /// success the stream is started and its raw buffer-size readback (which
    /// may be a negative error) is returned; stream ownership passes to the
    /// caller.
    fn open_attempt(
        config: &StreamConfig,
        capacity: Option<u32>,
        ctx: *mut StreamCtx,
    ) -> Result<(*mut aaudio_sys::AAudioStream, i32), BackendError> {
        let mut builder: *mut aaudio_sys::AAudioStreamBuilder = ptr::null_mut();
        // SAFETY: `builder` is a valid out-pointer to stack memory; on OK the
        // returned builder is deleted below on every path.
        let result = unsafe { aaudio_sys::AAudio_createStreamBuilder(&raw mut builder) };
        if result != aaudio_sys::OK || builder.is_null() {
            return Err(BackendError::Unavailable(format!(
                "AAudio cannot create a stream builder (error {result})"
            )));
        }
        let builder = builder;

        // SAFETY: see `configure_builder`.
        unsafe { configure_builder(builder, config, capacity, ctx) };

        let mut stream: *mut aaudio_sys::AAudioStream = ptr::null_mut();
        // SAFETY: `builder` is live and configured; `stream` is a valid
        // out-pointer to stack memory.
        let result =
            unsafe { aaudio_sys::AAudioStreamBuilder_openStream(builder, &raw mut stream) };
        // SAFETY: `builder` is live and deleted exactly once, here.
        let _ = unsafe { aaudio_sys::AAudioStreamBuilder_delete(builder) };
        if result != aaudio_sys::OK || stream.is_null() {
            return Err(open_error(result));
        }
        let stream = stream;

        if capacity.is_some() {
            let size = i32::try_from(config.buffer_frames).unwrap_or(aaudio_sys::UNSPECIFIED);
            // SAFETY: `stream` is live; the call clamps or refuses an
            // out-of-range size, which the exact check below handles.
            let _ = unsafe { aaudio_sys::AAudioStream_setBufferSizeInFrames(stream, size) };
        }
        // SAFETY: `stream` is live.
        let actual = unsafe { aaudio_sys::AAudioStream_getBufferSizeInFrames(stream) };

        // SAFETY: `stream` is live; `requestStart` may be called once here.
        let started = unsafe { aaudio_sys::AAudioStream_requestStart(stream) };
        if started != aaudio_sys::OK {
            // SAFETY: `stream` is live and closed exactly once on failure.
            let _ = unsafe { aaudio_sys::AAudioStream_close(stream) };
            return Err(open_error(started));
        }
        Ok((stream, actual))
    }
}
