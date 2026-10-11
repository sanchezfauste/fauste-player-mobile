//! Mobile audio backends implementing the desktop `AudioBackend` trait.

mod aaudio_backend;
pub mod stream_config;

pub use aaudio_backend::AaudioBackend;
