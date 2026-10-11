package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.pq.QuantumProofHttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class QuantumProofHttpTransportTest {
    private fun response(status: String, body: String, headers: String = "", action: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            val failure = AtomicReference<Throwable?>()
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10000
                        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        assertTrue(input.readLine().startsWith("POST "))
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: error("Missing HTTP headers")
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val request = CharArray(length)
                        var read = 0
                        while (read < request.size) { val count = input.read(request, read, request.size - read); check(count > 0); read += count }
                        assertTrue(String(request).contains("getProofQuery"))
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$headers\r\n").toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().write(bytes)
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            thread.start()
            try { action("http://127.0.0.1:${server.localPort}/jsonRPC") }
            finally { thread.join(10000); assertFalse(thread.isAlive); failure.get()?.let { throw AssertionError("HTTP fixture failed", it) } }
        }
    }
    private fun query(url: String, maximum: Int = 16) = QuantumProofHttpTransport(url.toHttpUrl(), OkHttpClient()).query(byteArrayOf(1, 2, 3, 4), maximum)
    @Test fun rejectsTrailingDocumentsAndNestedPayloads() {
        val valid = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"reply\":\"AQIDBA==\"}}"
        for (body in listOf(valid + "{}", valid + "garbage", valid + "\n" + valid,
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"reply\":{\"data\":\"AQIDBA==\"}}}")) {
            response("200 OK", body) {
                try { query(it); fail("Malformed proof envelope accepted") } catch (_: IllegalStateException) { }
            }
        }
        response("200 OK", " \n" + valid + "\t\r\n") { assertArrayEquals(byteArrayOf(1, 2, 3, 4), query(it)) }
    }
    @Test fun boundedHttpReplyAndFailures() {
        response("200 OK", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"reply\":\"AQIDBA==\"}}") { assertArrayEquals(byteArrayOf(1, 2, 3, 4), query(it)) }
        response("200 OK", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"reply\":\"AQIDBA==\"}}") { try { query(it); fail("Wrong ID accepted") } catch (_: IllegalStateException) { } }
        response("200 OK", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"reply\":\"AQIDBA==\"}}") { try { query(it, 2); fail("Oversized raw reply accepted") } catch (_: IllegalStateException) { } }
        response("302 Found", "", "Location: http://127.0.0.1:9/\r\n") { try { query(it); fail("Redirect accepted") } catch (_: IllegalStateException) { } }
    }
}
