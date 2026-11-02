package network.tos.wallet

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Public test replies: a usable unproven balance and an invalid native proof. */
internal class QuantumBalancePoisonServer : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    private val failure = AtomicReference<Throwable?>()
    val methods = CopyOnWriteArrayList<String>()
    @Volatile private var running = true
    val endpoint = "http://127.0.0.1:${server.localPort}"
    private val worker = Thread {
        while (running) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    check(input.readLine().startsWith("POST "))
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: error("Missing headers")
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    check(length in 1..32768)
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
                    val query = JSONObject(String(body))
                    val method = query.getString("method")
                    check(methods.size < 128)
                    methods.add(method)
                    val result = if (method == "getProofQuery") JSONObject().put("reply", "AA==") else
                        JSONObject().put("balance", "999999999000000000").put("state", "uninit")
                    val response = JSONObject().put("jsonrpc", "2.0").put("id", query.get("id")).put("result", result)
                        .toString().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(response)
                }
            } catch (_: SocketTimeoutException) {
            } catch (error: Throwable) {
                if (running) { failure.set(error); running = false }
            }
        }
    }.apply { start() }
    override fun close() {
        running = false
        server.close()
        worker.join(5000)
        check(!worker.isAlive) { "Balance fixture did not stop" }
        failure.get()?.let { throw AssertionError("Balance fixture failed", it) }
    }
}
