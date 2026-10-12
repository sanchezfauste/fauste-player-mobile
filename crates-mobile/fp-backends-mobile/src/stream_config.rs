//! Pure conversion between the desktop `fp_backends` stream types and the
//! AAudio platform constants.
//!
//! The AAudio values are re-declared here as plain `i32` constants instead of
//! pulling in `aaudio-sys`, so this module and its tests run on the host
//! without the NDK. Values mirror the NDK r27 `aaudio/AAudio.h` header.

use fp_backends::{SampleFormat, StreamErrorKind};

/// `AAUDIO_FORMAT_PCM_I16`: signed 16-bit integer samples.
pub const AAUDIO_FORMAT_PCM_I16: i32 = 1;
/// `AAUDIO_FORMAT_PCM_FLOAT`: single-precision floating point samples.
pub const AAUDIO_FORMAT_PCM_FLOAT: i32 = 2;
/// `AAUDIO_FORMAT_PCM_I24_PACKED`: signed 24-bit integer samples. Requires
/// API 31, above this crate's minSdk of 26, so I24 streams run as I32.
pub const AAUDIO_FORMAT_PCM_I24_PACKED: i32 = 3;
/// `AAUDIO_FORMAT_PCM_I32`: signed 32-bit integer samples.
pub const AAUDIO_FORMAT_PCM_I32: i32 = 4;
/// `AAUDIO_OK`: the call succeeded.
pub const AAUDIO_OK: i32 = 0;

/// `aaudio_stream_state_t` values (NDK `aaudio/AudioStream.h`) the backend
/// polls through `AAudioStream_waitForStateChange`.
pub const AAUDIO_STREAM_STATE_UNINITIALIZED: i32 = 0;
pub const AAUDIO_STREAM_STATE_UNKNOWN: i32 = 1;
pub const AAUDIO_STREAM_STATE_OPEN: i32 = 2;
pub const AAUDIO_STREAM_STATE_STARTING: i32 = 3;
pub const AAUDIO_STREAM_STATE_STARTED: i32 = 4;
pub const AAUDIO_STREAM_STATE_PAUSING: i32 = 5;
pub const AAUDIO_STREAM_STATE_PAUSED: i32 = 6;
pub const AAUDIO_STREAM_STATE_FLUSHING: i32 = 7;
pub const AAUDIO_STREAM_STATE_FLUSHED: i32 = 8;
pub const AAUDIO_STREAM_STATE_STOPPING: i32 = 9;
pub const AAUDIO_STREAM_STATE_STOPPED: i32 = 10;
pub const AAUDIO_STREAM_STATE_CLOSING: i32 = 11;
pub const AAUDIO_STREAM_STATE_CLOSED: i32 = 12;
pub const AAUDIO_STREAM_STATE_DISCONNECTED: i32 = 13;

/// Maps a desktop sample format to its `aaudio_format_t` value.
pub fn aaudio_format(format: SampleFormat) -> i32 {
    match format {
        SampleFormat::F32 => AAUDIO_FORMAT_PCM_FLOAT,
        SampleFormat::I32 => AAUDIO_FORMAT_PCM_I32,
        // Packed 24-bit needs API 31 (above minSdk 26): run I24 as 32-bit.
        SampleFormat::I24 => AAUDIO_FORMAT_PCM_I32,
        SampleFormat::I16 => AAUDIO_FORMAT_PCM_I16,
    }
}

/// Classifies an AAudio result code into a desktop error kind.
pub fn classify_aaudio_error(code: i32) -> StreamErrorKind {
    match code {
        // AAUDIO_ERROR_DISCONNECTED: the device went away mid-stream.
        -899 => StreamErrorKind::DeviceLost,
        // Everything else (including AAUDIO_OK) is not a device loss. XRUNs
        // are exposed by AAudio via AAudioStream_getXRunCount() and the
        // error callback (onErrorAfterClose with AAUDIO_ERROR_TIMEOUT),
        // not from a result code, and gets its arm in the next plan.
        _ => StreamErrorKind::Other,
    }
}
