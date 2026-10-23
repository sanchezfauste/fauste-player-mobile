# The FFI boundary (`fp-backends-mobile` → Kotlin)

The seam between the Rust core and the Kotlin state layer. Everything
Kotlin knows about the engine crosses here, so every change to
`src/ffi.rs` has a fixed cost: regenerate the bindings, ktlint-format
them, commit both, and re-run the on-device smoke when the surface
grows ([Testing](testing.md)).

## Mode

UniFFI 0.28's **proc-macro mode**: the interface is Rust annotations
(`uniffi::setup_scaffolding!`, `#[derive(uniffi::Record)]`,
`#[uniffi::export]`) — there is no `.udl` file (the spec's mention of
one was illustrative; a plan ruling). The `uniffi` feature gates the
whole module, so a plain `cargo build -p fp-backends-mobile` does not
even export the surface; the crate is `["lib", "cdylib"]` — `lib`
keeps host tests, `cdylib` is what the app packages and what
`--library`-mode generation reads. The Kotlin bindings are generated
from the compiled library and committed under
`crates-mobile/fp-backends-mobile/bindings/kotlin` (package
`org.fauste.player.rust`, pinned in `uniffi.toml`); Kotlin treats them
as read-only generated code.

## The surface, v1–v5

| Version | Phase | Change |
|---|---|---|
| v1 | 0 | `FausteEngine` as an inert stub: the records `EngineConfig`, `Track`, `EngineState`, `BackendInfo`; `get_state` reports stopped-at-zero, `get_available_backends` a static list |
| v2 | 2 | The real engine over `PlayerCore`; `Result`-returning methods and the `FausteError` enum (`Busy`, `Core`, `NotFound`); transport commands (`play`, `pause`, `stop`, `seek`, `set_volume`, `enqueue`) |
| v3 | 3 | The queue surface (`PlaylistEntry`, `get_playlist`, `remove_entry`, `move_entry`), real backend selection (`set_backend`), `remote_status` (`RemoteInfo`), availability probed at call time |
| v4 | 4 | `set_next` — the desktop's queue tap, the seam tap-to-jump needed |
| v5 | 5 | `EngineState` gains `current_entry_id` and `armed_entry_id` |

The surface drives the **main player** (`AppState.players[0]`, the only
one the default config creates). Every method maps to the desktop
`Command` the desktop UI itself sends, cited in `ffi.rs`'s doc
comments: enqueue is `Command::InsertPaths` appended at the end of the
main player's playlist, the queue edits are `RemoveEntry`/`MoveEntry`,
the jump is `SetNext`, the backend selection rides `UpdateConfig`
exactly as the desktop settings picker sends it. Two deliberate
shapes:

- **`enqueue` uses `index: usize::MAX`** — the reducer clamps it to the
  playlist length at apply time, which is what makes "append" race-free
  (reading the length here would race the conductor's applies).
- **`set_next` is arm-only**: it arms the entry as the player's
  explicit next and never touches the transport — what is on air
  plays to its end, a stopped player stays stopped, and the desktop's
  "Play now" gesture (Stop + SetNext + Play) is a different gesture
  this surface deliberately does not compose.

## The armed gate

`armed_entry_id` is the main player's `next` **gated on
`next_explicit`**: only a `set_next` sets that flag, while the model's
derived next always exists alongside it — so `None` means "nothing is
armed", never "a derived next exists". The arm consumes when it fires,
`previous` re-arms the entry it leaves, and removing the armed entry
clears it (all reducer semantics, cited per field in `ffi.rs`).
`current_entry_id` is the model's own `player.current`, the same join
key the desktop's table uses — exact where a path join is ambiguous
(duplicate paths each read as current).

## `Busy` and `NotFound`

- **`Busy`** means the conductor's command queue was full and the
  command was **dropped** (`ConductorHandle::send` returned `false`);
  retrying is safe. The Kotlin side maps it to `CommandResult.Busy`,
  and the UI surfaces it as a retryable outcome, never an error.
- **`NotFound`** is the up-front pre-check on `remove_entry`,
  `move_entry` and `set_next`: an unknown entry id is refused before
  anything is queued, the same pre-check shape the remote API has.
  An unknown backend id to `set_backend` is refused the same way; a
  known-but-currently-unavailable one is **accepted** (the engine
  falls back and keeps retrying, as on the desktop when a backend is
  lost).

## The port-0 guard

The mobile core pins the HTTP remote to loopback with an **ephemeral
port** (`remote.http.port = 0`; [Remote control API](remote-api.md)).
But the reducer re-validates every `UpdateConfig`, and
`RemoteConfig::validate` clamps the port into 1024…65535 — a naive
config round-trip would move the server from its ephemeral port to a
fixed 1024 within one 250 ms poll of the remote thread.

`PlayerCore::set_backend` (the one `UpdateConfig` writer) therefore
overwrites the payload's port **with the port the server is actually
listening on** before sending: a real port survives validation. Two
consequences, by design: the first switch after boot makes the remote
rebind once — to the same address — and later switches never touch the
remote at all; and while the server is not listening (still starting,
or a failed bind) the port is left alone. Pinned end-to-end in
`tests/ffi_state.rs`: `remote_status_reports_the_ephemeral_loopback_server`
and `set_backend_round_trips_without_moving_the_remote`, plus the
model-level normalization test.

## Bindings regeneration and the drift check

The committed bindings must be byte-identical to what the generator
produces. The flow:

```sh
cargo build -p fp-backends-mobile --features uniffi
cargo run -p uniffi-bindgen -- generate \
    --library target/debug/libfp_backends_mobile.so \
    --language kotlin \
    --out-dir <dir>
```

`uniffi-bindgen` is a workspace CLI shim (Cargo cannot run a
dependency's binary directly). The generator formats its output by
shelling out to `ktlint -F`, so regeneration must run with **ktlint
1.7.1** on PATH — the committed bindings are the formatted output, and
CI (`android.yml`) installs exactly that version before regenerating
and diffing (`diff -ru` against
`crates-mobile/fp-backends-mobile/bindings/kotlin`). A local
regeneration under a different ktlint fails the CI drift check on
formatting alone.

## The checksum coupling

The generated Kotlin verifies, at library load, one checksum symbol per
exported item (`uniffi_fp_backends_mobile_checksum_method_*` and
siblings) against what the loaded cdylib exports. UniFFI 0.28 derives
each checksum from the item's metadata buffer — signature, arguments,
return type **and docstring** (uniffi's `fnsig.rs` concatenates
`concat_long_str(#docstring)` into the hashed buffer). So a doc-comment
edit in `ffi.rs` changes the checksum: stale bindings fail at the first
call, not at compile time. That is why the rule is "regenerate on *any*
`ffi.rs` change", not just signature changes, and why the drift check
diffs the whole file.

## State reads

`get_state` mirrors the desktop remote Bridge's reads: the model
snapshot (`handle.model.load_full()`) and the telemetry
(`handle.telemetry.load()`) — ArcSwap/atomic loads, never a blocking
call. Status strings mirror the remote DTO (`"playing"` / `"paused"` /
`"stopped"`); the backend id comes from the model's
`live.audio_system_in_use` (`"null"` until the engine first reports).
No method blocks: commands are queued, reads are snapshots. The
constructor is the documented non-blocking exception (it loads the
store and spawns threads) — callers keep it off the main thread anyway.
