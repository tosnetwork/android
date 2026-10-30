package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import network.tos.wallet.data.account.pq.QuantumHistoryHttpTransport
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real loopback HTTP, without a node, external endpoint or test signer. */
@RunWith(AndroidJUnit4::class)
class QuantumHistoryHttpTransportTest {
    private val account = "0:" + "11".repeat(32)
    private val hash = "22".repeat(32)
    private val valid = """{"jsonrpc":"2.0","id":1,"result":[{"data":"AA=="}]}"""
    private fun query(server: Reply) = QuantumHistoryHttpTransport(server.url.toHttpUrl(), OkHttpClient())
        .transaction(account, "1", hash)

    @Test fun exactReplyPassesWhileWrongIDsTrailingDeepJSONAndRedirectsAreRefused() {
        Reply(valid).use { server ->
            assertEquals("AA==", query(server))
            val request = server.request.get()
            assertEquals("getTransactions", request.getString("method"))
            assertEquals(1, request.getJSONObject("params").getInt("limit"))
            assertEquals(account, request.getJSONObject("params").getString("address"))
        }
        val bodies = listOf(valid.replace("\"id\":1", "\"id\":2"), valid + "{}",
            valid.dropLast(1) + ",\"extra\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}")
        for (body in bodies) Reply(body).use { server ->
            assertTrue(runCatching { query(server) }.exceptionOrNull() is IllegalStateException)
        }
        Reply(valid).use { target ->
            Reply("", "302 Found", "Location: ${target.url}\r\n").use { redirect ->
                assertTrue(runCatching { query(redirect) }.exceptionOrNull() is IllegalStateException)
                assertEquals("Redirect reached a second endpoint", 1L, target.received.count)
            }
        }
    }

    @Test fun advertisedAndChunkedOversizeResponsesAreRefusedBeforeJSONIsUsed() {
        // Both responses contain valid JSON that would otherwise return AA==.
        val body = valid.dropLast(1) + ",\"extra\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}"
        Reply(body).use { server ->
            assertTrue(runCatching { query(server) }.exceptionOrNull() is IllegalStateException)
        }
        Reply(body, chunked = true).use { server ->
            assertTrue(runCatching { query(server) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test fun cancelledBlockingReadCannotReturnAUsableReplyAndServerWorkerIsJoined() = runBlocking {
        val release = CountDownLatch(1)
        Reply(valid, release = release).use { server ->
            val job = launch(Dispatchers.IO) { runInterruptible { query(server) }; fail("Cancelled reply became usable") }
            try {
                assertTrue("Request never reached loopback", server.received.await(5, TimeUnit.SECONDS))
                job.cancel()
            } finally { release.countDown() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    private class Reply(private val body: String, private val status: String = "200 OK",
                        private val extraHeaders: String = "",
                        private val chunked: Boolean = false, private val release: CountDownLatch? = null) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/jsonRPC"
        val received = CountDownLatch(1)
        val request = AtomicReference<JSONObject>()
        private val failure = AtomicReference<Throwable>()
        @Volatile private var active: Socket? = null
        @Volatile private var closing = false
        private val worker = Thread({
            try {
                listener.accept().use { socket ->
                    active = socket; socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val bytes = ArrayList<Byte>()
                        while (true) {
                            val value = input.read(); check(value >= 0)
                            if (value == 10) break
                            check(bytes.size < 8192); if (value != 13) bytes.add(value.toByte())
                        }
                        return bytes.toByteArray().toString(Charsets.US_ASCII)
                    }
                    check(line().startsWith("POST /jsonRPC "))
                    var length = -1
                    var headerCount = 0
                    while (true) {
                        check(headerCount++ < 64)
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    check(length in 1..4096)
                    val payload = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val n = input.read(payload, offset, length - offset); check(n > 0); offset += n }
                    request.set(JSONObject(payload.toString(Charsets.UTF_8))); received.countDown()
                    check(release?.await(5, TimeUnit.SECONDS) != false)
                    val encoded = body.toByteArray(Charsets.UTF_8)
                    val framing = if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${encoded.size}\r\n"
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 $status\r\n${extraHeaders}${framing}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    try {
                        if (chunked) output.write((encoded.size.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(encoded)
                        if (chunked) output.write("\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                    } catch (_: java.io.IOException) { /* A refused/cancelled client may close its socket. */ }
                }
            } catch (error: Throwable) { if (!(closing && error is SocketException)) failure.set(error) }
        }, "quantum-history-loopback").apply { isDaemon = true; start() }
        override fun close() {
            closing = true; release?.countDown(); listener.close(); active?.close(); worker.join(2000)
            assertFalse("Loopback worker leaked", worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        }
    }
}
