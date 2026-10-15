package org.fauste.player.remote

/**
 * The HTTP client surface for the engine's loopback remote: the same
 * server a desktop browser drives, consumed in-process.
 *
 * Phase 3 exposes the one read the mobile UI needs — `GET /api/v1/state`
 * (the remote's state snapshot, `fp-remote/src/http/mod.rs`), answered as
 * the raw JSON body. `null` is the honest "could not fetch": unreachable
 * server, non-200 answer (a token-guarded remote answers 401; Phase 3 has
 * no token flow, so that path is not reachable yet), or a transport
 * failure. There is no streaming and no typed model this phase — parsing
 * the JSON is Phase 4's decision.
 */
interface RemoteApi {
    /**
     * Fetches the raw JSON of `GET /api/v1/state` on `http://host:port`,
     * or `null` when the fetch fails for any reason.
     */
    suspend fun fetchState(
        host: String,
        port: Int,
    ): String?
}

/**
 * The platform HTTP implementation. The Android actual is plain
 * `HttpURLConnection` on `Dispatchers.IO` with bounded timeouts — no
 * third-party HTTP stack this phase; swapping in a multiplatform engine
 * is a Phase 4+ decision if a second target ever needs one.
 *
 * **Why an expect factory**: the interface above is common code, but the
 * transport is platform-owned. A future iOS/desktop target plugs its own
 * actual here without touching a single caller.
 */
expect fun remoteApi(): RemoteApi
