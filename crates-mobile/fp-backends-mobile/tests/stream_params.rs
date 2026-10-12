use fp_backends::dsd::DsdStream;
use fp_backends::{BackendError, DeviceId, StreamConfig};
use fp_backends_mobile::stream_params::{
    AAUDIO_CALLBACK_RESULT_CONTINUE, AAUDIO_CALLBACK_RESULT_STOP, AAUDIO_CONTENT_TYPE_MUSIC,
    AAUDIO_DIRECTION_OUTPUT, AAUDIO_ERROR_DISCONNECTED, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY,
    AAUDIO_PERFORMANCE_MODE_NONE, AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_SHARING_MODE_SHARED,
    AAUDIO_USAGE_MEDIA, buffer_attempts, check_device, check_dsd, performance_mode, sharing_mode,
};

/// AAudio builder constants from the NDK headers (r27 `AAudio.h`), asserted
/// once so the builder setup cannot drift from the platform values.
#[test]
fn aaudio_builder_constants_match_the_ndk_headers() {
    assert_eq!(AAUDIO_DIRECTION_OUTPUT, 0);
    // Exclusive = 0 is the counter-intuitive one: SHARED is non-zero.
    assert_eq!(AAUDIO_SHARING_MODE_EXCLUSIVE, 0);
    assert_eq!(AAUDIO_SHARING_MODE_SHARED, 1);
    assert_eq!(AAUDIO_PERFORMANCE_MODE_NONE, 10);
    assert_eq!(AAUDIO_PERFORMANCE_MODE_LOW_LATENCY, 12);
    assert_eq!(AAUDIO_USAGE_MEDIA, 1);
    assert_eq!(AAUDIO_CONTENT_TYPE_MUSIC, 2);
    assert_eq!(AAUDIO_CALLBACK_RESULT_CONTINUE, 0);
    assert_eq!(AAUDIO_CALLBACK_RESULT_STOP, 1);
    // AAUDIO_ERROR_BASE = -900, so DISCONNECTED is -899: the constant the
    // Phase-0 `classify_aaudio_error` deferred.
    assert_eq!(AAUDIO_ERROR_DISCONNECTED, -899);
}

#[test]
fn sharing_mode_follows_the_exclusive_flag() {
    assert_eq!(sharing_mode(true), AAUDIO_SHARING_MODE_EXCLUSIVE);
    assert_eq!(sharing_mode(false), AAUDIO_SHARING_MODE_SHARED);
}

#[test]
fn every_phase_1_stream_opens_low_latency() {
    assert_eq!(performance_mode(), AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    assert_eq!(performance_mode(), 12);
}

#[test]
fn only_the_default_device_opens() {
    assert_eq!(check_device(&DeviceId("default".to_owned())), Ok(()));
    assert_eq!(
        check_device(&DeviceId("usb".to_owned())),
        Err(BackendError::DeviceNotFound(DeviceId("usb".to_owned())))
    );
}

/// A PCM baseline config with only the DSD field varied.
fn pcm_config(dsd: Option<DsdStream>) -> StreamConfig {
    StreamConfig {
        sample_rate: 44_100,
        buffer_frames: 512,
        channels: 2,
        exclusive: false,
        dsd,
        exact_buffer: false,
    }
}

#[test]
fn pcm_streams_pass_the_dsd_gate() {
    assert_eq!(check_dsd(&pcm_config(None)), Ok(()));
}

#[test]
fn native_dsd_is_refused_saying_why() {
    let refused = check_dsd(&pcm_config(Some(DsdStream::Native)));
    assert!(matches!(refused, Err(BackendError::Unsupported(_))));
    if let Err(BackendError::Unsupported(msg)) = refused {
        assert!(msg.contains("no DSD path"), "reason missing: {msg}");
    }
}

#[test]
fn dop_is_refused_saying_why() {
    let refused = check_dsd(&pcm_config(Some(DsdStream::Dop)));
    assert!(matches!(refused, Err(BackendError::Unsupported(_))));
    if let Err(BackendError::Unsupported(msg)) = refused {
        assert!(msg.contains("integer words"), "reason missing: {msg}");
        assert!(msg.contains("float"), "reason missing: {msg}");
    }
}

#[test]
fn buffer_attempts_cover_the_requested_exact_combinations() {
    // Exact with a real request: only that size, no fallback attempt.
    assert_eq!(buffer_attempts(512, true), vec![Some(512)]);
    // Exact with the "no size of my own" 0: the value travels verbatim, the
    // function never invents a fallback for it.
    assert_eq!(buffer_attempts(0, true), vec![Some(0)]);
    // Non-exact with a real request: try it, then leave the device's own
    // default (`None`).
    assert_eq!(buffer_attempts(512, false), vec![Some(512), None]);
    // Non-exact with 0: the same two attempts.
    assert_eq!(buffer_attempts(0, false), vec![Some(0), None]);
}
