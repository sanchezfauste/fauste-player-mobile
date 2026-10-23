# The loopback remote

The engine's remote is the desktop's `fp-remote` HTTP/JSON API — same
router, same resources, same routes — running on the phone, bound to
loopback, with an ephemeral port. The API itself is the desktop's:
[remote control API](https://github.com/sanchezfauste/fauste-player/blob/master/docs/technical/remote-api.md).
The mobile-specific parts are the configuration pin, the Kotlin
client, and the honest limits.

## Pinned enabled / loopback / port-0

The desktop default leaves the remote **off**. The mobile core turns
it **on at boot, pinned to loopback with an ephemeral port**
(`PlayerCore::core`): `remote.http.enabled = true`, the bind forced to
`127.0.0.1` unless it already is loopback, and `port = 0`. The reasons,
recorded in the code:

- the mobile UI is its own client — the app is useless without the
  server (the settings screen's remote status, and the app smoke and
  the release measurements drive playback through it);
- a phone never serves beyond itself, and there is no token flow yet,
  so loopback-only is the honest exposure: the API can *operate* the
  player but reads no secrets — the file path is never exposed;
- the desktop default port (7380) could clash with a desktop player or
  a previous run that has not released it, so the port is ephemeral
  and discovered through `remote_status`.

The OSC half stays off (the mobile config leaves it disabled), so
`RemoteInfo` reports only the HTTP server's state: `enabled` follows
listening-or-retrying, `address` is the `127.0.0.1:<port>` string while
it listens, empty otherwise.

The pin interacts with the config round-trip:
`RemoteConfig::validate` clamps port 0 to 1024, and the remote thread
follows the model's `remote.http` every 250 ms — so `set_backend`
patches its payload's port to the bound port before sending (the port
guard, [FFI boundary](ffi.md)).

## The Kotlin client

`RemoteApi` is a minimal expect/actual (Phase 3, Ruling 6 — no Ktor):
`suspend fun fetchState(host, port): String?`. The Android actual is
one `HttpURLConnection` per fetch — the loopback remote serves a
single operator, so a connection pool is not worth its statefulness —
with connect/read timeouts set on every fetch, everything on the IO
dispatcher, and a null answer for any failure (never a throw, never a
partial body). The same actual is the host-JVM unit-test transport:
the module's only target is Android, so `commonTest` compiles against
it and the canned-`ServerSocket` tests prove the production path.
The settings screen shows the server through `RemoteServer`, a thin
`StateFlow` wrapper over the FFI's `remote_status`.

Cleartext HTTP on Android 9+ needs the manifest's
`networkSecurityConfig`, which permits cleartext **to `127.0.0.1`
only** — no other destination. And the socket itself needs the
manifest's `INTERNET` permission: Android grants AF_INET sockets, even
loopback ones, only to apps holding it — the app-smoke find every host
lane missed ([Testing](testing.md)); it is declared, not
runtime-requested (a normal install-time permission).

## What the remote can do

The full desktop API is served by the mobile's `MobileBridge` — the
desktop Bridge's `model`/`playback`/`send` over the conductor handle
(the same ArcSwap/telemetry reads), with `cover` and `peaks` answering
`None` until the media-cache surface is ported (a recorded deferral).
So a loopback client can read the whole state, operate the transport,
edit the queue — exactly what the app smoke, the release measurements
and the app's own status UI do with it.

## The disclosed limits

- **Remote-driven play to a stopped, backgrounded app produces no
  notification.** The poll's idle condition is
  `Background && !serviceActive` ([Threading](threading-and-realtime.md));
  with the app backgrounded and no service alive, nothing holds the
  gate open, so the poll never observes the Playing transition that
  would arm the service starter. The play happens in the engine; the
  notification arrives when the app is foregrounded again. The remote
  API has no way to start an Android service by itself — a background
  heartbeat would fight the idling design, so the corner is
  documented, not patched.
- **Loopback is not per-app isolated on Android**: another app on the
  device can reach `127.0.0.1:<port>`. Exposure is control-only (the
  API operates the player; it exposes no file paths and no secrets),
  but a token or an in-process bypass of the HTTP hop is a recorded
  follow-up from the same Phase-2 review that pinned the loopback
  bind.
- **Queue-manager staleness**: queue edits made through the HTTP
  remote bypass `QueueManager`, whose `playlist` flow refreshes on
  local mutations only — the UI catches up on its next local mutation
  or refresh. Documented in the class; the honest-unknown marker in
  the UI's up-next line is the visible consequence.
