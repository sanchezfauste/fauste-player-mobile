//! `uniffi-bindgen` CLI shim: exposes the generator as `cargo run -p uniffi-bindgen`.

fn main() {
    uniffi::uniffi_bindgen_main();
}
