//! Mobile audio backends implementing the desktop `AudioBackend` trait.

mod aaudio_backend;
pub mod stream_config;
pub mod stream_params;

#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!("fauste_player");

#[cfg(feature = "uniffi")]
pub mod ffi;

pub use aaudio_backend::AaudioBackend;
