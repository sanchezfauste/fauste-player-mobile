use fp_backends::{SampleFormat, StreamErrorKind};

/// AAudio format codes from the NDK headers (r27 `AAudio.h`), asserted once
/// so the conversion table cannot drift from the platform values.
#[test]
fn aaudio_format_codes_match_the_ndk_headers() {
    // AAUDIO_FORMAT_UNSPECIFIED = 0, I16 = 1, FLOAT = 2, I24_PACKED = 3,
    // I32 = 4, IEC61937 = 5 in the NDK.
    // The test pins the mapping the backend relies on.
    assert_eq!(
        fp_backends_mobile::stream_config::AAUDIO_FORMAT_PCM_FLOAT,
        2
    );
    assert_eq!(fp_backends_mobile::stream_config::AAUDIO_FORMAT_PCM_I32, 4);
    assert_eq!(fp_backends_mobile::stream_config::AAUDIO_FORMAT_PCM_I16, 1);
}

#[test]
fn desktop_formats_map_to_their_aaudio_equivalents() {
    use fp_backends_mobile::stream_config::aaudio_format;
    assert_eq!(aaudio_format(SampleFormat::F32), 2);
    assert_eq!(aaudio_format(SampleFormat::I32), 4);
    assert_eq!(aaudio_format(SampleFormat::I16), 1);
    // I24 maps to I32: AAUDIO_FORMAT_PCM_I24_PACKED (3) exists but requires
    // API 31 and minSdk is 26, so the backend converts to I32.
    assert_eq!(aaudio_format(SampleFormat::I24), 4);
}

#[test]
fn aaudio_error_codes_classify_into_desktop_kinds() {
    use fp_backends_mobile::stream_config::classify_aaudio_error;
    // AAUDIO_ERROR_BASE = -900, DISCONNECTED = -899, ILLEGAL_ARGUMENT = -898,
    // INTERNAL = -896, INVALID_STATE = -895, INVALID_HANDLE = -892,
    // UNIMPLEMENTED = -890, TIMEOUT = -885 in the NDK. XRUN is handled via
    // AAUDIO_STREAM_STATE_OVERFLOW in the state machine, not a result code.
    assert_eq!(classify_aaudio_error(-899), StreamErrorKind::DeviceLost);
    assert_eq!(classify_aaudio_error(-885), StreamErrorKind::Other);
    assert_eq!(classify_aaudio_error(0), StreamErrorKind::Other);
}
