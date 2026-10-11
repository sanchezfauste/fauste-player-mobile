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

    /// The id of the single device AAudio exposes: its default output.
    const DEFAULT_DEVICE_ID: &str = "default";

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
            _device: &DeviceId,
            _config: StreamConfig,
            _renderer: Box<dyn Renderer>,
            _errors: Arc<dyn StreamErrorSink>,
        ) -> Result<Box<dyn OutputStream>, BackendError> {
            // The real stream arrives in the next plan.
            Err(BackendError::Unsupported("not implemented yet".to_owned()))
        }
    }
}
