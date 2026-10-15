package org.fauste.player.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Android actual: one `HttpURLConnection` per fetch — the loopback
 * remote serves a single operator, so a connection pool is not worth its
 * statefulness. Connect and read timeouts are set on every fetch (a
 * wedged server must pin nobody), and the whole exchange runs on
 * [dispatcher] (`Dispatchers.IO` from the factory below), never the
 * caller's — the main thread included.
 *
 * **This actual doubles as the host-JVM unit-test implementation**: the
 * module's only target is Android, so `commonTest` compiles against
 * androidMain and exercises THIS transport, where the host JVM's
 * `HttpURLConnection` is the plain JDK implementation — the canned-
 * `ServerSocket` tests prove the production code path, not a lookalike.
 *
 * **On-device note (Phase 4 wiring)**: the engine's remote binds
 * cleartext loopback (`127.0.0.1`, `player_core.rs`), and Android 9+
 * blocks cleartext by default, so the app manifest will need a
 * `networkSecurityConfig` permitting cleartext to `127.0.0.1` before
 * the first on-device call. Host-JVM tests are unaffected.
 */
class HttpURLConnectionRemoteApi(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : RemoteApi {
    override suspend fun fetchState(
        host: String,
        port: Int,
    ): String? =
        withContext(dispatcher) {
            val connection =
                try {
                    (URL("http://$host:$port$STATE_PATH").openConnection() as HttpURLConnection).apply {
                        connectTimeout = connectTimeoutMillis
                        readTimeout = readTimeoutMillis
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A malformed host/port never reaches the network.
                    return@withContext null
                }
            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connect refused, timeouts, a cut connection: the honest
                // answer is null, never a throw and never a partial body.
                null
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        // The loopback remote answers in well under 10 ms; a full second
        // of silence means something is wrong, and the caller (a settings
        // screen) should see null, not a hang.
        const val CONNECT_TIMEOUT_MILLIS = 1_000

        const val READ_TIMEOUT_MILLIS = 1_000

        const val STATE_PATH = "/api/v1/state"
    }
}

/** The production [RemoteApi] over [HttpURLConnectionRemoteApi]. */
actual fun remoteApi(): RemoteApi = HttpURLConnectionRemoteApi()
