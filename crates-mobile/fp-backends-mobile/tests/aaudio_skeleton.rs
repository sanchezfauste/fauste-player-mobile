use fp_backends::AudioBackend;
use fp_backends_mobile::AaudioBackend;

#[test]
fn the_backend_id_is_aaudio() {
    assert_eq!(AaudioBackend::new().id().0, "aaudio");
}

#[cfg(not(target_os = "android"))]
#[test]
fn on_a_host_the_backend_reports_itself_unavailable() {
    let b = AaudioBackend::new();
    assert!(matches!(
        b.availability(),
        fp_backends::Availability::Unavailable(_)
    ));
}

#[cfg(not(target_os = "android"))]
#[test]
fn on_a_host_enumeration_is_the_same_unavailable() {
    let b = AaudioBackend::new();
    assert!(b.enumerate_devices().is_err());
    assert!(b.default_device().is_none());
}
