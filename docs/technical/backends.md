# Backends (`fp-backends-mobile`)

The mobile audio backend: `AaudioBackend`, implementing the desktop
`fp_backends::AudioBackend` trait so the unchanged engine can drive it.
The trait itself, the `Renderer` contract and the stream-config
semantics are the desktop's:
[backends](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/backends.md).

## Shape

- **One device.** AAudio has no enumeration API: `enumerate_devices`
  lists exactly one `DeviceInfo` — the default output, id `"default"`
  (2 channels, 44.1–192 kHz, exclusive-capable, no DSD). Picking a
  specific output (USB) would need `AAudioStreamBuilder_setDeviceId`
  with Java-side ids — deferred, documented in the code.
- **Float only.** The stream opens `AAUDIO_FORMAT_PCM_FLOAT`, and AAudio
  hands the callback a float buffer directly: there is no conversion
  machinery and no scratch buffer (the desktop cpal backend's
  integer-conversion paths are deliberately not ported). `I24` maps to
  the I32 code — packed 24-bit needs API 31 and the floor is 26.
- **Low latency, always** (`AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`):
  media playback wants the small buffers. `setUsage`/`setContentType`
  are deliberately not called — both setters are API 28+ and the
  wanted values are the platform defaults; skipping them keeps the
  API-26 link floor.
- **Exclusive** is requested through `AAUDIO_SHARING_MODE_EXCLUSIVE`
  when `StreamConfig::exclusive` asks for it (a host-tested mapping —
  `EXCLUSIVE = 0` is counter-intuitive, so the constants are pinned by
  test against the NDK r27 header); emulators usually refuse it, which
  is why the conformance suite opens shared and only the request path
  is unit-tested.

## Buffer sizing

`aaudio-sys 0.1` has no builder-level `setBufferSizeInFrames`; the size
is set on the stream after open. `buffer_attempts` walks
`[Some(requested), None]` (`None` leaves the device's own default),
unless `exact_buffer` demands the requested size and nothing else.
The actual size is read back: with `exact`, a size that differs from
the request — or cannot be read — is a refusal, not a pass; non-exact,
an unreadable size falls back to the request so the bus still plays.
`OutputStream::config()` reports the requested config with
`buffer_frames` replaced by the size the device actually runs.

## Errors

| Source | Path | Result |
|---|---|---|
| `openStream` / `requestStart` result | `open_error` (pure, host-tested) | `AAUDIO_ERROR_DISCONNECTED` → `DeviceNotFound`; `ILLEGAL_ARGUMENT` → `Unsupported`; `UNAVAILABLE`/`INVALID_STATE` → `Busy`; anything else → `Backend(msg)` |
| The error callback | `classify_aaudio_error` → `StreamErrorSink::report` | RT-safe; the bus watchdog reads the atomics and the conductor reacts (device loss = the desktop's watchdog reopen) |
| Underruns | the XRUN monitor thread | `StreamErrorKind::Xrun` per increase of `AAudioStream_getXRunCount` ([Threading](threading-and-realtime.md)) |

## Availability probing

`availability()` opens and closes a throwaway default stream per call
and confirms it reached `AAUDIO_STREAM_STATE_OPEN` — deliberately
**uncached** (Phase 1, Ruling 3): callers are rare (the settings
screen, backend selection) and hotplug correctness beats saving one
open/close round-trip. On a host the backend reports itself
`Unavailable("AAudio is Android-only")` and the type still compiles, so
the host arms of the one trait implementation are host-tested. The
FFI's `get_available_backends` probes the same way at call time;
`"null"` — the engine's always-present fallback — is always available.

## The `set_backend` path

Backend selection is the desktop settings picker's exact command:
`PlayerCore::set_backend` sends one `Command::UpdateConfig` carrying
the current configuration with only `outputs.backend` changed. It
returns after queueing, never blocking: the conductor applies the
switch asynchronously when the affected paths are quiet (the desktop's
live-settings rule), and the engine falls back to what opens if the
chosen backend is unavailable, keeping the retry — as on the desktop
when a backend is lost. The payload edit is validated before sending
(the desktop `update` shape), and an edit that changed nothing sends
nothing. The one mobile-specific addition is the port guard that keeps
the config round-trip from moving the loopback remote:
see [FFI boundary](ffi.md).

## Why DSD is refused

`check_dsd` refuses both DSD modes up front (Phase 1, Ruling 1):
`Native` → `Unsupported` (AAudio has no DSD path at all) and `DoP` →
`Unsupported` (DoP needs exact integer words, and the backend opens
float PCM — a DoP stream reaching the float path would be silently
mangled). The desktop cpal backend allows DoP with exclusive integer
access; this is a deliberate Phase-1 restriction, documented in the
module, to revisit with DSD-capable Android hardware and an integer
exclusive mode.

## The Null backend

Not mobile code: the desktop `NullBackend` is the engine's own
always-appended fallback (`Engine::new`), and it is what every host
lane runs — the Rust host tests, the Kotlin real-engine tests, the
Robolectric suites' scripted graph. On a host without AAudio the whole
core (store → engine → conductor → analyzer → remote) still runs and
transitions its transport, which is what makes the host test pyramid
meaningful ([Testing](testing.md)). `"null"` is also a valid
`set_backend` id.

## Conformance

`tests/conformance.rs` (cfg-gated to Android) ports the desktop
conformance suite's `conform()` shape for `AaudioBackend`: available
and lists a named device including the default; the default device
opens and reports a sane configuration; the renderer is called with
more than 4 800 frames within 3 s; **no callbacks arrive after drop**;
and the device reopens. It runs on the emulator
([Testing](testing.md)) — the on-device check each system needs before
a release, the same role the desktop's suite plays for cpal hosts.
`engine_smoke.rs` constructs the full core on-device (now with the
real player core, remote status, queue reads and the armed id
round-trip), and `decode_smoke.rs` proves decoding on Android.

## Drop order

`AaudioStream::drop`: set the monitor stop flag → `requestStop` →
join the monitor thread → `close` (AAudio guarantees callbacks end
before `close` returns) → reclaim the boxed callback context. The
monitor is joined **before** the close so it can never poll a closed
stream — the order is pinned by the plan after a review caught it.
