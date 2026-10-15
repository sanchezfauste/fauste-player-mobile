package org.fauste.player.remote

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android actual's transport test (the actual's KDoc explains why
 * this is a host-JVM test): a one-shot `ServerSocket` bound to loopback
 * answers a canned state JSON — the production `HttpURLConnection` path
 * must speak real HTTP to it, so this proves the transport, not a
 * lookalike client. Loopback only; no network. Every wait is bounded.
 */
class RemoteApiTest {
    @Test
    fun fetchStateReturnsTheBodyOfTheStateEndpoint() {
        val body = """{"players":[]}"""
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var requestLine: String? = null
            val accepter =
                thread {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        requestLine = reader.readLine()
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        val response =
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n" +
                                "\r\n" +
                                body
                        socket.getOutputStream().apply {
                            write(response.toByteArray())
                            flush()
                        }
                    }
                }

            val fetched = runBlocking { remoteApi().fetchState("127.0.0.1", server.localPort) }

            accepter.join(2_000)
            assertFalse(accepter.isAlive, "the one-shot accepter finished")
            assertEquals(body, fetched)
            assertEquals(
                "GET /api/v1/state HTTP/1.1",
                requestLine,
                "the actual must request the remote's state endpoint",
            )
        }
    }

    @Test
    fun fetchStateAnswersNullOnARefusedConnection() {
        // Grab an ephemeral port and close the listener: connects are refused.
        val port =
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.localPort
            }
        val fetched = runBlocking { remoteApi().fetchState("127.0.0.1", port) }
        assertNull(fetched, "a refused connection is null, never a throw")
    }

    @Test
    fun fetchStateHonorsTheReadTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepter =
                thread {
                    server.accept().use {
                        // Accepts, then never answers: the client's read
                        // must time out, not hang.
                        Thread.sleep(500)
                    }
                }

            val slow = HttpURLConnectionRemoteApi(readTimeoutMillis = 100)
            val fetched = runBlocking { slow.fetchState("127.0.0.1", server.localPort) }

            assertNull(fetched, "a wedged server times out to null")
            accepter.join(2_000)
        }
    }
}
