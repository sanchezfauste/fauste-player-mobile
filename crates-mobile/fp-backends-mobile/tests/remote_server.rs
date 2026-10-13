//! Host tests for the remote server the `PlayerCore` spawns.
//!
//! The remote thread listens only once the model's `config.remote.http`
//! asks for it; the core turns it on (loopback, ephemeral port) at
//! construction, so the assertions poll the published `RemoteStatus`
//! briefly instead of sleeping a fixed round.
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::time::{Duration, Instant};

use fp_backends_mobile::player_core::PlayerCore;
use fp_remote::server::ServerStatus;

/// A unique, empty directory pair under the system temp dir.
struct TempDirs {
    data: PathBuf,
    cache: PathBuf,
}

impl TempDirs {
    fn new(tag: &str) -> Self {
        let root =
            std::env::temp_dir().join(format!("fp-remote-test-{}-{tag}", std::process::id()));
        let dirs = Self {
            data: root.join("data"),
            cache: root.join("cache"),
        };
        std::fs::create_dir_all(&dirs.data).ok();
        std::fs::create_dir_all(&dirs.cache).ok();
        dirs
    }
}

impl Drop for TempDirs {
    fn drop(&mut self) {
        if let Some(root) = self.data.parent() {
            let _ = std::fs::remove_dir_all(root);
        }
    }
}

/// The address the core's remote HTTP server listens on, waiting for the
/// remote thread's first configuration round.
fn listening_addr(core: &PlayerCore) -> SocketAddr {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if let ServerStatus::Listening(addr) = core.remote_status().http {
            return addr;
        }
        assert!(
            Instant::now() < deadline,
            "the remote server never listened"
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn the_remote_server_serves_the_api_and_a_command_is_accepted() {
    let dirs = TempDirs::new("serving");
    let core = PlayerCore::new(dirs.data.clone(), dirs.cache.clone()).expect("construction");
    let addr = listening_addr(&core);
    assert!(addr.ip().is_loopback(), "must listen on loopback: {addr}");

    let mut stream = TcpStream::connect(addr).expect("the reported port accepts a connection");
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .expect("read timeout");
    stream
        .write_all(b"GET /api/v1/state HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .expect("request sent");
    let mut response = String::new();
    let _ = stream.read_to_string(&mut response).expect("a response");
    assert!(
        response.starts_with("HTTP/1.1 200"),
        "the API answers 200, got: {response:?}"
    );

    assert!(core.remote_control().send(fp_model::Command::StopAllCarts));
}

#[test]
fn dropping_the_core_stops_the_server() {
    let dirs = TempDirs::new("drop");
    let core = PlayerCore::new(dirs.data.clone(), dirs.cache.clone()).expect("construction");
    let addr = listening_addr(&core);
    assert!(TcpStream::connect(addr).is_ok());
    drop(core);
    assert!(
        TcpStream::connect(addr).is_err(),
        "the port is refused once the core is dropped"
    );
}
