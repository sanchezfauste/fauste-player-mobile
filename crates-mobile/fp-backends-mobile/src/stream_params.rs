//! Pure AAudio builder-parameter mapping: the desktop `StreamConfig` decisions
//! an `open_output` call needs, translated to `AAudioStreamBuilder_*`
//! arguments and open-time gates.
//!
//! Like `stream_config`, the AAudio values are plain `i32` constants mirrored
//! from the NDK r27 `aaudio/AAudio.h` header, so this module and its tests run
//! on the host without the NDK. Everything here is compiled on every target:
//! the Android arm consumes it, and hosts test it.

use fp_backends::dsd::DsdStream;
use fp_backends::{BackendError, DeviceId, StreamConfig};

/// The id of the single device AAudio exposes: its default output.
const DEFAULT_DEVICE_ID: &str = "default";

/// `AAUDIO_DIRECTION_OUTPUT` (NDK `aaudio/AAudio.h`): the builder opens a
/// playback stream.
pub const AAUDIO_DIRECTION_OUTPUT: i32 = 0;
/// `AAUDIO_SHARING_MODE_EXCLUSIVE`: the stream owns the device outright.
pub const AAUDIO_SHARING_MODE_EXCLUSIVE: i32 = 0;
/// `AAUDIO_SHARING_MODE_SHARED`: the stream shares the device with the Android
/// mixer. The non-zero value — exclusive is 0.
pub const AAUDIO_SHARING_MODE_SHARED: i32 = 1;
/// `AAUDIO_PERFORMANCE_MODE_NONE`: no performance hint.
pub const AAUDIO_PERFORMANCE_MODE_NONE: i32 = 10;
/// `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`: favor smaller buffers over power.
pub const AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;
/// `AAUDIO_USAGE_MEDIA`: media playback, the player's use case.
pub const AAUDIO_USAGE_MEDIA: i32 = 1;
/// `AAUDIO_CONTENT_TYPE_MUSIC`: the stream carries music.
pub const AAUDIO_CONTENT_TYPE_MUSIC: i32 = 2;
/// `AAudioStream_dataCallback` result: keep the stream running.
pub const AAUDIO_CALLBACK_RESULT_CONTINUE: i32 = 0;
/// `AAudioStream_dataCallback` result: stop the stream.
pub const AAUDIO_CALLBACK_RESULT_STOP: i32 = 1;
/// `AAUDIO_ERROR_DISCONNECTED`: the device went away mid-stream. The Phase-0
/// deferral: the magic literal in `classify_aaudio_error` becomes this name.
pub const AAUDIO_ERROR_DISCONNECTED: i32 = -899;

/// The `aaudio_sharing_mode_t` for the desktop `exclusive` flag: `EXCLUSIVE`
/// when requested, `SHARED` otherwise.
pub fn sharing_mode(exclusive: bool) -> i32 {
    if exclusive {
        AAUDIO_SHARING_MODE_EXCLUSIVE
    } else {
        AAUDIO_SHARING_MODE_SHARED
    }
}

/// The `aaudio_performance_mode_t` for every Phase-1 stream: `LOW_LATENCY`
/// (media playback wants the small buffers).
pub fn performance_mode() -> i32 {
    AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
}

/// The open-time device gate: AAudio exposes exactly one device, its default
/// output, so only `DeviceId("default")` opens; anything else (e.g. a USB id
/// from another platform) is `DeviceNotFound`. Specific devices would need
/// `AAudioStreamBuilder_setDeviceId` with Java-side ids — a later plan.
pub fn check_device(device: &DeviceId) -> Result<(), BackendError> {
    if device.0 == DEFAULT_DEVICE_ID {
        Ok(())
    } else {
        Err(BackendError::DeviceNotFound(device.clone()))
    }
}

/// The open-time DSD gate (plan Ruling 1): DSD is refused for Phase 1 because
/// the backend opens float PCM only. `Native` has no AAudio path at all; `DoP`
/// needs exact integer words the float stream cannot carry — either way the
/// error says why, and no DSD stream can reach the mixer half-mangled by
/// accident.
pub fn check_dsd(config: &StreamConfig) -> Result<(), BackendError> {
    match config.dsd {
        None => Ok(()),
        Some(DsdStream::Native) => Err(BackendError::Unsupported(
            "AAudio has no DSD path: native DSD streams are not supported".to_owned(),
        )),
        Some(DsdStream::Dop) => Err(BackendError::Unsupported(
            "DoP needs exact integer words, but the AAudio backend opens float PCM".to_owned(),
        )),
    }
}

/// The buffer sizes to try on `AAudioStreamBuilder_setBufferSizeInFrames`, in
/// order: the requested size first, then — unless `exact` demands that size
/// and nothing else — `None`, meaning leave the device's own default.
pub fn buffer_attempts(requested: u32, exact: bool) -> Vec<Option<u32>> {
    if exact {
        vec![Some(requested)]
    } else {
        vec![Some(requested), None]
    }
}
