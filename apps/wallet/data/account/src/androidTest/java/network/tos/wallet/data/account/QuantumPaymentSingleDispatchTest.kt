package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.pq.QuantumPaymentHttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Real loopback HTTP only; fake public bytes never reach a node or signer. */
@RunWith(AndroidJUnit4::class)
class QuantumPaymentSingleDispatchTest {
    private fun send(server: Endpoint) = QuantumPaymentHttpTransport(server.url.toHttpUrl(), OkHttpClient()).send(byteArrayOf(0))
    private fun accepted(request: JSONObject) = Reply(body = JSONObject().put("jsonrpc", "2.0")
        .put("id", request.getString("id")).put("result", "accepted").toString())
    @Test fun successIsOneRequestAndWrongResponseIDIsRefused() {
        Endpoint { request, _ -> accepted(request) }.use { server ->
            send(server)
            assertEquals(1, server.requests.size)
            assertEquals("sendBocReturnHash", server.requests.single().getString("method"))
        }
        Endpoint { _, _ -> Reply(body = """{"jsonrpc":"2.0","id":"wrong","result":"accepted"}""") }.use { server ->
            assertThrows(IllegalStateException::class.java) { send(server) }
            assertEquals(1, server.requests.size)
        }
    }
    @Test fun retryAfterZeroCannotCauseASecondHTTPDispatch() {
        Endpoint { request, number ->
            if (number == 1) Reply("503 Service Unavailable", "Retry-After: 0\r\n", "{}") else accepted(request)
        }.use { server ->
            assertNotNull(runCatching { send(server) }.exceptionOrNull())
            assertEquals("HTTP follow-up sent the authorization twice", 1, server.requests.size)
        }
    }
    @Test fun redirectAndLostResponseCannotDispatchAgain() {
        Endpoint { request, _ -> accepted(request) }.use { target ->
            Endpoint { _, _ -> Reply("307 Temporary Redirect", "Location: ${target.url}\r\n", "") }.use { server ->
                assertThrows(IllegalStateException::class.java) { send(server) }
                assertEquals(1, server.requests.size)
                assertTrue(target.requests.isEmpty())
            }
        }
        Endpoint { _, _ -> null }.use { server ->
            assertNotNull(runCatching { send(server) }.exceptionOrNull())
            assertEquals(1, server.requests.size)
        }
    }
    private data class Reply(val status: String = "200 OK", val headers: String = "", val body: String)
    private class Endpoint(private val reply: (JSONObject, Int) -> Reply?) : Closeable {
        private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
        val url = "http://127.0.0.1:${listener.localPort}/jsonRPC"
        val requests = CopyOnWriteArrayList<JSONObject>()
        private val failure = AtomicReference<Throwable>()
        @Volatile private var closing = false
        @Volatile private var active: Socket? = null
        private val worker = Thread({
            try {
                while (!closing) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    socket.use {
                        active = socket; socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        fun line(): String? {
                            val bytes = ArrayList<Byte>()
                            while (true) {
                                val value = input.read()
                                if (value < 0) { check(bytes.isEmpty()); return null }
                                if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII)
                                check(bytes.size < 8192); if (value != 13) bytes.add(value.toByte())
                            }
                        }
                        val start = line() ?: return@use
                        check(start.startsWith("POST /jsonRPC "))
                        var size = -1; var count = 0
                        while (true) {
                            check(count++ < 64)
                            val header = checkNotNull(line())
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt()
                        }
                        check(size in 1..4096)
                        val body = ByteArray(size); var offset = 0
                        while (offset < size) { val n = input.read(body, offset, size - offset); check(n > 0); offset += n }
                        val request = JSONObject(body.toString(Charsets.UTF_8)); requests.add(request)
                        val response = reply(request, requests.size) ?: return@use
                        val encoded = response.body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 ${response.status}\r\n${response.headers}Content-Length: ${encoded.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(encoded); flush()
                        }
                    }
                    active = null
                }
            } catch (error: Throwable) { if (!closing) failure.set(error) }
        }, "single-dispatch-loopback").apply { start() }
        override fun close() {
            closing = true; listener.close(); active?.close(); worker.join(6000)
            check(!worker.isAlive) { "Loopback worker did not stop" }
            failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        }
    }
}
