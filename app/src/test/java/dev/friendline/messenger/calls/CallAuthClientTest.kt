package dev.friendline.messenger.calls

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CallAuthClientTest {
    @Test
    fun createAndJoinSendOnlyTheExpectedMatrixAuthorization() {
        val calls = AtomicInteger()
        val authorization = AtomicReference<String>()
        val requestBody = AtomicReference<String>()
        val requestPath = AtomicReference<String>()
        val server = server { exchange ->
            calls.incrementAndGet()
            requestPath.set(exchange.requestURI.rawPath)
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            requestBody.set(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
            reply(exchange, 200, credentialsJson())
        }
        try {
            val client = CallAuthClient()
            val origin = "http://127.0.0.1:${server.address.port}"
            val created = client.createCall(origin, "matrix-secret-token", "!room:friendline.test")
            assertEquals(HANDLE, created.callId)
            assertEquals("wss://calls.friendline.test", created.liveKitUrl)
            assertEquals("L".repeat(80), created.liveKitToken)
            assertEquals("Bearer matrix-secret-token", authorization.get())
            assertEquals("{\"matrix_room_id\":\"!room:friendline.test\"}", requestBody.get())

            client.joinCall(origin, "matrix-secret-token", "!room:friendline.test", HANDLE)
            assertEquals(2, calls.get())
            assertEquals("/_friendline/calls/v1/calls/$HANDLE", requestPath.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun rejectsRedirectsWithoutForwardingTheBearerToken() {
        val secondServerRequests = AtomicInteger()
        val second = server { exchange ->
            secondServerRequests.incrementAndGet()
            reply(exchange, 200, credentialsJson())
        }
        val first = server { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${second.address.port}/steal")
            reply(exchange, 302, "redirect")
        }
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                CallAuthClient().createCall(
                    "http://127.0.0.1:${first.address.port}",
                    "matrix-secret-token",
                    "!room:friendline.test",
                )
            }
            assertFalse(failure.message.orEmpty().contains("matrix-secret-token"))
            assertEquals(0, secondServerRequests.get())
        } finally {
            first.stop(0)
            second.stop(0)
        }
    }

    @Test
    fun rejectsInsecureAndMismatchedLiveKitEndpoints() {
        val server = server { exchange -> reply(exchange, 200, credentialsJson(url = "ws://calls.friendline.test")) }
        try {
            val client = CallAuthClient()
            assertThrows(IllegalArgumentException::class.java) {
                client.createCall("https://user@friendline.test", "token", "!room:friendline.test")
            }
            assertThrows(IllegalStateException::class.java) {
                client.createCall("http://127.0.0.1:${server.address.port}", "token", "!room:friendline.test")
            }
        } finally {
            server.stop(0)
        }
    }

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                handler(exchange)
            }
            start()
        }

    private fun reply(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun credentialsJson(url: String = "wss://calls.friendline.test"): String =
        """{"call_id":"$HANDLE","url":"$url","token":"${"L".repeat(80)}","expires_at":${System.currentTimeMillis() / 1000 + 60}}"""

    private companion object {
        val HANDLE = "v1.${"A".repeat(86)}.${"B".repeat(43)}"
    }
}
