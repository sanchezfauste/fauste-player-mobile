# Threading and real time

The desktop's real-time rules apply unchanged on Android — the mixer
and the conductor are the same code — and the mobile adds its own
discipline on the Kotlin side. The desktop's page
([threading and real time](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/threading-and-realtime.md))
is the authority for the engine's threads; this page covers the
mobile-specific threads and rules.

## Threads

| Thread | Name | Class | May block? |
|---|---|---|---|
| AAudio data callback, one per open stream | set by AAudio | real time (AAudio's high-priority audio thread) | **never** |
| AAudio error callback | set by AAudio | real time | **never** (it only classifies and reports) |
| XRUN monitor, one per open stream | `aaudio-xrun-monitor` | normal | sleeps 250 ms between polls |
| Conductor | `fp-conductor` (desktop) | normal | no (5 ms tick) |
| Session saver | `fp-session-save` | normal | file I/O (saves) |
| Analysis pool | `fp-analysis-<n>` (desktop) | low | file I/O, CPU |
| Remote | `fp-remote` (desktop) | normal | network I/O (loopback) |
| Kotlin: the poll loop and all commands | injected dispatcher (`Dispatchers.IO`/`Default`) | normal | the JNA hops are non-blocking by FFI contract |
| Kotlin: the service's collector | `Dispatchers.IO` scope | normal | engine reads off-main |
| Kotlin: the media3 session | the main looper | normal | no (it only reads published snapshots and launches) |
| Kotlin: the UI | the main thread (Compose) | normal | no (it reads StateFlows and sends intents) |

## Rules for the real-time path

The AAudio data callback is the RT thread. It must not allocate, free,
lock, log, do I/O or panic — the same contract as the desktop mixer
(`fp_backends::Renderer` is contract-bound to it). The backend enforces
this by construction:

- **The callback body is plain Rust**, `callback.rs`'s `CallbackCore`:
  everything the real callback does minus the FFI, so it is host-tested
  ([Backends](backends.md)). The `extern "C"` wrapper in
  `aaudio_backend.rs` is a thin null/length-checked slice cast plus one
  `fill` call.
- **The renderer handoff is a one-slot `rtrb` ring** (the desktop cpal
  pattern): the producer is created with the callback, and the renderer
  is pushed only **after** the stream has opened, so a failed open
  attempt drops the unused producer and never the renderer. `rtrb`
  push/pop are wait-free; before the handoff lands, `fill` writes
  silence.
- **The error callback only classifies** the AAudio error code and calls
  `StreamErrorSink::report` (an RT-safe trait method that sets
  atomics) — nothing else; it never calls `requestStop` or `close`
  (AAudio forbids both there).
- **XRUNs are not delivered to callbacks** (Phase 1, Ruling 2): a
  non-RT monitor thread polls `AAudioStream_getXRunCount` every 250 ms
  and reports a `StreamErrorKind::Xrun` per increase; it exits on its
  stop flag or a poll error, and `Drop` joins it **before** closing the
  stream so it can never poll a closed handle.

Panic containment: a panic unwinding through an exported UniFFI method
never reaches an `extern "C"` boundary (uniffi 0.28 wraps every call in
`catch_unwind` and the generated Kotlin raises `InternalException`),
and the process-wide panic hook has already written its report
([Persistence](persistence.md) points at the crash story). The one
boundary with no Rust-side catcher is the AAudio callback itself — a
panic there aborts the process once the unwind escapes `extern "C"`,
*after* the hook has run — and the crate's `panic`-denying lints make
that path a non-possibility by construction.

## The Kotlin side

**Every engine call is off the main thread.** The FFI's methods are
non-blocking (queued commands, snapshot loads), but each is still a JNA
hop, and the constructor is disk-bound — so the Koin graph's rule is
that the first engine resolution and every later call happen on an
injected dispatcher, never the main thread. The ViewModels defer their
`Lazy` state-layer handles into the first dispatcher coroutine; the
service resolves Koin in a background scope; nothing engine-adjacent
runs in a composable.

**The poll loop** (`PlayerController`, 100 ms by default): each wake
checks the idle gate, then reads `get_state()` into the
`engineState` StateFlow. The catch ordering is load-bearing — on the
JVM `CancellationException` **is** an `IllegalStateException`, so it
must be rethrown first; then an `IllegalStateException` means the
UniFFI engine object was destroyed under us: recorded on
`lastPollFailure` and the loop stops (a dead engine is futile to poll);
anything else (a JNA hiccup) skips the tick and keeps polling — one bad
read never kills the flow. `lastPollFailure` is current poll health,
cleared by the next good tick.

**Lifecycle idling**: the idle condition is
`Background && !serviceActive`. Backgrounded alone, the loop skips the
FFI read entirely (no JNA hop; the in-process delay re-arm is the only
cost) and the first foreground wake reads immediately. The
**keep-awake gate** — `setPlaybackServiceActive`, set by the playback
service from its session's first update and every foreground
promotion, cleared on its exit paths — holds the poll open while the
service runs, because the media notification is fed by this very flow.
Idling stops UI state updates, not playback: the audio path is native
and keeps running.

**The service's IO-collect + main publish** (`FaustePlayerAdapter`): a
collector on the service's background scope combines
`controller.engineState` with the queue flow through the pure
`SessionPlaybackSnapshot.from` mapping — but the **publish** (the
volatile write plus media3's `invalidateState`) hops through
`Dispatchers.Main.immediate`, because media3 1.11.1's
`verifyApplicationThreadAndInitState()` throws off the application
looper. Publishing from the background scope crashed the app within
milliseconds of the service's `onCreate` on a real device — the
Robolectric lane never saw it (its test thread *is* the main looper).
`latestSnapshot` stays `null` until the first publish (the boot
window), so a not-yet-published idle seed is never read as engine
truth by the service's teardown watcher.

## The save thread

The session saver ([Persistence](persistence.md)) is a plain non-RT
thread that only reads ArcSwap snapshots and writes files — the same
reads the remote bridge does per request. The audio callbacks never
hear about it. The desktop's rule holds: nothing on the audio path
allocates in response to, or takes a lock shared with, persistence.
